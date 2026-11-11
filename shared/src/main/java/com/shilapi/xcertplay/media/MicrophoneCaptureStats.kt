package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MicrophoneConfig

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val metadata = metadata(config)
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var capturedBytes = 0L
    private var reads = 0L
    private var zeroReads = 0L
    private var readErrors = 0L
    private var readMaxNs = 0L
    private var encodedFrames = 0L
    private var emptyEncodedFrames = 0L
    private var udpSent = 0L
    private var sendErrors = 0L
    private var lastSentNs: Long? = null
    private var sendGapMaxNs = 0L
    private var routedDeviceType: Int? = null

    // Level of the captured PCM, per window and for the whole capture. Byte counters alone cannot tell a
    // microphone that records speech from one that records silence (measured 2026-10-08: the car's
    // telephony capture peaked at 5 while the bench peaked between 5000 and 18800), and the app's own
    // Log.i never reaches this ROM's logcat, so the level has to travel in the report.
    private var frames = 0L
    private var silentFrames = 0L
    private var peakMax = 0
    private val startedNs = windowStart
    private var totalFrames = 0L
    private var totalSilentFrames = 0L
    private var totalPeakMax = 0
    private var totalCapturedBytes = 0L
    private var totalUdpSent = 0L
    private var totalSendErrors = 0L
    private var totalReadErrors = 0L
    private var totalEmptyEncodedFrames = 0L

    // Debug detail for the silent-call hunt (2026-11): a peak alone cannot tell a muted input (every sample
    // exactly 0) from a live but unconnected one (a noise floor of a few counts), nor whether sound ever
    // arrived later in the call. These are removable once the call microphone is fixed.
    private var zeroFrames = 0L
    private var sumAbs = 0L
    private var samples = 0L
    private var totalZeroFrames = 0L
    private var totalLoudFrames = 0L
    private var totalSumAbs = 0L
    private var totalSamples = 0L
    private var firstSoundMs: Long? = null
    private var totalEncodedBytes = 0L
    private var totalEncodedBodies = 0L

    fun started(routeType: Int?, actualSource: Int? = null) {
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"}" +
            (actualSource?.let { " actualSource=${sourceName(it)}" } ?: ""))
    }

    /** One recorder candidate of the source ladder, what its first read returned, and that read's peak. */
    fun probe(source: Int, result: Int, peak: Int? = null) {
        emit("Microphone: probe $metadata candidate=${sourceName(source)} result=$result" +
            (peak?.let { " probePeak=$it" } ?: ""))
    }

    /**
     * A free-form debug line of this capture. [detail] must be built by the uplink from numbers and fixed
     * tokens only (never an address, port, key or exception message).
     */
    fun note(kind: String, detail: String) {
        emit("Microphone: $kind $metadata $detail")
    }

    /** Whether the voice effects ran on this capture; [detail] is a fixed vocabulary built by the uplink. */
    fun effects(detail: String) {
        emit("Microphone: effects $metadata $detail")
    }

    /**
     * Highest absolute sample of one 16-bit PCM frame, as measured by the capture thread, plus the sum of
     * absolute samples and the sample count of the same frame (0 when the caller does not measure them).
     */
    fun level(peak: Int, frameSumAbs: Long = 0, frameSamples: Int = 0) {
        frames++
        totalFrames++
        if (peak < SILENT_PEAK) {
            silentFrames++
            totalSilentFrames++
        } else if (firstSoundMs == null) {
            firstSoundMs = (nowNs() - startedNs).coerceAtLeast(0) / 1_000_000L
        }
        if (peak == 0) {
            zeroFrames++
            totalZeroFrames++
        }
        if (peak >= LOUD_PEAK) totalLoudFrames++
        if (frameSamples > 0) {
            sumAbs += frameSumAbs
            samples += frameSamples
            totalSumAbs += frameSumAbs
            totalSamples += frameSamples
        }
        if (peak > peakMax) peakMax = peak
        if (peak > totalPeakMax) totalPeakMax = peak
    }

    /**
     * One line for the whole capture, written when it ends: the failure-detail page reads this to
     * say whether the car recorded sound and whether it reached the phone.
     */
    fun finish(endedBy: String = "unknown") {
        val seconds = (nowNs() - startedNs).coerceAtLeast(0) / 1_000_000_000L
        val avgPacket = if (totalEncodedBodies > 0) totalEncodedBytes / totalEncodedBodies else 0
        emit("Microphone: summary $metadata seconds=$seconds captureBytes=$totalCapturedBytes " +
            "frames=$totalFrames silentFrames=$totalSilentFrames peakMax=$totalPeakMax " +
            "zeroFrames=$totalZeroFrames loudFrames=$totalLoudFrames meanAbs=${mean(totalSumAbs, totalSamples)} " +
            "firstSoundMs=${firstSoundMs ?: "never"} " +
            "readErrors=$totalReadErrors emptyEncodedFrames=$totalEmptyEncodedFrames " +
            "encodedBytes=$totalEncodedBytes avgPacketBytes=$avgPacket endedBy=$endedBy " +
            "udpSent=$totalUdpSent sendErrors=$totalSendErrors")
    }

    fun reading() { readStart = nowNs() }

    fun read(count: Int) {
        reads++
        readMaxNs = maxOf(readMaxNs, (nowNs() - readStart).coerceAtLeast(0))
        when {
            count > 0 -> { capturedBytes += count; totalCapturedBytes += count }
            count == 0 -> zeroReads++
            else -> { readErrors++; totalReadErrors++ }
        }
    }

    fun encoded(frameCount: Int, emptyCount: Int, bytes: Long = 0) {
        val nonEmpty = (frameCount - emptyCount).coerceAtLeast(0)
        totalEncodedBodies += nonEmpty
        totalEncodedBytes += bytes.coerceAtLeast(0)
        encodedFrames += frameCount.coerceAtLeast(0)
        emptyEncodedFrames += emptyCount.coerceAtLeast(0)
        totalEmptyEncodedFrames += emptyCount.coerceAtLeast(0)
    }

    fun sent() {
        val now = nowNs()
        lastSentNs?.let { sendGapMaxNs = maxOf(sendGapMaxNs, (now - it).coerceAtLeast(0)) }
        lastSentNs = now
        udpSent++
        totalUdpSent++
    }

    fun sendFailed() { sendErrors++; totalSendErrors++ }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null) {
        val now = nowNs()
        if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        emit("Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
            "captureBytes=$capturedBytes reads=$reads zeroReads=$zeroReads readErrors=$readErrors " +
            "readMaxMs=${readMaxNs / 1_000_000} encodedFrames=$encodedFrames " +
            "emptyEncodedFrames=$emptyEncodedFrames udpSent=$udpSent sendErrors=$sendErrors " +
            "sendGapMaxMs=${sendGapMaxNs / 1_000_000} frames=$frames silentFrames=$silentFrames " +
            "peakMax=$peakMax zeroFrames=$zeroFrames meanAbs=${mean(sumAbs, samples)} ended=$ended")
        windowStart = now
        capturedBytes = 0
        reads = 0
        zeroReads = 0
        readErrors = 0
        readMaxNs = 0
        encodedFrames = 0
        emptyEncodedFrames = 0
        udpSent = 0
        sendErrors = 0
        sendGapMaxNs = 0
        frames = 0
        silentFrames = 0
        peakMax = 0
        zeroFrames = 0
        sumAbs = 0
        samples = 0
    }

    private fun mean(sum: Long, count: Long): Long = if (count > 0) sum / count else 0

    private fun emit(message: String) { runCatching { report(message) } }

    companion object {
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        /**
         * A 16-bit frame whose loudest sample stays under this is counted as silent (about -54 dBFS).
         * Speech near a car microphone peaks in the thousands; the silent CS11 capture peaked at 5.
         */
        const val SILENT_PEAK = 64

        /** A frame at or above this peak is counted as clearly loud (speech close to the microphone). */
        const val LOUD_PEAK = 1000

        /** The AudioSource constant as a name; numbers outside the ladder stay numbers. */
        fun sourceName(source: Int): String = when (source) {
            0 -> "DEFAULT"
            1 -> "MIC"
            6 -> "VOICE_RECOGNITION"
            7 -> "VOICE_COMMUNICATION"
            else -> "source$source"
        }

        private fun metadata(config: MicrophoneConfig): String {
            val (type, source) = when (config.audioType) {
                "telephony" -> "telephony" to "VOICE_COMMUNICATION"
                "speechrecognition" -> "speechrecognition" to "VOICE_RECOGNITION"
                // Named, not "other": which of these a WeChat call arrives on is still open
                // (VoiceCallWatch), and the failure-detail page has to tell them apart.
                "default" -> "default" to "MIC"
                "compatibility" -> "compatibility" to "MIC"
                else -> "other" to "MIC"
            }
            return "type=$type source=$source codec=${config.codec.name} " +
                "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "reason=${failureReason(error)} " +
            "code=${code ?: "none"}"

        /**
         * The failure's kind, from a fixed vocabulary — never its message.
         *
         * Measured on the car on 2026-10-13: the wired microphone failed with
         * `stage=SOCKET_CREATION error=SocketException code=none`, and a bare class name cannot tell
         * "the address is not bindable" from "lwIP ran out of sockets". This classifies the exception
         * text into one of a closed set of tokens, so the reason is readable without ever putting the
         * exception's own text (which can carry endpoints or keys) into the log -- the invariant
         * `MicrophoneCaptureStatsTest` guards.
         */
        private fun failureReason(error: Throwable?): String {
            val text = buildString {
                append(error?.message ?: "")
                append(' ')
                append(error?.cause?.message ?: "")
                append(' ')
                append(error?.cause?.javaClass?.simpleName ?: "")
            }.lowercase()
            return when {
                text.contains("scope id") -> "ipv6-scope-id-required"
                text.contains("eaddrnotavail") || text.contains("errno 99") || text.contains("error=99") -> "addr-not-available"
                text.contains("eaddrinuse") || text.contains("errno 98") || text.contains("error=98") -> "addr-in-use"
                text.contains("enoprotoopt") || text.contains("errno 92") || text.contains("error=92") -> "no-proto-option"
                text.contains("enfile") || text.contains("errno 23") || text.contains("error=23") -> "no-sockets"
                text.contains("permission") || text.contains("eacces") || text.contains("errno 13") -> "permission-denied"
                text.contains("network is unreachable") || text.contains("enetunreach") -> "network-unreachable"
                text.contains("bind") -> "bind-failed"
                text.contains("socket") -> "socket-failed"
                text.isEmpty() || text.isBlank() -> "none"
                else -> "unclassified"
            }
        }

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }
}
