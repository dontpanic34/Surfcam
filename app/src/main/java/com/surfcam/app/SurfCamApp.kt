package com.surfcam.app

import android.app.Application
import com.surfcam.app.data.engine.RecordingEngine

class SurfCamApp : Application() {
    /** Unique moteur de capture, partagé entre l'écran et le service d'arrière-plan. */
    lateinit var engine: RecordingEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = RecordingEngine(this)
    }
}
