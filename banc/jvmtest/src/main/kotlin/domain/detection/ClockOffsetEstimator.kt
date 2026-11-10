package com.surfcam.app.domain.detection

/**
 * Estime l'écart entre deux horloges (celle des images d'analyse et celle de la vidéo encodée) à partir de paires
 * de mesures prises au même moment. La médiane des dernières mesures ignore les retards ponctuels (encodeur occupé).
 */
class ClockOffsetEstimator(private val window: Int = 21) {

    private val samples = ArrayDeque<Long>()

    /** Écart estimé en microsecondes : horloge d'analyse moins horloge vidéo. 0 tant qu'aucune mesure n'a été faite. */
    var offsetUs: Long = 0
        private set

    fun add(analysisUs: Long, encodedUs: Long) {
        samples.addLast(analysisUs - encodedUs)
        if (samples.size > window) samples.removeFirst()
        offsetUs = samples.sorted()[samples.size / 2]
    }

    /** Convertit une date de l'horloge d'analyse dans celle de la vidéo. */
    fun toEncodedClock(analysisUs: Long): Long = analysisUs - offsetUs
}
