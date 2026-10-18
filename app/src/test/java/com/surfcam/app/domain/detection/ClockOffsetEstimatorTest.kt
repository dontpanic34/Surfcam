package com.surfcam.app.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockOffsetEstimatorTest {

    @Test
    fun noMeasureMeansNoOffset() {
        assertEquals(1_000L, ClockOffsetEstimator().toEncodedClock(1_000L))
    }

    @Test
    fun recoversALargeConstantOffsetBetweenTheTwoClocks() {
        // L'horloge d'analyse a 3 h d'avance sur celle de la vidéo ; la vidéo a 40 ms de retard de traitement.
        val threeHours = 3L * 3600 * 1_000_000
        val estimator = ClockOffsetEstimator()
        for (i in 0 until 30) {
            val capture = 500_000_000L + i * 200_000L
            estimator.add(capture + threeHours, capture - 40_000L)
        }
        // Un décollage vu à t (horloge d'analyse) doit retomber sur t - 3 h (+40 ms de retard mesuré), pas au présent.
        val takeoffAnalysis = 520_000_000L + threeHours
        assertEquals((520_000_000L - 40_000L).toDouble(), estimator.toEncodedClock(takeoffAnalysis).toDouble(), 1_000.0)
    }

    @Test
    fun aFewSlowFramesDoNotShiftTheEstimate() {
        val estimator = ClockOffsetEstimator()
        for (i in 0 until 21) {
            val delay = if (i % 7 == 0) 600_000L else 30_000L // l'encodeur prend parfois du retard
            estimator.add(1_000_000_000L + i * 200_000L, 1_000_000_000L + i * 200_000L - delay)
        }
        assertEquals(30_000.0, estimator.offsetUs.toDouble(), 5_000.0)
    }
}
