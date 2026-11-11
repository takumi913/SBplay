package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.KernelSessionNetwork
import com.shilapi.xcertplay.transport.SessionNetwork
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String)

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}

    /**
     * The same frame, but its bytes live in a pooled buffer from [VideoFramePool]: the implementation
     * must call [OwnedVideoFrame.close] once the decoder no longer needs them. The default copy keeps
     * byte-array-only sinks working unchanged.
     */
    fun onVideoFrame(type: Int, frame: OwnedVideoFrame) {
        try {
            onVideoFrame(type, frame.copyOfBytes())
        } finally {
            frame.close()
        }
    }

    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(id: AudioStreamId) {}
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    /** One line per audio stream SETUP saying whether a microphone was planned for it, and why not. */
    fun onAudioSetupDiagnostic(line: String) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    /**
     * The network the channels run on: the kernel stack by default, lwIP when the wired session is carried
     * by the userspace NCM bridge. The Activity creates this engine while the transport is chosen at attach
     * time, so it is settable rather than a constructor parameter; the controller sets it before the wired
     * session starts (docs/_digest/58 §6 step 7).
     */
    @Volatile internal var network: SessionNetwork = KernelSessionNetwork
        set(value) {
            field = value
            // The microphone uplink is built by the sink, not by this engine, so the stack the session was
            // switched to has to reach it: otherwise the mic keeps the kernel default while every other
            // channel runs on lwIP, and on this ROM the platform layer rejects the link-local it then
            // binds (measured 2026-10-13: `stage=SOCKET_CREATION reason=ipv6-scope-id-required`, which
            // recorded WeChat voice notes as silence).
            (sink as? com.shilapi.xcertplay.media.AndroidMediaSink)?.network = value
        }

    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val videoSettingsChannels = ConcurrentHashMap<AirPlaySession, VideoSettingsChannel>()
    private val bufferedStreams = ConcurrentHashMap<AirPlaySession, BufferedStream>()

    private class BufferedStream(val stream: BufferedAudioStream, val connectionId: Any?)

    // The sink has one type-103 renderer, even while old control sessions remain connected.
    // Never hold this lock when closing/controlling a stream: sink callbacks can reenter us.
    private val bufferedOwnershipLock = Any()
    private var bufferedOwner: AirPlaySession? = null
    private var bufferedTransition: BufferedTransition? = null
    private val retiredBufferedSessions = Collections.newSetFromMap(WeakHashMap<AirPlaySession, Boolean>())
    private class BufferedTransition(val session: AirPlaySession, val candidate: BufferedStream?) {
        var cancelled = false // guarded by bufferedOwnershipLock
        var retiring: BufferedStream? = null
    }

    private fun mayInstallBufferedLocked(session: AirPlaySession): Boolean {
        // A reentrant sink callback cannot wait for itself. Preserve its retirement barrier
        // until cleanup completes; the volatile flag requires no reverse stream-lock order.
        bufferedTransition?.takeIf { it.candidate == null && it.retiring?.stream?.outputCleanupComplete == true }
            ?.let { bufferedTransition = null }
        return !session.isClosed && session !in retiredBufferedSessions && bufferedTransition == null
    }
    @Volatile private var nextRemoteControlStreamId = FIRST_REMOTE_CONTROL_STREAM_ID
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        val screen = ScreenStream(
            key,
            session::logDebug,
            network = network,
            expectedPeer = session.remoteAddress,
        )
        sink.setVideoDiagnosticHandler(type) {
            if (it == "first frame rendered") session.videoFrameRendered()
            session.logDebug("Video: $it")
        }
        sink.setVideoRecoveryHandler(type) {
            if (streams[streamKey] === screen) {
                // The command must name the display: the RTSP layer accepts a uuid-less forceKeyFrame
                // and reports success, but the phone then never re-sends an IDR for the main screen,
                // which leaves a rebuilt decoder with nothing to show — the black screen after
                // returning from the car's launcher (docs/_digest/36). The plain command stays as a
                // fallback for a phone that wants no uuid.
                val uuid = if (type == STREAM_TYPE_ALT_SCREEN) AirPlayInfoPlist.ALT_UUID
                else AirPlayInfoPlist.MAIN_UUID
                val named = session.sendCommand(
                    mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to uuid)),
                )
                val sent = named || session.sendCommand(mapOf("type" to "forceKeyFrame"))
                session.logDebug("Video recovery: requested keyframe uuid=$uuid named=$named sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = sink.onVideoCodec(type, codec)
                override fun onConfig(codecData: ByteArray) = sink.onVideoConfig(type, codecData)
                override fun onFrame(naluBytes: ByteArray) = sink.onVideoFrame(type, naluBytes)
                override fun onOwnedFrame(frame: OwnedVideoFrame) = sink.onVideoFrame(type, frame)
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    if (streams.remove(streamKey, screen)) {
                        sink.onScreenStreamActive(type, false)
                    }
                    session.close()
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        sink.onScreenStreamActive(type, true)
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        // Concurrent streams may share a type (e.g. music, guidance and Siri can all arrive as
        // type 100); only the same (type, audioType) pair replaces a previous stream.
        val streamKey = StreamKey(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        streams.remove(streamKey)?.close()
        audioMeta.remove(streamKey)
        audioCaptures.remove(streamKey)?.close()
        if (pendingMicrophone.remove(streamKey) != null) {
            reportMicrophoneStop(streamId, "resetup")
            sink.onMicrophoneStopped(streamId)
        }
        sink.onAudioStopped(streamId)

        val key = outputKey(session, stream) ?: run {
            // Debug (silent-call hunt): a declined SETUP never reached the `Audio: setup` line below.
            runCatching {
                sink.onAudioSetupDiagnostic("Audio: setup declined type=${safeToken(audioType)} stream=$type " +
                    "reason=no-output-key")
            }
            return null
        }
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "formatBits=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        var microphoneSkip = "none"
        val microphone = microphoneConfig(session, type, stream, format) { microphoneSkip = it }
        if (microphone != null) pendingMicrophone[streamKey] = microphone
        reportAudioSetup(type, audioType, format, stream, microphone, microphoneSkip)

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[streamKey] = capture
        val audio = AudioStream(key, type, session::logDebug, network = network)
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(streamId, format, firstSample)
                    microphone?.let { sink.onMicrophoneStarted(streamId, it) }
                }

                override fun onRtp(rtp: ByteArray, sample: Int) =
                    sink.onAudioRtp(streamId, format, rtp, sample)

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    capture?.record(wire, rtp, sample, error)
                }
            },
        )
        streams[streamKey] = audio
        audioMeta[streamKey] = meta
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
    }

    override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!synchronized(bufferedOwnershipLock) { mayInstallBufferedLocked(session) }) return null
        val type = BufferedAudioStream.STREAM_TYPE
        val streamKey = StreamKey(session, type, "media")
        val key = stream["shk"] as? ByteArray
        if (key == null || key.size != 32) {
            Log.w(TAG, "buffered audio SETUP without a 32-byte shk; declined")
            return null
        }
        val format = AudioStreamCodec.fromFormatBits((stream["audioFormat"] as? Number)?.toLong() ?: 0L, type, "media")
        if (format.codec != AudioCodecKind.AAC_LC) {
            Log.w(TAG, "buffered audio format ${stream["audioFormat"]} is not AAC-LC; declined")
            return null
        }
        if (stream["ct"]?.let { it !is Number || it.toInt() != 4 } == true ||
            stream["spf"]?.let { it !is Number || it.toInt() != 1024 } == true) {
            Log.w(TAG, "buffered audio requires AAC and 1024 samples per frame; declined")
            return null
        }
        val peer = session.remoteAddress ?: return null
        val buffered = try {
            BufferedAudioStream(key, format, sink, session::logDebug, expectedPeer = peer, network = network)
        } catch (error: Exception) {
            Log.w(TAG, "buffered audio listener failed", error)
            return null
        }
        // Validate before retiring a healthy owner. Detach its registry entries first, but
        // keep a transition barrier until close has finished its final sink callback.
        val candidate = BufferedStream(buffered, stream["streamConnectionID"])
        val transition = BufferedTransition(session, candidate)
        var previous: BufferedStream? = null
        val accepted = synchronized(bufferedOwnershipLock) {
            if (!mayInstallBufferedLocked(session)) false else {
                bufferedTransition = transition
                bufferedOwner?.let { oldSession ->
                    previous = detachBufferedLocked(oldSession)
                    if (oldSession !== session) retiredBufferedSessions.add(oldSession)
                }
                transition.retiring = previous
                true
            }
        }
        if (!accepted) {
            buffered.close()
            return null
        }
        val retired = runCatching { previous?.stream?.close() }.isSuccess &&
            previous?.stream?.outputCleanupComplete != false
        val installed = synchronized(bufferedOwnershipLock) {
            val usable = retired && bufferedTransition === transition && !transition.cancelled &&
                !session.isClosed && session !in retiredBufferedSessions
            if (usable) {
                streams[streamKey] = buffered
                bufferedStreams[session] = candidate
                bufferedOwner = session
            }
            if (bufferedTransition === transition) {
                bufferedTransition = if (retired) null else BufferedTransition(session, null).also { it.retiring = previous }
            }
            usable
        }
        if (!installed) {
            buffered.close()
            return null
        }
        buffered.start()
        if (bufferedStreams[session] !== candidate) return null
        Log.i(TAG, "buffered audio stream client=${stream["clientID"]} rate=${format.sampleRate} port=${buffered.port}")
        return linkedMapOf(
            "type" to type,
            "dataPort" to buffered.port,
            "audioBufferSize" to BufferedAudioStream.AUDIO_BUFFER_BYTES,
        )
    }

    override fun onBufferedAudioControl(
        session: AirPlaySession,
        method: String,
        body: Map<String, Any?>,
    ): Map<String, Any?>? {
        val buffered = synchronized(bufferedOwnershipLock) { bufferedStreams[session]?.stream } ?: return null
        val rtpTime = (body["rtpTime"] as? Number)?.toLong()
        val rate = when ((body["rate"] as? Number)?.toDouble()) {
            0.0 -> 0
            1.0 -> 1
            else -> null
        }
        return when (method) {
            "SETRATE", "SETRATEANCHORTIME" -> {
                if (rate == null || rate !in 0..1) null else buffered.setRate(rtpTime, rate, session.syncedNtp())
            }
            "GETANCHOR" -> buffered.anchor()
            "FLUSHBUFFERED" -> {
                (body["flushUntilTS"] as? Number)?.toLong()?.let { buffered.flush(it) }
                null
            }
            else -> null
        }
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (session.videoInCar) videoDataStream(session, uuid, stream)?.let { return it }
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> serverBindAddress()
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
            network = network,
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        val previous = streams.put(StreamKey(session, STREAM_TYPE_DATA), if (handler != null) bridge else tunnel)
        // A repeated SETUP replaces the tunnel; leaving the old one open leaks its port and receiver.
        previous?.let { runCatching { it.close() } }
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    /**
     * Video in car: the settings channel gets its own encrypted socket; the remote control sessions that
     * carry playback have none (controlType 1), their messages arrive as commands with X-Apple-StreamID.
     */
    private fun videoDataStream(session: AirPlaySession, uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (uuid in VideoInCar.REMOTE_CONTROL_UUIDS && (stream["controlType"] as? Number)?.toInt() == 1) {
            val streamId = nextRemoteControlStreamId++
            session.logTrace("video remote-control stream accepted uuid=$uuid streamID=$streamId")
            return linkedMapOf("type" to STREAM_TYPE_DATA, "streamID" to streamId)
        }
        if (uuid != VideoInCar.SETTINGS_CHANNEL_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            shared, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32,
        )
        val channel = VideoSettingsChannel(key(DATASTREAM_OUTPUT_KEY), key(DATASTREAM_INPUT_KEY), network) { session.logDebug(it) }
        val port = channel.listen(session.localAddress ?: InetAddress.getByName("::"))
        videoSettingsChannels.put(session, channel)?.close()
        session.logTrace("video settings stream listening port=$port")
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to VIDEO_SETTINGS_STREAM_ID, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        // Only this session's streams: a closed session can still be reporting while the next
        // one is already setting up.
        val active = audioMeta.entries.filter { it.key.session === session }.map { it.value }
        val buffered = bufferedStreams[session]
        if (active.isEmpty() && buffered == null) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        } + listOfNotNull(buffered?.let { it.stream.feedback(session.syncedNtp(), it.connectionId) })
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        if (type == BufferedAudioStream.STREAM_TYPE) {
            retireBuffered(session)
            return
        }
        // TEARDOWN carries only the stream type; release every audioType variant of it.
        val tornDown = streams.keys.filter { it.session === session && it.type == type }
        tornDown.forEach { key ->
            val streamId = AudioStreamId(key.type, key.audioType)
            if (pendingMicrophone.remove(key) != null) {
                reportMicrophoneStop(streamId, "teardown")
                sink.onMicrophoneStopped(streamId)
            }
            audioMeta.remove(key)
            audioCaptures.remove(key)?.close()
            // BufferedAudioStream owns serialized sink cleanup; stopping it first from here
            // could interleave with its in-flight delivery and recreate a stale renderer.
            if (key.type != BufferedAudioStream.STREAM_TYPE) sink.onAudioStopped(streamId)
            streams.remove(key)?.close()
        }
        if (isScreenStreamType(type)) sink.onScreenStreamActive(type, false)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        clearPendingIapTunnel(session)
        retireBuffered(session)
        videoSettingsChannels.remove(session)?.close()
        val sessionStreams = streams.keys.filter {
            it.session === session && it.type != BufferedAudioStream.STREAM_TYPE
        }
        sessionStreams
            .filter { isScreenStreamType(it.type) }
            .forEach { sink.onScreenStreamActive(it.type, false) }
        sessionStreams.forEach { streams.remove(it)?.close() }
        val sessionKeys = (audioMeta.keys.asSequence() + pendingMicrophone.keys.asSequence() +
            audioCaptures.keys.asSequence()).distinct().filter { it.session === session }
        sessionKeys.forEach { key ->
            audioMeta.remove(key)
            // Debug: the uplink is not stopped here (the sink's close() does it); the line says so.
            if (pendingMicrophone.remove(key) != null) {
                reportMicrophoneStop(AudioStreamId(key.type, key.audioType), "session-closed")
            }
            audioCaptures.remove(key)?.close()
        }
    }

    /** Removes ownership before a close that may block on delivery or invoke the sink. */
    private fun detachBufferedLocked(session: AirPlaySession): BufferedStream? {
        val previous = bufferedStreams.remove(session)
        previous?.let { streams.remove(StreamKey(session, BufferedAudioStream.STREAM_TYPE, "media"), it.stream) }
        if (bufferedOwner === session) bufferedOwner = null
        return previous
    }

    private fun retireBuffered(session: AirPlaySession) {
        var pending: BufferedStream? = null
        var retirement: BufferedTransition? = null
        val previous = synchronized(bufferedOwnershipLock) {
            bufferedTransition?.takeIf { it.session === session }?.let {
                it.cancelled = true
                pending = it.candidate
                // The SETUP thread still owns the barrier while it closes the earlier owner.
            }
            detachBufferedLocked(session).also {
                if (it != null && bufferedTransition == null) {
                    retirement = BufferedTransition(session, null).also { barrier -> barrier.retiring = it }
                    bufferedTransition = retirement
                }
            }
        }
        try {
            pending?.stream?.close()
            previous?.stream?.close()
        } finally {
            synchronized(bufferedOwnershipLock) {
                if (retirement != null && bufferedTransition === retirement &&
                    previous?.stream?.outputCleanupComplete == true) bufferedTransition = null
            }
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    /**
     * The report line for one audio SETUP. Whether iOS asked for the car's microphone on this stream
     * is the first question when a call is silent, and before this line it only reached logcat, which
     * this ROM does not show for the app. Values are closed tokens: no port, address or key.
     */
    private fun reportAudioSetup(
        type: Int,
        audioType: String,
        format: AudioFormat,
        stream: Map<String, Any?>,
        microphone: MicrophoneConfig?,
        skip: String,
    ) {
        val input = when (val raw = stream["input"]) {
            is Boolean -> raw.toString()
            is Number -> (raw.toInt() != 0).toString()
            null -> "absent"
            else -> "other"
        }
        val dataPort = if (((stream["dataPort"] as? Number)?.toInt() ?: 0) in 1..65535) "yes" else "no"
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val safeType = safeToken(audioType)
        val mic = if (microphone != null) "planned" else "skipped reason=$skip"
        // Debug (silent-call hunt): the names (never values) of what iOS put in this SETUP, so a field this
        // code does not read yet (an input format, a latency) can be spotted from one report.
        val keys = stream.keys.asSequence()
            .map { it.filter { c -> c.isLetterOrDigit() }.take(32) }
            .filter { it.isNotEmpty() && !UNSAFE_KEY_NAME.containsMatchIn(it) }
            .sorted().take(24).joinToString(",")
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val latency = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        runCatching {
            sink.onAudioSetupDiagnostic(
                "Audio: setup type=$safeType stream=$type codec=${format.codec.name} rate=${format.sampleRate} " +
                    "channels=${format.channels} formatBits=0x${java.lang.Long.toHexString(formatBits)} " +
                    "input=$input dataPort=$dataPort mic=$mic",
            )
            sink.onAudioSetupDiagnostic(
                "Audio: setup fields type=$safeType stream=$type framesPerPacket=$framesPerPacket " +
                    "latencyMs=$latency keys=$keys",
            )
        }
    }

    /** Debug: why the engine asked the sink to stop a stream's microphone. */
    private fun reportMicrophoneStop(id: AudioStreamId, reason: String) {
        runCatching {
            sink.onAudioSetupDiagnostic("Microphone: stop requested type=${safeToken(id.audioType)} " +
                "stream=${id.type} reason=$reason")
        }
    }

    private fun safeToken(raw: String): String =
        raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(24).ifEmpty { "unknown" }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
        skipped: (String) -> Unit = {},
    ): MicrophoneConfig? {
        if (!microphoneEnabled) return null.also { skipped("mic-disabled") }
        if (type != STREAM_TYPE_MAIN_AUDIO) return null.also { skipped("not-main-audio") }
        if (!wantsMicrophone(format.audioType, stream)) return null.also { skipped("no-input-requested") }
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null.also { skipped("no-data-port") }
        if (port !in 1..65535) return null.also { skipped("bad-data-port") }
        val host = session.remoteAddress ?: return null.also { skipped("no-peer-address") }
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null.also { skipped("no-stream-secret") }
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val preferredMillis = if (format.codec == AudioCodecKind.OPUS) {
            MicrophoneFramePolicy.DEFAULT_MILLIS
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(1, 60)
        } else {
            MicrophoneFramePolicy.DEFAULT_MILLIS
        }
        // Wired LPCM is capped so a sealed packet fits the NCM MTU: 20 ms at 48 kHz mono was 1956 bytes on
        // the wire, so the phone received IPv6 fragments and recorded silence while wireless (Opus, tiny
        // frames) kept working (docs/_digest/58 §10).
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            preferredMillis
        } else {
            MicrophoneFramePolicy.resolve(
                preferredMillis = preferredMillis,
                sampleRate = format.sampleRate,
                channels = format.channels,
                overrideMillis = MicrophoneFramePolicy.currentOverride(),
            )
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            opusClockRate = MicrophoneConfig.opusClockRate(formatBits),
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    internal companion object {
        /**
         * Calls and Siri always record. App audio ("default", e.g. a WhatsApp voice note) also carries
         * guidance prompts, so it records only when the iPhone asks for input: an explicit `input`
         * flag, or, without one, the uplink port the iPhone sends for a recording stream. Without
         * this, a voice note sent over CarPlay recorded silence unless a call had just ended
         * (upstream c2afb171).
         */
        fun wantsMicrophone(audioType: String, stream: Map<String, Any?>): Boolean = when (audioType) {
            "telephony", "speechrecognition" -> true
            "default", "compatibility" -> when (val input = stream["input"]) {
                is Boolean -> input
                is Number -> input.toInt() != 0
                else -> (stream["dataPort"] as? Number)?.toInt()?.let { it in 1..65535 } == true
            }
            else -> false
        }

        private const val TAG = "xcertplay-usb"
        /** SETUP key names that could look like secrets to the report redactor (which drops such lines). */
        private val UNSAFE_KEY_NAME = Regex("(?i)pass|token|secret|ssid|name|cert|record|payload|hex|body")
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        const val VIDEO_SETTINGS_STREAM_ID = 2L
        const val FIRST_REMOTE_CONTROL_STREAM_ID = 3L
        const val OPUS_24K = 0x20000000L
        const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}
