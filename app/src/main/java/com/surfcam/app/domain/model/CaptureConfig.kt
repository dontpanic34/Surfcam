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
        const val BUFFER_WINDOW_SEC = 8
        const val PRE_ROLL_SEC = 5
        const val POST_ROLL_SEC = 3

        // Taille fixe de l'aperçu à l'écran : l'aperçu n'a pas besoin de la 4K.
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
    }
}
