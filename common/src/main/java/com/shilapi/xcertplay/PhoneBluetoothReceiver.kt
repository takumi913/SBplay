package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Optional wake-up path for the iPhone selected in connection setup: when that phone reconnects over
 * Bluetooth after the car wakes, open DiPlay so wireless CarPlay starts without the driver tapping
 * the app. Ported from upstream `fe1a59d`; opt-in and off by default.
 */
class PhoneBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The wireless path can run without any activity being created; capture before it starts threads.
        com.shilapi.xcertplay.compat.WorkerThreads.capture()
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (CarPlayBackgroundSession.hasSession()) {
            if (CarPlayBackgroundSession.active) releaseDuringSession(context, intent)
            return
        }
        if (!DiPlayPreferences.connectOnPhoneBluetooth(context)) return
        val selected = DiPlayPreferences.phoneAddress(context) ?: return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        if (!address.equals(selected, ignoreCase = true)) return

        // The car can send several ACL events during one wake-up. Commit before launching so another
        // receiver instance cannot open a second connection from the same event burst.
        val prefs = context.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong("last_launch_elapsed", 0)
        if (prefs.contains("last_launch_elapsed") && now >= previous && now - previous < 15_000) return
        prefs.edit().putLong("last_launch_elapsed", now).commit()
        PhoneWakeDiagnostics.record(context, "matched")
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_AUTO_CONNECT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
            .onSuccess { PhoneWakeDiagnostics.record(context, "startActivity-returned") }
            .onFailure { error ->
                PhoneWakeDiagnostics.record(context, "failed", error)
                Log.w("DiPlayPhoneWake", "Bluetooth wake launch failed: ${error.javaClass.simpleName}")
            }
    }

    /**
     * The phone's Bluetooth came back while CarPlay is live: release its A2DP/HFP now rather than at the
     * watchdog's next tick, so a call that rings right after cannot land on the car's own dialer.
     * Only for the CarPlay phone (or any phone while none is stored); the release itself also refuses
     * any other device.
     */
    private fun releaseDuringSession(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val stored = validPhoneAddress(DiPlayPreferences.phoneBluetoothAddress(context))
            ?: validPhoneAddress(DiPlayPreferences.phoneAddress(context))
        if (stored != null && !stored.equals(address, ignoreCase = true)) return
        val watchdog = PhoneMediaAudioWatchdog.shared(context)
        releasePhoneBluetoothOnEvent(context.applicationContext ?: context, "phone bluetooth reconnected") { watchdog.log(it) }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "com.shihab.diplay.AUTO_CONNECT_SELECTED_PHONE"
    }
}

/** Only a timestamp and fixed outcome; the Bluetooth address and broadcast extras stay out of reports. */
internal object PhoneWakeDiagnostics {
    private const val PREFS = "diplay_phone_wake_diagnostics"

    fun record(context: Context, result: String, error: Throwable? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis())
            .putString("result", result)
            .putString("failureClass", error?.javaClass?.simpleName ?: "none")
            .apply()
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0)
        return if (at == 0L) {
            "Phone Bluetooth wake: no matching event recorded"
        } else {
            "Phone Bluetooth wake: matchedAtEpochMs=$at result=${prefs.getString("result", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none")}; " +
                "startActivity-returned does not prove the car displayed DiPlay."
        }
    }
}
