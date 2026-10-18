package com.surfcam.app.domain.model

/** Paramètres retenus pour la capture, après vérification de ce que le téléphone sait faire. */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val bufferWindowSec: Int = BUFFER_WINDOW_SEC,
    val keyFrameIntervalSec: Int = 1,
) {
    companion object {
        // 10 s : le décollage est détecté 1 à 2 s après coup, et le clip doit encore pouvoir remonter 5 s avant lui.
        const val BUFFER_WINDOW_SEC = 10
        const val PRE_ROLL_SEC = 5
        const val POST_ROLL_SEC = 3

        // Image réduite envoyée à l'analyse, et cadence maximale de celle-ci (5 images par seconde).
        const val ANALYSIS_WIDTH = 1280
        const val ANALYSIS_HEIGHT = 720
        const val ANALYSIS_INTERVAL_MS = 200L

        // Taille fixe de l'aperçu à l'écran : l'aperçu n'a pas besoin de la 4K.
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
    }
}
