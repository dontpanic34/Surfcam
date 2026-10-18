package com.surfcam.app

import android.app.Application
import android.util.Log
import com.surfcam.app.data.engine.RecordingEngine
import java.io.File
import java.util.Date

class SurfCamApp : Application() {
    /** Unique moteur de capture, partagé entre l'écran et le service d'arrière-plan. */
    lateinit var engine: RecordingEngine
        private set

    private val crashFile get() = File(filesDir, "crash.txt")

    override fun onCreate() {
        super.onCreate()
        engine = RecordingEngine(this)

        // Garde la trace du dernier plantage : elle est affichée au lancement suivant.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashFile.writeText("${Date()} · thread ${thread.name}\n" + Log.getStackTraceString(error))
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun readLastCrash(): String? = crashFile.takeIf { it.exists() }?.readText()

    fun clearLastCrash() {
        crashFile.delete()
    }
}
