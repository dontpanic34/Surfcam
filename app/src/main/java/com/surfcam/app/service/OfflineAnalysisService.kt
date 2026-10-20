package com.surfcam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import com.surfcam.app.SurfCamApp
import com.surfcam.app.data.analysis.OfflineAnalyzer
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.data.library.ClipCutter
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.presentation.MainActivity

/**
 * Service au premier plan qui analyse une session déjà filmée pour en retrouver les vagues, puis découpe chaque vague en
 * clip (sans réencoder). Il tourne téléphone verrouillé ; la bibliothèque affiche l'avancement.
 */
class OfflineAnalysisService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "session"
        createChannel()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION_ID, notification("Analyse de la session $title", 0f, indeterminate = true), type)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SurfCam:analysis").apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        Thread({ run(intent, title) }, "offline-analysis").start()
        return START_NOT_STICKY
    }

    private fun run(intent: Intent, title: String) {
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        try {
            if ((application as SurfCamApp).engine.state.value.phase.let { it == Phase.RUNNING || it == Phase.STARTING }) {
                finish(startMs, title, "Une session est en cours : l'analyse se fera après.")
                return
            }
            val uris = (intent.getStringArrayExtra(EXTRA_URIS) ?: emptyArray()).map(Uri::parse)
            val durations = intent.getLongArrayExtra(EXTRA_DURATIONS)?.toList() ?: emptyList()
            val existing = (intent.getLongArrayExtra(EXTRA_EXISTING) ?: LongArray(0)).toList().chunked(2).map { it[0] to it[1] }

            val cutter = ClipCutter(this)
            val parts = cutter.measure(uris, durations)
            val analyzer = OfflineAnalyzer(this)
            val watcher = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    if (OfflineAnalysis.cancelRequested) analyzer.cancelled = true
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }.also { it.start() }
            var lastNotified = 0L
            val result = try {
                analyzer.analyse(parts, LearningRepository(this).currentConfig()) { progress, found ->
                    val now = System.currentTimeMillis()
                    OfflineAnalysis.job.value?.let { OfflineAnalysis.set(it.copy(progress = progress, found = found)) }
                    if (now - lastNotified > 3_000) {
                        lastNotified = now
                        notify("Analyse de la session $title : ${(progress * 100).toInt()} %, $found vague(s)", progress)
                    }
                }
            } finally {
                watcher.interrupt()
            }
            if (OfflineAnalysis.cancelRequested) {
                finish(startMs, title, "Analyse annulée.")
                return
            }
            val ranges = result.getOrElse {
                finish(startMs, title, "Analyse interrompue : ${it.message ?: it.javaClass.simpleName}")
                return
            }
            // On ne refait pas les vagues déjà enregistrées pendant la session.
            val fresh = ranges.filter { r ->
                val a = startMs + r.startUs / 1000
                val b = startMs + r.endUs / 1000
                existing.none { (s, e) -> a < e && b > s }
            }
            val library = VideoLibrary(this)
            val taken = library.list().map { it.name.removeSuffix(".mp4") }.toMutableSet()
            var created = 0
            var failure: String? = null
            for ((i, r) in fresh.withIndex()) {
                if (OfflineAnalysis.cancelRequested) break
                OfflineAnalysis.job.value?.let { OfflineAnalysis.set(it.copy(phase = "Création des clips", progress = i.toFloat() / fresh.size, found = fresh.size)) }
                notify("Création des clips : ${i + 1} sur ${fresh.size}", i.toFloat() / fresh.size)
                val clipStartMs = startMs + r.startUs / 1000
                var number = 1
                while (VideoNaming.clipName(clipStartMs, number) in taken) number++
                val name = VideoNaming.clipName(clipStartMs, number)
                val error = cutter.cut(parts, r.startUs, r.endUs, "$name.mp4")
                if (error == null) {
                    taken += name
                    created++
                } else {
                    failure = error
                }
            }
            val skipped = ranges.size - fresh.size
            val text = buildString {
                append("$created vague(s) retrouvée(s) dans la session $title")
                if (skipped > 0) append(" ($skipped déjà enregistrée(s))")
                append(".")
                if (failure != null) append(" Un clip n'a pas pu être créé : $failure")
                if (created == 0 && failure == null) append(" Aucune nouvelle vague.")
            }
            finish(startMs, title, text)
        } catch (t: Throwable) {
            finish(startMs, title, "Analyse interrompue : ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun finish(startMs: Long, title: String, text: String) {
        OfflineAnalysis.set(OfflineJob(startMs, title, phase = "Terminé", progress = 1f, done = true, result = text))
        getSystemService(NotificationManager::class.java).notify(
            DONE_NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.surfcam.app.R.drawable.ic_notification)
                .setContentTitle("Analyse terminée")
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, progress: Float, indeterminate: Boolean = false): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.surfcam.app.R.drawable.ic_notification)
            .setContentTitle("SurfCam retrouve les vagues")
            .setContentText(text)
            .setContentIntent(openApp())
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .setOngoing(true)
            .build()

    private fun notify(text: String, progress: Float) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, progress))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Analyse de session", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_DURATIONS = "durations"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_TITLE = "title"
        const val EXTRA_EXISTING = "existing"
        private const val CHANNEL_ID = "analysis"
        private const val NOTIFICATION_ID = 2
        private const val DONE_NOTIFICATION_ID = 3
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
