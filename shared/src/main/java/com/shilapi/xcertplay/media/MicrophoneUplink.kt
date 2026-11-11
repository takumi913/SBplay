package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.compat.recorderRouteTypeCompat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.transport.DatagramNetwork
import com.shilapi.xcertplay.transport.DatagramTransport
import com.shilapi.xcertplay.transport.KernelDatagramNetwork
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.compat.WorkerThreads

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
    /**
     * Injected so a test can describe a head unit whose only encoder is the bundled one, or whose
     * encoders all fail. Defaults to the real chain: MediaCodec, then libopus, then Concentus.
     */
    private val opusEncoderFactory: (Int) -> MicrophoneOpusEncoder? = { bitrate ->
        MicrophoneOpusEncoders.create(
            bitrate = bitrate,
            software = { value ->
                SoftwareOpusEncoder(value, onError = { message, error ->
                    Log.w(TAG, message, error)
                    // The report, not just logcat: a silent call whose frames all fail to encode looks
                    // like "sent nothing" otherwise. Class name and a fixed stage only, never the text.
                    val stage = if (message.contains("unavailable")) "init" else "encode"
                    runCatching {
                        onDiagnostic("Microphone: encoder error type=${config.audioType.take(24).filter { it.isLetterOrDigit() }} " +
                            "implementation=software stage=$stage error=${error.javaClass.simpleName.take(48)}")
                    }
                })
            },
        )
    },
    /** The uplink endpoint comes from here so the wired path can run on a userspace stack. */
    private val network: DatagramNetwork = KernelDatagramNetwork,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramTransport? = null
    @Volatile private var opusEncoder: MicrophoneOpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    /** What happened to each voice effect this capture tried, for the report (never audio). */
    private val effectStatus = LinkedHashMap<String, String>()
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoderFactory(config.bitrate ?: 48_000)
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        if (nextEncoder != null) {
            // Named in the report because the CS11 has no MediaCodec Opus encoder: whether the
            // uplink runs on libopus or on the vendored Java encoder decides how much CPU the
            // 50 packets per second cost, and that cannot be seen from the car without this line.
            val message = "Microphone: encoder type=${config.audioType} codec=OPUS " +
                "implementation=${nextEncoder.implementation}"
            Log.i(TAG, message)
            // A failing report callback must not cost the car its microphone.
            runCatching { onDiagnostic(message) }
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // Debug (silent-call hunt): everything the capture was asked to do, from numbers and fixed tokens.
        stats.note("config", "frameBytes=${config.frameBytes} payloadType=${config.payloadType} " +
            "rtpSamples=${config.rtpSamplesPerPacket} opusClock=${config.opusClockRate} " +
            "bitrate=${config.bitrate ?: "none"} peer=${addressKind(config.host)} " +
            "minBuffer=$minBuffer bufferSize=$bufferSize requestedSource=${MicrophoneCaptureStats.sourceName(source)}")
        // The car keeps its own always-on voice assistant on the microphone, and this 4.4 audio HAL
        // answers a competing capture with ERROR_INVALID_OPERATION (-3) on the first read. Walk the
        // source ladder and keep the first source that actually returns samples.
        val probe = ByteArray(minOf(config.frameBytes, MIN_READ_BYTES))
        var chosen: AudioRecord? = null
        for (candidate in sourceLadder(source)) {
            val candidateRecorder = try {
                // CS11: 4.4 走 legacy 构造
                @Suppress("DEPRECATION")
                AudioRecord(candidate, config.sampleRate, channelMask,
                    AndroidAudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (error: Exception) {
                Log.e(TAG, "microphone recorder creation failed source=$candidate", error)
                stats.probe(candidate, PROBE_CREATE_FAILED)
                continue
            }
            if (candidateRecorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "microphone recorder not initialized source=$candidate state=${candidateRecorder.state}")
                stats.probe(candidate, PROBE_NOT_INITIALIZED)
                candidateRecorder.release()
                continue
            }
            val read = try {
                candidateRecorder.startRecording()
                candidateRecorder.read(probe, 0, probe.size)
            } catch (error: Exception) {
                Log.e(TAG, "microphone probe failed source=$candidate", error)
                -1
            }
            Log.i(TAG, "microphone probe source=$candidate rate=${config.sampleRate} result=$read")
            // The report, not just logcat: which source the car let us open (or that every source was
            // refused while its own voice assistant held the microphone) decides the next step.
            stats.probe(candidate, read, if (read > 0) peakOf(probe, read) else null)
            if (read >= 0) {
                chosen = candidateRecorder
                break
            }
            runCatching { candidateRecorder.stop() }
            candidateRecorder.release()
        }
        val nextRecorder = chosen
        val chosenSource = nextRecorder?.audioSource
        if (nextRecorder == null) {
            Log.w(TAG, "microphone unavailable on every source rate=${config.sampleRate}")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION)
            reportBlocked(MicrophoneBlockedNotice.Reason.ALL_SOURCES_REFUSED)
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val bindAddress = serverBindAddress()
        val nextSocket = try {
            network.open(bindAddress)
        } catch (error: Exception) {
            // The address and the cause, not just the exception: measured on 2026-10-13 the wired mic
            // reported `stage=SOCKET_CREATION error=SocketException` and nothing else, while Siri's own
            // input worked -- so the failing value is the whole diagnosis. serverBindAddress() falls back
            // to this ROM's rejected all-zero wildcard when no session address is published, and that is
            // indistinguishable from a lwIP socket-table failure without seeing the address.
            Log.e(
                TAG,
                "microphone socket creation failed address=$bindAddress " +
                    "cause=${error.cause?.javaClass?.name}:${error.cause?.message}",
                error,
            )
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            // CS11: with the voice effects chain attached the telephony capture returns near-silence
            // (microphone level peak ~5 while the caller hears nothing), so it can be switched off at
            // runtime with `ADB shell setprop debug.diplay.mic_effects 0` for an on-car comparison.
            val effectsSwitch = debugSwitch("debug.diplay.mic_effects", true)
            if (config.audioType == "telephony" && effectsSwitch) {
                effects = voiceEffects(nextRecorder.audioSessionId)
            }
            reportEffects(effectsSwitch)
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder), chosenSource)
            // Debug (silent-call hunt): what the HAL actually granted, which can differ from the request.
            runCatching {
                stats.note("recorder", "recordingState=${nextRecorder.recordingState} state=${nextRecorder.state} " +
                    "actualRate=${nextRecorder.sampleRate} actualChannels=${nextRecorder.channelCount} " +
                    "audioFormat=${nextRecorder.audioFormat} sessionId=${nextRecorder.audioSessionId} " +
                    "bindAddress=${addressKind(bindAddress)}")
            }
            thread = WorkerThreads.create({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    /** Highest absolute 16-bit sample of one PCM frame; 0 means the capture is silent. */
    private fun peakOf(frame: ByteArray, length: Int = frame.size): Int {
        var peak = 0
        var index = 0
        while (index + 1 < length) {
            val low = frame[index].toInt() and 0xFF
            val high = frame[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return peak
    }

    /** Sum of absolute 16-bit samples of one PCM frame, for the report's mean level. */
    private fun sumAbsOf(frame: ByteArray): Long {
        var sum = 0L
        var index = 0
        while (index + 1 < frame.size) {
            val sample = (((frame[index + 1].toInt()) shl 8) or (frame[index].toInt() and 0xFF)).toShort().toInt()
            sum += if (sample < 0) -sample else sample
            index += 2
        }
        return sum
    }

    /** The kind of an address only — never the address itself — for the report. */
    private fun addressKind(address: java.net.InetAddress?): String = when (address) {
        null -> "none"
        is java.net.Inet4Address -> if (address.isAnyLocalAddress) "ipv4-any" else "ipv4"
        is java.net.Inet6Address -> when {
            address.isAnyLocalAddress -> "ipv6-any"
            address.isLinkLocalAddress -> if (address.scopeId != 0) "ipv6-linklocal-scoped" else "ipv6-linklocal-unscoped"
            else -> "ipv6"
        }
        else -> "other"
    }

    private fun sourceLadder(primary: Int): List<Int> = listOf(
        primary,
        // The CS11 only opens the microphone through VOICE_COMMUNICATION: VOICE_RECOGNITION, MIC and
        // DEFAULT all answer the probe with -3 there, which is why Siri (speechrecognition) fell back
        // to the iPhone's own microphone while a call — which already uses VOICE_COMMUNICATION — sent
        // the car's microphone fine.
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    ).distinct()

    private fun voiceEffects(sessionId: Int): List<AudioEffect> = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    effectStatus[name] = "enabled"
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
                effectStatus[name] = "refused$status"
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
                effectStatus[name] = "unavailable"
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
            effectStatus[name] = "failed"
        }
        effect?.let(::releaseEffect)
        return null
    }

    /**
     * One line saying whether echo cancellation / noise suppression ran on this capture and why.
     * They only ever run for `telephony`; the switch is `debug.diplay.mic_effects`.
     */
    private fun reportEffects(switchOn: Boolean) {
        val detail = when {
            config.audioType != "telephony" -> "applied=no reason=not-telephony switch=${onOff(switchOn)}"
            !switchOn -> "applied=no reason=switch-off switch=off"
            else -> "applied=yes switch=on " +
                listOf("AEC", "NS").joinToString(" ") { "${it.lowercase()}=${effectStatus[it] ?: "unknown"}" }
        }
        stats.effects(detail)
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramTransport) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        var filled = 0
        var levelPeak = 0
        var levelFrames = 0
        var endedBy = "closed"
        val silence = DigitalSilenceDetector(config.sampleRate)
        val samplesPerFrame = frame.size / 2 / maxOf(1, config.channels)
        try {
            while (running.get()) {
                stats.reading()
                // CS11: 4.4 的 3 参 read 即阻塞
                val count = recorder.read(readBuffer, 0, readBuffer.size)
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        endedBy = "read-error"
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        // A silent capture (routing or effect chain) otherwise looks identical to a
                        // working one in the byte counters, so report the level once per second.
                        val framePeak = peakOf(frame)
                        stats.level(framePeak, sumAbsOf(frame), frame.size / 2)
                        levelPeak = maxOf(levelPeak, framePeak)
                        if (silence.onFrame(framePeak, samplesPerFrame)) {
                            reportBlocked(MicrophoneBlockedNotice.Reason.DIGITAL_SILENCE)
                        }
                        if (++levelFrames >= LEVEL_FRAMES_PER_LOG) {
                            Log.i(TAG, "microphone level peak=$levelPeak frames=$levelFrames")
                            levelPeak = 0
                            levelFrames = 0
                        }
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                endedBy = "exception"
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            stats.finish(endedBy)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramTransport, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() }, bodies.sumOf { it.size.toLong() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramTransport,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(packet, packet.size, config.host, config.port)
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorderRouteTypeCompat(recorder) }.getOrNull()

    /**
     * The car's voice wake word most likely holds the microphone: say so in the report and on screen.
     * The report line is what the diagnostic page and a later readback grep for.
     */
    private fun reportBlocked(reason: MicrophoneBlockedNotice.Reason) {
        val message = "Microphone: blocked type=${config.audioType.take(24).filter { it.isLetterOrDigit() }} " +
            "reason=${reason.token}"
        Log.w(TAG, message)
        runCatching { onDiagnostic(message) }
        MicrophoneBlockedNotice.publish(reason)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        /** 20 ms frames per level report (one second at 48 kHz). */
        const val LEVEL_FRAMES_PER_LOG = 50
        const val CLOSE_JOIN_MILLIS = 500L
        /** Probe results that are not AudioRecord codes: the recorder could not be built or initialized. */
        const val PROBE_CREATE_FAILED = -1000
        const val PROBE_NOT_INITIALIZED = -1001
    }
}
