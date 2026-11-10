package com.surfcam.app.domain.analysis

import com.surfcam.app.domain.tracking.BoundingBox
import com.surfcam.app.domain.tracking.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRangeFinderTest {
    private val second = 1_000_000L
    private val frameUs = 333_333L // environ 3 images par seconde, comme l'analyse

    private fun surfer(x: Float, tsUs: Long): Detection {
        val h = 60f
        val w = 50f
        return Detection(BoundingBox(x - w / 2, 300f - h / 2, x + w / 2, 300f + h / 2), 0.8f, tsUs)
    }

    private fun run(seconds: Float, position: (Float) -> Float?): List<ClipRange> {
        val finder = ClipRangeFinder()
        val ranges = mutableListOf<ClipRange>()
        var t = 0L
        while (t <= (seconds * second).toLong()) {
            val x = position(t / second.toFloat())
            ranges += finder.onFrame(if (x == null) emptyList() else listOf(surfer(x, t)), t)
            t += frameUs
        }
        ranges += finder.finish((seconds * second).toLong())
        return ranges
    }

    @Test
    fun `une vague donne un seul passage avec cinq secondes avant et trois apres`() {
        // Immobile 10 s, file à 3 corps par seconde de 10 s à 16 s, puis immobile.
        val ranges = run(30f) { t ->
            when {
                t < 10f -> 100f
                t < 16f -> 100f + (t - 10f) * 180f
                else -> 100f + 6f * 180f
            }
        }
        assertEquals(1, ranges.size)
        val r = ranges.first()
        assertTrue("début ${r.startUs}", r.startUs in 3 * second..7 * second)
        assertTrue("fin ${r.endUs}", r.endUs in 17 * second..24 * second)
    }

    @Test
    fun `personne qui bouge pas, aucun passage`() {
        assertTrue(run(30f) { 100f }.isEmpty())
    }

    @Test
    fun `une vague encore en cours a la fin de la video est fermee a la fin`() {
        val ranges = run(14f) { t -> if (t < 8f) 100f else 100f + (t - 8f) * 180f }
        assertEquals(1, ranges.size)
        assertEquals(14 * second, ranges.first().endUs)
    }

    @Test
    fun `deux vagues separees donnent deux passages`() {
        val ranges = run(60f) { t ->
            when {
                t < 8f -> 100f
                t < 14f -> 100f + (t - 8f) * 180f
                t < 40f -> 100f
                t < 46f -> 100f + (t - 40f) * 180f
                else -> 100f
            }
        }
        assertEquals(2, ranges.size)
        assertTrue(ranges[0].endUs < ranges[1].startUs)
    }
}
