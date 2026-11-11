package com.shilapi.xcertplay

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.compat.systemServiceOrNull

/** Whether the phone still holds the Bluetooth media (A2DP) link to this head unit. */
internal enum class MediaAudioLinkState { CONNECTED, DISCONNECTED, UNKNOWN }

/** The part of the Bluetooth stack the watcher needs; abstracted so the decision can be unit tested. */
internal interface PhoneMediaAudioLink {
    fun state(): MediaAudioLinkState
    fun release(): Boolean
    fun restore(): Boolean
}

/**
 * Keeps the phone's Bluetooth media link out of the CarPlay session.
 *
 * The CS11's own music engine pauses whatever the phone plays over its A2DP link whenever the head
 * unit's active source is not Bluetooth ("current audio is not bt, so pause bt music" ->
 * `reqAvrcpPause`). With wireless CarPlay the active source is CarPlay, so the phone was paused
 * about a second after the user pressed play in Apple Music or QQ Music. CarPlay itself only needs
 * the iAP2 link, so the media profile is released (and kept released) while a session lives.
 */
internal class PhoneMediaAudioWatcher(
    private val link: PhoneMediaAudioLink,
    private val log: (String) -> Unit,
) {
    /** Releases the media link when the phone holds it. Returns true when this tick acted. */
    fun tick(): Boolean {
        if (link.state() != MediaAudioLinkState.CONNECTED) return false
        val released = link.release()
        log("released the phone's Bluetooth media link (A2DP) released=$released")
        return released
    }

    /** Called when the session ends so the phone gets its media link back. */
    fun restore() {
        if (link.restore()) log("restored the phone's Bluetooth media link (A2DP)")
    }
}

/**
 * Decides when a lost CarPlay session has been gone long enough to give the phone's Bluetooth back.
 *
 * The release is scoped to a session, so the end of one owes the phone its link back: calls through
 * the head unit's own hands-free, and its own Bluetooth music, both need it. The grace exists
 * because a wired loss can be transient -- the cable moves, the session comes straight back -- and
 * restoring on the first lost packet would flap the phone's Bluetooth on and off for nothing.
 *
 * Measured on the car on 2026-10-15 13:12: with no grace at all (the only path that ever restored
 * was the notification's own "Disconnect"), pulling the cable left the head unit holding the phone
 * released for the rest of the capture -- no `connectState` line followed `110_110`, and the app
 * itself had already worked out `sessionActive=false` while both watchdogs kept ticking.
 */
internal class MediaAudioRestorePolicy {
    private var pending = false

    /** A session is live again: nothing is owed. */
    @Synchronized fun sessionActive() {
        pending = false
    }

    /** True when the caller should arm the grace timer -- the first loss of a session, and only it. */
    @Synchronized fun sessionLost(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    /** The grace timer fired: true when the session is still gone, so the phone must be given back. */
    @Synchronized fun graceElapsed(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}

/**
 * Every watchdog this process built, so the end of a session can stop all of them at once.
 *
 * Two live at a time and only one of them is ever stopped by its owner: `DiPlaySessionService` stops
 * its own on destroy, while the host activity's -- the one wired to the private log -- is never
 * stopped anywhere. Measured on the car on 2026-10-15 13:13: both were ticking
 * `bluetooth profiles a2dp=false hfp=false` (interleaved 1.1 s apart, one per 5 s), so a single
 * `stop()` and the `restore()` inside it could not stick: the survivor re-releases within one tick.
 *
 * Since 2026-11-11 both owners share [PhoneMediaAudioWatchdog.shared], so this set holds one instance;
 * it is kept so the end of a session still stops whatever exists without knowing who built it.
 */
private val liveWatchdogs: MutableSet<PhoneMediaAudioWatchdog> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap())

/**
 * Runs [PhoneMediaAudioWatcher] on a background thread against the real A2DP profile.
 *
 * The iPhone may re-establish A2DP on its own, so the check is periodic rather than a one-shot.
 *
 * Measured on the car on 2026-10-13: the app's `Log.i` output does not reach logcat on this ROM, so a
 * watchdog that logged only there was invisible -- the field log showed the `disableBluetooth` handler
 * running and then nothing at all from the release, which read as "never called" when the release had in
 * fact run and answered false. The host activity attaches its own diagnostic sink (`appendLog`, see
 * [attachLog]) so these lines land in the session log.
 *
 * One instance per process ([shared]); see there for why.
 */
internal class PhoneMediaAudioWatchdog private constructor(
    private val context: Context,
) {
    init {
        liveWatchdogs.add(this)
    }

    /**
     * Where this watchdog's lines go besides logcat. The host activity attaches its session log while it
     * lives ([attachLog] / [detachLog]); the service has no log of its own, so it attaches nothing.
     */
    @Volatile private var logSink: ((String) -> Unit)? = null

    fun attachLog(sink: (String) -> Unit) {
        logSink = sink
    }

    /** Detaches [sink] only if it is still the attached one, so a newer activity's sink survives. */
    fun detachLog(sink: (String) -> Unit) {
        if (logSink === sink) logSink = null
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var proxy: BluetoothProfile? = null
    private var device: BluetoothDevice? = null
    @Volatile private var watcher: PhoneMediaAudioWatcher? = null
    private var phoneAddress: String? = null
    @Volatile private var running = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.A2DP) return
            this@PhoneMediaAudioWatchdog.proxy = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) proxy = null
        }
    }

    @Synchronized
    fun start() {
        val adapter = runCatching { context.systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (running) {
            // The address can change under a running watchdog: the first start of a session may have had to
            // guess the bonded iPhone, and the phone's own deviceId (onDeviceInfo) arrives afterwards. The
            // watcher's vendor link captured the old address, and its disconnect refuses any device that is
            // not that one ("connected device is not the selected phone"), so with two iPhones paired a
            // wrong guess left the real phone's HFP connected for the whole drive. Follow the new address.
            val current = currentAddress(adapter)
            if (adapter != null && current != null && !current.equals(phoneAddress, ignoreCase = true)) {
                retarget(adapter, current)
            } else {
                // Said out loud because "already running" and "never asked" looked identical in the field log.
                log("phone media watchdog already running for $phoneAddress")
            }
            return
        }
        // The stored address is the one the pairing flow saved, and it is only written there -- so on a
        // wired session it can still be missing and this watchdog used to log
        // `phone media watchdog not started (adapter=true address=false)` and never release anything
        // (measured on the car on 2026-10-13, while the head unit paused every track a second in).
        // The reference asks its Bluetooth backend instead (`connectedAddress(profile)` / `isBonded` in
        // LegacyVendorBluetoothHandoff), so ask the stack the same question here: whichever bonded device
        // is an iPhone is the one CarPlay means. This also keeps the release independent of the AirPlay
        // session having delivered `/info` at all.
        // The Bluetooth key first, then the legacy field only when it really holds a MAC: on the car,
        // `phone_address` held an IP and cost every release attempt (2026-10-13 18:41).
        // Both fields are validated as MACs before use: the legacy one holds an IP on this car, and the
        // Bluetooth one is written by other paths too, so an unvalidated value reaches getRemoteDevice and
        // comes back as "bad phone address" -- measured on 2026-10-13, which left the periodic re-check
        // switched off while the direct release (which validated first) worked, so a phone that
        // re-established HFP mid-drive was never released again.
        val address = currentAddress(adapter)
        if (adapter == null || address.isNullOrBlank()) {
            log("phone media watchdog not started (adapter=${adapter != null} address=${!address.isNullOrBlank()})")
            return
        }
        val remote = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (remote == null) {
            log("phone media watchdog not started (bad phone address)")
            return
        }
        val requested = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP) }.getOrDefault(false)
        if (!requested) {
            log("phone media watchdog not started (A2DP profile proxy unavailable)")
            return
        }
        device = remote
        phoneAddress = address
        watcher = PhoneMediaAudioWatcher(AndroidMediaAudioLink(), ::log)
        val worker = HandlerThread("diplay-media-audio").also { it.start() }
        thread = worker
        handler = Handler(worker.looper)
        running = true
        // A session holds the link again, so this watchdog may release it from here on.
        restoreRequested.set(false)
        log("phone media watchdog started for $address")
        handler?.post(tick)
    }

    /** The stored phone, else the bonded iPhone (saved so the next start finds it); null when neither. */
    private fun currentAddress(adapter: BluetoothAdapter?): String? {
        val stored = validPhoneAddress(DiPlayPreferences.phoneBluetoothAddress(context))
            ?: validPhoneAddress(DiPlayPreferences.phoneAddress(context))
        val address = stored?.takeIf { it.isNotBlank() } ?: bondedIphoneAddress(adapter) ?: return null
        if (address != stored) {
            log("phone media watchdog using the bonded iPhone $address (stored=$stored)")
            runCatching { DiPlayPreferences.savePhone(context, address, DiPlayPreferences.phoneName(context)) }
        }
        return address
    }

    /**
     * Points the running watchdog at [address] without a stop/start: `stop()` gives the phone its
     * Bluetooth back, which is the opposite of what a session that is still live needs. The tick thread
     * reads [watcher] fresh on every run, so swapping it is enough.
     */
    private fun retarget(adapter: BluetoothAdapter, address: String) {
        val remote = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (remote == null) {
            log("phone media watchdog kept $phoneAddress (new address $address rejected)")
            return
        }
        log("phone media watchdog now follows $address (was $phoneAddress)")
        device = remote
        phoneAddress = address
        watcher = PhoneMediaAudioWatcher(AndroidMediaAudioLink(), ::log)
        // Check the new phone now rather than at the next 5 s tick.
        handler?.post { if (running) runCatching { watcher?.tick() } }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        watcher?.restore()
        watcher = null
        val adapter = runCatching { context.systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        proxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        proxy = null
        device = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { watcher?.tick() }
            handler?.postDelayed(this, INTERVAL_MILLIS)
        }
    }

    private inner class AndroidMediaAudioLink : PhoneMediaAudioLink {
        private val vendor = VendorA2dpControl(context, phoneAddress, ::log)

        override fun state(): MediaAudioLinkState {
            // A session is owed its link back: nothing this watchdog asks may put the phone down again.
            if (restoreRequested.get()) return MediaAudioLinkState.UNKNOWN
            // A2DP and HFP are released independently. Once CarPlay owns the media audio the A2DP link
            // is already gone while HFP still carries phone calls to the head unit — and that is
            // exactly when the head unit's own dialer takes a call over. Either profile being
            // connected means there is still something to release, so both count here.
            val media = vendor.isConnected()
            val handsFree = vendor.isHfpConnected()
            // Raw profile states every tick: the vendor API is the only source on this head unit, and
            // whether it reports a connection decides if the release below ever runs.
            if (media != null || handsFree != null) log("bluetooth profiles a2dp=$media hfp=$handsFree")
            if (media == true || handsFree == true) return MediaAudioLinkState.CONNECTED
            if (media == false && handsFree == false) return MediaAudioLinkState.DISCONNECTED
            val current = proxy as? BluetoothA2dp ?: return MediaAudioLinkState.UNKNOWN
            val target = device ?: return MediaAudioLinkState.UNKNOWN
            return when (runCatching { current.getConnectionState(target) }.getOrNull()) {
                BluetoothProfile.STATE_CONNECTED -> MediaAudioLinkState.CONNECTED
                BluetoothProfile.STATE_DISCONNECTED -> MediaAudioLinkState.DISCONNECTED
                else -> MediaAudioLinkState.UNKNOWN
            }
        }

        override fun release(): Boolean {
            // See `state()`: a tick already past its own guard must not undo a restore either.
            if (restoreRequested.get()) return false
            val media = vendor.disconnect() == true
            if (restoreRequested.get()) return media
            // CarPlay has to own phone calls: while HFP stays connected the head unit's own dialer
            // takes the call over and CarPlay's telephony stream never carries its audio.
            // Both profiles in the same tick: returning after A2DP used to leave HFP connected until
            // the next tick, 5 s later, and a call arriving in that gap rang on the car's own dialer.
            val handsFree = vendor.isHfpConnected() != false && vendor.disconnectHfp() == true
            if (media || handsFree) return true
            return callProfileMethod("disconnect")
        }

        override fun restore(): Boolean {
            // Both profiles are asked for, with no short-circuit. `release()` keeps the early-return
            // shape because the 5 s tick gives it a second chance; this runs once, from `stop()`, so
            // an HFP-first answer would leave A2DP down for good. The two do come up independently on
            // this car: measured 2026-10-15 13:03, iOS re-attaching by itself walked
            // `110_120 -> 120_120 -> 120_140 -> 140_140`, A2DP first, HFP second.
            val handsFree = vendor.connectHfp() == true
            val media = vendor.connect() == true
            return handsFree || media || callProfileMethod("connect")
        }
    }

    /**
     * CS11: `BluetoothA2dp.connect/disconnect` have always existed but are @hide, so they are absent
     * from the compile SDK and reached reflectively on the live profile proxy (like this project's
     * other hidden-API calls).
     */
    private fun callProfileMethod(name: String): Boolean {
        val current = proxy ?: return false
        val target = device ?: return false
        return runCatching {
            val method = current.javaClass.getMethod(name, BluetoothDevice::class.java)
            method.invoke(current, target) as? Boolean ?: false
        }.getOrDefault(false)
    }

    internal fun log(message: String) {
        Log.i(TAG, message)
        // The file sink is the one that survives on this ROM; a failure here must never cost the release.
        runCatching { logSink?.invoke(message) }
    }

    companion object {
        private const val TAG = "DiPlay-BtAudio"
        private const val INTERVAL_MILLIS = 5_000L

        @Volatile private var instance: PhoneMediaAudioWatchdog? = null

        /**
         * The process's one watchdog.
         *
         * There used to be one per owner -- `DiPlaySessionService` and the host activity each built their
         * own -- so two ticked side by side (measured 2026-10-15 13:13, interleaved 1.1 s apart), only one
         * of them wrote to the session log, and a stop of one was undone by the other's next tick. Both
         * owners now share this instance; [restorePhoneMediaAudio] still stops it for everyone.
         */
        fun shared(context: Context): PhoneMediaAudioWatchdog =
            instance ?: synchronized(this) {
                instance ?: PhoneMediaAudioWatchdog(context.applicationContext ?: context).also { instance = it }
            }
    }
}

/** Convenience for the service: silence when Bluetooth is unavailable. */
/**
 * The bonded device that is an iPhone: the phone this head unit pairs with.
 *
 * The pairing flow stores the address it saw, but a wired session can start without that ever having
 * been written (see [PhoneMediaAudioWatchdog.start]). The reference reads the bonded/connected list
 * instead, which is the same question asked of the stack. A wrong answer costs nothing here: the
 * watcher only releases a link whose own profile state says it is connected.
 */
private fun bondedIphoneAddress(adapter: android.bluetooth.BluetoothAdapter?): String? =
    runCatching {
        adapter?.bondedDevices?.firstOrNull { device ->
            val name = device?.name ?: return@firstOrNull false
            name.contains("iPhone", ignoreCase = true) || name.contains("iPad", ignoreCase = true)
        }?.address
    }.getOrNull()

/**
 * Starts the watchdog, and never silently.
 *
 * Measured on the car on 2026-10-13: `CarPlay asked for Bluetooth off; releasing the phone's A2DP link`
 * reached the diagnostic log and the watchdog itself then produced no line at all, in logcat or in the
 * private log -- because the previous body was a bare `runCatching { start() }`. A release that fails in
 * silence leaves the driver with music that stops after a second and no reason in the log, which is
 * exactly the report this whole path exists to answer, so a failure now names itself.
 */
/**
 * A Bluetooth MAC as the reference validates it (`CarPlayController.BLUETOOTH_ADDRESS`).
 *
 * Measured on the car on 2026-10-13 18:35: the watchdog logged `not started (bad phone address)`, so the
 * stored preference had been handed to `getRemoteDevice` and rejected. Whatever the stored value came
 * from, a release must not be lost to it: an address that is not a MAC is not an address, and the bonded
 * iPhone below is the answer the reference asks its backend for anyway.
 */
internal val BLUETOOTH_MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

/**
 * [address] in the spelling this head unit's Bluetooth stack accepts, or null when it is not a MAC.
 *
 * iOS reports its address lower case (`deviceId=9c:92:4f:02:b7:b7`, measured 2026-10-14/15) and that is
 * what gets stored, but the car answers with upper case (`9C:92:4F:02:B7:B7` in logcat's
 * `BtServiceProxy` / our own `reqA2dpDisconnect` line). Upper case is the form the stack takes: on the
 * cold-start connection of 2026-10-15 the stored lower case cost both paths a release —
 * `phone media watchdog not started (bad phone address)` (framework `getRemoteDevice` runs the address
 * through `checkBluetoothAddress`, which accepts only `0-9A-F`) and `reqA2dpDisconnect(9c:92:4f:02:b7:b7)=false`
 * — while the reconnect four minutes later answered `reqA2dpDisconnect(9C:92:4F:02:B7:B7)=true` and the
 * stack then showed `connectState(a2dp_hfp) 9C:92:4F:02:B7:B7>110_110`. Canonicalising once, here,
 * covers every caller: the watchdog's `getRemoteDevice` and the release's vendor call both use it.
 */
internal fun validPhoneAddress(address: String?): String? =
    address?.trim()?.takeIf { BLUETOOTH_MAC.matches(it) }?.uppercase()

internal fun PhoneMediaAudioWatchdog.startQuietly() {
    runCatching { start() }.onFailure { error ->
        log("phone media watchdog failed to start: ${error.javaClass.name}: ${error.message}")
    }
}

/**
 * Releases the phone's A2DP link once, without starting the watchdog.
 *
 * Split out so CarPlay's `disableBluetooth` can act immediately and independently of the watchdog's own
 * startup: on 2026-10-13 the handler's line reached the diagnostic log while the watchdog produced no
 * line at all, in either sink, so the command's effect must not depend on that path having gone right.
 * The address follows the same rule as the watchdog -- the stored one, else the bonded iPhone -- and
 * every step is logged to logcat under [DiPlay-BtAudio] so a failure names the call that did not resolve.
 */
internal fun releasePhoneA2dp(context: Context, onLog: (String) -> Unit = {}): Boolean {
    fun say(message: String) {
        Log.i(A2DP_LOG_TAG, message)
        runCatching { onLog(message) }
    }

    return runCatching {
        val adapter = context.systemServiceOrNull(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        // The Bluetooth key first, then the legacy field only when it really holds a MAC: on the car,
        // `phone_address` held an IP and cost every release attempt (2026-10-13 18:41).
        // Both fields are validated as MACs before use: the legacy one holds an IP on this car, and the
        // Bluetooth one is written by other paths too, so an unvalidated value reaches getRemoteDevice and
        // comes back as "bad phone address" -- measured on 2026-10-13, which left the periodic re-check
        // switched off while the direct release (which validated first) worked, so a phone that
        // re-established HFP mid-drive was never released again.
        val stored = validPhoneAddress(DiPlayPreferences.phoneBluetoothAddress(context))
            ?: validPhoneAddress(DiPlayPreferences.phoneAddress(context))?.takeIf { it.isNotBlank() }
        val address = stored ?: bondedIphoneAddress(adapter)
        if (address.isNullOrBlank()) {
            say("a2dp release skipped: no phone address (stored=${!stored.isNullOrBlank()})")
            return@runCatching false
        }
        val vendor = VendorA2dpControl(context, address) { message -> say(message) }
        val connected = runCatching { vendor.isConnected() }.getOrNull()
        val hfpConnected = runCatching { vendor.isHfpConnected() }.getOrNull()
        val released = runCatching { vendor.disconnect() }.getOrNull()
        // HFP goes with A2DP, wired included. The requirement is that calls stay inside CarPlay: while
        // HFP stays connected the head unit's own dialer takes them over -- the same rule the periodic
        // watcher follows (PhoneMediaAudioWatchdog.kt, `CarPlay has to own phone calls`) and the same pair
        // the reference reports (LegacyVendorBluetoothHandoff.Result: a2dpHandled, hfpHandled).
        // Confirmed on the car on 2026-10-13: releasing only A2DP left the head unit showing
        // Bluetooth + phone (HFP) and kept its dialer in play.
        val hfpReleased = if (hfpConnected == true) runCatching { vendor.disconnectHfp() }.getOrNull() else null
        say(
            "a2dp release address=$address source=${if (stored != null) "stored" else "bonded"} " +
                "ready=${vendor.serviceReady()} connected=$connected hfp=$hfpConnected " +
                "released=$released hfpReleased=$hfpReleased",
        )
        runCatching { DiPlayPreferences.savePhone(context, address, DiPlayPreferences.phoneName(context)) }
        // Either profile going down is the release the session needs: reporting only A2DP made an
        // HFP-only release read as "nothing happened" (2026-10-14 logged `released=false` on the same
        // line as `hfpReleased=true`).
        released == true || hfpReleased == true
    }.getOrElse { error ->
        say("a2dp release failed: ${error.javaClass.name}: ${error.message}")
        false
    }
}

/** One ladder at a time: the session start and iOS's own command both ask for the same release. */
private val releaseLadderRunning = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Set when the session ends, so a ladder still in flight cannot re-release what [restorePhoneMediaAudio]
 * has just given back. The ladder spans ~26 s of delays, which is long enough to matter.
 */
private val releaseCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Set while the phone is owed its link back, and cleared when a session starts a watchdog again.
 *
 * [restorePhoneMediaAudio] and a tick that is already inside its vendor call can overlap, and that
 * tick's disconnect would land after the restore's connect: down again, with no watchdog left running
 * to correct it. A tick that has not started yet is covered by `running`; this covers the one that has.
 */
private val restoreRequested = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Runs [releasePhoneA2dp] now, and again if the head unit's Bluetooth API refuses it.
 *
 * Measured on the car on 2026-10-15 12:06, the first connection of a cold start: `Bt.create()` registers
 * the vendor callbacks and fills its cache at 11.04-11.08, the session's release ran at 11.15 and was
 * answered `ready=… connected=false hfp=false released=false`, while the head unit's own stack held the
 * phone at `9C:92:4F:02:B7:B7 140_140` throughout (logcat, XCBTServiceLog) — so nothing was released and
 * Bluetooth stayed attached, which is the regression the owner reported. The identical code answered
 * `connected=true released=true hfpReleased=true` on the reconnect four minutes later, when that handle
 * had already been created once. The ladder covers the window in between; it stops at the first success.
 * A second request while one is running joins it rather than doubling the attempts.
 */
internal fun releasePhoneA2dpRetrying(
    context: Context,
    onLog: (String) -> Unit = {},
    delaysMillis: LongArray = longArrayOf(0, 1_000, 3_000, 7_000, 15_000),
) {
    if (!releaseLadderRunning.compareAndSet(false, true)) {
        onLog("a2dp release ladder already running; this request joins it")
        return
    }
    releaseCancelled.set(false)
    val worker = HandlerThread("diplay-a2dp-release").apply { start() }
    val handler = Handler(worker.looper)
    var attempt = 0
    val task = object : Runnable {
        override fun run() {
            if (releaseCancelled.get()) {
                onLog("a2dp release ladder cancelled after attempt=$attempt; the session ended")
                releaseLadderRunning.set(false)
                worker.quitSafely()
                return
            }
            attempt++
            val released = runCatching { releasePhoneA2dp(context) { onLog("a2dp release attempt=$attempt $it") } }
                .getOrDefault(false)
            val next = if (released) null else delaysMillis.getOrNull(attempt)
            if (next == null) {
                onLog(
                    "a2dp release ladder finished after attempt=$attempt " +
                        "(released=$released, no further retry)",
                )
                releaseLadderRunning.set(false)
                worker.quitSafely()
                return
            }
            handler.postDelayed(this, next)
        }
    }
    handler.post(task)
}

/**
 * Releases the phone's A2DP/HFP now because an event says they may be back, instead of waiting for the
 * watchdog's next 5 s tick.
 *
 * The tick leaves a gap: the iPhone re-attaches HFP by itself, and a call that rings inside that gap is
 * taken by the head unit's own Bluetooth dialer instead of CarPlay. Two events close it -- a CarPlay call
 * appearing (iAP2 CallStateUpdate / the inferred VOIP call) and the phone's Bluetooth ACL coming back
 * while a session is live. The ladder is used because a fresh ACL brings HFP/A2DP up a second or more
 * after it, and the ladder stops at its first success.
 *
 * Callers check that a session is live; this only refuses while the phone is owed its link back.
 */
internal fun releasePhoneBluetoothOnEvent(context: Context, reason: String, onLog: (String) -> Unit = {}) {
    if (restoreRequested.get()) {
        onLog("bluetooth release on $reason skipped: the session already gave the phone its Bluetooth back")
        return
    }
    onLog("bluetooth release requested by $reason")
    releasePhoneA2dpRetrying(context, onLog)
}

/**
 * Stops every watchdog and hands the phone's Bluetooth back.
 *
 * This is what a CarPlay session owes the phone when it ends: the head unit's own hands-free and its
 * own Bluetooth music both need the link, and the "not connected" state is one *we* created. Only the
 * notification's "Disconnect" ever reached a restore before -- measured on the car on 2026-10-15
 * 13:12, pulling the cable left `reconnectAfterLoss` retrying forever, so `DiPlaySessionService` was
 * never stopped and no watchdog ever reached `stop()`; the head unit held the phone released for the
 * rest of the capture, with no `connectState` line after `110_110`, while the app itself had already
 * worked out `sessionActive=false`.
 *
 * Every instance has to go, not just the service's: the host activity's watchdog is never stopped by
 * its owner, and one survivor re-releases within its next 5 s tick. A release ladder still in flight
 * is cancelled for the same reason -- it spans ~26 s and would otherwise undo exactly this.
 */
internal fun restorePhoneMediaAudio() {
    // Set before stopping anything: `stop()` cannot interrupt a tick that is already inside its vendor
    // call, and that tick's disconnect would otherwise undo the connect below.
    restoreRequested.set(true)
    releaseCancelled.set(true)
    liveWatchdogs.toList().forEach { runCatching { it.stop() } }
}

/** The logcat tag the A2DP release uses, shared with [PhoneMediaAudioWatchdog]'s own lines. */
internal const val A2DP_LOG_TAG = "DiPlay-BtAudio"

/**
 * The head unit's own adapter API (`com.ecarx.xui.adaptapi`, in `/system/framework/ecarx.adapterapi.jar`)
 * hands car apps the Bluetooth A2DP profile, which is what this vendor Bluetooth stack actually
 * services — the framework's `BluetoothA2dp` proxy never reports a connection here. Reached
 * reflectively because that jar is not part of our build, and every step is logged so a failure
 * names the exact call that did not resolve.
 */
private class VendorA2dpControl(
    private val context: Context,
    private val phoneAddress: String?,
    private val log: (String) -> Unit,
) {
    private val bt: Any? = createBt()
    private val a2dp: Any? = bt?.let { owner ->
        invoke(owner, "getA2dp")?.also { log("adaptapi a2dp=${it.javaClass.name} methods=${methodNames(it).take(14)}") }
    }

    fun isConnected(): Boolean? {
        val value = a2dp?.let { invoke(it, "isA2dpConnected") ?: invoke(it, "getA2dpConnectionState") } ?: return null
        return (value as? Boolean) ?: ((value as? Int) == CONNECTION_STATE_CONNECTED)
    }

    /**
     * The vendor's own readiness flags, logged with each release so a refusal names its reason.
     *
     * Measured on the car on 2026-10-15 12:06 (cold start): `Bt.create()` makes the A2DP/HFP handles
     * register their callbacks and learn their state at 11.05-11.08, and the release attempted at 11.15
     * was answered `connected=false hfp=false released=false` although the stack had the phone at
     * `9C:92:4F:02:B7:B7 140_140` the whole time (logcat, XCBTServiceLog).
     */
    fun serviceReady(): String =
        "a2dp=${a2dp?.let { invoke(it, "isA2dpServiceReady") }} " +
            "hfp=${hfp?.let { invoke(it, "isHfpServiceReady") }}"

    /** True when the head unit accepted a disconnect for the phone's media profile. */
    fun disconnect(): Boolean? = request(a2dp, "reqA2dpDisconnect", "getA2dpConnectedAddress", disconnecting = true)

    /** The hands-free profile of the same adapter API, so CarPlay can own phone calls. */
    private val hfp: Any? = bt?.let { owner ->
        invoke(owner, "getHfp")?.also { log("adaptapi hfp=${it.javaClass.name}") }
    }

    fun connect(): Boolean? = request(a2dp, "reqA2dpConnect", "getA2dpConnectedAddress", disconnecting = false)

    /** Whether the head unit still holds the phone's hands-free profile. */
    fun isHfpConnected(): Boolean? {
        val value = hfp?.let { invoke(it, "isHfpConnected") ?: invoke(it, "getHfpConnectionState") } ?: return null
        return (value as? Boolean) ?: ((value as? Int) == CONNECTION_STATE_CONNECTED)
    }

    fun connectHfp(): Boolean? = request(hfp, "reqHfpConnect", "getHfpConnectedAddress", disconnecting = false)

    fun disconnectHfp(): Boolean? = request(hfp, "reqHfpDisconnect", "getHfpConnectedAddress", disconnecting = true)

    /**
     * Tries the live address first, then the paired one, logging every attempt's result.
     *
     * When releasing, the vendor's connected address must be *our* phone: this head unit can hold
     * another paired device, and disconnecting whatever it reports would drop that device instead
     * (EasyPlay guards its handoff the same way, docs/_digest/37). Connecting ignores that check,
     * because restoring our own link is always the intent.
     */
    private fun request(target: Any?, method: String, addressGetter: String, disconnecting: Boolean): Boolean? {
        if (target == null) return null
        val connected = invoke(target, addressGetter) as? String
        val paired = phoneAddress?.takeIf { it.isNotBlank() }
        val candidates = when {
            connected.isNullOrBlank() -> listOfNotNull(paired)
            paired == null || connected.equals(paired, ignoreCase = true) -> listOf(connected)
            disconnecting -> {
                log("$method skipped: connected device is not the selected phone")
                return false
            }
            else -> listOf(paired)
        }
        for (address in candidates.distinct()) {
            val result = invoke(target, method, address) as? Boolean
            log("$method($address)=$result")
            if (result == true) return true
        }
        return false
    }

    private fun createBt(): Any? {
        val type = runCatching { Class.forName(BT) }.getOrNull()
        if (type == null) {
            log("bt api class not on the classpath")
            return null
        }
        log("bt api methods=${methodNames(type).take(20)}")
        val creator = type.methods.firstOrNull { it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(context) }
        val instance = creator?.let { runCatching { it.invoke(null, context) }.getOrNull() }
        log("bt instance via create(Context)=${instance != null}")
        return instance
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size &&
                it.parameterTypes.indices.all { index -> args[index] == null || it.parameterTypes[index].isInstance(args[index]) }
        } ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    private fun methodNames(target: Any): List<String> =
        target.javaClass.methods.filterNot { it.declaringClass == Any::class.java }.map { it.name }.distinct().sorted()

    private companion object {
        const val BT = "com.ecarx.xui.adaptapi.bt.Bt"
        const val CONNECTION_STATE_CONNECTED = 2
    }
}

