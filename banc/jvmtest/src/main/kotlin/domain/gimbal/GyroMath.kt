package com.surfcam.app.domain.gimbal

import kotlin.math.abs
import kotlin.math.sqrt

/** Calculs purs pour comparer la rotation commandée au gimbal et la rotation réellement mesurée par le téléphone. */
object GyroMath {

    /**
     * Vitesse de rotation autour de la verticale (rad/s, positive dans le sens anti-horaire vu d'en haut), d'après le
     * gyroscope [wx, wy, wz] (rad/s, repère de l'appareil) et la lecture du capteur de gravité [gx, gy, gz] (m/s²).
     * Convention Android : à plat sur une table, la gravité se lit +9,81 sur z, donc le vecteur lu pointe vers le haut.
     * Renvoie 0 si la gravité est inexploitable (chute libre, capteur absent).
     */
    fun rateAboutUp(wx: Float, wy: Float, wz: Float, gx: Float, gy: Float, gz: Float): Float {
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 3f) return 0f
        return (wx * gx + wy * gy + wz * gz) / norm
    }

    /** Une commande de pivotement : angle ordonné et angle mesuré (degrés, positifs vers la droite). */
    data class Sample(val commandedDeg: Float, val measuredDeg: Float)

    /** Statistiques de fidélité des pivotements : l'angle ordonné est-il l'angle effectué ? */
    class Accuracy {
        private val samples = ArrayList<Sample>()

        @Synchronized
        fun add(commanded: Float, measured: Float) { samples += Sample(commanded, measured) }

        @Synchronized
        fun count() = samples.size

        /** Rapport mesuré / commandé par moindres carrés (1 = fidèle, 0,5 = fait la moitié, négatif = sens inversé). */
        @Synchronized
        fun ratio(): Float? {
            val den = samples.sumOf { (it.commandedDeg * it.commandedDeg).toDouble() }
            if (samples.isEmpty() || den < 1e-6) return null
            return (samples.sumOf { (it.commandedDeg * it.measuredDeg).toDouble() } / den).toFloat()
        }

        @Synchronized
        fun meanAbsErrorDeg(): Float? = if (samples.isEmpty()) null else samples.map { abs(it.measuredDeg - it.commandedDeg) }.average().toFloat()

        /** Part des pivotements dont le sens mesuré est celui ordonné (ignore les mesures sous 0,3°). */
        @Synchronized
        fun signAgreement(): Float? {
            val usable = samples.filter { abs(it.commandedDeg) > 0.3f && abs(it.measuredDeg) > 0.3f }
            if (usable.isEmpty()) return null
            return usable.count { it.commandedDeg * it.measuredDeg > 0 }.toFloat() / usable.size
        }

        @Synchronized
        fun summary(): String {
            val r = ratio() ?: return "aucune commande mesurée"
            return "%d commande(s) · rapport mesuré/commandé %.2f · écart moyen %.1f° · sens respecté %.0f %%".format(
                count(), r, meanAbsErrorDeg() ?: 0f, (signAgreement() ?: 0f) * 100f,
            )
        }
    }
}
