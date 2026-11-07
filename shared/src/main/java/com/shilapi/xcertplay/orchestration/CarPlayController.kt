package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.diagnostics.DiagnosticLog
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.publishSessionBindAddress
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoPlaybackDelivery
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.media.GovernorController
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayListenerService
import com.shilapi.xcertplay.network.LegacySoftApControl
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessInterfaceDiagnostics
import com.shilapi.xcertplay.network.WirelessReceiveDiagnostics
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupDiagnostics
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LegacyNcmDescriptors
import com.shilapi.xcertplay.transport.LegacyUsbNative
import com.shilapi.xcertplay.transport.NcmLegacyMode
import com.shilapi.xcertplay.transport.NcmLegacyPolicy
import com.shilapi.xcertplay.transport.UserspaceNcmTransport
import com.shilapi.xcertplay.transport.LinkLocalAddress
import com.shilapi.xcertplay.transport.UserSpaceNcmLink
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.wiredTransportDecision
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.shilapi.xcertplay.compat.systemServiceOrNull
import com.shilapi.xcertplay.compat.noBackupFilesDirCompat
import com.shilapi.xcertplay.compat.WorkerThreads

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val usbManager: UsbManager? = context.systemServiceOrNull(UsbManager::class.java)

    /**
     * 会话期间把 CPU 顶到高性能档（docs/_digest/79 的 A6）。
     *
     * 固件已经授权：`init.freescale.rc` 把 `scaling_governor`/`scaling_min_freq` 设成
     * `chown root system` + `chmod 0664`，而 app 属 `system` 组 —— 所以这件事**不需要 root**，
     * 是本项目里唯一能持久化、能交付的车机侧性能手段（见 doc 78 §3.1）。
     * `debug.diplay.cpu_governor=off` 可关闭。
     */
    private val governorController: GovernorController? =
        if (GovernorController.enabled()) {
            GovernorController(report = ::debugLog)
        } else {
            null
        }

    init {
        // The file log starts here rather than from an Application subclass, which this app does not have.
        // With USB debugging off there is no `adb logcat` on this car, so this file is the only record a
        // normal-use session leaves behind (docs/_digest/59). It is one file per app run and it never throws.
        DiagnosticLog.start(appContext, "transport=${config.transport}")
    }
    // Ported from upstream d379f036: on older Androids BluetoothManager.getAdapter() can answer null even
    // though a radio exists, so the deprecated static is the fallback (relevant on this API 19 car).
    private val bluetoothAdapter =
        appContext.systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    private val iphoneHost by lazy {
        IphoneUsbHost(
            appContext,
            requireUsbManager(),
            if (config.iphoneDevices.isNotEmpty()) {
                IphoneUsbMatcher(config.iphoneDevices)
            } else {
                IphoneUsbMatcher.appleVendor()
            },
        )
    }
    // Named threads (docs/_digest/69 P0-5): these showed up as `pool-N-thread-1`, which made
    // `taskset`/systrace attribution and ANR triage guesswork. The media sink already names its
    // own workers ("carplay-video-recovery"), so this follows the same shape.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(namedThread("carplay-usb"))
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThread("carplay-touch"))
    private val touchGate = com.shilapi.xcertplay.airplay.TouchDispatchGate()
    /**
     * Slow, user-initiated HID commands (Siri / telephony / media) that used to share
     * [touchExecutor] (docs/_digest/69 P0-5). They are not on the touch path, and each one sends two
     * reports under the session's event write lock, so queueing them behind 60-120 touch states per
     * second added a whole drag's worth of delay to a button press.
     */
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThread("carplay-command"))
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThread("carplay-iap2-tunnel"))
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0

    /** Set once this controller has done the pre-connect software replug. */
    private var softReplugAttempted = false
    /** The one startup CarPlay-mode reset runs at most once per attempt (docs/_digest/102 P0-3). */
    private var lastReportedStatus: CarPlayStatus? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shilapi.xcertplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        WorkerThreads.create(task, "diplay-first-tcp-timeout").apply { isDaemon = true }
    }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var listenerService: CarPlayListenerService? = null

    /**
     * The userspace NCM link when this wired session runs on lwIP instead of the kernel netdev. It owns the
     * USB bridge, so it is closed with the session (docs/_digest/58 §7).
     */
    @Volatile private var userSpaceLink: UserSpaceNcmLink? = null

    /**
     * The USB bridge behind [userSpaceLink]. The link only pumps frames; this object owns the
     * connection and the claimed NCM interfaces, so it has to be closed explicitly on teardown.
     * It never was, which is why a configuration switch on the same device answered EBUSY (errno 16,
     * tablet bench 2026-10-19).
     */
    @Volatile private var userSpaceBridge: NcmUsbBridge? = null

    /**
     * The iPhone this wired session opened its data paths on, kept only so a teardown can make the
     * phone see a detach (see [requestWiredReenumeration]). Null while no wired session owns one.
     */
    @Volatile private var wiredDevice: UsbDevice? = null
    @Volatile private var listenerBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var detachCloseable: Closeable? = null

    /**
     * Set when this controller itself asks the iPhone to leave the bus during teardown, so the detach that
     * follows is not mistaken for the cable being pulled (WiredDetachPolicy).
     */
    @Volatile private var teardownDetachExpected = false
    private var listenerLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            listenerService = (binder as CarPlayListenerService.LocalBinder).service
            listenerLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            listenerService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    /**
     * 会话开始/结束时切换 CPU 档位。**任何失败都不能影响会话**，所以整个调用被包起来：
     * 这是一项"有则更好"的优化，不是正确性的一部分。
     */
    private fun applyGovernorForSession(active: Boolean) {
        val controller = governorController ?: return
        runCatching {
            if (active) controller.enterSession() else controller.exitSession()
        }.onFailure {
            debugLog("CPU governor switch failed active=$active ${it.javaClass.simpleName}")
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            val replacement = activeSession !== session
            if (replacement) {
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            touchGate.reset()
            if (replacement) restoreDashboardContent(session)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            // 会话已经立起来了，这时才值得占用高频（docs/_digest/79 A6）。放在调试日志之后、
            // UI 回调之前：它只写两次 sysfs，但绝不能让 UI 回调的异常把它顶掉。
            applyGovernorForSession(true)
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                touchGate.reset()
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                // 会话结束就把 CPU 交还给原厂策略：这本是我们借来的高频。
                applyGovernorForSession(false)
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            try {
                uiListener?.onDeviceInfo(session, info)
            } catch (error: Exception) {
                debugLog("UI device info callback failed ${error.javaClass.name}: ${error.message}")
            }
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            // The session already wrote this line to logcat (AirPlaySession.debugLog); this callback
            // only feeds the UI and the on-device diagnostic buffer. Calling debugLog() here logged
            // the same message a second time under the same tag and millisecond, which duplicated
            // every session line in the field logs (docs/_digest/43).
            try {
                uiListener?.onDebugLog(message)
            } catch (error: Exception) {
                Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
            }
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Nonblocking identity for wheel controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && listenerService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        if (!hasRequiredUsbService()) return
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
            detachCloseable = iphoneHost.registerDetachReceiver(::onUsbDetached)
        }
        startMfi()
    }

    /** Reopens the MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        // The user is explicitly asking for the prompt back after refusing it once.
        iphoneHost.resetPermissionGate()
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        if (activeSession == null) return false
        // Touch reports are absolute snapshots: while the encrypted event channel stalls, replaying
        // every queued position makes the pointer trail the finger, so only the newest move is kept
        // (finger down/up transitions are always delivered in order). The accepted time travels with
        // the state so the latency report includes the wait for the writer.
        val state = com.shilapi.xcertplay.airplay.TouchState(contacts)
        if (!touchGate.enqueue(state)) return true
        return try {
            touchExecutor.execute(::drainTouch)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun drainTouch() {
        while (true) {
            val state = touchGate.next() ?: return
            val session = activeSession
            if (session == null) {
                // reset() 只清队列，workerActive 仍为 true；下一次会话的 enqueue 会以为还有
                // worker 在跑而不再调度，触摸从此永久失效。这里必须把 worker 自己也释放掉。
                touchGate.abort()
                return
            }
            try {
                session.sendTouch(state.contacts, state.enqueuedNs)
            } catch (_: Exception) {
                // 任何一次发送异常都不能把 worker 标志留在 true。
                touchGate.abort()
                return
            }
        }
    }

    /** Moves CarPlay on the main screen to its declared view area [index], for example another dock edge. */
    fun showViewArea(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    debugLog("View area $index sent=${session.showViewArea(index)}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shilapi.xcertplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** A visible physical map and its session/stream generation; null for a paused/virtual/turn-card route. */
    fun dashboardMapRoute(): Any? {
        val session = activeSession ?: return null
        val content = session.clusterContentRoute() ?: return null
        val stream = content.first
        val route = session to stream
        val visibility = clusterUiVisibility
        val shown = visibility?.takeIf { it.first == route }?.second ?: true
        if (!DashboardMapEligibility.permits(content.second,
                dashboardMapOutputVisible, stream, shown, closed)) return null
        return Triple(session, stream, dashboardMapEpoch.get() to content.third)
    }

    /** Whether the wheel can currently control the visible dashboard map. */
    fun dashboardMapStreaming(): Boolean = dashboardMapRoute() != null

    /** One zoom step for the dashboard map, as the car's own zoom controls send it. */
    fun zoomDashboardMap(zoomIn: Boolean): Boolean {
        val route = dashboardMapRoute() ?: return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session && dashboardMapRoute() == route) session.changeMapZoomLevel(zoomIn)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            commandExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone (telephony Hook Switch), as the wheel's call button does. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one (telephony Drop). */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    private fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            commandExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            commandExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Makes the iPhone drop its CarPlay session by asking it to re-enumerate.
     *
     * The vendor request is the software equivalent of unplugging the cable. Without it the phone keeps
     * believing the accessory is still connected after our teardown, refuses the next CarPlayStartSession,
     * and only a physical replug (or deleting the car in iOS settings) clears it - measured on the tablet
     * bench on 2026-10-19: iAP2 ran, 0x4301 was sent, and the phone sent zero NCM frames.
     *
     * Blocking by design: the callers run on the teardown executor and must finish this before [close]
     * tears the connection down. Returns false when there is no wired device or the request failed.
     */
    fun requestWiredReenumeration(): Boolean {
        val device = wiredDevice
        if (device == null) {
            debugLog("wired teardown: no wired device to re-enumerate")
            return false
        }
        // Deliberately NOT cleared here: close() reads the same field to switch the device off the
        // CarPlay configuration, and it runs right after this call on the teardown thread.
        teardownDetachExpected = true
        debugLog("wired teardown: asking the iPhone to re-enumerate")
        return try {
            when (val result = iphoneHost.requestCarPlayReenumeration(device)) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    debugLog("wired teardown: iPhone asked to re-enumerate")
                    // The vendor request is the software replug. Wait for the phone to actually leave
                    // the bus before the caller tears the process down, or the next connect finds the
                    // old node and the phone keeps its CarPlay session (doc 117 §4quater).
                    awaitDeviceReenumeration(device)
                    true
                }
                is IphoneUsbHost.TransitionResult.Failed -> {
                    debugLog("wired teardown: iPhone re-enumeration failed: ${result.error.message}")
                    false
                }
            }
        } catch (error: Throwable) {
            debugLog(
                "wired teardown: iPhone re-enumeration failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            false
        }
    }

    /**
     * Releases everything this session holds on the iPhone, then moves the phone off the CarPlay
     * configuration - synchronously, because every caller ends by killing the process.
     *
     * [close] cannot be trusted with this: its heavy teardown runs on a worker thread that the
     * process exit usually beats, which is why the configuration switch never executed on the tablet
     * (no `Removed device` in logcat after any disconnect, bench 2026-10-19). Closing the link first
     * also matters: a configuration switch is EBUSY while any interface is still claimed.
     */
    fun detachWiredForReconnect(): Boolean {
        val device = wiredDevice ?: return false
        teardownDetachExpected = true
        val link = userSpaceLink
        userSpaceLink = null
        runCatching { link?.close() }
            .onFailure { debugLog("wired detach: link close failed: ${it.message}") }
        // The link does not own the bridge: without this the NCM interfaces stay claimed and the
        // configuration switch answers EBUSY.
        val bridge = userSpaceBridge
        userSpaceBridge = null
        runCatching { bridge?.close() }
            .onFailure { debugLog("wired detach: NCM bridge close failed: ${it.message}") }
        runCatching { csm?.close() }
            .onFailure { debugLog("wired detach: CSM close failed: ${it.message}") }
        csm = null
        // The USBMUX interface lives on the mux's own connection, not on the CSM stream: without this
        // the configuration switch answers EBUSY (errno 16, measured on the tablet 2026-10-19).
        val muxHost = mux
        mux = null
        runCatching { muxHost?.close() }
            .onFailure { debugLog("wired detach: USBMUX close failed: ${it.message}") }
        val reset = runCatching { iphoneHost.resetCarPlayConfiguration(device) }
            .onFailure { debugLog("wired detach: configuration reset failed: ${it.message}") }
            .getOrDefault(false)
        // Logcat on purpose: the app log buffers and loses its tail when the process dies right after.
        Log.i("xcertplay-usb", "wired detach: configuration reset ok=$reset")
        if (reset) awaitDeviceReenumeration(device)
        return reset
    }

    /**
     * Waits until the phone has actually left the bus after the teardown's re-enumeration request
     * (or the configuration switch when that one succeeded).
     *
     * The request alone is not the software replug: the phone has to be seen going away before the
     * caller tears the process down, or the next connect finds the same node and the phone keeps its
     * CarPlay session - 0x4301 is sent and nothing comes back (docs/_digest/117 §4quater).
     */
    private fun awaitDeviceReenumeration(device: UsbDevice) {
        val deadline = System.nanoTime() + RESET_REENUMERATION_WAIT_MILLIS * 1_000_000L
        val original = device.deviceName
        var waited = 0L
        while (System.nanoTime() < deadline) {
            val devices = runCatching { requireUsbManager().deviceList }.getOrDefault(emptyMap())
            if (!devices.containsKey(original)) {
                debugLog("wired teardown: phone re-enumerated waitedMs=$waited")
                return
            }
            Thread.sleep(RESET_REENUMERATION_POLL_MILLIS)
            waited += RESET_REENUMERATION_POLL_MILLIS
        }
        debugLog(
            "wired teardown: phone still on $original after ${RESET_REENUMERATION_WAIT_MILLIS}ms " +
                "(the vendor usbmuxd may still be holding it)",
        )
    }

    override fun close() {
        // 会话若还活着就被拆掉（force-stop、切到别的 App 后被回收、连接失败回滚），
        // onSessionEnded 不一定会走到 —— 这里必须把借来的高频还回去，否则 CPU 会一直停在性能档。
        // 已还原过就是空操作（exitSession 有 applied 守卫）。
        applyGovernorForSession(false)
        val detachDevice = wiredDevice
        synchronized(this) {
            if (closed) return
            closed = true
            wiredDevice = null
            dashboardMapOutputVisible = false
        }
        firstTcpWatchdog?.terminate()
        startupTimer.shutdownNow()
        (hotspot as? ManualHotspotManager)?.close()
        // The userspace link owns its USB claim and its lwIP netif, so it goes with the session (null first,
        // because closing it lets the endpoint drains fail, and those callbacks may re-enter here).
        val link = userSpaceLink
        userSpaceLink = null
        runCatching { link?.close() }
        // The bridge owns the connection and the NCM interfaces; the link only pumps frames.
        val linkBridge = userSpaceBridge
        userSpaceBridge = null
        runCatching { linkBridge?.close() }
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        commandExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = listenerService
        unbindListenerService()
        WorkerThreads.create(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    // Only now is every interface we held released. Moving the phone off the CarPlay
                    // configuration is what makes the next connect re-select it instead of reusing it;
                    // reusing it is exactly the case where the phone keeps the old session and never
                    // starts the media plane. A switch before the USBMUX close answers EBUSY.
                    if (detachDevice != null && config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("CarPlay configuration reset") {
                            iphoneHost.resetCarPlayConfiguration(detachDevice)
                        }
                    }
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("airplay-listener") { service?.detach() }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessRuntimeIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    // A cluster stream DiPlay has not paused yet starts with the map drawn.
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The wheel zoom follows what the dashboard shows now, so zoom mode starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
        }
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        val stream = session.clusterStream.takeIf { it > 0 } ?: return@synchronized
        if (clusterUiStream != session to stream) {
            clusterUiStream = session to stream
            clusterUiShown = true
            clusterUiVisibility = (session to stream) to true
        }
        if (shown == clusterUiShown && (!shown || session.clusterUrl() != null)) return@synchronized
        if (session.setClusterUiShown(shown)) {
            clusterUiShown = shown
            clusterUiVisibility = (session to stream) to shown
            dashboardMapEpoch.incrementAndGet()
            debugLog("Cluster map: ${if (shown) "showUI, the cluster shows the map" else "stopUI, the cluster hides the map"}")
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        com.shilapi.xcertplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    private fun onArtworkTransfer(transfer: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        if (!hasRequiredUsbService()) return
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val offlineDirectory = java.io.File(appContext.noBackupFilesDirCompat(), LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        executor.execute { runWireless(generation) }
    }

    private fun restartWireless() {
        val generation = synchronized(wirelessResourceLock) {
            firstTcpWatchdog?.terminate()
            wirelessGeneration.incrementAndGet()
        }
        WorkerThreads.create(
            {
                closeWirelessStack(generation = generation)
                startWireless(generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack(generation = generation)
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val listenerIdentity = AirPlayListenerIdentity(generation)
            val watchdog = FirstTcpWatchdog(
                listener = listenerIdentity,
                schedule = { delay, action ->
                    val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    val cancel: () -> Unit = { future.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (!closed && generation == wirelessGeneration.get()) {
                        fail(WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                            "No AirPlay TCP after CarPlay StartSession"), generation)
                        WorkerThreads.create({ closeWirelessStack(generation = generation) }, "diplay-startup-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                },
                log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
            )
            firstTcpWatchdog = watchdog
            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            // Wireless CarPlay begins over Bluetooth: a radio that is off is found before the Wi-Fi is changed.
            // Ported from upstream 5fbd7171. Our tree has no BYD Bluetooth-suspend feature, so a radio that
            // DiPlay paused itself cannot be the case here and `pausedByDiPlay` is always false.
            BluetoothRadioCheck.blocker(
                adapterPresent = bluetoothAdapter != null,
                enabled = bluetoothAdapter?.isEnabled == true,
                pausedByDiPlay = false,
            )?.let { throw IOException(it) }
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                return
            }
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val adapter = bluetoothAdapter
                ?: throw IOException(BluetoothRadioCheck.NO_ADAPTER)
            if (!adapter.isEnabled && !requestWirelessBluetoothOn(adapter)) {
                throw IOException(BluetoothRadioCheck.RADIO_OFF)
            }
            val device = selectWirelessBluetoothDevice(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                    "address=${device.address} localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitListenerService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                startedHotspot?.validateReady()
                when (
                    val result = service.attachWireless(
                        bindAddress = hostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = wirelessSessionListener(generation, watchdog),
                        listenerIdentity = watchdog.listener,
                        media = media,
                        additionalBindAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
                    )
                ) {
                    CarPlayListenerService.AttachResult.Started -> Unit
                    CarPlayListenerService.AttachResult.AlreadyStarted ->
                        throw IOException("Wireless AirPlay transport is already attached")
                    is CarPlayListenerService.AttachResult.Failed ->
                        throw IOException(result.message)
                }
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
                additionalAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress },
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                bonjour = bonjourClient
                startedHotspot?.validateReady()
                bonjourClient.start()
            }
            startedBonjour = bonjourClient
            diagnostics.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                return
            }

            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM connecting address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            val socket = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
            }
            logBluetoothConnectionSnapshot(device, "before-connect")
            val bluetoothStarted = System.nanoTime()
            try {
                connectBluetoothSocket(socket, device.address)
                connectionDiagnostic("Bluetooth connect completed elapsedMs=${elapsedMillis(bluetoothStarted)}")
            } catch (error: Throwable) {
                connectionDiagnostic(
                    "Bluetooth connect failed elapsedMs=${elapsedMillis(bluetoothStarted)} " +
                        "failureClass=${diagnosticFailureClass(error)}",
                )
                logBluetoothConnectionSnapshot(device, "after-failure")
                throw error
            }
            debugLog("wireless RFCOMM connected address=${device.address}")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val stream = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
            }
            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) {
                    channel.close()
                    return
                }
                csm = channel
            }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                wirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                wirelessIdentification,
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessRuntimeIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            debugLog(
                "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
            )
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog(
                "wireless Bluetooth iAP2 bootstrap starting " +
                    "location=false vehicleStatus=false",
            )
            startedHotspot?.validateReady()
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = bootstrapIdentification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                beforeStartSession = { startedHotspot?.validateReady() },
                onStartSessionSent = { watchdog.startSessionSent(it.sentAtNanos) },
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    diagnostics.controlProgress(message)
                    debugLog(message)
                },
            )
            if (isStaleWirelessRun(generation)) {
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    if (!wirelessActiveReported.get()) {
                        onStatus(CarPlayStatus.ControlEnded)
                    }
            }
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                fail(error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessRuntimeIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog(
            "wireless iAP2 runtime tunnel control starting " +
                "location=${identification.locationInformationEnabled} " +
                "vehicleStatus=${identification.vehicleStatusEnabled}",
        )
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            private var reportedFrameSession: AirPlaySession? = null

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (isStaleWirelessRun(generation)) return
                if (watchdog.accepted(event)) wirelessDiagnostics?.connectionAccepted()
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                watchdog.terminate()
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || !watchdog.sessionEstablished()) return
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                // Keep wirelessActiveReported false until maybeCompleteWirelessHandoff() closes
                // the Bluetooth bootstrap after the tunneled iAP2 channel is ready.
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                if (!watchdog.sessionEstablished()) return
                wirelessConnectionProof.rendered(generation, session)
                val firstFrame = synchronized(this) {
                    if (reportedFrameSession === session) false else {
                        reportedFrameSession = session
                        true
                    }
                }
                if (firstFrame) uiListener?.onVideoFrameRendered(session)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(message)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        wirelessConnectionProof.authenticated(generation)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        WorkerThreads.create(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@create
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                WorkerThreads.create(
                    { handleWirelessHandoffTimeout(generation) },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun handleWirelessHandoffTimeout(generation: Int) = synchronized(wirelessResourceLock) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get() ||
            !wirelessHandoffRequested.get() || wirelessActiveReported.get() || wirelessTunnelReady.get()) {
            return@synchronized
        }
        // Session establishment alone also occurs on black-screen failures. Only a rendered
        // frame proves that projection is usable when the optional tunneled control is absent.
        if (activeSession != null && wirelessConnectionProof.hasRenderedFrame(generation)) {
            if (!wirelessActiveReported.compareAndSet(false, true)) return@synchronized
            debugLog("wireless handoff fallback after rendered video; tunnel iAP2 unavailable; " +
                "preserving AirPlay and closing Bluetooth bootstrap")
            closeBluetoothBootstrapTransport()
            onStatus(CarPlayStatus.WirelessActiveFallback, generation)
        } else {
            closeWirelessStack(generation = generation)
            fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), generation)
        }
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)} " +
                    // deviceName is /dev/bus/usb/<bus>/<address>: the only port signal API 19 exposes, and
                    // the line that says afterwards whether this was the car's own port or the debug one.
                    "device=${device.deviceName} bus=${device.deviceName.substringBeforeLast('/').substringAfterLast('/')} " +
                    "interfaces=${device.interfaceCount}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        // CS11: API19 无配置枚举——检查活动配置是否已含 USBMUX 接口
                        val configurationReady =
                            IphoneCarPlayConfiguration.activeConfigCarPlayReady(result.device)
                        connectionDiagnostic(
                            "USB configuration ready=$configurationReady " +
                                "configurationId=none " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                "action=${when {
                                    configurationReady -> "reuse-descriptors"
                                    reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS -> "request-transition"
                                    else -> "reject-missing-configuration"
                                }}",
                        )
                        if (configurationReady) {
                            softReplugThenOpenDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                            beginReenumeration(result.device)
                        } else {
                            fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (requireUsbManager().hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    /**
     * The USB detach broadcast (README gap "USB 拔出广播"). Logged for every Apple device with a stable
     * `STEP usb/detached` line; it ends the session only when WiredDetachPolicy says the node in use is gone
     * during an open session. The failure takes the same path as a failed USB read - the host's existing
     * retry then waits for the phone to be plugged in again - it just no longer depends on a blocked
     * requestWait() waking up.
     */
    private fun onUsbDetached(device: UsbDevice) {
        val decision = WiredDetachPolicy.decide(
            detachedDeviceName = device.deviceName,
            wiredDeviceName = wiredDevice?.deviceName,
            sessionPhase = phase == Phase.DATAPATHS || phase == Phase.CONTROL,
            teardownExpected = teardownDetachExpected,
            closed = closed,
        )
        if (device.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID || decision == WiredDetachPolicy.Decision.END_SESSION) {
            debugLog("STEP usb/detached: device=${device.deviceName} phase=$phase decision=$decision")
        }
        if (decision == WiredDetachPolicy.Decision.END_SESSION) {
            fail(IphoneUsbException.DeviceUnavailable("iPhone USB cable unplugged (USB_DEVICE_DETACHED)"))
        }
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    /**
     * Runs the software replug once per controller, then opens the data paths.
     *
     * The phone keeps its CarPlay session across an app restart, and only a real USB re-enumeration
     * clears it. The switch blocks on claim/release and enumeration, so it stays on the USB worker;
     * the connect is posted back after it, never overlapping it (docs/_digest/117 §4quater).
     */
    private fun softReplugThenOpenDataPaths(device: UsbDevice) {
        if (!softReplugBeforeConnect() || softReplugAttempted) {
            openDataPaths(device)
            return
        }
        softReplugAttempted = true
        executor.execute {
            val target = softReplugWithConfigurationRoundTrip(device) ?: device
            mainHandler.post { if (!closed) openDataPaths(target) }
        }
    }

    /**
     * Switches the phone off its CarPlay configuration and back, waiting for a re-enumeration each leg.
     *
     * Force-claiming every interface makes SET_CONFIGURATION possible (it answers EBUSY while a driver
     * holds one), so this is the software equivalent of unplugging and replugging the cable - the only
     * thing that clears the phone's old CarPlay session. Returns the device that came back.
     */
    private fun softReplugWithConfigurationRoundTrip(device: UsbDevice): UsbDevice? {
        val manager = requireUsbManager()
        val carPlayId: Int
        val otherId: Int
        val first = manager.openDevice(device) ?: run {
            debugLog("soft replug: could not open the iPhone")
            return null
        }
        try {
            val raw = first.rawDescriptors ?: run {
                debugLog("soft replug: no raw descriptors")
                return null
            }
            carPlayId = carPlayConfigurationId(raw) ?: run {
                debugLog("soft replug: descriptors carry no USBMUX+NCM configuration")
                return null
            }
            otherId = otherConfigurationId(raw, carPlayId) ?: run {
                debugLog("soft replug: no other NCM configuration to switch to")
                return null
            }
            debugLog("soft replug: switching ${device.deviceName} to $otherId, then back to $carPlayId")
            if (!switchConfiguration(first, device, otherId)) {
                debugLog("soft replug: switch to $otherId failed")
                return null
            }
        } finally {
            runCatching { first.close() }
        }
        val afterOther = awaitReenumeratedDevice(SOFT_REPLUG_SETTLE_MILLIS) ?: run {
            debugLog("soft replug: the phone did not come back on $otherId")
            return null
        }
        val second = manager.openDevice(afterOther) ?: run {
            debugLog("soft replug: could not reopen the phone")
            return null
        }
        try {
            if (!switchConfiguration(second, afterOther, carPlayId)) {
                debugLog("soft replug: switch back to $carPlayId failed")
                return null
            }
        } finally {
            runCatching { second.close() }
        }
        val afterCarPlay = awaitReenumeratedDevice(SOFT_REPLUG_SETTLE_MILLIS)
        debugLog("soft replug: done device=${afterCarPlay?.deviceName ?: "not re-enumerated"}")
        return afterCarPlay ?: afterOther
    }

    /** A configuration other than the CarPlay one that still carries NCM (id 5 on this car). */
    private fun otherConfigurationId(raw: ByteArray, carPlayId: Int): Int? =
        LegacyNcmDescriptors.parse(raw)
            .firstOrNull { it.id != carPlayId && it.hasNcm }
            ?.id

    /** Force-claims the interfaces, then switches the configuration, retrying while a driver rebinds. */
    private fun switchConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        id: Int,
    ): Boolean {
        for (attempt in 0 until CONFIGURATION_SWITCH_ATTEMPTS) {
            val claimed = claimAllForConfigurationSwitch(connection, device)
            releaseClaimed(connection, claimed)
            Thread.sleep(CONFIGURATION_SWITCH_SETTLE_MILLIS)
            val errno = LegacyUsbNative.setConfiguration(connection.fileDescriptor, id)
            debugLog(
                "soft replug: set id=$id attempt=$attempt claimed=${claimed.size} " +
                    "ioctl=$errno (${LegacyUsbNative.describe(errno)})",
            )
            if (errno == 0) return true
        }
        return false
    }

    /**
     * Waits a bounded time for a configuration switch to settle, then returns the Apple device listed now.
     *
     * A re-enumeration is not reliably observable from here: the online usbmuxd switches the phone
     * straight back to the CarPlay configuration, and the node often keeps its deviceName. Looking for
     * "the old node left" therefore reports a false negative, so the wait is time-boxed and the caller
     * continues with whatever the manager lists afterwards.
     */
    private fun awaitReenumeratedDevice(settleMillis: Long): UsbDevice? {
        Thread.sleep(settleMillis)
        return runCatching {
            requireUsbManager().deviceList.values.firstOrNull {
                it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
            }
        }.getOrNull()
    }

    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        wiredDevice = device
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    var earlyMux: Iap2UsbMuxHost? = null
                    try {
                        // Experiment E-1 (docs/_digest/117): the car's usbmuxd opens the USBMUX session the
                        // instant the iPhone attaches, and the NCM function only returns complete parameters
                        // while a mux session already exists. Opening ours before the NCM negotiation
                        // reproduces that condition; `debug.diplay.ncm_after_mux=0` restores the old order.
                        if (openMuxBeforeNcm()) {
                            earlyMux = Iap2UsbMuxHost.open(result.session, onDiagnostic = ::connectionDiagnostic)
                            debugLog("wired USBMUX host opened before NCM (ncm_after_mux default)")
                        }
                        // Both older wired data paths are gone from this fork: the kernel cdc_ncm netdev
                        // needed root+adb to fire (docs/_digest/49) and the VpnService/TUN transport was
                        // deleted in 6620ad61 because the CS11 kernel has no TUN. The userspace lwIP
                        // bridge is therefore the only transport that can carry media.
                        val mode = NcmLegacyPolicy.current()
                        val decision = wiredTransportDecision(
                            mode,
                            UserspaceNcmTransport.readiness(LegacyUsbNative.available),
                        )
                        // FORCE with a missing stack or library is still worth a probe: it reports what
                        // the descriptors and the negotiation actually did.
                        val userspaceLink = if (decision.userspace) {
                            openUserspaceNcm(device)
                        } else {
                            null
                        }
                        if (userspaceLink == null && mode == NcmLegacyMode.FORCE && !decision.userspace) {
                            probeUserspaceNcm(device)
                        }
                        if (userspaceLink == null) {
                            debugLog(decision.reason)
                            // A short GET_NTB_PARAMETERS answer is what a phone left on its non-CarPlay
                            // configuration returns. Ask it to re-enumerate into the CarPlay one and try
                            // again; the vendor request is the software equivalent of a replug (117 §4ter).
                            if (decision.userspace && ncmConfigurationRetry() &&
                                reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS
                            ) {
                                debugLog("userspace NCM failed; re-enumerating into the CarPlay configuration")
                                runCatching { earlyMux?.close() }
                                result.session.close()
                                beginReenumeration(device)
                                return@openIap2UsbSessionAsync
                            }
                        }
                        runStack(result.session, earlyMux)
                    } catch (error: Throwable) {
                        runCatching { earlyMux?.close() }
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }


    /**
     * Force-claims every interface so the kernel drivers let go of the device.
     *
     * Without this the switch answers EBUSY (errno 16) even with the vendor usbmuxd stopped: on this
     * ROM the kernel cdc_ncm keeps the NCM function bound. The caller releases exactly what was claimed.
     */
    private fun claimAllForConfigurationSwitch(
        connection: UsbDeviceConnection,
        device: UsbDevice,
    ): List<UsbInterface> {
        val claimed = ArrayList<UsbInterface>()
        for (index in 0 until device.interfaceCount) {
            val usbInterface = runCatching { device.getInterface(index) }.getOrNull() ?: continue
            if (claimed.contains(usbInterface)) continue
            if (runCatching { connection.claimInterface(usbInterface, true) }.getOrDefault(false)) {
                claimed.add(usbInterface)
            }
        }
        return claimed
    }

    private fun releaseClaimed(connection: UsbDeviceConnection, claimed: List<UsbInterface>) {
        for (usbInterface in claimed) runCatching { connection.releaseInterface(usbInterface) }
    }

    /** The configuration that carries USBMUX and NCM, preferring Apple Ethernet. */
    private fun carPlayConfigurationId(raw: ByteArray): Int? {
        val candidates = LegacyNcmDescriptors.parse(raw).filter { it.hasUsbMux && it.hasNcm }
        return (candidates.firstOrNull { configuration ->
            configuration.interfaces.any { it.isAppleEthernet }
        } ?: candidates.firstOrNull())?.id
    }


    /** `debug.diplay.soft_replug=0` disables the pre-connect software replug (on by default). */
    private fun softReplugBeforeConnect(): Boolean =
        systemProperty("debug.diplay.soft_replug")?.trim() != "0"

    /** `debug.diplay.ncm_config_retry=0` disables the re-enumeration after a short NCM answer. */
    private fun ncmConfigurationRetry(): Boolean =
        systemProperty("debug.diplay.ncm_config_retry")?.trim() != "0"

    /**
     * Experiment E-1's switch: `debug.diplay.ncm_after_mux=0` restores the old order (NCM first).
     *
     * Default is the new order because the car's usbmuxd opens a mux session the moment the iPhone
     * attaches, and NCM only answers with complete parameters while one exists (docs/_digest/117).
     */
    private fun openMuxBeforeNcm(): Boolean =
        systemProperty("debug.diplay.ncm_after_mux")?.trim() != "0"

    /** `debug.diplay.*` reads; API 19 exposes no public SystemProperties. */
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
    }.getOrNull()

    /**
     * Opens the API-19 userspace NCM bridge and starts lwIP on it (docs/_digest/58 §7).
     *
     * Returns null - with the reason logged - when the bridge or the stack cannot come up, so the caller
     * keeps the kernel netdev. The interfaces stay claimed by us for the whole session, which is why this is
     * not [probeUserspaceNcm]: nothing is released and re-opened.
     */
    private fun openUserspaceNcm(device: UsbDevice): UserSpaceNcmLink? {
        val connection = requireUsbManager().openDevice(device)
        if (connection == null) {
            debugLog("userspace NCM: the iPhone connection could not be opened")
            return null
        }
        var bridge: NcmUsbBridge? = null
        try {
            val raw = connection.rawDescriptors
                ?: throw IphoneUsbException.Protocol("the iPhone exposes no raw USB descriptors")
            // The reference find() (LegacyUsbConfiguration.java:951-985) collects every configuration that
            // carries USBMUX and NCM and then prefers one that also exposes an Apple Ethernet interface.
            // This used singleOrNull, which answers null as soon as the phone offers more than one such
            // configuration - measured on the car, where the iPhone reports six configurations - and the
            // error it threw then said "no configuration", which pointed the diagnosis the wrong way.
            val candidates = LegacyNcmDescriptors.parse(raw).filter { it.hasUsbMux && it.hasNcm }
            val chosen = candidates.firstOrNull { configuration ->
                configuration.interfaces.any { it.isAppleEthernet }
            } ?: candidates.firstOrNull()
            debugLog(
                "NCM configuration candidates=${candidates.size} ids=${candidates.map { it.id }} " +
                    "chosen=${chosen?.id} appleEthernet=${chosen?.interfaces?.count { it.isAppleEthernet }}",
            )
            val configurationId = chosen?.id
                ?: throw IphoneUsbException.Protocol(
                    "no iPhone USB configuration carries USBMUX and NCM together " +
                        "(parsed=${LegacyNcmDescriptors.parse(raw).size} configurations)",
                )
            bridge = NcmUsbBridge.openLegacy(connection, device, raw, configurationId, ::debugLog)
            val link = UserSpaceNcmLink(bridge, onDiagnostic = ::debugLog)
            val started = link.start()
            if (started != 0) {
                debugLog("userspace NCM: lwIP refused the netif (result=$started)")
                // The pump never started, so UserSpaceNcmLink.close() cannot release the USB claim;
                // close the bridge explicitly or the next attempt sees EBUSY.
                link.close()
                runCatching { bridge?.close() }
                return null
            }
            link.startPump()
            userSpaceLink = link
            userSpaceBridge = bridge
            debugLog("userspace NCM bridge ready configuration=$configurationId; lwIP carries the wired link")
            return link
        } catch (error: Throwable) {
            debugLog("userspace NCM bring-up failed: ${error.javaClass.simpleName}: ${error.message}")
            if (bridge != null) runCatching { bridge.close() } else runCatching { connection.close() }
            return null
        }
    }

    /**
     * Binds the AirPlay listener on the userspace stack rather than the kernel one.
     *
     * There is no TUN on this ROM, so nothing is routed: this is the same listener-only attach the wireless
     * path uses, pointed at the lwIP network. The engine is built by the Activity, so its network is set
     * here as well - both it and the service have to hand the same network to the channels they create
     * (docs/_digest/58 §6 step 7).
     */
    private fun attachUserspace(link: UserSpaceNcmLink): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitListenerService() ?: run {
            debugLog("userspace NCM: VPN service bind failed")
            return false
        }
        (media as? CarPlayMediaEngine)?.network = link
        service.network = link
        // serverBindAddress() finds the kernel netdev's scoped fe80::2, and there is no netdev on this path:
        // the NCM interfaces belong to us. lwIP binds raw IPv6 bytes and has no scope concept, so the
        // address the phone was told is the right one to bind, and publishing it keeps every channel - the
        // iAP tunnel included - on the address the control channel was accepted on.
        // The address has to be the one the netif actually carries, and that is the MAC-derived EUI-64: the
        // reference implementation's AirPlay SETUP URL reads rtsp://fe80::9c92:4fff:fe09:bf8b, the EUI-64 of
        // the link MAC, while this path used to pin fe80::2 - an address the phone never dialled.
        val bindAddress = runCatching {
            val derived = LinkLocalAddress.fromMacOrNull(link.hostMac) ?: config.linkLocal.substringBefore('%')
            InetAddress.getByName(derived)
        }.getOrElse { InetAddress.getByName("fe80::2") }
        publishSessionBindAddress(bindAddress)
        val result = try {
            service.attachWireless(
                bindAddress = bindAddress,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayListenerService.AttachResult.Started -> {
                debugLog("userspace NCM AirPlay listener attached on lwIP address=${bindAddress.hostAddress}")
                // Nothing else tells the phone this link carries an AirPlay receiver: the Bonjour side is
                // JmDNS on the platform stack, and this address exists only inside lwIP (docs/_digest/64).
                // The TXT values come from the same AirPlayInfoPlist the /info answer does, so features and
                // model cannot drift apart, and debug.diplay.mdns=off turns the announcement off (ADR-8).
                val advertisedPort = service.boundPort()
                if (advertisedPort != null && com.shilapi.xcertplay.transport.mdns.MdnsSwitch.enabled()) {
                    val info = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(airPlayConfig)
                    val txt = LinkedHashMap<String, String>()
                    listOf("deviceID", "features", "model", "srcvers", "flags", "statusFlags", "pi", "pk", "gid", "psi")
                        .forEach { key ->
                            val value = info[key] ?: return@forEach
                            txt[key.lowercase()] = when (value) {
                                is Long -> "0x" + value.toString(16).uppercase()
                                is Int -> "0x" + value.toString(16).uppercase()
                                else -> value.toString()
                            }
                        }
                    val advertised = com.shilapi.xcertplay.transport.mdns.DnsSdMessages.Service(
                        instance = "DiPlay._airplay._tcp.local",
                        host = "DiPlay.local",
                        port = advertisedPort,
                        address = bindAddress.address,
                        txt = txt,
                    )
                    val announcer = com.shilapi.xcertplay.transport.mdns.MdnsAnnouncer(
                        com.shilapi.xcertplay.transport.mdns.MdnsDatagramSocket.over(
                            com.shilapi.xcertplay.transport.UserSpaceNetworkNative,
                        ),
                        ::debugLog,
                    )
                    runCatching {
                        val message = com.shilapi.xcertplay.transport.mdns.DnsSdMessages.announcement(advertised)
                        // Two announcements 200 ms apart: the old 1 s gap only delayed the iAP2 control loop,
                        // and the phone is not browsing this early anyway. Repeated announcements follow
                        // asynchronously while the phone brings its CarPlay stack up (docs/_digest/99).
                        announcer.announce(message, times = 2, gapMillis = 200L)
                        Thread {
                            runCatching {
                                Thread.sleep(2_500L)
                                announcer.announce(message, times = 1)
                                Thread.sleep(2_500L)
                                announcer.announce(message, times = 1)
                            }.onFailure { error -> debugLog("mdns: repeat announcement stopped: ${error.message}") }
                        }.apply {
                            isDaemon = true
                            name = "mdns-repeat"
                        }.start()
                    }.onFailure { error -> debugLog("mdns: announcement failed: ${error.message}") }

                    // Answering the phone's queries is the other half of DNS-SD: the announcement alone was
                    // measured not to be enough (docs/_digest/64 section 7). Its own daemon thread, because
                    // the loop blocks in recv; it ends when the link does, and never throws into the session.
                    val responder = com.shilapi.xcertplay.transport.mdns.MdnsResponder(
                        com.shilapi.xcertplay.transport.mdns.MdnsQuerySocket.over(
                            com.shilapi.xcertplay.transport.UserSpaceNetworkNative,
                        ),
                        ::debugLog,
                    )
                    Thread {
                        // Stop when this controller closes: otherwise the responder keeps 5353 bound on a dead
                        // link and the next attempt fails to bind (observed 2026-10-20, errno 98).
                        runCatching { responder.run(advertised, stop = { closed }) }
                            .onFailure { error -> debugLog("mdns: query loop failed: ${error.message}") }
                    }.apply {
                        isDaemon = true
                        name = "mdns-queries"
                    }.start()
                }
                true
            }
            CarPlayListenerService.AttachResult.AlreadyStarted -> {
                debugLog("userspace NCM listener was already started")
                true
            }
            is CarPlayListenerService.AttachResult.Failed -> {
                debugLog("userspace NCM listener attach failed: ${result.message}")
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    /**
     * Exercises the API-19 userspace bring-up end to end - descriptor parse, claims, native altsetting,
     * NCM negotiation - and releases it again. It is deliberately **not** the transport yet: AirPlay
     * needs a TCP/IP stack that does not exist on this ROM (no TUN), and holding the NCM interfaces
     * would take them away from the kernel driver that currently works. `debug.diplay.ncm_legacy=force`
     * (or the "userspace only" setting) runs it for one ignition window (docs/_digest/58 §3 stage 1).
     */
    private fun probeUserspaceNcm(device: UsbDevice) {
        val connection = requireUsbManager().openDevice(device)
        if (connection == null) {
            debugLog("userspace NCM probe skipped: the iPhone connection could not be opened")
            return
        }
        var bridge: NcmUsbBridge? = null
        try {
            val raw = connection.rawDescriptors
                ?: throw IphoneUsbException.Protocol("the iPhone exposes no raw USB descriptors")
            // The reference find() (LegacyUsbConfiguration.java:951-985) collects every configuration that
            // carries USBMUX and NCM and then prefers one that also exposes an Apple Ethernet interface.
            // This used singleOrNull, which answers null as soon as the phone offers more than one such
            // configuration - measured on the car, where the iPhone reports six configurations - and the
            // error it threw then said "no configuration", which pointed the diagnosis the wrong way.
            val candidates = LegacyNcmDescriptors.parse(raw).filter { it.hasUsbMux && it.hasNcm }
            val chosen = candidates.firstOrNull { configuration ->
                configuration.interfaces.any { it.isAppleEthernet }
            } ?: candidates.firstOrNull()
            debugLog(
                "NCM configuration candidates=${candidates.size} ids=${candidates.map { it.id }} " +
                    "chosen=${chosen?.id} appleEthernet=${chosen?.interfaces?.count { it.isAppleEthernet }}",
            )
            val configurationId = chosen?.id
                ?: throw IphoneUsbException.Protocol(
                    "no iPhone USB configuration carries USBMUX and NCM together " +
                        "(parsed=${LegacyNcmDescriptors.parse(raw).size} configurations)",
                )
            bridge = NcmUsbBridge.openLegacy(connection, device, raw, configurationId, ::debugLog)
            debugLog("userspace NCM probe ok configuration=$configurationId: parsed, claimed, switched, negotiated")
        } catch (error: Throwable) {
            debugLog("userspace NCM probe failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            if (bridge != null) {
                runCatching { bridge.close() }
            } else {
                runCatching { connection.close() }
            }
        }
    }

    private fun runStack(usbSession: Iap2UsbSession, preopenedMux: Iap2UsbMuxHost? = null) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        // Set once the userspace link has handed the listener to the service: until then the link is ours to
        // release, exactly like `ncmOwnedLocally` above.
        var userspaceAttached = false
        try {
            if (closed) {
                runCatching { preopenedMux?.close() }
                return
            }
            val mux = preopenedMux ?: Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            this.mux = mux
            debugLog(
                if (preopenedMux == null) "wired USBMUX host opened"
                else "wired USBMUX host reused (opened before NCM)",
            )
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            // CS11 perf: the phone syslog relay capture was removed here — its two-minute stream
            // flooded the log pipeline and starved the media threads on the i.MX6.

            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            val tracedCarkit = object : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        carkit.send(data)
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = userSpaceLink?.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            // CS11: the kernel has no TUN driver, so the classic VpnService/NCM bridge cannot
            // attach ("Cannot create interface"). The userspace bridge that owns lwIP is the only
            // transport; the kernel cdc_ncm netdev and its root fire are deleted (docs/_digest/58 §9).
            val userspace = userSpaceLink
            if (userspace != null) {
                if (attachUserspace(userspace)) {
                    userspaceAttached = true
                } else {
                    debugLog("CS11 degraded mode: userspace NCM listener failed; control channel only")
                }
            } else {
                debugLog("CS11 degraded mode: no NCM bridge; control channel only")
            }
            ncmOwnedLocally = false
            debugLog("wired AirPlay transport attached userspaceLwip=${userspace != null}")
            if (closed) {
                listenerService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(LinkLocalAddress.fromMacOrNull(ncmHostMac) ?: config.linkLocal),
                airPlayPort = listenerService?.boundPort() ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog(
                "wired iAP2 runtime control starting " +
                    "location=${config.identification.locationInformationEnabled} " +
                    "vehicleStatus=${config.identification.vehicleStatusEnabled}",
            )
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message -> debugLog("wired $message") },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) listenerService?.detach()
            fail(error)
        } finally {
            if (!userspaceAttached) {
                // Nothing else owns the link yet, so this session has to release the USB claim and the netif.
                userSpaceLink?.close()
                userSpaceLink = null
            }
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        val readyDeadline = System.nanoTime() + WirelessStartupPolicy.HOTSPOT_READY_MILLIS * 1_000_000
        val hotspotMode = config.wirelessHotspotMode
        if (com.shilapi.xcertplay.network.CarHotspotSettings.shouldEnable(
                appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
            )
        ) {
            val result = com.shilapi.xcertplay.network.CarHotspotTethering.enable(
                appContext,
                isCancelled = { isStaleWirelessRun(generation) ||
                    !com.shilapi.xcertplay.network.CarHotspotSettings.enabled(appContext) },
                timeoutMillis = ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1),
                log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting hotspot interface") },
            )
            val manualFallback = result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.UNSUPPORTED &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == null
            if (result != com.shilapi.xcertplay.network.CarHotspotTethering.Result.READY && !manualFallback) {
                throw WirelessStartupException(
                    if (result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.TIMED_OUT ||
                        result == com.shilapi.xcertplay.network.CarHotspotTethering.Result.FAILED)
                        WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        if (hotspotMode == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == false
        ) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "The car hotspot is off. Turn it on in the car settings and connect again.")
        }
        // CS11/4.4: the user may not have typed any credentials; when the firmware stores a usable
        // hotspot configuration, adopt its live SSID/passphrase/security instead of the saved form.
        val adoptedManual = if (hotspotMode == WirelessHotspotMode.MANUAL) {
            LegacySoftApControl.readUsableStored(appContext)?.also { adopted ->
                debugLog(
                    "generation=$generation manual hotspot adopts live AP configuration " +
                        "ssid='${adopted.ssid}' security=${adopted.security}",
                )
            }
        } else {
            null
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext, ::debugLog,
                preferredChannel = config.wifiP2pPreferredChannel)
            WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
                appContext, config.existingWifiSsid, config.existingWifiPassphrase, ::debugLog,
                onNetworkChanged = { if (!isStaleWirelessRun(generation)) restartWireless() },
            )
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = adoptedManual?.ssid ?: config.manualHotspotSsid
                    ?: throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot SSID is not configured"),
                passphrase = if (adoptedManual != null) adoptedManual.password.orEmpty() else config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = when (adoptedManual?.security) {
                    LegacySoftApControl.StoredSecurity.OPEN -> ManualHotspotSecurity.OPEN
                    LegacySoftApControl.StoredSecurity.WPA_PSK -> ManualHotspotSecurity.WPA2
                    else -> config.manualHotspotSecurity
                },
                onDiagnostic = { debugLog("generation=$generation $it") },
                isCancelled = { isStaleWirelessRun(generation) },
            )
        }
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            hotspot = manager
        }
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
            ((readyDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    // Attempts to bring Bluetooth up on firmware where it is off at connect time (CS11/4.4);
    // best effort, cancellable, and never blocks longer than the enable timeout.
    @Suppress("DEPRECATION")
    private fun requestWirelessBluetoothOn(adapter: BluetoothAdapter): Boolean {
        debugLog("wireless Bluetooth is off; requesting enable")
        if (runCatching { adapter.enable() }.getOrDefault(false) != true) {
            debugLog("wireless Bluetooth enable request was refused")
            return false
        }
        val deadline = System.nanoTime() + BLUETOOTH_ENABLE_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (adapter.isEnabled) {
                debugLog("wireless Bluetooth enabled")
                return true
            }
            if (closed) return false
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return adapter.isEnabled
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        WorkerThreads.create(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            val uuids = device.uuids
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "bondState=${device.bondState} cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlayListenerService? = listenerService, generation: Int? = null) =
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return@synchronized
            val owner = firstTcpWatchdog?.listener
            firstTcpWatchdog?.terminate()
            val diagnostics = wirelessDiagnostics
            wirelessDiagnostics = null
            diagnostics?.close()
            wirelessConnectionProof.clear()
            media.setIapTunnelHandler(null)
            val activeTunnel = wirelessTunnelChannel
            wirelessTunnelChannel = null
            if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

            closeBluetoothBootstrapTransport()

            val activeBonjour = bonjour
            bonjour = null
            if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

            val activeHotspot = hotspot
            hotspot = null
            if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
            wirelessRuntimeIdentification = null
            wirelessAirPlayEndpoint = null
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)

            if (service != null && owner != null) closeBestEffort("AirPlay service") { service.detachWireless(owner) }
        }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }


    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitListenerService(): CarPlayListenerService? {
        listenerService?.let { return it }
        bindListenerService()
        return try {
            if (listenerLatch.await(LISTENER_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) listenerService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindListenerService() {
        if (listenerBound) return
        listenerBound = true
        try {
            val intent = Intent(appContext, CarPlayListenerService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                listenerBound = false
                listenerLatch.countDown()
            }
        } catch (_: Throwable) {
            listenerBound = false
            listenerLatch.countDown()
        }
    }

    private fun unbindListenerService() {
        if (!listenerBound) return
        listenerBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        listenerService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, detachCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        detachCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun requireUsbManager(): UsbManager =
        usbManager ?: throw IOException("USB service is unavailable on this device")

    private fun hasRequiredUsbService(): Boolean {
        if (usbManager != null || config.transport != CarPlayTransport.WIRED) return true
        fail(IOException("USB service is unavailable on this device"))
        return false
    }

    private fun fail(error: Throwable, generation: Int? = null) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) return
                firstTcpWatchdog?.terminate()
            }
            val causes = generateSequence(error) { it.cause }.toList()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                causes.any { it is com.shilapi.xcertplay.network.P2pResetRequiredException },
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason),
                if (wireless) wirelessGeneration.get() else null)
        }
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        DiagnosticLog.line(message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        DiagnosticLog.error(message, error)
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    /**
     * Renders a throwable and its cause chain with the top stack frame of each, so failures that
     * are wrapped (e.g. "USBMUX read failed" hiding the real cause) are diagnosable from the session
     * log alone, without logcat. Bounded in depth; the redactor still runs on the result.
     */
    private fun describeThrowable(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current.message?.let { builder.append(": ").append(it) }
            current.stackTrace.firstOrNull()?.let { frame ->
                builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return builder.toString()
    }

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        mainHandler.post {
            if (!closed && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: the MFi authentication provider is not ready; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting USB access for MFi"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        // Indices in the telephony HID report (AirPlayHid.telephonyDescriptor).
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LISTENER_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        /** Same reasoning as the availability poll: the grant arrives within milliseconds. */
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 100L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        /**
         * How often `UsbManager.deviceList` is re-read while waiting for the iPhone.
         *
         * Was 2 s. The iPhone's re-enumeration takes about one second, and a missed USB_DEVICE_ATTACHED
         * broadcast meant waiting up to two more seconds before noticing it had arrived. Reading the device
         * list is a cheap binder call, and this only runs while the controller is waiting, so 150 ms keeps the
         * wait close to the hardware's own time.
         */
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 150L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val BLUETOOTH_ENABLE_TIMEOUT_MILLIS = 6_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2

        /** How long teardown waits for the phone to re-enumerate after the configuration switch. */
        private const val RESET_REENUMERATION_WAIT_MILLIS = 1_500L
        private const val RESET_REENUMERATION_POLL_MILLIS = 50L

        /** SET_CONFIGURATION is retried after force-claiming the interfaces (EBUSY while one is bound). */
        private const val CONFIGURATION_SWITCH_ATTEMPTS = 2
        private const val CONFIGURATION_SWITCH_SETTLE_MILLIS = 150L

        /** Each leg of the pre-connect replug waits this long for the switch to settle. */
        private const val SOFT_REPLUG_SETTLE_MILLIS = 1_200L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        /**
         * A [ThreadFactory] that names its threads, so `top -H`, `taskset` and systrace output
         * identify the pipeline stage instead of `pool-N-thread-1` (docs/_digest/69 P0-5).
         * Daemon, matching the media sink's workers.
         */
        private fun namedThread(name: String): ThreadFactory = ThreadFactory { task ->
            WorkerThreads.create(task, name).apply { isDaemon = true }
        }
    }
}
