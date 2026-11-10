package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.OwnedVideoFrame
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.transport.DatagramNetwork
import com.shilapi.xcertplay.transport.KernelDatagramNetwork
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.compat.systemServiceOrNull
import com.shilapi.xcertplay.compat.inputBufferCompat
import com.shilapi.xcertplay.compat.outputBufferCompat
import com.shilapi.xcertplay.compat.trackUnderrunsCompat
import com.shilapi.xcertplay.compat.trackRouteTypeCompat
import com.shilapi.xcertplay.compat.trackBufferSizeFramesCompat
import com.shilapi.xcertplay.compat.WorkerThreads

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val streamType: Int)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var held = false
    private var requestedChannel: AudioChannel? = null
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // A callback we do not act on is still worth a line. The window between a telephony
            // track closing and media reopening is exactly where a head unit that keeps focus for
            // itself produces its callback, and a report that shows nothing there cannot be told
            // apart from a head unit that never called back at all, so a stale callback stays
            // visible as one. "Stale" is read from our own state: no live request ([held]) or no
            // eligible track left. On API 19 the legacy requestAudioFocus call keeps no request
            // object, so [held] is the only honest signal for that half of the condition.
            if (active.isEmpty() || !held) {
                runCatching {
                    report("Audio: focus change=$change dropped held=$held activeTracks=${active.size}")
                }
            } else {
                runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_GAIN -> setVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent or transient loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, streamType: Int = AudioManager.STREAM_MUSIC) {
        if (!enabled || manager == null) return
        active[track] = Entry(channel, streamType)
        refreshRequest()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    private fun refreshRequest() {
        // A navigation prompt is short but must own the car's volume keys while it plays: this head
        // unit adjusts getCurMasterStream, which follows the stream that currently holds focus, so a
        // prompt that never takes focus leaves the keys on media (docs/_digest/35).
        val primary = active.values.firstOrNull { it.channel == AudioChannel.NAVIGATION }
            ?: active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            abandonFocus()
            requestedChannel = null
            return
        }
        if (held && requestedChannel == primary.channel) return
        if (held) abandonFocus()
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        // CS11: 遗留调用携带车机音量键应跟随的 stream：导航走 stream 12（vendor
        // STREAM_NAVI_TTS），其余走 STREAM_MUSIC。
        val granted = manager?.requestAudioFocus(listener, primary.streamType, gain) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = granted
        // A refused request must be retried, so do not remember it as the held channel/stream.
        requestedChannel = if (granted) primary.channel else null
        val line = "Audio: focus requested channel=${primary.channel} stream=${primary.streamType} " +
            "gain=$gain granted=$granted activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun abandonFocus() {
        manager?.abandonAudioFocus(listener)
        held = false
    }

    private fun setVolume(volume: Float) {
        active.keys.forEach { track -> runCatching { track.setStereoVolume(volume, volume) } }
    }

    // A call must win the focus even while music keeps its track open: the ROM derives its volume key
    // target (getCurMasterStream) from the focus owner, so ranking MEDIA above PHONE left the wheel
    // adjusting stream 3 during a call (docs/_digest/68 §5.4). Prompt channels stay below MEDIA until
    // batch 2 adds their liveness rule, or a lingering prompt entry would hold focus forever.
    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.PHONE -> 3
        AudioChannel.MEDIA -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    private val phoneChannel: Int = 0,
    /**
     * AAC 解码器偏好（设置页「声音设置」，见 [AudioDecoderChoice]）。
     * 只是**偏好**：Freescale 那条路不可用时渲染器一律回落到 MediaCodec，用户不会因此没声音。
     */
    private val audioDecoderChoice: AudioDecoderChoice = AudioDecoderChoice.DEFAULT,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
) : MediaSink {
    /**
     * The session's datagram stack. [CarPlayMediaEngine] points this at the userspace link on the wired
     * path, and [MicrophoneUplink] has to use it: with the kernel default the microphone bound through the
     * platform layer, which this ROM rejects for a link-local without a scope id -- measured on the car on
     * 2026-10-13 as `stage=SOCKET_CREATION reason=ipv6-scope-id-required`, and the reason a WeChat voice
     * note recorded silence while the phone still showed its own recording indicator.
     */
    @Volatile internal var network: DatagramNetwork = KernelDatagramNetwork

    private val appContext = context?.applicationContext
    private val audioManager = appContext?.systemServiceOrNull(AudioManager::class.java)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        onAudioDiagnostic,
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()

    /**
     * The VOIP-call inference: a two-way CarPlay audio stream on a type that can carry a call is a call
     * the iPhone never announced (docs/_digest/121). Feeding it here, from the events the engine already
     * delivers, keeps the audio path unchanged: this only reads stream lifecycle.
     */
    private val voiceCallWatch = VoiceCallWatch(
        report = { line -> runCatching { onAudioDiagnostic(line) } },
        publish = { active, since -> runCatching { CarPlayGlance.onVoiceCall(active, since) } },
    )
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        WorkerThreads.create(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    /**
     * Cumulative counters of the main screen's decoder, for the on-screen "显示画面帧率" overlay
     * (EasyPlay 10-80 parity). Null until the main decoder exists.
     */
    fun liveVideoCounters(): LiveVideoCounters? = videoDecoders[110]?.liveVideoCounters()

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        Log.i("xcertplay-usb", "video surface set type=$type id=${System.identityHashCode(surface)} valid=${surface.isValid}")
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        Log.i("xcertplay-usb", "video surface cleared type=$type id=${System.identityHashCode(surface)}")
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(mirrorLock) {
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
        }
        lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        lastVideoConfig[type] = codec to codecData
        videoDecoder(type).configure(codec, codecData)
        mirrorDecoders(type).forEach { it.configure(codec, codecData) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
        mirrorDecoders(type).forEach { it.submit(naluBytes) }
    }

    override fun onVideoFrame(type: Int, frame: OwnedVideoFrame) {
        try {
            val mirrors = mirrorDecoders(type)
            if (mirrors.isEmpty()) {
                // The pooled buffer goes straight to the decoder, which releases it after feeding the
                // codec. This is the last statement, so a failure here cannot leave it queued.
                videoDecoder(type).submit(frame)
                return
            }
            // A mirror decodes the same stream on another thread, so it cannot share the pooled buffer:
            // give the mirrors one copy (as before this change) and let the primary decoder own it.
            val copy = frame.copyOfBytes()
            mirrors.forEach { it.submit(copy) }
            videoDecoder(type).submit(frame)
        } catch (error: Throwable) {
            // Nothing has taken ownership yet (submit is the last call on each path), so return it.
            frame.close()
            throw error
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioSetupDiagnostic(line: String) {
        runCatching { onAudioDiagnostic(line) }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        // The microphone of a call starts on this event (the first downlink packet), so a call whose
        // downlink never arrives never opens the microphone; this line is how the report shows that.
        runCatching {
            onAudioDiagnostic("Audio: downlink started type=${safeAudioType(id.audioType)} stream=${id.type} " +
                "codec=${format.codec.name} rate=${format.sampleRate} channels=${format.channels}")
        }
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
        // Downlink audio is the half of a VOIP call the iPhone cannot hide: see [VoiceCallWatch].
        voiceCallWatch.downlinkStarted(id)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        voiceCallWatch.downlinkStopped(id)
        audioRenderers.remove(id)?.let { renderer ->
            renderer.close()
            runCatching {
                onAudioDiagnostic("Audio: downlink stopped type=${safeAudioType(id.audioType)} stream=${id.type}")
            }
        }
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            // Which of the two CS11 telephony switches were on for this capture: the failure-detail page
            // needs them to say whether a silent call can be blamed on them (they only act on telephony).
            runCatching {
                onAudioDiagnostic("Microphone: switches type=${safeAudioType(config.audioType)} " +
                    "stream=${id.type} codec=${config.codec.name} rate=${config.sampleRate} " +
                    "mic_effects=${switchText("debug.diplay.mic_effects")} " +
                    "mic_mode=${switchText("debug.diplay.mic_mode")} " +
                    "appliesToThisStream=${config.audioType == "telephony"}")
            }
            // CS11: MODE_IN_COMMUNICATION may hand the capture to a silent vendor input; it can be
            // left alone at runtime with `ADB shell setprop debug.diplay.mic_mode 0`.
            if (config.audioType == "telephony" && debugSwitch("debug.diplay.mic_mode", true)) {
                enterCommunicationMode(id)
            }
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                MicrophoneUplink(config, onAudioDiagnostic, network = network)
            }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            } else {
                // Only a microphone that is really running counts: a refused capture would otherwise be
                // half of a "two-way" call the driver is not on (see [VoiceCallWatch]).
                voiceCallWatch.microphoneStarted(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        // Before the close: the card must go even if the uplink's own teardown throws.
        voiceCallWatch.microphoneStopped(id)
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun switchText(name: String): String = if (debugSwitch(name, true)) "on" else "off"

    /** iOS names the audio type; only a short lowercase token of it may reach the report. */
    private fun safeAudioType(raw: String): String =
        raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(24).ifEmpty { "unknown" }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            val modeBeforeCall = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i("xcertplay-usb", "audio mode $modeBeforeCall -> ${manager.mode} for telephony stream=$id")
            // The report, not just logcat: a head unit left in the voice path plays no media, and
            // that cannot be diagnosed from a car without adb. A failing report must not disturb the
            // mode, so it is the last thing done here and it cannot throw out of this block.
            runCatching {
                onAudioDiagnostic("Audio: mode entered communication from=$modeBeforeCall" +
                    " now=${manager.mode} stream=$id")
            }
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            // The line is built inside the try but sent outside it: reporting from in there would
            // let a failing diagnostic callback be caught as a failed mode restore.
            // AudioService keeps one mode request per process and uses the newest. Setting back the mode found at
            // the start of the call would keep DiPlay asking for it after the process that set it, such as the
            // Telecom call for the car's phone audio, lets go, and the head unit stays in the voice path (#987).
            // MODE_NORMAL withdraws DiPlay's request: the mode returns to another process's, or to normal.
            val line = try {
                manager.mode = AudioManager.MODE_NORMAL
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
                "Audio: mode restored requested=${AudioManager.MODE_NORMAL} now=${manager.mode} stream=$active"
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode", error)
                // A vendor AudioManager whose setMode was refused can refuse the getter too, and an
                // escaping throw here would abort the microphone teardown that called us.
                val currentMode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
                "Audio: mode restore failed requested=${AudioManager.MODE_NORMAL} now=$currentMode" +
                    " error=${error.javaClass.simpleName}"
            }
            runCatching { onAudioDiagnostic(line) }
        }
    }

    fun close() {
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        synchronized(mirrorLock) {
            mirrorDecoders.values.forEach(VideoDecoder::close)
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        // The session is going away: a voice call inferred from its audio must not leave a card on the
        // cluster, and this is the one teardown that does not go through the per-stream stop callbacks.
        voiceCallWatch.reset()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) { newVideoDecoder(type, surfaces[type] ?: defaultSurface) }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        // A worker that exited (decoder/track failure) released its resources; returning it would
        // keep the stream silent forever, so rebuild instead.
        if (existing != null && existing.format == format && existing.isRunning) return existing
        if (existing != null) {
            existing.close()
            audioRenderers.remove(id, existing)
        }
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            phoneChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            audioDecoderChoice,
        ).also { audioRenderers[id] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
) : Closeable {
    private val backlogMode = VideoBacklogPolicy.current()
    private val stallRecoveryNs = VideoBacklogPolicy.stallRecoveryNs(backlogMode)
    private val queue = VideoDecodeQueue(
        VideoBacklogPolicy.maxFrames(backlogMode), VideoBacklogPolicy.maxBytes(backlogMode),
    )
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    // CS11: a decoder rebuilt for a new surface can land in the vendor's client-side buffer mode,
    // where it produces no output at all while our counters still look healthy — the black screen
    // after returning from the car's launcher. EasyPlay retries the rebuild on a one second cooldown
    // until frames appear (docs/_digest/36); these counters drive the same retry here.
    private var outputFramesSinceConfigure = 0
    private var inputsSinceConfigure = 0
    private var receivedSinceConfigure = 0
    private var lastConfigureAtNs = 0L
    private var lastRebuildAtNs = 0L
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    /**
     * `drainOutput` 复用的输出缓冲描述符，而不是每帧 `MediaCodec.BufferInfo()`（doc 41 §5）。
     * 只在 `carplay-video` 线程上访问，因此不加同步。
     */
    private val outputBufferInfo = MediaCodec.BufferInfo()
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")

    /**
     * Cumulative counters for the on-screen FPS overlay (EasyPlay 10-80's "显示画面帧率").
     *
     * [VideoStats] resets its counters every five seconds for the log line, so the overlay samples these
     * totals instead and derives a rate over its own one-second window.
     */
    private val liveReceived = java.util.concurrent.atomic.AtomicLong()
    private val liveRendered = java.util.concurrent.atomic.AtomicLong()

    fun liveVideoCounters(): LiveVideoCounters = LiveVideoCounters(liveReceived.get(), liveRendered.get())

    // --- debug.diplay.video_timing: where the decode loop actually blocks ------------------------
    // `dropped` only says frames aged out; it cannot say whether the thread was waiting for an input
    // buffer, blocked posting to the Surface (SurfaceView backpressure) or slow inside the codec.
    private val timingEnabled = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "debug.diplay.video_timing") as? String
    }.getOrNull()?.trim() == "1"
    private var timingWindowNs = 0L
    private var inWaitNs = 0L
    private var inWaitMaxNs = 0L
    private var inWaitCount = 0
    private var releaseNs = 0L
    private var releaseMaxNs = 0L
    private var releaseCount = 0
    private var releaseSlowCount = 0

    private fun logTimingIfDue() {
        if (!timingEnabled) return
        val now = System.nanoTime()
        if (timingWindowNs == 0L) {
            timingWindowNs = now
            return
        }
        if (now - timingWindowNs < 5_000_000_000L) return
        val waitAvg = if (inWaitCount > 0) inWaitNs / inWaitCount / 1_000_000 else 0
        val relAvg = if (releaseCount > 0) releaseNs / releaseCount / 1_000_000 else 0
        report(
            "video timing in avg=${waitAvg}ms max=${inWaitMaxNs / 1_000_000}ms n=$inWaitCount " +
                "release avg=${relAvg}ms max=${releaseMaxNs / 1_000_000}ms n=$releaseCount " +
                "slow>5ms=$releaseSlowCount",
        )
        timingWindowNs = now
        inWaitNs = 0; inWaitMaxNs = 0; inWaitCount = 0
        releaseNs = 0; releaseMaxNs = 0; releaseCount = 0; releaseSlowCount = 0
    }

    private val thread = WorkerThreads.create({
        android.os.Process.setThreadPriority(com.shilapi.xcertplay.airplay.VideoThreadPriority.current())
        run()
    }, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        liveReceived.incrementAndGet()
        // Data is arriving from the phone; the retry guard cares whether any of it ever renders.
        receivedSinceConfigure++
        queue.offer(VideoJob.Frame(nalus))
    }

    /** Takes ownership of a pooled frame; it is released once the decoder has fed it to the codec. */
    fun submit(frame: OwnedVideoFrame) {
        stats.onReceived(frame.length)
        liveReceived.incrementAndGet()
        receivedSinceConfigure++
        queue.offer(VideoJob.Frame(frame.bytes, length = frame.length, owned = frame))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
        // A new surface rebuilds the decoder, which then starts mid-GOP: without a fresh IDR the panel
        // stays black. That is exactly what the car showed after its launcher covered CarPlay (the
        // surface is destroyed, then handed back on return), and API 19 has no MediaCodec
        // setOutputSurface to avoid the rebuild.
        if (surface != null) requestKeyFrame()
    }

    override fun close() {
        running = false
        thread.interrupt()
        // Release pooled frames the decoder thread will never reach.
        queue.discardFrames()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            // CS11: at 50 fps the old discard+recreate recovery fired every ~1.5 s and
                            // visibly stuttered. Feed every frame and let drainOutput drop stale displays
                            // instead; only a real one-second stall recreates the decoder.
                            if (System.nanoTime() - job.receivedNs > stallRecoveryNs) {
                                job.release()
                                queue.discardFrames()
                                recover("video backlog exceeded ${stallRecoveryNs / 1_000_000} ms")
                            } else feed(job)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.onQueueDepth(queue.pendingFrames())
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
            // CS11: a decoder rebuilt for a new surface can stop rendering entirely while the stream
            // keeps arriving. Two shapes of that: the vendor component lands in client-side buffer
            // mode, or the reference chain never sees a keyframe so nothing is ever handed to it. Both
            // look the same from here — data arrives, nothing renders — and both are what EasyPlay's
            // one second rebuild cooldown exists for (docs/_digest/36).
            if (outputFramesSinceConfigure == 0 && receivedSinceConfigure > 0 && decoder != null &&
                lastConfig != null && outputSurface != null
            ) {
                val now = System.nanoTime()
                if (now - lastConfigureAtNs > NO_OUTPUT_GRACE_NS && now - lastRebuildAtNs > REBUILD_COOLDOWN_NS) {
                    lastRebuildAtNs = now
                    Log.w(
                        TAG,
                        "video decoder rendered nothing since configure " +
                            "(received=$receivedSinceConfigure inputs=$inputsSinceConfigure); rebuilding it",
                    )
                    releaseDecoder()
                    lastConfig?.let(::configureDecoder)
                    requestKeyFrameIfDue()
                }
            }
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        // CS11: H.264 only (see [VideoCodec]); the avcC parameter sets are the decoder's CSD.
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val codecData = config.codecData
        val csd = run {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        //
        // `debug.diplay.video_untuned=1` is the A/B arm for "which format key costs the decoder its
        // throughput": the first attempt keeps the same vendor decoder but drops MAX_INPUT_SIZE,
        // PRIORITY, operating-rate and frame-rate, so `video timing in avg=` can be compared directly.
        val untuned = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "debug.diplay.video_untuned") as? String
        }.getOrNull()?.trim() == "1"
        if (untuned) Log.i(TAG, "video decoder tuned keys disabled by debug.diplay.video_untuned")
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = !untuned),
            DecoderAttempt(codecName = null, tuned = false),
        ) +
            // Last resort: every other registered name for this MIME. This ROM's vendor H.264
            // component can refuse a configure/start (docs/_digest/36, /37) and createDecoderByType
            // only ever offers the default, so walking the rest is worth having before giving up.
            enumeratedCodecNames(mime).map { DecoderAttempt(it, tuned = false) }
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
            // A freshly configured decoder owes us its first frame; the retry guard watches for it.
            outputFramesSinceConfigure = 0
            inputsSinceConfigure = 0
            receivedSinceConfigure = 0
            lastConfigureAtNs = System.nanoTime()
            // A new configuration starts a new no-output window; keeping the old rebuild timestamp
            // would let the cooldown swallow the first recovery attempt after a surface switch.
            lastRebuildAtNs = 0L
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    /**
     * Every other registered decoder name for [mime], for the API-19 fallback above.
     *
     * `MediaCodecList.getCodecInfos` is API 21+, so the deprecated static pair does the work here and
     * the whole body is guarded (EasyPlay's LegacyCodecFactory enumerates exactly this way). The guard
     * is also why the old calls sit in their own method: Dalvik resolves references per method, so an
     * API-21-only reference must never share a body with an API-19 one.
     */
    @Suppress("DEPRECATION")
    private fun enumeratedCodecNames(mime: String): List<String> =
        runCatching {
            val count = android.media.MediaCodecList.getCodecCount()
            (0 until count).mapNotNull { index ->
                val info = android.media.MediaCodecList.getCodecInfoAt(index) ?: return@mapNotNull null
                if (info.isEncoder) {
                    null
                } else {
                    info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }?.let { info.name }
                }
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // 4.4 的 vendor OMX 靠这些键决定进高性能档还是省电档（doc 41 §2.3）。
                // 键名用字符串（MediaFormat.KEY_OPERATING_RATE 是 API 21+），但**值必须是 Int**：
                // createVideoFormat 在 API 19 就已把 frame-rate 预设成 Int，若这里换成 String，
                // stagefright 会 toString() 后按 Int 解析并抛异常 —— 而单元测试跑在 SDK 29、
                // 且没有真解码器，捕不到这个问题。
                // 只在 tuned 分支设置，所以拒绝这些键的组件会退到 tuned=false 的最小 format，
                // 不会因此配置失败。
                setInteger(OPERATING_RATE_KEY, targetFrameRate())
                setInteger(FRAME_RATE_KEY, targetFrameRate())
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    /**
     * 给解码器的目标帧率提示。CarPlay 的分辨率请求里 fps 是协商项，但会话建立后 sink 不知道它，
     * 所以这里读 `debug.diplay.video_fps`（默认 30，与本机实测的稳态帧率一致，doc 78 §1）。
     * 与项目其余调试属性同形，便于一个点火窗口内 A/B。
     */
    private fun targetFrameRate(): Int = VideoFrameRateHint.current()

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            reportFailure("stage=configure tuned=${attempt.tuned} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun createDecoder(mime: String): MediaCodec = MediaCodec.createDecoderByType(mime)

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        releaseDecoder()
        // CS11: rebuilding the vendor decoder immediately after releasing it makes the component fall
        // back to client-side buffers ("OMX_SetParameter() failed for StoreMetaDataInBuffers" then
        // "Using client-side OMX mux"), and those frames never reach the visible surface — the black
        // screen after the surface is recreated. Letting the old instance tear down avoids it.
        runCatching { Thread.sleep(200L) }
        lastConfig?.let(::configureDecoder)
    }

    /**
     * 送进解码器的 PTS：这一帧**从网络到达**的时刻（微秒）。
     *
     * [VideoJob.Frame.receivedNs] 由接收线程在构造时打上（[VideoJob.Frame] 的默认参数求值于构造点，
     * 即 `ScreenStream` 收到帧的那一刻），所以它度量的是真实的"相机→送显"年龄。
     * 兜底：旧调用点若没打时间戳（0），退回"此刻"，让行为与改动前一致，而不是把所有帧都判成过期。
     */
    private fun presentationTimeUsOf(job: VideoJob.Frame): Long =
        if (job.receivedNs > 0L) job.receivedNs / 1000L else System.nanoTime() / 1000L

    private fun feed(job: VideoJob.Frame) {
        try {
            val nalus = job.nalus
            // A pooled frame is already Annex B (the screen stream converts it in place); a borrowed
            // array may still carry four-byte NAL lengths.
            val annexB = if (job.owned != null) nalus else MediaCodecSupport.toAnnexB(nalus)
            val annexBLength = if (job.owned != null) job.length else annexB.size
            val config = lastConfig ?: return
            if (outputSurface == null) return
            if (annexBLength <= 0) { recover("invalid video access unit"); return }
            if (!referenceChain.accepts(annexB, annexBLength, config.codec)) {
                requestKeyFrameIfDue()
                return
            }
            if (decoder == null) configureDecoder(config)
            val codec = decoder ?: return
            if (!submittedFrameLogged) {
                submittedFrameLogged = true
                Log.i(
                    TAG,
                    "video decoder first input avcc=${job.length} annexB=$annexBLength",
                )
            }
            val waitStartNs = System.nanoTime()
            val index = VideoInputPump.acquire(
                running = { running }, drain = { drainOutput(codec) },
                dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            )
            if (timingEnabled) {
                val dt = System.nanoTime() - waitStartNs
                inWaitNs += dt
                inWaitCount++
                if (dt > inWaitMaxNs) inWaitMaxNs = dt
            }
            if (index < 0) { recover("video decoder input stalled"); return }
            val input = checkNotNull(codec.inputBufferCompat(index)) { "Decoder input buffer unavailable" }
            input.clear()
            if (annexBLength <= input.remaining()) {
                input.put(annexB, 0, annexBLength)
                // 用这一帧**从网络到达的时刻**当 PTS，而不是"轮到这个 worker 处理的时刻"。
                // drainOutput 拿 now - presentationTimeUs 与 250 ms 比较来决定是否上屏；若这里用
                // System.nanoTime()，那么在 VideoDecodeQueue 里排队近一秒的旧帧一出队 age≈0，会被
                // 当成新鲜帧照常上屏 —— 画面滞后但不丢帧，正是 maxGap 尖刺的观感来源（doc 41 §2.2）。
                // 改成到达时刻后，250 ms 规则才真正度量"相机→送显"年龄：卡顿时只显示最新帧，而不是补播旧帧。
                codec.queueInputBuffer(index, 0, annexBLength, presentationTimeUsOf(job), 0)
                inputsSinceConfigure++
                referenceChain.onQueued()
            } else {
                recover("video frame exceeded codec input capacity")
                return
            }
            drainOutput(codec)
            logTimingIfDue()
        } finally {
            // The pooled buffer is reusable as soon as the frame is in the codec's input queue.
            job.release()
        }
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        // 复用同一个 BufferInfo，而不是每帧 new 一个（30 fps 下就是 30 个/秒的垃圾）。
        // 线程约束：drainOutput 只在 carplay-video 线程上跑（run() 主循环，以及同线程内
        // feed() 经 VideoInputPump.acquire 的重入），所以这个字段不需要同步（doc 41 §5）。
        val info = outputBufferInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    // Decode-but-drop: when a frame's display is far behind (input burst or decoder
                    // catch-up), skip presenting it but keep decoding the reference chain, so the
                    // surface stays current without decoder re-creation or keyframe waits.
                    val ageUs = if (info.presentationTimeUs <= 0L) 0L
                    else (System.nanoTime() / 1000) - info.presentationTimeUs
                    val render = outputSurface != null && ageUs <= RENDER_MAX_AGE_US
                    val releaseStartNs = System.nanoTime()
                    codec.releaseOutputBuffer(index, render)
                    if (timingEnabled) {
                        val dt = System.nanoTime() - releaseStartNs
                        releaseNs += dt
                        releaseCount++
                        if (dt > releaseMaxNs) releaseMaxNs = dt
                        if (dt > 5_000_000L) releaseSlowCount++
                    }
                    if (render) {
                        stats.onRendered()
                liveRendered.incrementAndGet()
                        outputFramesSinceConfigure++
                    }
                    // Without a surface nothing can be presented yet; only a refused stale frame counts.
                    else if (outputSurface != null) stats.onDropped()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=19 $context " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        // The stall threshold moved to VideoBacklogPolicy.AUTO_STALL_RECOVERY_NS, so the shipped
        // value and the `debug.diplay.video_backlog` experiment cannot drift apart.
        const val RENDER_MAX_AGE_US = 250_000L
        /** How long a freshly configured decoder may render nothing before it is rebuilt (CS11). */
        const val NO_OUTPUT_GRACE_NS = 700_000_000L
        /** Minimum spacing between those rebuilds, matching EasyPlay's one second cooldown. */
        const val REBUILD_COOLDOWN_NS = 1_000_000_000L
        /** 4.4 vendor OMX 读取的调度提示键名（对应的 `MediaFormat.KEY_*` 是 API 21+）；值是 Int。 */
        const val OPERATING_RATE_KEY = "operating-rate"
        const val FRAME_RATE_KEY = "frame-rate"
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val phoneChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    /** 设置页的解码器偏好；不可用时回落到 MediaCodec（见 [AudioDecoderChoice]）。 */
    private val decoderChoice: AudioDecoderChoice = AudioDecoderChoice.DEFAULT,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    /** Stream this renderer's track actually plays on; guidance focus follows it. */
    private var effectiveStreamType = AudioManager.STREAM_MUSIC
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    // This ROM has no Opus decoder in MediaCodec, and CarPlay sends Opus on the telephony and
    // speechRecognition streams; libopus decodes those packets instead of dropping them.
    private var softwareOpus: LegacyNativeOpus.Decoder? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    /**
     * 复用的 7 字节 ADTS 头缓冲（doc 79 A5：原来是每包新建一个 header+AU 数组）。
     * 只在 `carplay-audio` 线程的 `handle()` → `feedCodecStreaming` 路径上访问，无需同步。
     */
    private var headerScratch = ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES)
    /**
     * 厂商软解器输出 short → AudioTrack 要的字节流所用的复用缓冲（A1）。
     * 只在 `carplay-audio` 线程上访问。
     */
    private var pcmBytes = ByteArray(0)
    /**
     * 厂商 Freescale AAC 软解器。仅当用户选了它**且**探测判定真能解码时才非 null；
     * 否则一律走 MediaCodec，绝不静默降级成"没声音"。
     */
    private var freescaleAac: FreescaleAacDecoder? = null
    /**
     * `drainCodec` 复用的输出缓冲描述符（音频 48–135 包/秒），只在 `carplay-audio` 线程上访问。
     */
    private val outputBufferInfo = MediaCodec.BufferInfo()
    /**
     * Reused destination for the wired LPCM byte swap (docs/_digest/51 §12). The wired link carries
     * 137 uncompressed packets per second and every one of them used to allocate a fresh copy of its
     * payload just to swap it; the render thread owns this buffer and reuses it for the life of the
     * track, so the swap costs no allocation at all.
     */
    private var pcmSwap = ByteArray(0)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    /** One log line for a dropped over-long AAC access unit; inputDropped carries the count. */
    private var oversizedAacPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    /** Audio skipped forward to keep the jitter queue near its target (docs/_digest/41 §12). */
    private val packetsSkipped = AtomicInteger()
    /** Frames carried by one packet, learned from consecutive RTP timestamps; 0 while unknown. */
    private var framesPerPacket = 0
    private var hasSubmittedSample = false
    private var lastSubmittedSample = 0
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    /** Times the decoder's real output format disagreed with the advertised one (see drainCodec). */
    private var decoderFormatMismatches = 0
    private val thread = WorkerThreads.create({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        run()
    }, "carplay-audio").apply { isDaemon = true }

    /** False once the worker exited; it releases its resources in `finally`. */
    val isRunning: Boolean get() = running && thread.isAlive

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
            observePacketFrames(sample)
        }
        if (!started) return
        if (queue.offer(AudioPacket(rtp, sample))) return
        // Full. Drop the *oldest* packet to make room instead of refusing the newest: the old shape tore
        // holes exactly when a burst arrived, which is what the tablet bench heard (docs/_digest/41 §12).
        // NOTE: deliberately no "trim to a target depth" here - audio arrives in ~400 ms bursts, so
        // trimming a healthy buffer on every arrival throws away most of the music (that mistake made
        // the stutter far worse: skippedMs was ~2400 ms per 5000 ms window).
        if (queue.poll() != null) packetsSkipped.incrementAndGet()
        if (!queue.offer(AudioPacket(rtp, sample))) {
            packetsDropped.incrementAndGet()
            if (!droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    /** Learns the packet duration from consecutive RTP timestamps, so skip counters are in audio time. */
    private fun observePacketFrames(sample: Int) {
        if (framesPerPacket != 0) return
        val frames = AudioJitterTarget.packetFrames(
            previous = lastSubmittedSample,
            current = sample,
            hasPrevious = hasSubmittedSample,
            sampleRate = format.sampleRate,
        )
        hasSubmittedSample = true
        lastSubmittedSample = sample
        if (frames > 0) framesPerPacket = frames
    }

    override fun close() {
        running = false
        // A three-argument write blocks until the track drains; stop() is what releases it.
        runCatching { track?.stop() }
        thread.interrupt()
    }

    /**
     * 把「AAC 解码器偏好 → 实际使用哪一个」变成一行诊断，**不静默回落**。
     *
     * 各结果可区分，便于在车机上一条日志定性：
     *   - 选平台码：`decoder=platform reason=selected`（始终成立）
     *   - 选厂商码但不具备条件：`decoder=platform fallback=freescale-unavailable reason=…`
     *   - 选厂商码且已具备条件：`decoder=platform note=freescale-bridge-pending …`
     *     —— **这条目前仍走 MediaCodec 解码**：A1 的 `ctx` 结构初始化尚未恢复
     *     （见 `work/cs11-aac-jni/evidence/A1-findings.md` E5），所以桥接层只证明"库可加载、可调用，
     *     入口符号齐全"，还不能真正解码。等 ctx 语义补全后，这一分支才会切到厂商解码器。
     */
    private fun describeDecoderChoice(): String = when (decoderChoice) {
        AudioDecoderChoice.MEDIACODEC -> "Audio: decoder=platform reason=selected"

        AudioDecoderChoice.FREESCALE -> when (val availability = FreescaleAacBridge.availability()) {
            is FreescaleAacAvailability.Unavailable ->
                "Audio: decoder=platform fallback=freescale-unavailable reason=${availability.reason}"

            is FreescaleAacAvailability.Ready ->
                "Audio: decoder=platform note=freescale-bridge-pending " +
                    "freescalePath=${availability.path} " +
                    "memDescriptors=${availability.descriptors.take(2).joinToString(",")}"
        }
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=19 " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> {
                    // A1：用户选了厂商 Freescale 解码器时先尝试建它。
                    // 建不起来（库不在 / ABI 不符 / init 失败）就回落 MediaCodec —— 绝不让用户没声音。
                    if (decoderChoice == AudioDecoderChoice.FREESCALE) {
                        freescaleAac = FreescaleAacDecoder.create(
                            maxChannels = format.channels.coerceIn(1, 2),
                            onDiagnostic = { message -> runCatching { report("Audio: $message") } },
                        )
                    }
                    // 厂商软解器起来了就不需要 MediaCodec；否则照常建一个。
                    if (freescaleAac == null) configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                }
                AudioCodecKind.OPUS -> if (!forcedSoftwareOpus() || !LegacyNativeOpus.available) {
                    configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                }
                AudioCodecKind.LPCM -> Unit
            }
            // 解码器偏好只对 AAC 有意义（LPCM 不解码；Opus 走自己的软解路径）。
            // 这里把"偏好 → 实际使用"的结论写进诊断行，**永远不静默回落**：
            // 用户选了厂商解码器却拿到平台解码器时，必须能从日志看出是哪一步不成立。
            if (format.codec == AudioCodecKind.AAC_LC) {
                runCatching { report(describeDecoderChoice()) }
            }
            if (codec == null && format.codec == AudioCodecKind.OPUS) {
                softwareOpus = LegacyNativeOpus.Decoder().takeIf { it.isAvailable }
                runCatching {
                    report("Audio: software Opus decoder active=${softwareOpus != null}")
                }
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    /**
     * Debug hook for the bundled libopus path: `adb shell setprop debug.diplay.software_opus 1`
     * forces it on a device whose MediaCodec does have an Opus decoder, so the fallback can be
     * exercised (and heard) without the CS11's missing codec. Read through reflection because
     * android.os.SystemProperties is not part of the public SDK.
     */
    private fun forcedSoftwareOpus(): Boolean = softwareOpusForced()

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = channelMask()
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=19 stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            // Failing the worker lets the next packet rebuild the renderer; returning would keep
            // dropping every sample while the session looked healthy.
            throw IllegalStateException(
                "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}",
            )
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        // CS11: 4.4 一律走 legacy 构造（usage 路由退为 mapper 的 streamType）
        val streamType = if (streamOverride != 0) streamOverride else streamType()
        effectiveStreamType = streamType
        routeLabel = "streamType=$streamType"
        val attempts = LegacyAudioFallback.Attempts(
            AudioStreamFallback.fallbackCandidates(streamType),
        )
        built = LegacyAudioFallback.build(
            createLegacy = { buildLegacyTrack(streamType, plan) },
            isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
            release = { it.release() },
            createFallback = {
                diagnosticStage = "track-fallback-build"
                val choice = attempts.next(
                    build = { route ->
                        routeLabel = "${route.label}(fallback)"
                        buildRouteTrack(route, plan)
                    },
                    isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                    release = { it.release() },
                )
                // Guidance lands on the music stream here, so focus and the volume stream
                // follow the stream that actually carries the sound, not the preferred one
                // the ROM refused.
                (choice.route as? AudioTrackRoute.Stream)?.let { effectiveStreamType = it.streamType }
                // The report names every stream this channel tried once the sound is up;
                // before that there is nothing useful to say beyond the per-attempt logs.
                AudioStreamFallback.rejectionReport(streamType, choice.attempted)?.let { line ->
                    Log.w(TAG, line)
                    runCatching { report(line) }
                }
                choice.track
            },
        )
        track = built
        diagnosticStage = "track-capacity"
        val capacityBytes = plan.trackBufferBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        val trackMetadata = runCatching {
            "trackState=${built.state} trackRate=${built.sampleRate}"
        }.getOrDefault("trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        AudioChannel.PHONE -> phoneChannel
        // Siri shares the guidance stream by design; there is no separate assistant setting.
        AudioChannel.ASSISTANT -> 0
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, effectiveStreamType) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun buildLegacyTrack(streamType: Int, plan: MediaAudioBuffer.Plan): AudioTrack = AudioTrack(
        streamType, format.sampleRate, channelMask(), AndroidAudioFormat.ENCODING_PCM_16BIT,
        plan.trackBufferBytes, AudioTrack.MODE_STREAM,
    )

    private fun buildRouteTrack(route: AudioTrackRoute, plan: MediaAudioBuffer.Plan): AudioTrack =
        buildLegacyTrack((route as AudioTrackRoute.Stream).streamType, plan)

    private fun channelMask(): Int =
        if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO else AndroidAudioFormat.CHANNEL_OUT_MONO

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> {
                val payloadBytes = rtp.size - RTP_HEADER_BYTES
                if (payloadBytes > 0) {
                    if (pcmSwap.size < payloadBytes) pcmSwap = ByteArray(payloadBytes)
                    byteSwapS16Into(rtp, RTP_HEADER_BYTES, payloadBytes, pcmSwap)
                    writePcm(pcmSwap, 0, payloadBytes)
                }
            }
            AudioCodecKind.AAC_LC -> {
                // A5：不再把访问单元拷出来、也不再用 adtsFrame 拼一个 header+AU 的新数组。
                // ADTS 头直接写进 MediaCodec 的输入缓冲，访问单元从原 RTP 数组流式跟在后面 ——
                // 每包 0 次分配、0 次中间拷贝（原来是 2 次分配 + 2 次拷贝 × ~48 包/秒）。
                val auBytes = rtp.size - RTP_HEADER_BYTES
                if (auBytes <= 0) return
                // ADTS frame_length is 13 bits: a longer access unit cannot be described by an
                // ADTS header, and the header would otherwise have to either truncate the audio or
                // describe the wrong stream. Drop the packet like a short Opus packet below -
                // dropping one frame keeps the stream alive, while throwing would end the track,
                // and a real AAC-LC access unit is far below this bound.
                if (auBytes > MediaCodecSupport.ADTS_MAX_ACCESS_UNIT_BYTES) {
                    inputDropped++
                    if (!oversizedAacPacketLogged) {
                        oversizedAacPacketLogged = true
                        Log.w(
                            TAG,
                            "audio AAC skipping oversized access unit bytes=$auBytes " +
                                "max=${MediaCodecSupport.ADTS_MAX_ACCESS_UNIT_BYTES} " +
                                "queued=$inputQueued dropped=$inputDropped",
                        )
                    }
                    return
                }
                if (!firstAacPayloadLogged) {
                    firstAacPayloadLogged = true
                    Log.i(TAG, "audio AAC access unit bytes=$auBytes")
                }
                // A1：厂商软解器直接吃裸 AU（我们不拼 ADTS 字节），头部字段单独传。
                freescaleAac?.let { decoder ->
                    // RTP 载荷必须是 ADTS 帧（CarPlay 的 type-102 就是），才能取出那些字段。
                    val header = MediaCodecSupport.parseAdtsHeader(rtp, RTP_HEADER_BYTES)
                    if (header == null) {
                        inputDropped++
                        return
                    }
                    if (decoder.decode(rtp, RTP_HEADER_BYTES, auBytes, header)) {
                        outputBuffers++
                        writePcm(decoder.pcmScratch, decoder.decodedSamples)
                    } else {
                        // 单帧失败不致命（错误隐藏会兜），但连续失败要看得见。
                        if (decoder.failures == FIRST_FREESCALE_FAILURE) {
                            report("Audio: Freescale AAC decode failing (first at len=$auBytes)")
                        }
                    }
                    return
                }
                feedCodecStreaming(timestampUs) { input ->
                    val total = MediaCodecSupport.ADTS_HEADER_BYTES + auBytes
                    if (total > input.remaining()) {
                        0
                    } else {
                        headerScratch = if (headerScratch.size < MediaCodecSupport.ADTS_HEADER_BYTES) {
                            ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES)
                        } else {
                            headerScratch
                        }
                        val headerBytes = MediaCodecSupport.writeAdtsHeader(
                            headerScratch, 0, auBytes, format.sampleRate, format.channels,
                        )
                        if (headerBytes <= 0) {
                            0
                        } else {
                            input.put(headerScratch, 0, headerBytes)
                            // 访问单元紧跟头部：一次批量拷贝，来源是原 RTP 数组本身。
                            input.put(rtp, RTP_HEADER_BYTES, auBytes)
                            total
                        }
                    }
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        softwareOpus?.let { decoder ->
            diagnosticStage = "software-opus"
            val decoded = runCatching { decoder.decode(payload, 0, payload.size) }.getOrNull()
            if (decoded != null && decoded.isNotEmpty()) {
                outputBuffers++
                writePcm(decoded)
            } else {
                decoderUnavailablePackets++
            }
            return
        }
        feedCodecStreaming(presentationTimeUs) { input ->
            if (payload.size > input.remaining()) 0 else payload.size.also { input.put(payload) }
        }
    }

    /**
     * Writes one decoder input **straight into the MediaCodec input buffer**, instead of building an
     * intermediate `header + accessUnit` array first.
     *
     * Why (docs/_digest/79 A5): the wireless AAC path used to do `rtp.copyOfRange(12, …)` and then
     * let `adtsFrame` allocate and copy again — two allocations and two copies for each of ~48
     * packets/s. Streaming removes all of them.
     *
     * [write] must lay the bytes out **contiguously from the buffer's current position** and return
     * how many bytes it wrote; returning 0 (or a negative) means "does not fit / cannot be
     * described", which the caller counts as a drop exactly like the previous size check did.
     * The commit length is validated against `input.position()` rather than against the returned
     * value alone, so a [write] that mis-reports its length can never enqueue uninitialized bytes.
     *
     * Only the MediaCodec path goes through here; the bundled software Opus decoder has its own
     * buffer protocol and is handled by [feedCodec] before this is reached.
     */
    private inline fun feedCodecStreaming(
        presentationTimeUs: Long,
        write: (java.nio.ByteBuffer) -> Int,
    ) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        // A blocking track write holds this thread while decoded output fills the codec, so a single
        // 10 ms wait would drop the packet (~21 ms of music each time; upstream #381 / daffd2de).
        // Drain decoded output and wait again, exactly as the video decoder's input pump does.
        //
        // But cap that wait at 100 ms rather than the video's 500 ms (upstream 7d974f42 / #649): a
        // slowed decoder then falls further and further behind, and with the CarPlay screen in the
        // background the queue grew to 190 packets and the track underran every few seconds. One drain
        // and retry — which is all #381 needed — always happens before the cap is checked, because
        // VideoInputPump drains before it looks at the clock.
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            timeoutNs = AUDIO_INPUT_WAIT_NS,
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.inputBufferCompat(index) ?: return
        input.clear()
        val reported = write(input)
        val written = input.position()
        if (reported in 1..written) {
            codec.queueInputBuffer(index, 0, reported, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(TAG, "audio decoder first input codec=${format.codec} bytes=$reported")
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        // 同 drainOutput：复用而非每包 new（音频 48–135 包/秒）。只在 carplay-audio 线程上访问
        //（run() 主循环，以及同线程内 feedCodec() 的调用），所以无需同步。
        val info = outputBufferInfo
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val rate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                        val metadata = "rate=$rate channels=$channels " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                        // The track was built from the advertised format; if the decoder disagrees, the
                        // PCM is written at the wrong pitch/length. EasyPlay rebuilds the track here
                        // (docs/_digest/37); we report it first, because rebuilding mid-stream is not
                        // worth the risk until the car shows it actually happens.
                        if ((rate != null && rate != format.sampleRate) ||
                            (channels != null && channels != format.channels)
                        ) {
                            decoderFormatMismatches++
                            Log.w(
                                TAG,
                                "audio decoder output disagrees with the advertised format " +
                                    "advertised=${format.sampleRate}Hz/${format.channels}ch " +
                                    "decoded=${rate}Hz/${channels}ch audioType=${format.audioType}",
                            )
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    // Release the output buffer before writing PCM to the track: the write can block
                    // for a long time on this ROM (no hardware AAC decode), and a held buffer stops
                    // the decoder from accepting input at all (upstream 21195488).
                    var released = false
                    if (size > 0) {
                        val output = codec.outputBufferCompat(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            codec.releaseOutputBuffer(index, false)
                            released = true
                            writePcm(pcm, 0, size)
                        }
                    }
                    if (!released) codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * Q15（主机序 short）→ AudioTrack 要的 16-bit 小端字节流。
     *
     * 厂商解码器输出的就是 short，而 API 19 只有 `AudioTrack.write(byte[], …)`，
     * 所以这一次转换躲不掉——但缓冲是复用的，不产生每帧分配。
     * ARM 是little-endian，因此 short → 两个字节是低字节在前。
     */
    private fun writePcm(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val needed = count * 2
        if (pcmBytes.size < needed) pcmBytes = ByteArray(needed)
        val bytes = pcmBytes
        var out = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            bytes[out++] = (value and 0xFF).toByte()
            bytes[out++] = ((value shr 8) and 0xFF).toByte()
        }
        writePcm(bytes, 0, needed)
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            // CS11: 4.4 用 3 参阻塞写
            val count = track.write(data, offset + written, writeLength)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = trackUnderrunsCompat(track)
        track.play()
        playbackStarted = true
        // Guidance holds the car's volume keys only while it actually plays: the head unit adjusts
        // getCurMasterStream, which follows the stream holding focus, so the keys stay on media
        // between prompts and switch to the navigation stream while one is speaking.
        if (mappedChannel == AudioChannel.NAVIGATION) {
            audioFocusCoordinator.acquire(track, AudioChannel.NAVIGATION, effectiveStreamType)
        }
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                trackUnderrunsCompat(track) > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Hand the volume keys back to media once the prompt stops.
            if (mappedChannel == AudioChannel.NAVIGATION) audioFocusCoordinator.release(track)
            // Pausing retains queued PCM. Count it toward the restart threshold so a blocking write
            // cannot fill the paused track before we call play().
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.let { trackUnderrunsCompat(it) } ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        // Windowed skip counters, so audio dropped to stay bounded is visible in the field.
        val skippedPackets = packetsSkipped.getAndSet(0)
        val skippedAudioMs = skippedPackets.toLong() *
            AudioJitterTarget.packetMillis(framesPerPacket, format.sampleRate)
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.let { trackRouteTypeCompat(it) } ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.let { trackBufferSizeFramesCompat(it) } ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} skipped=$skippedPackets skippedMs=$skippedAudioMs " +
            "underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=19 " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    /**
     * Writes the big-endian 16-bit LPCM payload at [sourceOffset] into [destination] as little-endian,
     * without allocating: the wired link carries 137 of these per second and the previous
     * `byteSwapS16(rtp.copyOfRange(...))` allocated a fresh payload copy for every one of them
     * (docs/_digest/51 §12). An odd trailing byte - which an S16 stream should never have - is copied
     * unchanged rather than dropped, so a malformed packet cannot lose its last byte silently.
     */
    private fun byteSwapS16Into(source: ByteArray, sourceOffset: Int, length: Int, destination: ByteArray) {
        var index = 0
        while (index + 1 < length) {
            destination[index] = source[sourceOffset + index + 1]
            destination[index + 1] = source[sourceOffset + index]
            index += 2
        }
        if (index < length) destination[index] = source[sourceOffset + index]
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        softwareOpus?.close()
        softwareOpus = null
        freescaleAac?.close()
        freescaleAac = null
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        /** The RTP header every audio packet carries ahead of its payload. */
        const val RTP_HEADER_BYTES = 12
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        /**
         * Ceiling on how long the audio decoder input pump waits for a free input buffer, once its
         * drain-and-retry has run (upstream 7d974f42 / #649). The video pump keeps the shared 500 ms
         * default; audio must not, because a decoder that is merely slow would hold every packet and
         * the track would underrun continuously instead of occasionally.
         */
        const val AUDIO_INPUT_WAIT_NS = 100_000_000L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Hard ceiling for the jitter queue (~4 s of AAC). The queue is normally trimmed well below it
        // by AudioJitterTarget, so this only bounds a pathological backlog.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        /** 厂商软解器第几次失败时打一行（只报一次，避免刷屏）。 */
        const val FIRST_FREESCALE_FAILURE = 1
    }
}
