package com.surfcam.app.domain.gimbal

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

/** Un surfeur vu par la détection : position horizontale (0 à 1) et hauteur dans l'image. */
data class FollowBox(val centerX: Float, val height: Float, val riding: Boolean)

data class FollowConfig(
    /** Écart (en degrés) sous lequel on ne bouge pas : évite de trembler autour du centre. */
    val deadbandDeg: Float = 1.5f,
    /** Gain proportionnel : degrés par seconde demandés par degré d'écart. */
    val gain: Float = 1.2f,
    /** Vitesse maximale commandée au gimbal, en degrés par seconde. */
    val maxDegPerSec: Float = 25f,
)

/**
 * Asservissement du gimbal sur le surfeur : on garde le surfeur au centre de l'image en faisant pivoter la tête à une
 * vitesse proportionnelle à l'écart. Pur calcul : le Bluetooth et la caméra sont ailleurs.
 */
object FollowController {

    /** Le surfeur à suivre : celui qui surfe, sinon le plus proche du précédent, sinon le plus grand. */
    fun chooseTarget(boxes: List<FollowBox>, previousX: Float?): FollowBox? {
        if (boxes.isEmpty()) return null
        boxes.filter { it.riding }.maxByOrNull { it.height }?.let { return it }
        if (previousX != null) return boxes.minByOrNull { abs(it.centerX - previousX) }
        return boxes.maxByOrNull { it.height }
    }

    /** Champ de vision horizontal de l'image, en degrés, pour une focale exprimée en pixels de cette image. */
    fun horizontalFovDeg(focalPx: Float, widthPx: Int): Float =
        Math.toDegrees(2.0 * atan(widthPx / 2.0 / focalPx)).toFloat()

    /** Vitesse de pivotement voulue (degrés par seconde, positive vers la droite) pour un écart en degrés. */
    fun desiredSpeedDegPerSec(errorDeg: Float, config: FollowConfig = FollowConfig()): Float {
        if (abs(errorDeg) < config.deadbandDeg) return 0f
        // On retranche la zone morte : la vitesse part de zéro à sa limite, sans à-coup.
        val effective = errorDeg - config.deadbandDeg * if (errorDeg > 0) 1 else -1
        return (effective * config.gain).coerceIn(-config.maxDegPerSec, config.maxDegPerSec)
    }

    /** Traduit des degrés par seconde en unités du protocole du gimbal, d'après le calibrage ; [invert] inverse le sens. */
    fun toProtocolUnits(speedDegPerSec: Float, gainDegPerSecPerUnit: Float, invert: Boolean): Int {
        if (gainDegPerSecPerUnit <= 0f) return 0
        val units = (speedDegPerSec / gainDegPerSecPerUnit).roundToInt()
        return (if (invert) -units else units).coerceIn(-MAX_UNITS, MAX_UNITS)
    }

    const val MAX_UNITS = 3000
}
