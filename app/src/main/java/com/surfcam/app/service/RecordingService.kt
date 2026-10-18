package com.surfcam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.surfcam.app.SurfCamApp
import com.surfcam.app.presentation.MainActivity

/**
 * Service de premier plan (type caméra) : la notification persistante empêche Android de couper la capture
 * quand l'écran se verrouille ou que l'app passe en arrière-plan.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as SurfCamApp).engine
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        acquireWakeLock()
        engine.start(intent?.getIntExtra(EXTRA_ROTATION_DEGREES, 90) ?: 90)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as SurfCamApp).engine.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SurfCam:recording").apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session de surf", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("SurfCam surveille la mer")
            .setContentText("Les clips de vagues sont enregistrés automatiquement")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.surfcam.app.STOP"
        const val EXTRA_ROTATION_DEGREES = "rotation_degrees"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L
    }
}
