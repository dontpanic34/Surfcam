package com.surfcam.app.domain.geometry

import kotlin.math.roundToInt

/**
 * Distance estimée d'un surfeur d'après sa hauteur dans l'image (modèle du sténopé) : distance = focale en pixels ×
 * taille réelle ÷ hauteur en pixels. La taille réelle est supposée (1,75 m, debout) : un surfeur accroupi ou allongé
 * paraît plus petit, donc plus loin ; le résultat est un ordre de grandeur, pas une mesure.
 */
object DistanceEstimator {
    const val PERSON_HEIGHT_M = 1.75f

    /** Part de la hauteur de l'image que le surfeur devrait occuper pour être bien lisible. */
    const val TARGET_HEIGHT_FRACTION = 0.15f

    fun distanceMeters(focalPx: Float, heightPx: Float, personHeightM: Float = PERSON_HEIGHT_M): Float? {
        if (focalPx <= 0f || heightPx < 2f) return null
        return focalPx * personHeightM / heightPx
    }

    /** Arrondi lisible : 5 m près jusqu'à 100 m, puis 10 m. */
    fun rounded(distance: Float): Int {
        val step = if (distance < 100f) 5 else 10
        return ((distance / step).roundToInt() * step).coerceAtLeast(step)
    }

    /**
     * Zoom conseillé pour que le surfeur occupe [TARGET_HEIGHT_FRACTION] de l'image, ramené à ×1, ×2 ou ×5 : ce sont les
     * seuls vrais crans optiques (principal, recadrage du capteur, téléobjectif), au-delà l'image devient molle.
     */
    fun recommendedZoom(
        currentZoom: Float,
        heightPx: Float,
        frameHeightPx: Float,
        minZoom: Float,
        maxZoom: Float,
    ): Float {
        if (heightPx < 2f) return currentZoom
        val wanted = currentZoom * TARGET_HEIGHT_FRACTION * frameHeightPx / heightPx
        val step = when {
            wanted >= 3.5f -> 5f
            wanted >= 1.5f -> 2f
            else -> 1f
        }
        return step.coerceIn(minZoom, maxZoom)
    }
}
