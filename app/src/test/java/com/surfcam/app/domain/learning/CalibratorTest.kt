package com.surfcam.app.domain.learning

import com.surfcam.app.domain.tracking.RideStats
import com.surfcam.app.domain.tracking.WaveConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorTest {

    private fun ride(speed: Float, aspect: Float, seconds: Float = 6f, conf: Float = 0.7f, good: Boolean) =
        LabeledRide(RideStats((seconds * 1_000_000).toLong(), speed, aspect, conf, 50f), good)

    @Test
    fun tooFewExamplesChangesNothing() {
        val r = Calibrator.calibrate(WaveConfig(), listOf(ride(2f, 1.5f, good = true), ride(1.1f, 1.0f, good = false)))
        assertEquals(WaveConfig(), r.config)
        assertTrue(r.note!!.contains("au moins"))
    }

    @Test
    fun noFalseAlarmMeansNothingToCorrect() {
        val examples = List(6) { ride(2f + it * 0.1f, 1.5f, good = true) }
        assertEquals(WaveConfig(), Calibrator.calibrate(WaveConfig(), examples).config)
    }

    @Test
    fun falseAlarmsWithAWeakPostureRaiseTheMinimumPosture() {
        // Les vraies vagues sont franchement debout (1,4 à 1,8) ; les fausses alertes sont à la limite (0,95 à 1,05).
        val goods = listOf(1.4f, 1.5f, 1.6f, 1.8f, 1.5f).map { ride(2.2f, it, good = true) }
        val bads = listOf(0.95f, 1.0f, 1.05f).map { ride(2.2f, it, good = false) }
        val r = Calibrator.calibrate(WaveConfig(), goods + bads)

        assertTrue("seuil = ${r.config.minStandingAspect}", r.config.minStandingAspect > 1.0f)
        assertTrue(r.config.minStandingAspect < 1.4f)
        assertTrue(r.changes.any { it.startsWith("posture debout minimale") })
        // Toutes les bonnes vagues restent retenues, aucune fausse alerte ne passe.
        assertTrue(goods.all { Calibrator.passes(r.config, it.stats) })
        assertTrue(bads.none { Calibrator.passes(r.config, it.stats) })
    }

    @Test
    fun shortFalseAlarmsRaiseTheMinimumDuration() {
        val goods = listOf(7f, 9f, 6f, 8f).map { ride(2f, 1.5f, seconds = it, good = true) }
        val bads = listOf(3.1f, 3.4f, 3.6f).map { ride(2f, 1.5f, seconds = it, good = false) }
        val r = Calibrator.calibrate(WaveConfig(), goods + bads)
        assertTrue("durée = ${r.config.minRideUs}", r.config.minRideUs > 3_000_000L)
        assertTrue(goods.all { Calibrator.passes(r.config, it.stats) })
    }

    @Test
    fun aChangeIsCappedWithinSaneBounds() {
        val goods = List(4) { ride(2f, 1.5f, good = true) }
        val bads = List(4) { ride(1.0f, 0.95f, good = false) }
        val c = Calibrator.calibrate(WaveConfig(), goods + bads).config
        assertTrue(c.startSpeed in 0.8f..2.5f)
        assertTrue(c.minStandingAspect in 0.8f..1.6f)
    }
}
