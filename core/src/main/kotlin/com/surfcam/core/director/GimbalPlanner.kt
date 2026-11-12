package com.surfcam.core.director

import com.surfcam.core.heading.GimbalOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

data class GimbalStyle(
    /** Zone libre autour du centre, en fraction du demi-champ : la caméra ne bouge pas tant que la cible y reste. */
    val deadZoneFraction: Double,
    /** Anticipation : la caméra vise où la cible sera dans ce délai. */
    val leadSec: Double,
    /** Vitesse de rotation maximale demandée au gimbal (°/s) : plus lent = plus beau à l'image. */
    val maxRateDegPerSec: Double,
    /** Plus grand pas autorisé en un ordre. */
    val maxStepDeg: Double,
    /** Durée minimale d'un ordre (un ordre trop court donne un à-coup). */
    val minDurationSec: Double = 0.5,
    /**
     * Mouvement continu, comme un caméraman : au lieu de pas espacés, des ordres courts enchaînés sans pause, dont la vitesse
     * suit celle de la cible plus une correction proportionnelle à l'écart. Le panoramique est fluide, sans à-coups.
     */
    val continuous: Boolean = false,
    /** Mouvement continu : gain de rattrapage de l'écart (1/s) et durée de chaque ordre. */
    val catchUpGainPerSec: Double = 1.2,
    val periodSec: Double = 0.6,
) {
    companion object {
        /** Suivi au téléobjectif : on laisse le surfeur vivre dans le cadre, on recadre par pas doux. */
        val FOLLOW = GimbalStyle(deadZoneFraction = 0.30, leadSec = 0.8, maxRateDegPerSec = 8.0, maxStepDeg = 12.0)
        /** Pendant une vague : anticipation plus forte, rotation plus franche. */
        // Calé sur la V1, dont le suivi était jugé bon par l'utilisateur (session du 06/11 : vitesse médiane 3°/s, 90 % des
        // ordres sous 7,4°/s). Un surfeur à 100-150 m se déplace de 2 à 4°/s ; la V2 du 12/11 tournait à 14°/s.
        val RIDE = GimbalStyle(deadZoneFraction = 0.10, leadSec = 0.0, maxRateDegPerSec = 6.0, maxStepDeg = 15.0, continuous = true, catchUpGainPerSec = 0.8)
        /** Déplacements sans enjeu d'image (recherche, retour au pic). */
        val REPOSITION = GimbalStyle(deadZoneFraction = 0.10, leadSec = 0.0, maxRateDegPerSec = 15.0, maxStepDeg = 45.0)
    }
}

/**
 * Transforme un cap voulu en ordres relatifs pour le gimbal (le DJI ne connaît que « tourne de X° en T s »).
 * Un seul ordre à la fois : on attend la fin du précédent, puis on mesure l'écart réel avec le gyroscope.
 */
class GimbalPlanner(
    /** Butées autour du cap de départ (degrés). */
    private val limitDeg: Double = 90.0,
) {
    private var busyUntilUs = 0L

    fun isBusyAt(nowUs: Long) = nowUs < busyUntilUs

    /**
     * @param currentHeadingDeg cap réel (gyroscope) maintenant
     * @param targetDeg cap où devrait être la cible maintenant
     * @param targetRateDegPerSec vitesse de la cible (°/s), pour anticiper
     * @param hfovDeg champ horizontal actuel
     */
    fun plan(nowUs: Long, currentHeadingDeg: Double, targetDeg: Double, targetRateDegPerSec: Double, hfovDeg: Double, style: GimbalStyle): GimbalOrder? {
        if (nowUs < busyUntilUs) return null
        val error = targetDeg - currentHeadingDeg
        if (style.continuous) {
            val dead = style.deadZoneFraction * hfovDeg / 2.0
            if (abs(error) <= dead && abs(targetRateDegPerSec) < 0.3) return null
            val rate = (targetRateDegPerSec + style.catchUpGainPerSec * error).coerceIn(-style.maxRateDegPerSec, style.maxRateDegPerSec)
            var step = rate * style.periodSec
            val wanted = (currentHeadingDeg + step).coerceIn(-limitDeg, limitDeg)
            step = wanted - currentHeadingDeg
            if (abs(step) < 0.1) return null
            val durUs = (style.periodSec * 1e6).toLong()
            // Pas de pause de stabilisation : l'ordre suivant part à la fin de celui-ci, le mouvement ne s'arrête pas.
            busyUntilUs = nowUs + durUs - 50_000
            return GimbalOrder(nowUs, step, durUs)
        }
        val dead = style.deadZoneFraction * hfovDeg / 2.0
        if (abs(error) <= dead) return null
        // On vise un peu au-delà de la cible dans son sens de déplacement, sans dépasser le pas maximal.
        val lead = (targetRateDegPerSec * style.leadSec).coerceIn(-hfovDeg * 0.25, hfovDeg * 0.25)
        var step = error + if (sign(lead) == sign(error)) lead else 0.0
        step = step.coerceIn(-style.maxStepDeg, style.maxStepDeg)
        val wanted = (currentHeadingDeg + step).coerceIn(-limitDeg, limitDeg)
        step = wanted - currentHeadingDeg
        if (abs(step) < 0.3) return null
        val duration = max(style.minDurationSec, abs(step) / style.maxRateDegPerSec)
        val durUs = (duration * 1e6).toLong()
        // On attend la fin du mouvement plus un court temps de stabilisation avant de mesurer et recommander.
        busyUntilUs = nowUs + durUs + 250_000
        return GimbalOrder(nowUs, step, durUs)
    }

    fun reset() {
        busyUntilUs = 0L
    }
}
