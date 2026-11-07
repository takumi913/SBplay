package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AudioOpusPolicy
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.OemIconAbsence
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.media.LiveVideoMeter
import com.shilapi.xcertplay.network.CarPlayListenerService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.DisplaySettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.compat.hasPermissionCompat
import com.shilapi.xcertplay.compat.noBackupFilesDirCompat

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by the Apple vendor ID of the attached USB device.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaRects: MutableMap<DisplaySize, SafeAreaRect?>,
        val customIconBytes: ByteArray?,
        /** The corner mask saves as soon as its switch moves, so Cancel has to undo it by hand. */
        val screenCornerMask: Boolean,
    )

    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null

    /** The Wi-Fi reset failure has already sent the driver to the home page's recovery dialog. */
    private var wifiRecoveryHandedOff = false
    private var reconnectAttempts = 0
    private val startupRetryBudget = WirelessStartupRetryBudget()

    private var startupRetryStopped = false
    private var startupRetryButton: View? = null

    /**
     * Whether this host instance has already opened a connection attempt record (ConnectionAttemptRecord).
     * Automatic retries inside the same instance keep the failure already recorded; only a new instance -
     * a new connect from the home page, a USB plug-in, the Bluetooth receiver - starts a fresh record.
     */
    private var attemptRecorded = false

    /** Last non-failure step of this attempt, so a failure can say where it stopped. */
    private var lastAttemptStage: String? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = DiPlayBootstrap.CARPLAY_NAME,
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        label = DiPlayBootstrap.CARPLAY_NAME,
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        existingWifiSsid = existingWifiSsid,
        existingWifiPassphrase = existingWifiPassphrase,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    private var videoView: TextureView? = null

    /**
     * Experimental video sink for the `debug.diplay.video_surface` A/B: a SurfaceView renders the
     * decoder's output through SurfaceFlinger instead of drawing a full-screen textured quad on the
     * UI thread (which is the single biggest per-thread cost on API 19, where HWUI draws there).
     *
     * Null unless the property opted in; TextureView stays the shipped default.
     */
    private var videoSurfaceView: SurfaceView? = null

    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var cornerMaskOverlay: CarPlayCornerMaskOverlay? = null
    private var settingsMenu: View? = null
    /** Marks where the settings menu goes; the menu itself is built on first open. */
    private var settingsMenuAnchor: ViewGroup? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    /** The connecting screen's stage list, newest last, so bring-up progress is visible and loggable. */
    private val recentStages = java.util.ArrayDeque<String>()
    /**
     * Node progress for the wired bring-up, so the connecting screen can say which step is running and which
     * one failed instead of only a rolling list of vague texts (owner request, 2026-10-18).
     */
    private var connectNodeIndex = 0
    private var connectNodeFailed = false
    /** First status of this attempt, so the log can say how long each checklist node took (doc 99). */
    private var connectStartedAtMs = 0L
    /**
     * True once any controller status has been reported.
     *
     * The checklist is only drawn after that: rendering eight pending rows on the idle screen made the panel
     * taller than a 720 dp viewport before a connection had even started, which `PreparationLayoutTest`
     * catches (`top=-98`). The idle screen keeps its original layout.
     */
    private var connectNodeSeen = false
    private var stageListView: LinearLayout? = null
    private val recentStageLimit = 5
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var existingWifiFields: View? = null
    private var existingWifiErrorView: TextView? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    /** The app-drawn answer / hang-up banner; null until this activity has shown CarPlay once. */
    private var callBanner: CallBannerOverlay? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    /** Set when the video surface is destroyed; a fresh activity is the only working recovery. */
    private var surfaceLostSinceCreate = false
    private var isActivityStarted = false
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScalePercent = 100
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = 14
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    /** The view areas the next session declares (see createAirPlayConfig). */
    private var pendingViewAreas: CarPlayViewAreas? = null
    /** The dock the running session declared; null before the first connection. */
    private var sessionDock: CarPlayDock? = null
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    /** Paints the screen's four corners to match the car's rounded bezel; off by default. */
    private var screenCornerMask = false
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    /**
     * The persisted cluster-push switch, handed to [VendorClusterBridge.attach] on every start.
     *
     * Initialised to the persistence default so a settings screen opened before
     * [loadPersistedSettings] has run cannot leave the push off by accident.
     */
    private var clusterBridgeEnabled = true
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"

    /** Started from the home page: hand the screen back to it while the link comes up. */
    private var startBehind = false

    /** The home page's still frame shown while the host owns the screen during a from-home connect. */
    private var launchMirror: View? = null

    /** Finished on request of the home page rather than by the driver leaving this screen. */
    private var finishingFromBackground = false

    private var darkMode = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = AndroidAmbientLight(this),
            scheduler = MainThreadNightModeScheduler(),
            initialNight = darkMode,
        ) { night ->
            darkMode = night
            paintWaitingScreen()
            appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
            logThemeState(nightModeDiagnosticSource, resources.configuration)
            syncAirPlayDarkMode(nightModeDiagnosticSource)
        }
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var gestureFingerCount = 3
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    // Platform pointer ids the tracked gesture was established with. The finger count alone is not
    // enough: a device can report the configured pointerCount while repeating one pointer id, which
    // is not a multi-finger gesture at all (EasyPlay v71 keys its swipe by id and rejects such a
    // sequence as "invalid_pointer_sequence", docs/_digest/42).
    private val gesturePointerIds = mutableSetOf<Int>()
    // Where each tracked finger started, by pointer id. A gesture established mid-motion has no
    // earlier sample to compare against, so the start of a finger that was already down is taken from
    // the previous event; measuring the gesture from the establishing event itself would compare that
    // event with itself and never see any travel.
    private val gestureStartPositions = mutableMapOf<Int, PointF>()
    // Coordinates of the previous event of the current sequence, captured by value. The framework
    // recycles one MotionEvent through a whole gesture, so holding the event itself and reading it
    // later would read this event's coordinates instead of the previous one's.
    private var gesturePreviousSample: PreviousGestureSample? = null
    private var recoveryPendingAfterMenu = false
    private var failurePendingAfterMenu: CarPlayStatus.Failed? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * On-screen frame-rate overlay, ported from EasyPlay 10-80 ("显示画面帧率（输出 / 接收）").
     * It samples the sink's cumulative counters once a second and never affects the session: the view
     * is not clickable, not focusable and hidden unless the setting is on and a session is running.
     */
    private var showVideoFps = false

    /**
     * Experimental SurfaceView sink, read once per host creation (the video view is built in
     * [buildContentView]). Changing it therefore applies the next time CarPlay starts.
     */
    private var surfaceViewExperimental = false
    private var fpsView: TextView? = null
    private var fpsReporting = false
    private val fpsMeter = LiveVideoMeter()
    private val reportVideoFps = object : Runnable {
        override fun run() {
            fpsReporting = false
            if (!shouldShowVideoFps()) {
                refreshVideoFps()
                return
            }
            val reading = fpsMeter.update(sink?.liveVideoCounters(), System.nanoTime())
            val view = fpsView ?: return
            if (reading != null) {
                view.text = getString(R.string.show_video_fps_format, reading.shownFps, reading.receivedFps)
                view.visibility = View.VISIBLE
            }
            fpsReporting = true
            mainHandler.postDelayed(this, VIDEO_FPS_INTERVAL_MILLIS)
        }
    }
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    // Some head units update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            // The vendor decoder cannot be rebuilt into surface mode on this head unit (it falls back
            // to client-side buffers and renders nothing visible), so a lost surface means the whole
            // host activity has to come back fresh — see onResume (docs/_digest/36).
            surfaceLostSinceCreate = true
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    /**
     * SurfaceView is an experimental settings switch ([VideoSurfaceMode]); the `debug.diplay.video_surface`
     * property still wins for lab sessions, and TextureView stays the default.
     */
    private fun useSurfaceViewForVideo(): Boolean = VideoSurfaceMode.useSurfaceView(this)

    private val videoSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surface = holder.surface ?: return
            currentSurface = surface
            appendLog(
                "SurfaceView surface created id=${System.identityHashCode(surface)} valid=${surface.isValid}",
            )
            attachSurface(surface)
            // `surfaceFrame` can still report the full display at creation time; the view itself is
            // laid out inside the activity window (1280x650 above the car's bottom bar), so negotiate
            // from that once layout has run. Using the display size here made the iPhone encode
            // 1280x720 and the sink squash it into 650.
            videoSurfaceView?.post {
                val view = videoSurfaceView
                if (view != null && view.width > 0 && view.height > 0) {
                    scheduleDisplaySize(view.width, view.height)
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val view = videoSurfaceView
            if (view != null && view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            } else {
                scheduleDisplaySize(width, height)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // The SurfaceView owns this Surface; only detach it from the decoders.
            val surface = currentSurface
            if (surface != null) {
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            }
            currentSurface = null
            currentSurfaceTexture = null
            surfaceLostSinceCreate = true
            appendLog("SurfaceView surface destroyed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A session can be started from a notification or the Bluetooth receiver, so the group has to be
        // captured here too and not only in DiPlayActivity; see WorkerThreads.
        com.shilapi.xcertplay.compat.WorkerThreads.capture()
        super.onCreate(savedInstanceState)
        NavigationWidgetUpdater.attach(applicationContext)
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (isIphoneUsbAttachment(intent)) {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        // Plugging the iPhone in starts this screen directly; that path must land on the home page as well
        // (the connecting screen this activity used to show has been deleted), so treat it like a
        // from-home connect: start the link, hand the screen back, come forward again with the video.
        startBehind = intent.getBooleanExtra(EXTRA_START_BEHIND, false) || isIphoneUsbAttachment(intent)
        if (startBehind) {
            // This window is only an implementation detail of the connect: no task-switch zoom, no
            // window animation. The still frame it paints is meant to look like nothing happened.
            window.setWindowAnimations(R.style.NoWindowAnimation)
        }
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        // CS11: strip the legacy title/action bar the API 19 fallback theme would otherwise show.
        @Suppress("DEPRECATION")
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(buildContentView())
        // The host takes the screen in its own task for a few hundred milliseconds while the surface is
        // measured and the session starts; paint the home page's last frame over it so that switch is
        // invisible instead of a black flash (see CarPlayLaunchSnapshot).
        if (startBehind) showLaunchSnapshot()
        // Parse the offline MFi identity while the surface and first layout are still coming up. `load` costs
        // about 1.2 s on this CPU (provider init plus a P-256 sign/verify) and used to be pure waiting at the
        // start of every wired connection.
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-mfi-preload") {
            runCatching {
                LocalMfiAuthenticationClient.preload(
                    java.io.File(noBackupFilesDirCompat(), LocalMfiAuthenticationClient.DIRECTORY),
                )
            }
        }
        applyFullscreenMode()
        // Bench: db shell setprop debug.diplay.settings_menu 1 opens the three-finger menu on start, so
        // the layout can be screenshotted at a head unit's exact resolution without injecting multi-touch.
        if (debugSettingsMenuRequested()) window.decorView.post { openSettingsMenu() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else {
                        // No in-CarPlay panel any more: Back always returns to the app.
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            hasPermissionCompat(Manifest.permission.RECORD_AUDIO)
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
        )
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        clusterBridgeEnabled = AirPlayPersistence.loadClusterBridgeEnabled(this)
        // The high-rate diagnostic traces follow this setting; they are off unless it is on.
        com.shilapi.xcertplay.diag.DiagLog.update(debugLogsEnabled)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        showVideoFps = AirPlayPersistence.loadShowVideoFps(this)
        surfaceViewExperimental = AirPlayPersistence.loadSurfaceViewExperimental(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        screenCornerMask = AirPlayPersistence.loadScreenCornerMask(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlayActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        // The wired transport needs no VPN authorisation any more.
        //
        // This used to call `VpnService.prepare` and launch a consent Activity that does not exist on
        // this head unit (`com.android.vpndialogs` is absent), catch the resulting
        // ActivityNotFoundException, and show "VPN 授权被拒绝" to the driver on every connection - all to
        // authorise a tunnel the kernel cannot create (no /dev/tun, no tun module). The VpnService/NCM
        // path was removed with it, so there is nothing left to authorise before starting.
        if (wirelessEnabled) requestWirelessPermissions()
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        hasPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION)


    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { hasPermissionCompat(it) }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            hasPermissionCompat(it)
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI -> emptyList()
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isIphoneUsbAttachment(intent)) {
            if (wirelessEnabled) {
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                wirelessPermissionsReady = true
                // The restart below publishes again at its controller start; this covers the gap before it.
                if (controller != null) CarPlayProgress.publishTransport(false)
                if (controller != null) {
                    restartCarPlay("switching to USB")
                }
                requestStartupPrerequisites()
            } else if (controller == null) {
                requestStartupPrerequisites()
            }
        }
    }

    private fun isIphoneUsbAttachment(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        return device?.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        DiPlayScreenState.carPlayHostVisible = true
        // One honest line about what this ROM actually provides. It is reported, never acted on:
        // wireless CarPlay must keep advertising Opus even though the platform has none, because
        // dropping it from the advertisement makes iOS stop routing audio to the car at all.
        runCatching { appendLog(com.shilapi.xcertplay.media.CodecCapabilityReport.line()) }
        // Give the cluster bridge its context, its arrow renderer and the persisted on/off setting. The
        // setting is re-read here rather than trusted from onCreate so a change made in the settings
        // screen takes effect on the way back, and so the push is on after an ignition without adb.
        clusterBridgeEnabled = AirPlayPersistence.loadClusterBridgeEnabled(this)
        attachClusterBridge()
        // One-off reconnaissance for the two vendor integrations worth doing next (docs/_digest/37):
        // what the car's media centre answers, and what the DIM navigation API offers. Log-only.
        if (!DiPlayScreenState.vendorProbesDone) {
            DiPlayScreenState.vendorProbesDone = true
            runCatching { VendorMediaCenterProbe.probe(this) }
            runCatching { VendorMediaCenterConnection.probe(this) }
            runCatching { VendorDimProbe.probe(this) }
        }
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.removeCallbacks(reportVideoFps)
        mainHandler.post(pollConfiguration)
    }

    override fun onResume() {
        super.onResume()
        // Coming back after the car's launcher took the screen: the TextureView surface was destroyed,
        // and the decoder rebuilt for the new one cannot render to a surface on this vendor codec
        // (OMX_SetParameter() failed for StoreMetaDataInBuffers → client-side buffers, nothing on the
        // panel). A freshly created activity builds a fresh decoder, which is the path that works, so
        // restart ourselves once instead of showing a black screen (docs/_digest/36).
        if (surfaceLostSinceCreate && !shuttingDown.get() && !isFinishing && !isChangingConfigurations) {
            surfaceLostSinceCreate = false
            appendLog("surface was lost; recreating the CarPlay screen for a fresh decoder")
            recreate()
            return
        }
        // Also make sure the decoder is bound to whatever surface is live now.
        currentSurface?.takeIf { it.isValid }?.let(::attachSurface)
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold || savedDelay != ambientDelaySeconds) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds)
        }
        nightModeController.resume(systemNight)
        if (!menuOpen) {
            displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
            displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
            updateResolutionMenu()
        }
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        val languagePreference = AppLocale.preference(this)
        if (languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        // The settings screen returns here with FLAG_ACTIVITY_REORDER_TO_FRONT, so this screen is
        // resumed, not recreated: refresh what that screen can change before it is used again.
        if (!menuOpen) loadConnectionSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (!menuOpen) gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
        var systemBarsChanged = false
        if (!menuOpen) {
            val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
            val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
            systemBarsChanged = hideTopBar != savedHideTopBar || hideBottomBar != savedHideBottomBar
            hideTopBar = savedHideTopBar
            hideBottomBar = savedHideBottomBar
        }
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }
    }


    /**
     * Hands the cluster bridge its context and the persisted on/off setting.
     *
     * Called from [onStart] and again when the settings menu closes, because the bridge reads its
     * enablement on every media and guidance update: without the second call a new switch position
     * would only apply at the next ignition, which is the very delay the persisted setting removes.
     */
    private fun attachClusterBridge() {
        runCatching {
            com.shilapi.xcertplay.media.VendorClusterBridge.attach(this, clusterBridgeEnabled)
            // The cluster's answer/hang-up keys call back into the vendor, which forwards them here;
            // the controller is read per press, so a session that starts later still answers.
            com.shilapi.xcertplay.media.VendorClusterBridge.attachTelephonyActions(
                onAnswer = { controller?.answerCall() },
                onEnd = { controller?.endCall() },
            )
        }
        attachCallBanner()
    }

    /**
     * Draws the answer / hang-up banner over whatever screen the driver is on.
     *
     * With a CarPlay box the head unit's own phone UI drew it, because HFP carried the call; this project
     * releases HFP so calls stay inside CarPlay, so the banner is ours (docs/_digest/121 §8). It is a
     * window rather than a view: the launcher and the task page are exactly where the box's banner was
     * seen, and this app is not visible there. Its keys are the same CarPlay telephony HID the cluster
     * card's keys use, read from the controller per press.
     *
     * Deliberately independent of the cluster switch: a driver who turned the instrument card off still
     * wants to be able to answer.
     */
    private fun attachCallBanner() {
        val banner = callBanner ?: runCatching {
            CallBannerOverlay(this) { line -> appendLog(line) }
        }.onFailure {
            Log.w(TAG, "call banner unavailable", it)
        }.getOrNull()?.also { callBanner = it } ?: return
        com.shilapi.xcertplay.glance.CarPlayGlance.callListener = { notice ->
            mainHandler.post {
                banner.update(notice, { controller?.answerCall() }, { controller?.endCall() })
            }
        }
        // A call that was already up when this activity attached still gets its banner.
        mainHandler.post {
            banner.update(
                com.shilapi.xcertplay.glance.CarPlayGlance.currentBanner(),
                { controller?.answerCall() },
                { controller?.endCall() },
            )
        }
    }

    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!menuOpen && AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // During a CarPlay call the wheel's call key answers on the iPhone.
        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        // The same press may also arrive through the wheel-key accessibility service or the
        // MEDIA_BUTTON broadcast; only the first delivery opens Siri (docs/_digest/37).
        if (!MediaKeyEchoGate.accept(event.keyCode, event.downTime, event.eventTime)) {
            appendLog("Siri: voice key ${event.keyCode} repeat ignored")
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val sent = controller?.requestSiri() == true
            appendLog("Siri: voice key ${event.keyCode} sent=$sent")
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        isActivityStarted = false
        DiPlayScreenState.carPlayHostVisible = false
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.removeCallbacks(reportVideoFps)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        // The cluster push's artwork tier is *not* released here: the CarPlay session outlives this
        // Activity, and the cluster fetches the cover lazily, so the process-wide loopback server and
        // the covers it serves must stay up until the session really ends. That teardown lives in the
        // host's own `shutdown`, which calls `VendorClusterBridge.release`; see `detach`.
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.detach() }
        // The banner is a window of its own: it would outlive this activity's window if it were left
        // behind, and no card can be answered once the session's UI is gone.
        com.shilapi.xcertplay.glance.CarPlayGlance.callListener = null
        callBanner?.release()
        callBanner = null
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.removeCallbacks(reportVideoFps)
        // 重建（旋转/换语言）时丢弃上一实例的恢复定时器：否则它会在新实例 active 之前
        // 把手机蓝牙恢复一次，并且让旧 Activity 被主 Handler 多持有整个 grace。
        if (isChangingConfigurations) cancelMediaAudioRestore()
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
        // Ending the screen ends the process.
        //
        // The userspace lwIP stack and its netif belong to the process, and the native side has no way to
        // reset them - only per-socket close. Finishing the activity does not end the process either: Android
        // keeps it cached, so the next plug-in runs the host again on the same stack. Measured on the car
        // 2026-10-18 after a burst of swipes: the link reported rxFrames=7104 while injected=29, seven
        // `ncm-status-in` threads were alive from six reconnect attempts, and re-plugging the cable never
        // recovered because every attempt reused the wedged stack.
        //
        // Leaving the activity is the point at which this app has nothing to keep, so it takes the process
        // with it. A rotation or a temporary stop is not that point - only a real finish is, and neither is
        // a disconnect asked for from the home page: that screen is still there and killing the process
        // under it showed up as a black frame (owner report, 2026-10-29).
        if (isFinishing && !isChangingConfigurations && !finishingFromBackground) {
            mainHandler.postDelayed(
                { android.os.Process.killProcess(android.os.Process.myPid()) },
                PROCESS_EXIT_DELAY_MILLIS,
            )
        }
    }

    /** Covers the host's first frames with the home page's last image; dropped when the session goes live. */
    private fun showLaunchSnapshot() {
        val bitmap = CarPlayLaunchSnapshot.bitmap ?: return
        CarPlayLaunchSnapshot.bitmap = null
        val container = findViewById<ViewGroup>(android.R.id.content) ?: return
        val mirror = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        }
        container.addView(mirror, ViewGroup.LayoutParams(-1, -1))
        launchMirror = mirror
    }

    private fun clearLaunchSnapshot() {
        val mirror = launchMirror ?: return
        launchMirror = null
        (mirror.parent as? ViewGroup)?.removeView(mirror)
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surfaceMode = useSurfaceViewForVideo()
        val video = if (surfaceMode) null else TextureView(this).apply {
            // 视频面本身不透明：置 true 让合成器走不透明路径，省掉每帧一次 alpha 混合读
            //（doc 41 §6 的"零风险小赢"，doc 69 P1-10 / doc 70 §2.2 B4 都列为待做）。
            // 与「画面调节」不冲突：那项经 CarPlayPicture 走 setLayerPaint(ColorMatrixColorFilter)，
            // 是独立的离屏层，与这里的 alpha 语义不是同一回事。
            isOpaque = true
            surfaceTextureListener = textureListener
        }
        if (video != null) {
            pictureBinding = CarPlayPicture.Binding(video)
        } else {
            // A/B arm: SurfaceFlinger composites the decoder buffers, so the UI thread stops drawing a
            // full-screen textured quad every frame.
            videoSurfaceView = SurfaceView(this).apply { holder.addCallback(videoSurfaceCallback) }
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        if (video != null) {
            root.addView(video, FrameLayout.LayoutParams(-1, -1))
        } else {
            videoSurfaceView?.let { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        // The corner mask sits over the picture but under the touch layer: it is drawn, never
        // dispatched to, so the three-finger gesture and every CarPlay touch reach the layer above
        // it exactly as before. Its four children are non-clickable and non-focusable for the same
        // reason, and the flag only toggles the mask's visibility so the tree never changes here.
        val cornerMask = CarPlayCornerMaskOverlay(this)
        root.addView(cornerMask, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // The settings menu is built on first open, not here.
        //
        // `openSettingsMenu` already rebuilds it every time (`buildSettingsMenu()` replaces the previous
        // instance), so the one built during `onCreate` was discarded unread - and building it cost part of
        // the several seconds of black screen between plugging in the iPhone and the connecting screen
        // (measured: Host started -> Starting controller took 2203 ms on the car, 2026-10-18). The anchor
        // keeps the menu's z-order without building anything.
        settingsMenuAnchor = FrameLayout(this)
        root.addView(settingsMenuAnchor, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        gestureOverlay = gestureLayer
        cornerMaskOverlay = cornerMask
        // On activity start the setting is the persisted one, so a reboot brings the mask back.
        applyScreenCornerMask()
        updateDebugOverlays()
        return root
    }






    private fun updateMfiTargetFields() {
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }


    /**
     * The three-finger overlay's theme. It has existed since the settings page was redesigned
     * (`SettingsTheme.OVERLAY` is documented as "the three-finger overlay sits on top of CarPlay"), but
     * the panel kept drawing its own sections, statuses and errors from the retired `MENU_*` palette —
     * a cool blue-black canvas with a green accent, against the monochrome warm-black the rest of the
     * product moved to. That mismatch is what made the panel read as a different app (docs/_digest/60).
     */
    private val settingsTheme = SettingsTheme.OVERLAY

    private fun settingsCategoryHeader(title: String): TextView =
        SettingsWidgets.createCategoryHeader(this, title, settingsTheme)

    /** Secondary prose, statuses and inline errors, on the overlay's tokens. */
    private fun settingsDescription(text: String, emphasis: Boolean = false, danger: Boolean = false): TextView =
        SettingsWidgets.createDescription(this, text, settingsTheme, emphasis = emphasis, danger = danger)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                settingsCaption(getString(R.string.report_location_to_iphone)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_android_location_to_the_iphone)
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsDescription(
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }



    /**
     * Shows or hides the four corner views. Only visibility changes, so the mask stays in the tree
     * between the picture and the touch layer and its draw order is fixed for the life of the
     * activity -- it can be switched on and off while CarPlay keeps running.
     */
    private fun applyScreenCornerMask() {
        val overlay = cornerMaskOverlay ?: return
        val visible = screenCornerMask && !menuOpen
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }










    /** Whether the overlay should be sampling: setting on, session running, settings menu closed. */
    private fun shouldShowVideoFps(): Boolean =
        showVideoFps && !menuOpen && activeAirPlaySession != null && fpsView != null

    /** Starts or stops the one-second sampling to match [shouldShowVideoFps]. */
    private fun refreshVideoFps() {
        if (!shouldShowVideoFps()) {
            mainHandler.removeCallbacks(reportVideoFps)
            fpsReporting = false
            fpsMeter.reset()
            fpsView?.visibility = View.GONE
            return
        }
        if (!fpsReporting) {
            fpsReporting = true
            mainHandler.post(reportVideoFps)
        }
    }



    private fun updateManualHotspotFields() {
        existingWifiFields?.visibility = if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) View.VISIBLE else View.GONE
        existingWifiErrorView?.visibility = View.GONE
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.LOCAL && runCatching { DiPlayBootstrap.ensure(this, mfiTarget) }.isFailure ->
                getString(R.string.setup_error_auth)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.remote_server_address_is_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.remote_server_address_must_start_with_http_or_https)
            '\u0000' in remoteMfiServer -> getString(R.string.remote_server_address_contains_u_0000)
            '\u0000' in remoteMfiToken -> getString(R.string.remote_token_contains_u_0000)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(existingWifiSsid, existingWifiPassphrase)
            existingWifiErrorView?.text = error?.let { getString(it.messageResource()) }.orEmpty()
            existingWifiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
            return error == null
        }
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in manualHotspotSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
    }

    /**
     * A page title inside the overlay. The overlay carries its hierarchy in weight and spacing rather
     * than size, so this is deliberately smaller than the old 32 sp headline that competed with the
     * section headers under it.
     */
    private fun settingsTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextColor(settingsTheme.textPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    /** A group label inside a section: same micro-label treatment the settings page uses. */
    private fun settingsCaption(text: String): TextView =
        SettingsWidgets.createCategoryHeader(this, text, settingsTheme)

    /** A live figure or state the driver is meant to read: the accent plus one step of weight. */
    private fun settingsValue(text: String = ""): TextView =
        settingsDescription(text, emphasis = true).apply { textSize = 19f }

    /** An inline failure next to the control that caused it. */
    private fun settingsError(text: String = ""): TextView =
        settingsDescription(text, danger = true).apply {
            visibility = View.GONE
        }

    /**
     * An action in the overlay.
     *
     * Both this and the settings page now go through [SettingsWidgets.createButton], so a secondary button
     * is the same control on both surfaces: the raised fill plus the control border, 1.22:1 off the panel and
     * 3.14:1 for its edge. It used to be a solid white block here and a white hairline there, and the panel's
     * other four buttons (choose image, default icon, set, reset, cancel, save) were not styled at all and
     * drew the framework's default with the theme's light-blue `colorAccent` — four schemes on one screen.
     */
    private fun settingsAction(
        title: String,
        onClick: () -> Unit,
    ): Button = SettingsWidgets.createButton(this, title, theme = settingsTheme, minHeightDp = if (compactSettingsMenu()) 40 else 52) { onClick() }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = SettingsWidgets.createChoiceRow(
        context = this,
        label = label,
        options = options,
        selected = selected,
        theme = SettingsTheme.OVERLAY,
        onSelected = onSelected,
    ).container

    private fun updateResolutionMenu() {
        resolutionValueView?.text = "${displayScalePercent}%"
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.handshake_resolution_waiting_for_display)
        } else {
            val negotiated = CarPlayDisplayScale.applyPercent(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScalePercent,
            )
            "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        // CS11: H.264 only, so the video transport is fixed.
        val transport = "H.264"
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            // The safe area is fixed now that its editor is gone: the summary line that used to be
            // appended here described a value the driver could no longer change.
        }
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 || minOf(display.widthPixels, display.heightPixels) > 2160) {
            CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
        } else decoderCanvasSupport()

    private fun decoderCanvasSupport(): CanvasSupport {
        return try {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        // CS11: 4.4 没有 VideoCapabilities 探测；确认默认解码器可创建即可
        val probe = runCatching { android.media.MediaCodec.createDecoderByType(mime) }.getOrNull()
        if (probe == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            try {
                CanvasSupport(true, "api19_unverified",
                    "Decoder capability codec=${probe.name} mime=$mime result=api19_unverified")
            } finally {
                runCatching { probe.release() }
            }
        }
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
        }
    }

    /**
     * A handshake canvas forced through `debug.diplay.canvas`, for trying sizes without a rebuild.
     *
     * `adb shell setprop debug.diplay.canvas 1200x700` then reconnect; `''` clears it. The value is taken as
     * the stream size the iPhone is asked for, so a different aspect ratio than the view will letterbox.
     * Kept read-only and opt-in: an absent or malformed property changes nothing.
     */
    /** `debug.diplay.settings_menu` as a bench switch: 1/true opens the settings menu at start. */
    private fun debugSettingsMenuRequested(): Boolean = runCatching {
        val raw = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "debug.diplay.settings_menu") as? String
        val text = raw?.trim().orEmpty()
        text == "1" || text.equals("true", ignoreCase = true)
    }.getOrDefault(false)
    /** The head unit keeps a 1280x650 window above its own bar; the tablet has room to spare. */
    private fun compactSettingsMenu(): Boolean = resources.displayMetrics.heightPixels < 800

    private fun debugCanvasOverride(): DisplaySize? = runCatching {
        val raw = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "debug.diplay.canvas") as? String
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return@runCatching null
        val parts = text.lowercase().split("x")
        if (parts.size != 2) return@runCatching null
        val width = parts[0].trim().toIntOrNull() ?: return@runCatching null
        val height = parts[1].trim().toIntOrNull() ?: return@runCatching null
        if (width < 320 || height < 240 || width > 4096 || height > 4096) return@runCatching null
        DisplaySize(width, height)
    }.getOrNull()

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        // The home settings page can change the name while this host stays alive.
        if (!menuOpen) oemLabel = AirPlayPersistence.loadOemLabel(this)
        val forced = debugCanvasOverride()
        val safeWidth = ((forced?.width ?: size.width) / 2 * 2).coerceAtLeast(2)
        val safeHeight = ((forced?.height ?: size.height) / 2 * 2).coerceAtLeast(2)
        val alignedSize = DisplaySize(safeWidth, safeHeight)
        if (forced != null) {
            appendLog("Canvas override active: negotiating ${safeWidth}x$safeHeight instead of ${size.width}x${size.height}")
        }
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            // Removing the smaller-controls enlargement may leave a separately enlarged resolution.
            if (displayScalePercent > 100) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > 100) {
            resolutionFallback = true
            displayScalePercent = 100
            displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        // A fixed dock declares one area per edge and the car switches between them live.
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        val viewAreas = CarPlayViewAreas.build(display.widthPixels, display.heightPixels, dock)
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) display else display.copy(viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps codec=H.264"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        // The OEM icon declaration is carried through the config so the /info path can log what was
        // really sent; the car's own log is the only place that answers why no icon appears.
        val declaredIcon = loadAirPlayIcon()
        return AirPlayConfig(
            deviceName = DiPlayBootstrap.CARPLAY_NAME,
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = null,
            rightHandDrive = rightHandDrive,
            microphone = microphoneAvailable,
            // Probed before the first info plist: wireless CarPlay sends calls and Siri as Opus, and
            // the CS11 has no MediaCodec Opus encoder, so the microphone input is advertised only
            // when the car can really encode it (libopus or the vendored Java encoder). Output
            // formats are deliberately not gated — removing Opus there stops audio entirely.
            // `debug.diplay.opus` moves both sides only for the experiment in docs/_digest/28.
            microphoneOpus = AudioOpusPolicy.microphoneOpus(
                AudioOpusPolicy.current(), com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            ),
            opusOutput = AudioOpusPolicy.opusOutput(AudioOpusPolicy.current()),
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOfNotNull(declaredIcon.icon),
            oemIconAbsence = declaredIcon.absence,
            videoInCar = false,
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
        )
    }

    /** The OEM icon to declare, plus why there is none when [icon] is null. */
    private class AirPlayIconDeclaration(val icon: AirPlayIcon?, val absence: OemIconAbsence?)

    /**
     * Loads the OEM icon the way the previous implementation did -- the user's custom 1:1 image when
     * it decodes, the packaged `R.raw.ic_car_home` otherwise -- and reports which candidate failed
     * when neither can be used.
     *
     * The one behaviour change is that a packaged icon that does not decode no longer aborts the
     * session with `IllegalStateException`: an accessory that cannot offer an icon is still a working
     * accessory, and a crash here costs the whole connect for a decoration. The dictionary then
     * carries no `oemIcons` at all and [AirPlayConfig.oemIconAbsence] says why, so the reason survives
     * into the /info log instead of into a stack trace.
     */
    private fun loadAirPlayIcon(): AirPlayIconDeclaration {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return AirPlayIconDeclaration(it, null) }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        val fallback = runCatching { decodeAirPlayIcon(defaultAirPlayIconBytes()) }.getOrNull()
        if (fallback != null) return AirPlayIconDeclaration(fallback, null)
        return AirPlayIconDeclaration(
            null,
            if (customBytes != null) OemIconAbsence.CUSTOM_ICON_REJECTED_AS_NOT_SQUARE
            else OemIconAbsence.NO_CUSTOM_FILE_AND_PACKAGED_DEFAULT_UNDECODABLE,
        )
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_placeholder_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        // Both video sinks size themselves to the activity window, which is what the car leaves above
        // its bottom bar (1280x650 here). The SurfaceView arm must report that, not the full 720-line
        // display: negotiating 720 makes the iPhone encode a frame whose bottom 70 lines are hidden
        // behind the bar, and the decoder then processes 1280x736 for nothing.
        val view: View? = videoView ?: videoSurfaceView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )








    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            phoneChannel = AirPlayPersistence.loadCallAudioChannel(this),
            // 设置页「声音设置」的解码器偏好。取下次建流生效；不可用时渲染器自动回落并写诊断行。
            audioDecoderChoice = AirPlayPersistence.loadAudioDecoder(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    /**
     * Releases the phone's Bluetooth media link while CarPlay owns the audio.
     *
     * The CS11's own engine (`com.ecarx.multimedia`'s `BTMusicPlaybackEngine`) pauses whatever the phone
     * plays over its A2DP link whenever the head unit's current source is not Bluetooth, which is what
     * the field reports as "the music stops one second after play" (docs/_digest/27 §6sexies, measured
     * 2026-10-07: `[AVRCP_PLAYING_STATUS_ID_PLAYING]` -> `NotToPlay current audio is not bt,so pause bt
     * music` -> `reqAvrcpPause` -> `[AVRCP_PLAYING_STATUS_ID_PAUSED]`). Wired CarPlay hits it too: the
     * iPhone stays A2DP-paired to this head unit, so iOS 26 even asks for the link to go away itself
     * (the `disableBluetooth` command below).
     */
    private val mediaAudioWatchdog by lazy { PhoneMediaAudioWatchdog(this) { appendLog(it) } }

    /** Whether a lost session still owes the phone its Bluetooth back. See [MediaAudioRestorePolicy]. */
    private val mediaAudioRestorePolicy = MediaAudioRestorePolicy()

    /**
     * Gives the phone its Bluetooth back once a lost session has stayed lost for the whole grace.
     *
     * Re-checked against the live state rather than trusting the timer alone: a session that came back
     * (or a shutdown that already restored) makes this a no-op.
     */
    private val mediaAudioRestore = object : Runnable {
        override fun run() {
            if (!mediaAudioRestorePolicy.graceElapsed()) return
            if (shuttingDown.get() || CarPlayBackgroundSession.active) return
            appendLog(
                "no CarPlay session for ${MEDIA_AUDIO_RESTORE_GRACE_MILLIS}ms; " +
                    "giving the phone's Bluetooth back",
            )
            restorePhoneMediaAudio()
        }
    }

    /** Arms the grace on the first loss of a session; the timer's own firing is the only consumer. */
    private fun scheduleMediaAudioRestore() {
        if (mediaAudioRestorePolicy.sessionLost()) {
            mainHandler.postDelayed(mediaAudioRestore, MEDIA_AUDIO_RESTORE_GRACE_MILLIS)
        }
    }

    /** A session is live again (or the app is going away): the phone is not owed anything yet. */
    private fun cancelMediaAudioRestore() {
        mediaAudioRestorePolicy.sessionActive()
        mainHandler.removeCallbacks(mediaAudioRestore)
    }

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onSessionActive(session: AirPlaySession) {
                // A live session is no longer owed the phone's Bluetooth, so a grace armed by an earlier
                // loss is dropped before anything else can fire it.
                cancelMediaAudioRestore()
                // Started here as well as by DiPlaySessionService: the release is what keeps the phone
                // playing at all, and the service is not always the thing that brought the session up.
                mediaAudioWatchdog.startQuietly()
                // Released here too, without waiting for iOS's `disableBluetooth`: on a cold start the head
                // unit's Bluetooth API answers "not connected" for the first attempt (measured on the car
                // on 2026-10-15, see `releasePhoneA2dpRetrying`), and the command can also arrive late.
                releasePhoneA2dpRetrying(this@CarPlayHostActivity, onLog = { appendLog(it) })
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    refreshVideoFps()
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    CarPlayProgress.clear()
                    // The attempt succeeded: there is no failure left to show on the home page.
                    ConnectionAttemptRecord.sessionActive(this@CarPlayHostActivity)
                    // The bring-up is over: if the user started it from the home page, bring the video
                    // forward the way the connecting screen used to when it swapped to the session.
                    if (startBehind) {
                        startBehind = false
                        // Drop the home page's still frame before the window comes forward with the video.
                        clearLaunchSnapshot()
                        startActivity(
                            Intent(this@CarPlayHostActivity, CarPlayHostActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                        )
                    }
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            /**
             * iOS asks the accessory to drop its Bluetooth link once CarPlay owns the media audio.
             *
             * The command arrives on both transports; only the wireless handoff used to look at it, so a
             * wired session ignored it and the head unit kept pausing the phone over A2DP. Releasing the
             * link is the protocol's own remedy (the same release `PhoneMediaAudioWatchdog` performs
             * periodically, because the iPhone may re-establish A2DP on its own).
             */
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                if (!type.equals("disableBluetooth", ignoreCase = true)) return
                // Wrapped so a failure names itself: on 2026-10-13 the log showed the line below and then
                // nothing from the watchdog, which is indistinguishable from "the phone was never asked".
                // The direct release is called as well as the watchdog: the watchdog owns the periodic
                // re-check and the address bookkeeping, while this call is the one that must not depend on
                // any of that having gone right first.
                runCatching {
                    android.util.Log.i("DiPlay-BtAudio", "disableBluetooth received; releasing the phone's A2DP link")
                    appendLog("CarPlay asked for Bluetooth off; releasing the phone's A2DP link")
                    val released = runCatching { releasePhoneA2dp(this@CarPlayHostActivity) { appendLog(it) } }
                        .getOrDefault(false)
                    // The ladder's first attempt is immediate, so the command's own release is kept and the
                    // retries its cold-start refusal needs are added on top (2026-10-15, see
                    // `releasePhoneA2dpRetrying`).
                    releasePhoneA2dpRetrying(this@CarPlayHostActivity, onLog = { appendLog(it) })
                    android.util.Log.i("DiPlay-BtAudio", "disableBluetooth release -> a2dpReleased=$released")
                    appendLog("released the phone's A2DP link=$released")
                    mediaAudioWatchdog.startQuietly()
                    android.util.Log.i("DiPlay-BtAudio", "disableBluetooth watchdog start requested")
                }.onFailure { error ->
                    android.util.Log.i("DiPlay-BtAudio", "disableBluetooth handling failed: ${error.javaClass.name}: ${error.message}")
                    appendLog("releasing the phone's A2DP link failed: ${error.javaClass.name}: ${error.message}")
                }
            }

            /**
             * Records the phone's own address, which is the device the A2DP release has to disconnect.
             *
             * The iPhone reports `9c:92:4f:02:b7:b7` as its deviceId, and the head unit's adapter API takes
             * exactly that address (`reqA2dpDisconnect(9C:92:4F:02:B7:B7)=true` in the 2026-10-07 log,
             * docs/_digest/27 §6sexies). Only the wireless pairing flow used to save it, so a wired
             * session left [PhoneMediaAudioWatchdog] without one: it logged
             * `phone media watchdog not started (adapter=true address=false)` on 2026-10-13 and the head
             * unit kept pausing the phone a second into every track.
             */
            override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
                if (info.deviceId.count { it == ':' } != 5) return
                val known = DiPlayPreferences.phoneBluetoothAddress(this@CarPlayHostActivity)
                if (known != null && known.equals(info.deviceId, ignoreCase = true)) return
                DiPlayPreferences.savePhoneBluetoothAddress(this@CarPlayHostActivity, info.deviceId)
                appendLog("recorded the phone's Bluetooth address ${info.deviceId}")
                mediaAudioWatchdog.startQuietly()
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) &&
                            startupRetryBudget.resetIfStable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        // 探针或非活动握手结束时，保留当前播放会话。
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    activeAirPlaySession = null
                    refreshVideoFps()
                    CarPlayBackgroundSession.active = false
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (menuOpen) return@runOnUiThread
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    /**
     * The wired bring-up in the order the controller runs it: label and the status that marks it current.
     *
     * Labels reuse the strings `CarPlayStatus.describe()` already shows, so the checklist needs no new
     * translations and stays in step with the status texts.
     */
    private val connectNodes: List<Pair<CarPlayStatus, Int>>
        get() = listOf(
            CarPlayStatus.DiscoveringMfi to R.string.preparing_mfi_authentication,
            CarPlayStatus.DiscoveringIphone to R.string.discovering_iphone,
            CarPlayStatus.SelectingConfiguration to R.string.selecting_carplay_configuration,
            CarPlayStatus.WaitingForReenumeration to R.string.status_waiting_reenumeration,
            CarPlayStatus.OpeningDataPaths to R.string.opening_usb_data_paths,
            CarPlayStatus.Pairing to R.string.pairing_with_iphone,
            CarPlayStatus.AttachingNetwork to R.string.status_attaching_airplay_lwip,
            CarPlayStatus.RunningControl to R.string.carplay_control_running,
        )

    /**
     * Moves the checklist cursor. A failure marks the node it happened on and stays there: that is the
     * answer to "where did it stop", which a rolling log cannot give once the retry overwrites it.
     */
    private fun trackConnectNode(status: CarPlayStatus) {
        if (connectStartedAtMs == 0L) connectStartedAtMs = SystemClock.elapsedRealtime()
        connectNodeSeen = true
        if (status is CarPlayStatus.Failed) {
            connectNodeFailed = true
            return
        }
        val index = connectNodes.indexOfFirst { it.first == status }
        if (index >= 0) {
            connectNodeIndex = index
            connectNodeFailed = false
        }
    }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        // Recorded before the menu check: an open menu defers the reaction, not the fact that it failed.
        recordAttemptStatus(status)
        if (menuOpen) {
            if (status is CarPlayStatus.Failed) failurePendingAfterMenu = status
            return@report
        }
        updateHotspotStatus(status)
        trackConnectNode(status)
        val description = status.describe()
        setConnectionStage(description)
        when (status) {
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                // The connecting screen that carried this button has been deleted; the recovery dialog
                // itself lives on the home page (page "wireless-recovery"), so send the driver there once
                // instead of restarting by itself.
                wifiRecoveryButton?.visibility = View.VISIBLE
                if (!wifiRecoveryHandedOff) {
                    wifiRecoveryHandedOff = true
                    showDiPlayHome("wireless-recovery", noAnimation = true)
                }
            } else {
                wifiRecoveryHandedOff = false
                wifiRecoveryButton?.visibility = View.GONE
                if (status.startupFailure != null) {
                    if (startupFailureGeneration == controllerGeneration) return@report
                    startupFailureGeneration = controllerGeneration
                }
                reconnectAfterLoss(description, status.startupFailure)
            }
            else -> Unit
        }
    }

    /**
     * Writes the step or the failure into ConnectionAttemptRecord. Display only: nothing here changes how
     * the connection proceeds or retries.
     */
    private fun recordAttemptStatus(status: CarPlayStatus) {
        val description = status.describe()
        if (status is CarPlayStatus.Failed) {
            val reason = when (status.startupFailure) {
                WirelessStartupFailure.HOTSPOT_NOT_READY, WirelessStartupFailure.FIRST_TCP_TIMEOUT ->
                    "$description (${status.message})"
                else -> status.message
            }
            ConnectionAttemptRecord.failed(this, lastAttemptStage, reason)
        } else {
            lastAttemptStage = description
            ConnectionAttemptRecord.progressed(this, description)
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        // Failure visibility (README gap): open or continue the on-disk record of this attempt before the
        // bring-up, so the home page can still say what happened after this screen and its process are gone.
        ConnectionAttemptRecord.started(this, wirelessEnabled, keepFailure = attemptRecorded)
        // Mode convergence: the home page's tab follows the transport actually being brought up.
        CarPlayProgress.publishTransport(wirelessEnabled)
        if (!attemptRecorded) lastAttemptStage = null
        attemptRecorded = true
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else {
            size
        }
        val airPlayConfig = createAirPlayConfig(effectiveSize)
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) AndroidCarPlayLocationProvider(this) else null
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${displayScalePercent}%) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=H.264 decoder=hardware " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${displayScalePercent}% " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
        )
        controller = next
        CarPlayMediaKeys.attach(this, next)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, effectiveSize.width, effectiveSize.height,
            viewAreas = pendingViewAreas,
        )
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                // If this screen is not the one on display, the home page asked for the disconnect: finish
                // quietly and leave the process alone so that page survives.
                finishingFromBackground = !isActivityStarted
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            val serviceIntent = Intent(this, DiPlaySessionService::class.java)
            // CS11: startForegroundService 是 API26；4.4 走 startService
            startService(serviceIntent)
            // Hand the screen back *before* the bring-up: `next.start()` spends the next stretch loading the
            // offline MFi identity and opening the link, and letting the host stay on top through that was
            // the flash of the checklist the owner saw. `setVisible(false)` hides this window instead of
            // leaving a blank one on top, so the home page below is already visible while the activity
            // manager finishes the reorder (see EXTRA_START_BEHIND).
            if (startBehind) {
                // Home comes forward over the still frame; hiding this window first would leave a beat
                // where neither screen is drawn, which is the flash the owner saw.
                showDiPlayHome(noAnimation = true)
                // The host lives in its own task; without this the cross-task switch reads as a black
                // flash between two moments where the home page should be the only thing on screen.
                overridePendingTransition(0, 0)
            }
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        // shutdown 之后该线程池已关闭，再投递会抛 RejectedExecutionException。
        if (airPlayCommandExecutor.isShutdown) return
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean {
        return false
    }


    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        val layoutChanged = displayLayoutChanged()
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    /**
     * Whether the session must be torn down and rebuilt because the window no longer matches it.
     *
     * Only an actual rotation and a change to the negotiated system-bar layout qualify. A window that
     * merely changes shape keeps its session, because the stream is fitted to the view by the GPU
     * ([CarPlayVideoLayout]) instead of being renegotiated.
     */
    private fun displayLayoutChanged(): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        return false
    }



    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // Every declared area (a fixed dock's edges) is the whole canvas, so the stream always fits.
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        view.setTransform(Matrix().apply {
            setScale(content.width / viewWidth, content.height / viewHeight)
            postTranslate(content.left, content.top)
        })
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        // 这个函数会以 500 ms 自投递等待另一个 Activity 让出会话；Activity 已 finish/销毁后
        // 仍会继续投递并可能把会话重新绑到一个死实例上。
        if (isFinishing || isDestroyed) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        // Wired never needs a VPN gate now; only the wireless path has permissions to wait for.
        val transportReady = !wirelessEnabled || wirelessPermissionsReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String, startupFailure: WirelessStartupFailure? = null) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (menuOpen) recoveryPendingAfterMenu = true
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRetryStopped) return
        if (reconnectScheduled) return
        // The session is gone from here on, and wired loss is retried indefinitely, so nothing else
        // will ever stop the watchdogs: arm the grace that gives the phone its Bluetooth back.
        scheduleMediaAudioRestore()
        val startupDelay = if (startupFailure != null && startupFailure != WirelessStartupFailure.HOTSPOT_CONFIGURATION)
            startupRetryBudget.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            startupRetryStopped = true
            startupRetryButton?.visibility = View.VISIBLE
            setConnectionStage(if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) reason
                else "$reason\n${getString(R.string.wireless_startup_retries_exhausted)}")
            appendLog("wireless startup recovery stopped generation=$restartGeneration reason=$startupFailure retries=${startupRetryBudget.retries}")
            return
        }
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure startupRetries=${startupRetryBudget.retries}")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (menuOpen && generation == restartGeneration) recoveryPendingAfterMenu = true
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress || startupRetryStopped ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        startupRetryBudget.disconnected()
        startupRetryButton?.visibility = View.GONE
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        sink = null
        sessionDisplay = null
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            // The iPhone must see a detach before the next CarPlayStartSession, or it silently ignores
            // it (tablet bench 2026-10-19). This is the software equivalent of unplugging the cable.
            runCatching { oldController?.requestWiredReenumeration() }
                .onFailure { Log.w(TAG, "wired re-enumeration request failed", it) }
            // Synchronous, and before close(): this block ends by killing the process on every exit
            // path, and the configuration switch has to reach the phone first.
            runCatching { oldController?.detachWiredForReconnect() }
                .onFailure { Log.w(TAG, "wired detach failed", it) }
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home", noAnimation: Boolean = false) {
        controller?.sendTouch(emptyList())
        val intent = Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // A task switch on 4.4 plays the system zoom animation (the page shrinking in and out); the
        // from-home connect must not show it, because it reads as the screen being re-launched.
        if (noAnimation) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
    }

    private fun openSettingsMenu() {
        if (menuOpen) return
        // The three-finger gesture no longer builds an in-CarPlay panel: it opens the app's own settings
        // page, so there is exactly one settings screen to maintain. The session keeps running behind it.
        controller?.sendTouch(emptyList())
        showDiPlayHome("settings", noAnimation = true)
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        controller?.sendTouch(emptyList())
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(this, ::closePicturePanel)
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }




    private fun exitApplication() {
        if (shuttingDown.get()) return
        finish()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        // No session is being brought up any more, and the home page must stop spinning for it.
        CarPlayProgress.clear()
        CarPlayProgress.clearTransport()
        // A clean end (disconnect, exit) is not an interruption; a recorded failure stays visible.
        ConnectionAttemptRecord.ended(this)
        clearLaunchSnapshot()
        // Shutdown restores through DiPlaySessionService's own stop; a grace still pending would fire
        // against a stack that is already gone.
        cancelMediaAudioRestore()
        // The session is really over: hand the phone's Bluetooth back and stop every surviving
        // watchdog instance, including this Activity's own. Without this the home-page "Disconnect"
        // (terminateProcess = false) leaves an instance that re-releases within its next 5 s tick,
        // and nothing ever calls restore() again for the life of the process.
        restorePhoneMediaAudio()
        startupRetryBudget.disconnected()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        sessionDisplay = null
        // 会话已结束：UI 侧的会话引用也必须清掉。否则退出瞬间的环境光/日夜模式回调仍会
        // 往 airPlayCommandExecutor 投递任务，而它在 teardown 里已被 shutdown，抛出 REE。
        activeAirPlaySession = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            // Ask the iPhone to re-enumerate (the software equivalent of unplugging): without it the
            // phone keeps the CarPlay session and refuses the next one, so a reconnect needs a physical
            // replug. Runs before close() because the request needs the USB device still open.
            runCatching { oldController?.requestWiredReenumeration() }
                .onFailure { Log.w(TAG, "wired re-enumeration request failed", it) }
            // Synchronous, and before close(): this block ends by killing the process on every exit
            // path, and the configuration switch has to reach the phone first.
            runCatching { oldController?.detachWiredForReconnect() }
                .onFailure { Log.w(TAG, "wired detach failed", it) }
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            // The CarPlay session is over, so the artwork tier's process-wide state goes with it: the
            // covers the cluster may still have fetched and the loopback port their URL names. This is
            // deliberately *not* in `onDestroy` -- an Activity that is merely recreated (or backgrounded)
            // must leave that URL answering, which is the whole reason the store and the server are
            // process-scoped. See `VendorClusterBridge.release`.
            runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.release() }
                .onFailure { Log.w(TAG, "cluster artwork session release failed", it) }
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayListenerService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        appendLog("attach surface id=${System.identityHashCode(surface)} valid=${surface.isValid}")
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        // Stream 111 must NOT share the main SurfaceTexture: with the dashboard map off this used to
        // hand it the same surface, so two decoders wrote into one SurfaceTexture and the main stream
        // never won a buffer (shown=0 while its stream kept arriving) — the black screen after the
        // surface came back. The centre-screen card has its own mirror surfaces (mirrorSink →
        // setMirrorSurface), so nothing here depends on that sharing (docs/_digest/36).
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true
        // The previous event's coordinates, still holding the previous event while this one is
        // handled: a gesture established mid-motion needs to know where its fingers were before the
        // event that established it. Cleared on ACTION_DOWN, so it never outlives its sequence.
        val previous = gesturePreviousSample
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            gesturePreviousSample = PreviousGestureSample.of(event)
        }
        return handleHostTouch(view, event, previous)
    }

    private fun handleHostTouch(view: View, event: MotionEvent, previous: PreviousGestureSample?): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                resetSettingsSwipeGesture()
                gesturePreviousSample = PreviousGestureSample.of(event)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == gestureFingerCount && !gestureSequenceActive) {
                    startSettingsSwipeTracking(event, previous)
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            val requiredFingersDown = event.actionMasked != MotionEvent.ACTION_POINTER_UP
            if (!gestureTracking && requiredFingersDown) {
                // A tracked finger went away without the gesture completing; keep swallowing the
                // sequence until it ends.
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    resetSettingsSwipeGesture()
                }
                return true
            }
            if (gestureTracking) {
                val lifting = event.actionMasked == MotionEvent.ACTION_POINTER_UP
                val moved = event.actionMasked == MotionEvent.ACTION_MOVE
                if (!(moved || lifting)) {
                    // TEMPORARY: a system gesture monitor (MIUI's three-finger shortcut) ends the stream
                    // with ACTION_CANCEL a few ms in; without this line the panel simply never opens.
                    appendLog("Settings swipe dropped action=${MotionEvent.actionToString(event.actionMasked)}")
                } else if (!pointerIdsStillDown(event)) {
                    appendLog("Settings swipe lost a finger pointers=${event.pointerCount}")
                }
                if ((moved || lifting) && pointerIdsStillDown(event)) {
                    // Both ACTION_MOVE and ACTION_POINTER_UP are evaluated: a user who puts three
                    // fingers down and swipes in one motion, or who lifts a finger as the swipe ends,
                    // must still land the gesture (EasyPlay v71 evaluates action 2 and 6 alike). A
                    // POINTER_UP is the gesture's last chance either way: the finger that lifted is
                    // gone, so whether or not the threshold is reached here, tracking must not stay
                    // armed with only the remaining fingers.
                    if (swipeReachedSettingsThreshold(
                            event,
                            liftedId = if (lifting) event.getPointerId(event.actionIndex) else -1,
                        )
                    ) {
                        gestureSequenceActive = false
                        gestureTracking = false
                        resetSettingsSwipeGesture()
                        openSettingsMenu()
                        return true
                    }
                    if (lifting) {
                        gestureTracking = false
                        resetSettingsSwipeGesture()
                    }
                } else {
                    gestureTracking = false
                    resetSettingsSwipeGesture()
                }
                return true
            }
            // Tracking was dropped earlier in this sequence: swallow the rest of it.
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureSequenceActive = false
                resetSettingsSwipeGesture()
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    /**
     * Establishes a settings swipe from any event that already carries the configured number of
     * distinct fingers, not only from a fresh ACTION_POINTER_DOWN: a user who lands three fingers and
     * swipes in one motion may only ever deliver ACTION_MOVE with pointerCount == 3 (EasyPlay v71
     * builds its Swipe candidate for action 5 *or* 2, docs/_digest/42).
     *
     * Every tracked finger takes its start position from where it was *before* the establishing event.
     * Measuring every finger from the establishing event would compare that event with itself and
     * never see any travel at all, which is why the previous event is needed.
     */
    private fun startSettingsSwipeTracking(event: MotionEvent, previous: PreviousGestureSample?) {
        val ids = (0 until event.pointerCount).map { event.getPointerId(it) }.toMutableSet()
        if (ids.size != gestureFingerCount) {
            // The same pointer id repeated across indices is not a multi-finger gesture; the touch
            // path must keep forwarding it instead of swallowing it as a swipe.
            return
        }
        gestureSequenceActive = true
        gestureTracking = true
        gesturePointerIds.clear()
        gesturePointerIds.addAll(ids)
        gestureStartPositions.clear()
        for (id in ids) {
            val sample = previous?.coords(id)
            if (sample != null) {
                gestureStartPositions[id] = PointF(sample.x, sample.y)
            }
            // A finger that was not down before this event has no sample yet:
            // swipeReachedSettingsThreshold records one for it on the first evaluation and leaves it
            // out of that evaluation, so the travel that established the gesture is never credited to it.
        }
        controller?.sendTouch(emptyList())
        appendLog("Settings swipe tracking started; fingers=$gestureFingerCount")
    }

    /** Drops every pointer id and start position of the current gesture. */
    private fun resetSettingsSwipeGesture() {
        gesturePointerIds.clear()
        gestureStartPositions.clear()
    }

    /** True when every finger the gesture was established with is still present in [event]. */
    private fun pointerIdsStillDown(event: MotionEvent): Boolean {
        for (id in gesturePointerIds) {
            if (event.findPointerIndex(id) < 0) return false
        }
        return true
    }

    /**
     * True when every finger that is still touching has travelled far enough downward for the
     * settings swipe. [liftedId] is the pointer the platform reports as leaving the screen in an
     * ACTION_POINTER_UP: the platform still delivers its coordinates, so it has to be excluded
     * explicitly, otherwise a finger that never moved would block the gesture. Requiring every finger
     * to pass on its own keeps the gesture from completing off one stray pointer (EasyPlay v71 checks
     * each Origin that way, docs/_digest/42).
     *
     * A finger with no recorded start position cannot be judged: the first evaluation records this
     * event's position for it and leaves it out, so the travel that established the gesture is not
     * credited to a finger that arrived with it. That finger is judged from the next event onwards,
     * and the gesture is never rejected for it alone.
     */
    private fun swipeReachedSettingsThreshold(event: MotionEvent, liftedId: Int): Boolean {
        val threshold = dp(SETTINGS_SWIPE_DISTANCE_DP).toFloat()
        var counted = 0
        var horizontal = 0f
        var vertical = 0f
        for (id in gesturePointerIds) {
            if (id == liftedId) continue
            val index = event.findPointerIndex(id)
            if (index < 0) continue
            val start = gestureStartPositions[id]
            if (start == null) {
                gestureStartPositions[id] = PointF(event.getX(index), event.getY(index))
                continue
            }
            if (!fingerTravelledDownward(event, index, start, threshold)) return false
            horizontal += event.getX(index) - start.x
            vertical += event.getY(index) - start.y
            counted += 1
        }
        if (counted == 0) return false
        // The centroid check repeats the per-finger one at gesture scale: it rejects a set of fingers
        // whose average drift is sideways even though each of them individually moved downward.
        val deltaX = Math.abs(horizontal / counted)
        val deltaY = vertical / counted
        return deltaY >= threshold && deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
    }

    private fun fingerTravelledDownward(event: MotionEvent, index: Int, start: PointF, threshold: Float): Boolean {
        val deltaX = Math.abs(event.getX(index) - start.x)
        val deltaY = event.getY(index) - start.y
        return deltaY >= threshold && deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
    }

    /**
     * Pointer coordinates of one MotionEvent, copied out of it. MotionEvent objects are recycled by
     * the framework for every event of a sequence, so "the previous event" has to be captured by
     * value while it is still the current one.
     */
    private class PreviousGestureSample(private val byId: Map<Int, PointF>) {
        fun coords(id: Int): PointF? = byId[id]

        companion object {
            fun of(event: MotionEvent): PreviousGestureSample {
                val byId = mutableMapOf<Int, PointF>()
                for (index in 0 until event.pointerCount) {
                    byId[event.getPointerId(index)] = PointF(event.getX(index), event.getY(index))
                }
                return PreviousGestureSample(byId)
            }
        }
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        val friendly = friendlyStage(message)
        // The home page keeps showing this line while it stays in front during the bring-up.
        CarPlayProgress.publish(friendly)
        stageStatusView?.text = friendly
        // The recent stages are the connecting screen's own list: it says how far the bring-up got, which is
        // what a driver waiting at the screen - and a log read afterwards - needs (docs/_digest/60).
        // Wired redraws the checklist on every status, not only when the friendly text changes: the friendly
        // mapper collapses many stages into one sentence, so the dedup below swallowed most node advances and
        // the screen sat on "preparing MFi" until CarPlay appeared (measured: two redraws, node=0 both times).
        if (!wirelessEnabled) {
            renderConnectChecklist()
        } else if (recentStages.isEmpty() || recentStages.last() != friendly) {
            recentStages.addLast(friendly)
            while (recentStages.size > recentStageLimit) recentStages.removeFirst()
            renderRecentStages()
        }
        updateDebugOverlays()
    }

    /**
     * Draws the connecting screen's step list.
     *
     * Wired shows a **fixed checklist** of the bring-up nodes (`connectNodes`), each with its state, so the
     * driver can see which step is running, which ones are done, and - when it fails - which node it died on
     * and where the retry is. Wireless keeps the rolling [recentStages] list, because its pipeline branches
     * (hotspot, Bluetooth, handoff) in ways a single ordered checklist would misrepresent.
     */
    private fun renderRecentStages() {
        if (!wirelessEnabled) {
            renderConnectChecklist()
            return
        }
        val list = stageListView ?: return
        val colors = WaitingScreenColors.of(darkMode)
        list.removeAllViews()
        val entries = recentStages.toList()
        entries.forEachIndexed { index, text ->
            val current = index == entries.lastIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            row.addView(
                TextView(this).apply {
                    this.text = "%02d".format(index + 1)
                    textSize = 12f
                    setTextColor(colors.secondary)
                },
                LinearLayout.LayoutParams(dp(34), -2),
            )
            row.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 17f
                    setTextColor(if (current) colors.text else colors.secondary)
                    typeface = Typeface.create(
                        if (current) "sans-serif-medium" else "sans-serif",
                        Typeface.NORMAL,
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            list.addView(
                View(this).apply { setBackgroundColor(Color.rgb(0x2A, 0x29, 0x26)) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
        }
    }

    /** The wired checklist: every node, with a marker for done / running / pending / failed. */
    private fun renderConnectChecklist() {
        val list = stageListView ?: return
        // Diagnostic: proves whether the checklist is being redrawn as the bring-up advances, and whether it
        // reaches the screen at all (the owner saw the status line stay on MFi until CarPlay appeared).
        appendLog(
            "CHECKLIST render node=$connectNodeIndex failed=$connectNodeFailed seen=$connectNodeSeen " +
                "elapsedMs=${if (connectStartedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - connectStartedAtMs} " +
                "attached=${list.parent != null}",
        )
        list.removeAllViews()
        if (!connectNodeSeen) return
        val colors = WaitingScreenColors.of(darkMode)
        val failedColor = Color.rgb(0xFF, 0x6B, 0x6B)
        list.removeAllViews()
        val nodes = connectNodes
        // A moving window of five nodes, not all eight.
        //
        // `fitPreparationContent` scales the whole panel when it is taller than the viewport, and a scaled
        // panel renders its text blurry on this GPU. Eight rows made it tall enough to shrink, which the owner
        // saw as "content is blurry and the layout is a little off". Five rows matches the height the old
        // rolling list could reach, so the panel stays unscaled at 1280x650.
        val first = (connectNodeIndex - 2).coerceIn(0, (nodes.size - CHECKLIST_VISIBLE_NODES).coerceAtLeast(0))
        val last = (first + CHECKLIST_VISIBLE_NODES - 1).coerceAtMost(nodes.size - 1)
        nodes.forEachIndexed { index, (_, labelRes) ->
            if (index < first || index > last) return@forEachIndexed
            val done = index < connectNodeIndex
            val active = index == connectNodeIndex
            val failed = active && connectNodeFailed
            val marker = when {
                failed -> "\u2716"
                done -> "\u2714"
                active -> "\u25CF"
                else -> "\u25CB"
            }
            // Compact on purpose: `PreparationLayoutTest` requires this panel to fit a 200 dp viewport
            // without scrolling, and eight rows of the roomier rolling-list style overflow it.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(
                TextView(this).apply {
                    text = marker
                    textSize = 13f
                    setTextColor(if (failed) failedColor else if (active) colors.text else colors.secondary)
                },
                LinearLayout.LayoutParams(dp(26), -2),
            )
            row.addView(
                TextView(this).apply {
                    text = getString(labelRes)
                    textSize = 15f
                    setTextColor(if (failed) failedColor else if (active) colors.text else colors.secondary)
                    typeface = Typeface.create(
                        if (active) "sans-serif-medium" else "sans-serif",
                        Typeface.NORMAL,
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            list.addView(
                View(this).apply { setBackgroundColor(Color.rgb(0x2A, 0x29, 0x26)) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
        }
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun friendlyStage(message: String): String = when {
        message == getString(R.string.waiting_for_mfi_coprocessor) ||
            message == getString(R.string.requesting_mfi_usb_permission) -> message
        message.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        // Ported from upstream 5fbd7171: the radio-off message asks the driver to act, and this turns it into
        // the head unit's own wording. Fully qualified because this file has no transport import for it.
        message.contains(com.shilapi.xcertplay.transport.BluetoothRadioCheck.RADIO_OFF_MARK, true) ->
            getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) -> getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("unsupported", true) || message.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) || message.contains("error", true) -> getString(R.string.connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()


    // CS11: backgroundTintList 是 API21+；4.4 退化为纯色背景
    private fun View.tintBackgroundCompat(color: Int) {
        setBackgroundColor(color)
    }

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                getString(R.string.existing_wifi_ready, ssid, band, channel, address)
            } else getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.starting_airplay_service) else getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> getString(R.string.first_tcp_timeout)
            else -> getString(R.string.status_failed, message)
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"

        /** Grace before the process exits with a finished screen; see `onDestroy`. */
        private const val PROCESS_EXIT_DELAY_MILLIS = 400L

        /** Overlay sampling period, as EasyPlay uses (one second). */
        private const val VIDEO_FPS_INTERVAL_MILLIS = 1_000L
        /** Rows the connecting checklist may occupy; see `renderConnectChecklist`. */
        private const val CHECKLIST_VISIBLE_NODES = 5
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        /**
         * How long a lost session may stay lost before the phone gets its Bluetooth back. Long enough
         * that a cable that merely moved (or an iAP tunnel that is being rebuilt) comes back first, so
         * the phone's Bluetooth does not flap; short enough that an actually-pulled cable gives the
         * head unit's own hands-free back while the driver is still in the car.
         */
        const val MEDIA_AUDIO_RESTORE_GRACE_MILLIS = 25_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val SETTINGS_SWIPE_DISTANCE_DP = 72
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
        // The three-finger overlay's colours now come from SettingsTheme.OVERLAY; the retired MENU_*
        // palette (cool blue-black with a green accent) is gone so nothing can drift back to it.
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    /** The view areas this session declared, or null for the whole screen as one area. */
    val viewAreas: CarPlayViewAreas? = null,
)

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        // The detach request is NOT made here: the action below runs shutdown(), whose teardown
        // executor already asks the iPhone for it before closing (CarPlayHostActivity.shutdown).
        // Keeping this method synchronous matters: it is called from the UI thread and the tests
        // assert the follow-up activity start right after it returns.
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
        display = null
    }
}











