package com.surfcam.app.domain.gimbal

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

/** Un surfeur vu par la détection : position horizontale (0 à 1) et hauteur dans l'image. */
data class FollowBox(val centerX: Float, val height: Float, val riding: Boolean, val marked: Boolean = false)

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

    /** Le surfeur à suivre : celui qui porte le manchon, sinon celui qui surfe, sinon le plus proche du précédent, sinon le plus grand. */
    fun chooseTarget(boxes: List<FollowBox>, previousX: Float?): FollowBox? {
        if (boxes.isEmpty()) return null
        boxes.filter { it.marked }.maxByOrNull { it.height }?.let { return it }
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

    /**
     * Suivi par pas d'angle : on mesure l'écart, on commande un pivotement relatif d'une fraction de cet écart, puis on
     * attend avant de mesurer de nouveau. Insensible au retard de l'analyse (les ordres de vitesse continuaient à tourner
     * après le centrage et faisaient partir le gimbal en oscillation) et sans calibrage de vitesse.
     */
    data class StepConfig(
        /** Écart (en degrés) sous lequel on ne bouge pas. */
        val deadbandDeg: Float = 2.5f,
        /** Part de l'écart corrigée à chaque pas : sous 1, pour ne pas dépasser le centre. */
        val gain: Float = 0.8f,
        /** Pas maximal, en degrés. */
        val maxStepDeg: Float = 15f,
    )

    /** Angle à pivoter (degrés, positif vers la droite), 0 si l'écart est dans la zone morte. */
    fun stepDegrees(errorDeg: Float, config: StepConfig = StepConfig()): Float {
        if (abs(errorDeg) < config.deadbandDeg) return 0f
        return (errorDeg * config.gain).coerceIn(-config.maxStepDeg, config.maxStepDeg)
    }

    /**
     * Pas d'angle avec anticipation : le surfeur avance pendant que l'analyse répond et que le gimbal tourne. On vise
     * donc là où il sera dans [leadSeconds] d'après sa vitesse angulaire [velocityDegPerSec] (degrés par seconde dans
     * le repère fixe). Une vitesse sous 1 °/s est du bruit de mesure : ignorée.
     */
    fun stepWithLead(errorDeg: Float, velocityDegPerSec: Float, leadSeconds: Float, config: StepConfig = StepConfig()): Float {
        val v = if (abs(velocityDegPerSec) < 1f) 0f else velocityDegPerSec
        val predicted = errorDeg + v * leadSeconds
        // La zone morte s'applique à l'écart prévu : un surfeur qui file doit être suivi même s'il est encore au centre.
        return stepDegrees(predicted, config)
    }

    /** Durée du pivotement : douce pour les petits pas, un peu plus longue pour les grands. */
    fun moveSeconds(stepDeg: Float): Float = (0.5f + abs(stepDeg) / 40f).coerceIn(0.5f, 1.2f)

    /** Réactivité du suivi, choisie dans l'appli : plus c'est réactif, plus le gimbal corrige vite et fort. */
    enum class FollowStyle(
        val label: String,
        val step: StepConfig,
        /** Pause après un pivotement, avant de mesurer de nouveau (secondes). */
        val settleSeconds: Float,
        /** Anticipation : on vise là où le surfeur sera dans ce délai (secondes). */
        val leadSeconds: Float,
        /** Facteur sur la durée des pivotements : sous 1, ils sont plus rapides. */
        val moveScale: Float,
    ) {
        SMOOTH("Doux", StepConfig(deadbandDeg = 3.5f, gain = 0.6f, maxStepDeg = 8f), 0.8f, 0.8f, 1.3f),
        NORMAL("Normal", StepConfig(), 0.5f, 1.0f, 1.0f),
        REACTIVE("Réactif", StepConfig(deadbandDeg = 1.5f, gain = 1.0f, maxStepDeg = 25f), 0.3f, 1.3f, 0.75f),
        ;

        companion object {
            fun fromName(name: String?): FollowStyle = entries.firstOrNull { it.name == name } ?: NORMAL
        }
    }

    /** Limite le pas pour que le gimbal reste dans la zone de surf : [limitDeg] de chaque côté de sa position de départ. */
    fun clampToZone(currentYawDeg: Float, stepDeg: Float, limitDeg: Float): Float =
        (currentYawDeg + stepDeg).coerceIn(-limitDeg, limitDeg) - currentYawDeg

    const val MAX_UNITS = 3000
}
