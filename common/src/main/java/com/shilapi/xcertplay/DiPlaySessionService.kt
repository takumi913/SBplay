package com.shilapi.xcertplay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    /** The same process-wide watchdog the host activity uses; see [PhoneMediaAudioWatchdog.shared]. */
    private val mediaAudio get() = PhoneMediaAudioWatchdog.shared(this)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            mediaAudio.stop()
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // The car pauses the phone's Bluetooth media whenever CarPlay owns the audio; release it.
        mediaAudio.startQuietly()
        val open = PendingIntent.getActivity(this, 0, Intent(this, CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("SBplay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
        startForeground(1, builder.build())
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        mediaAudio.stop()
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaAudio.stop()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
    }
}


