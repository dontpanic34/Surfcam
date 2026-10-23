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
import com.surfcam.app.data.engine.SessionOptions
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
            DoNotDisturb.restore(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        acquireWakeLock()
        val options = SessionOptions(
            tele = intent?.getBooleanExtra(EXTRA_TELE, false) == true,
            fullSession = intent?.getBooleanExtra(EXTRA_FULL_SESSION, false) == true,
            hd = intent?.getBooleanExtra(EXTRA_HD, false) == true,
            storage = intent?.getStringExtra(EXTRA_STORAGE) ?: "external_primary",
            fps60 = intent?.getBooleanExtra(EXTRA_FPS60, false) == true,
            lockExposure = intent?.getBooleanExtra(EXTRA_LOCK_EXPOSURE, false) == true,
            reframe = intent?.getBooleanExtra(EXTRA_REFRAME, false) == true,
            followGimbal = intent?.getBooleanExtra(EXTRA_FOLLOW_GIMBAL, false) == true,
            hdr = intent?.getBooleanExtra(EXTRA_HDR, false) == true,
            ledBlink = intent?.getBooleanExtra(EXTRA_LED, true) != false,
            markerColor = com.surfcam.app.domain.detection.MarkerColor.fromName(intent?.getStringExtra(EXTRA_MARKER)),
        )
        if (intent?.getBooleanExtra(EXTRA_DND, false) == true) DoNotDisturb.enable(this)
        engine.start(intent?.getIntExtra(EXTRA_ROTATION_DEGREES, 90) ?: 90, options)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as SurfCamApp).engine.stop()
        DoNotDisturb.restore(this)
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
            .setSmallIcon(com.surfcam.app.R.drawable.ic_notification)
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
        const val EXTRA_TELE = "tele"
        const val EXTRA_FULL_SESSION = "full_session"
        const val EXTRA_HD = "hd"
        const val EXTRA_STORAGE = "storage"
        const val EXTRA_FPS60 = "fps60"
        const val EXTRA_DND = "dnd"
        const val EXTRA_LOCK_EXPOSURE = "lock_exposure"
        const val EXTRA_REFRAME = "reframe"
        const val EXTRA_FOLLOW_GIMBAL = "follow_gimbal"
        const val EXTRA_HDR = "hdr"
        const val EXTRA_LED = "led"
        const val EXTRA_MARKER = "marker_color"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L
    }
}
