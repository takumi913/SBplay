package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
) {
    private val appContext = context.applicationContext

    /**
     * Remembers which attachment is already being prompted.
     *
     * Re-enumeration makes the controller ask again for a device the user is still looking at
     * (CarPlayController.onIphoneAttached -> requestIphonePermission), and Android shows one
     * system dialog per `requestPermission` call. EasyPlay v71 keeps the same per-attach state
     * (`IphoneUsbPermissionCoordinator.observe`, `:222-283`).
     */
    private val permissionGate = UsbPermissionGate()

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) {
            permissionGate.resolved(device)
            return PermissionRequest.AlreadyGranted(device)
        }
        permissionGate.forgetDetached(usbManager.deviceList.keys)
        if (!permissionGate.shouldRequest(device)) {
            // The same attachment already has a prompt pending, or the user refused it. Asking
            // again would stack a second system dialog on the one they are looking at.
            Log.i(IphoneCarPlayConfiguration.TAG, "usb permission prompt suppressed for ${device.deviceName}")
            return PermissionRequest.Requested(device)
        }
        permissionGate.requested(device)
        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /**
     * Returns null for unrelated broadcasts, malformed results, or devices that are not currently
     * attached.
     *
     * The device handed back is the one from the current enumeration, never the object the system
     * carried through the permission dialog: after the iPhone switches USB mode the old object no
     * longer opens, and every caller here goes on to open or reconfigure the device
     * (`IphoneCarPlayConfiguration.find`, `activeConfigCarPlayReady`, `beginReenumeration`).
     */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        val attached = usbManager.deviceList[device.deviceName] ?: return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            permissionGate.resolved(attached)
            PermissionResult.Granted(attached)
        } else {
            permissionGate.denied(attached)
            PermissionResult.Denied(attached)
        }
    }

    /**
     * Forgets which attachment is mid-prompt or was refused.
     *
     * The user asking for a reconnect is the one case where a suppressed prompt must come back:
     * without this, refusing the dialog once would leave that attachment silent until it is
     * unplugged.
     */
    fun resetPermissionGate() {
        permissionGate.forgetDetached(emptyList())
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Any USB device leaving the bus (API 12+). Deliberately not filtered by [matcher]: the caller compares
     * the device node with the one its session opened, which is the only reliable identity here - the
     * product id can change between the normal and the CarPlay configuration.
     */
    fun registerDetachReceiver(onDetached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)) { intent ->
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) intent.usbDevice()?.let(onDetached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection -> sendCarPlayConfigurationRequest(connection) })
        }
    }

    /**
     * Synchronous form of [requestCarPlayReenumerationAsync], for the teardown path.
     *
     * A teardown runs on a background thread and closes the session's connection right after this
     * call, so the vendor request has to be finished before it returns - an executor hop would race
     * the close and the iPhone would never see the detach that makes it drop the old CarPlay session.
     */
    fun requestCarPlayReenumeration(device: UsbDevice): TransitionResult =
        runTransition(device) { connection -> sendCarPlayConfigurationRequest(connection) }

    private fun sendCarPlayConfigurationRequest(connection: UsbDeviceConnection): TransitionResult {
        val response = ByteArray(VENDOR_RESPONSE_LENGTH)
        val transferred = connection.controlTransfer(
            USB_VENDOR_DEVICE_IN,
            CARPLAY_CONFIGURATION_REQUEST,
            0,
            CARPLAY_CONFIGURATION_INDEX,
            response,
            response.size,
            CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        if (transferred != response.size) {
            throw IphoneUsbException.Protocol(
                "CarPlay configuration request transferred $transferred of ${response.size} bytes",
            )
        }
        return TransitionResult.ReenumerationRequested
    }

    /**
     * Moves the iPhone off its CarPlay configuration - the wired teardown's "unplug".
     *
     * A session that ends while the phone stays in configuration 6 leaves it unable to start the
     * media plane on the next connect: `request-transition` re-arms it, `reuse-descriptors` does not
     * (tablet bench 2026-10-19, four consecutive stalls). Sending the vendor request alone does not
     * move it either, so the configuration itself is switched, which makes the next connect see
     * "not in the CarPlay configuration" and re-select it.
     *
     * Every interface this app holds must already be released when this runs. The target comes from
     * our own descriptor parse and the switch goes through USBDEVFS_SETCONFIGURATION (API 19).
     *
     * Returns false when the phone offers no other configuration or the kernel refused the switch.
     */
    fun resetCarPlayConfiguration(device: UsbDevice): Boolean {
        val tag = IphoneCarPlayConfiguration.TAG
        val connection = usbManager.openDevice(device) ?: run {
            Log.i(tag, "configuration reset: could not open the iPhone")
            return false
        }
        return try {
            val parsed = connection.rawDescriptors?.let { LegacyNcmDescriptors.parse(it) }.orEmpty()
            val carPlayId = CARPLAY_CONFIGURATION_FALLBACK_ID
            // Prefer a configuration that still carries NCM (a normal data mode) over the first
            // unrelated one: switching to PTP-only (id 1) was refused on the tablet.
            val targetId = parsed.filter { it.id != carPlayId }.firstOrNull { it.hasNcm }?.id
                ?: parsed.map { it.id }.firstOrNull { it != carPlayId }
            if (targetId == null) {
                Log.i(tag, "configuration reset: no other configuration parsed=${parsed.map { it.id }}")
                return false
            }
            var switched = false
            // The switch is EBUSY while a just-closed connection still holds an interface, so let the
            // closes settle and retry briefly before concluding the phone refused it.
            Thread.sleep(RESET_SETTLE_MILLIS)
            for (attempt in 0 until RESET_ATTEMPTS) {
                // EBUSY comes from a driver still holding an interface - the car's cdc_ncm keeps the
                // NCM function bound even with the vendor usbmuxd stopped. Force-claiming every
                // interface and releasing it detaches those drivers before the switch.
                forceClaimForConfiguration(connection, device)
                // API 19 has no platform configuration switch; the ioctl is the call, and it reports
                // errno, which is what separates EBUSY (someone still holds an interface) from a
                // refusal by the phone.
                val ioctlErrno = if (LegacyUsbNative.available) {
                    LegacyUsbNative.setConfiguration(connection.fileDescriptor, targetId)
                } else {
                    0
                }
                switched = ioctlErrno == 0
                Log.i(
                    tag,
                    "configuration reset carPlay=$carPlayId target=$targetId " +
                        "ioctl=$ioctlErrno (${LegacyUsbNative.describe(ioctlErrno)}) ok=$switched attempt=$attempt",
                )
                if (switched) break
                Thread.sleep(RESET_RETRY_DELAY_MILLIS)
            }
            switched
        } catch (error: Throwable) {
            Log.w(tag, "configuration reset failed", error)
            false
        } finally {
            runCatching { connection.close() }
        }
    }

    /**
     * Force-claims every interface once and releases it, so kernel drivers let go before the switch.
     *
     * The configuration switch answers EBUSY (errno 16) while any interface still has a driver
     * bound: on this ROM the kernel cdc_ncm keeps the NCM function even with the vendor usbmuxd
     * stopped. Release is immediate because a held claim would itself block SET_CONFIGURATION.
     */
    private fun forceClaimForConfiguration(connection: UsbDeviceConnection, device: UsbDevice) {
        val claimed = ArrayList<UsbInterface>()
        for (index in 0 until device.interfaceCount) {
            val usbInterface = runCatching { device.getInterface(index) }.getOrNull() ?: continue
            if (claimed.contains(usbInterface)) continue
            if (runCatching { connection.claimInterface(usbInterface, true) }.getOrDefault(false)) {
                claimed.add(usbInterface)
            }
        }
        for (usbInterface in claimed) runCatching { connection.releaseInterface(usbInterface) }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        softReplug: Boolean = false,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            // The phone keeps its CarPlay session across an app restart, and only a re-enumeration
            // clears it. Without one the next 0x4301 is ignored and the control channel merely times
            // out (docs/_digest/117 §4quater). The vendor request is the software replug; switching
            // the configuration is not usable here (EBUSY while the vendor usbmuxd holds interfaces).
            val target = if (softReplug) softReplugAndAwait(device) ?: device else device
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(target))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    /**
     * Asks the phone to re-enumerate (the software replug) and returns the device that came back.
     *
     * Returns null when the request was refused - the usual reason is a missing USB permission on
     * the object handed over by a previous session - or the phone did not return in time. The caller
     * then tries the original device, which is no worse than not asking at all.
     */
    private fun softReplugAndAwait(
        device: UsbDevice,
        timeoutMillis: Long = SOFT_REPLUG_TIMEOUT_MILLIS,
    ): UsbDevice? {
        val transition = runCatching { requestCarPlayReenumeration(device) }.getOrNull()
        if (transition !is TransitionResult.ReenumerationRequested) {
            Log.i(IphoneCarPlayConfiguration.TAG, "soft replug: request refused ($transition)")
            return null
        }
        Log.i(IphoneCarPlayConfiguration.TAG, "soft replug: requested; waiting for the phone")
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var oldGone = false
        while (System.nanoTime() < deadline) {
            val devices = runCatching { usbManager.deviceList }.getOrDefault(emptyMap())
            if (!oldGone && !devices.containsKey(device.deviceName)) oldGone = true
            if (oldGone) {
                devices.values.firstOrNull { matcher.matches(it.vendorId, it.productId) }?.let {
                    Log.i(IphoneCarPlayConfiguration.TAG, "soft replug: phone back as ${it.deviceName}")
                    return it
                }
            }
            Thread.sleep(SOFT_REPLUG_POLL_MILLIS)
        }
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "soft replug: phone did not come back in ${timeoutMillis}ms",
        )
        return null
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            // CS11: API19 无配置枚举——用活动配置直取接口；iPhone 收到请求后自行切换配置。
            Log.i(IphoneCarPlayConfiguration.TAG, "using the active USBMUX interface")
            val usbMux = IphoneCarPlayConfiguration.activeUsbMuxInterface(device)
                ?: throw IphoneUsbException.Protocol("Active iPhone configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=active iface=-1 alt=-1 " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        // The system answers by filling EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED into this
        // intent; API 19 has no PendingIntent mutability flag.
        return PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        appContext.registerReceiver(receiver, filter)
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004

        /**
         * The CarPlay configuration id on every iPhone measured so far. Used only when the
         * configuration list cannot be inspected (API 19) to pick something else to fall back to.
         */
        private const val CARPLAY_CONFIGURATION_FALLBACK_ID = 6

        /** A configuration switch can be briefly EBUSY after a close; retry a few times. */
        private const val RESET_ATTEMPTS = 3
        private const val RESET_RETRY_DELAY_MILLIS = 150L
        private const val RESET_SETTLE_MILLIS = 250L
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    // One persistent read request and one direct buffer for the whole session, as in the NCM
    // bridge: the previous shape allocated both per read and cancelled plus drained the request at
    // every idle timeout. Only close() cancels it now.
    private var pendingRead: UsbRequest? = null
    private var readQueued = false
    private val readBuffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        // A short transfer is resumed from the offset the pipe reached (MUSB can truncate a large
        // buffer) under one shared deadline; see UsbBulkWrite. The slice is only built on the rare
        // resume, so the normal single-shot path allocates nothing.
        val result = UsbBulkWrite.all(data.size, timeoutMillis) { offset, length, timeout ->
            val remaining = if (offset == 0 && length == data.size) data
            else data.copyOfRange(offset, offset + length)
            connection.bulkTransfer(outEndpoint, remaining, remaining.size, timeout)
        }
        if (!result.isComplete(data.size)) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred ${result.written} of ${data.size} bytes" +
                    if (result.timedOut) " before its deadline" else "",
            )
        }
    }

    /**
     * Returns null only when no completed USB request arrives before [timeoutMillis].
     *
     * The request and its direct buffer live for the whole session (the shape the NCM bridge already
     * uses): the previous version allocated a UsbRequest plus a 64 KiB direct buffer on every read
     * and cancelled plus drained the request at every idle timeout, churning the Dalvik heap and
     * re-queueing a fresh URB for every poll. A timeout now leaves the request queued and the next
     * read simply waits again; only [close] cancels it.
     */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue under one lock hold: close() cancels whatever is published here, and
            // the policy must not retry a rejected submission after the session was closed.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = pendingRead ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                        )
                    }
                    pendingRead = it
                }
                if (!readQueued) {
                    readBuffer.clear()
                    val submit: (java.nio.ByteBuffer) -> Boolean = { queuedBuffer ->
                        @Suppress("DEPRECATION")
                        current.queue(queuedBuffer, queuedBuffer.remaining())
                    }
                    var queueResult = readQueuePolicy.queue(readBuffer, ::checkOpenLocked, submit)
                    if (!queueResult.queued) {
                        // A queue refused right as a session starts is usually the previous URB still being
                        // released (the phone re-enumerated after a force-stop or a mode reset), not a dead
                        // pipe: the same request queues a moment later. Retry once before failing the attempt,
                        // which used to cost a whole reconnect (bench 2026-10-22).
                        Thread.sleep(QUEUE_RETRY_MILLIS)
                        checkOpenLocked()
                        readBuffer.clear()
                        queueResult = readQueuePolicy.queue(readBuffer, ::checkOpenLocked, submit)
                    }
                    if (!queueResult.queued) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis, readBuffer.capacity())} " +
                                "firstBytes=${queueResult.firstBytes} fallbackBytes=${queueResult.fallbackBytes ?: "not_attempted"})",
                        )
                    }
                    readQueued = true
                    acceptedFallback = queueResult
                }
                current
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
        // The policy caches the accepted size, so the controller refuses the requested read size
        // once per pipe. Log it as well as reporting it: the session has no diagnostic listener
        // wired in production, and logcat is how this head unit is diagnosed.
        acceptedFallback?.let { queued ->
            if (queued.fallbackBytes != null) {
                val fallback = "USBMUX read queue compatibility fallback api=19 " +
                    "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                    "fallbackBytes=${queued.fallbackBytes}"
                Log.i(IphoneCarPlayConfiguration.TAG, fallback)
                runCatching { onDiagnostic(fallback) }
            }
        }
        try {
            val completed = try {
                // CS11: 4.4 走无参阻塞版（由 close() 的 cancel 唤醒）
                @Suppress("DEPRECATION")
                connection.requestWait()
            } catch (_: TimeoutException) {
                // Nothing arrived; the request stays queued for the next read instead of being
                // cancelled and drained on every idle period.
                return@synchronized null
            }
            if (completed == null) {
                throw failSession("Android returned no USBMUX read request")
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            readQueued = false
            val transferred = readBuffer.position()
            if (transferred <= 0) return@synchronized ByteArray(0)
            return@synchronized ByteArray(transferred).also {
                readBuffer.flip()
                readBuffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        // cancel() 在请求已完成或连接已异常时可能抛 RuntimeException；它绝不能挡住
        // connection.close()，否则连接与已认领的 USBMUX 接口一起泄漏，下一次 claim 直接失败。
        runCatching { requestToCancel?.cancel() }
        try {
            connection.close()
        } finally {
            runCatching { requestToCancel?.close() }
        }
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long, bufferBytes: Int? = null): String = buildString {
        append("api=19")
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
        if (bufferBytes != null) append(" bufferBytes=").append(bufferBytes)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536

        /** One short pause before re-queueing a read the kernel refused while it released the previous URB. */
        const val QUEUE_RETRY_MILLIS = 50L
    }
}

internal fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi authentication failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}

/** How long the pre-connect software replug waits for the phone to leave and come back. */
private const val SOFT_REPLUG_TIMEOUT_MILLIS = 2_500L
private const val SOFT_REPLUG_POLL_MILLIS = 50L
