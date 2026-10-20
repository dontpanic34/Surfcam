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
        hardCrash = com.surfcam.app.data.engine.Trail.init(this)
        // Arrêt brutal pendant un démarrage en HDR : on coupe le HDR pour ne jamais replanter, et on le dit.
        if (hardCrash?.contains("hdr=true") == true) {
            getSharedPreferences("camera", MODE_PRIVATE).edit().putBoolean("hdr_broken", true).apply()
            getSharedPreferences("options", MODE_PRIVATE).edit().putBoolean("hdr", false).apply()
            hardCrash += "\n\nLe HDR a été désactivé automatiquement. Tu peux le réessayer avec « Qualité maximale »."
        }
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

    private var hardCrash: String? = null

    fun readLastCrash(): String? = crashFile.takeIf { it.exists() }?.readText() ?: hardCrash

    fun clearLastCrash() {
        crashFile.delete()
        hardCrash = null
    }
}
