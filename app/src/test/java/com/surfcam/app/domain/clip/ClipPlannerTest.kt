package com.surfcam.app.domain.clip

import com.surfcam.app.domain.tracking.WaveEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPlannerTest {

    private val s = 1_000_000L

    @Test
    fun aWaveGivesOneClipWithMarginsBeforeAndAfter() {
        val planner = ClipPlanner()
        assertEquals(listOf(ClipAction.Start(95 * s)), planner.onWave(WaveEvent.Started(100 * s, 1), 101 * s))
        assertTrue(planner.onWave(WaveEvent.Ended(100 * s, 110 * s, 1), 112 * s).isEmpty())
        assertTrue(planner.onTick(112 * s).isEmpty())
        assertEquals(listOf(ClipAction.Stop(113 * s)), planner.onTick(113 * s))
        assertTrue(!planner.isRecording)
    }

    @Test
    fun aNewWaveBeforeTheEndMarginExtendsTheSameClip() {
        val planner = ClipPlanner()
        planner.onWave(WaveEvent.Started(100 * s, 1), 101 * s)
        planner.onWave(WaveEvent.Ended(100 * s, 104 * s, 1), 105 * s)
        // Repart 1,6 s plus tard, pendant la marge de 3 s : pas de second clip.
        assertTrue(planner.onWave(WaveEvent.Started((105.6 * s).toLong(), 1), 106 * s).isEmpty())
        assertTrue(planner.onTick(108 * s).isEmpty()) // l'ancienne fin (107 s) ne doit plus couper le clip
        planner.onWave(WaveEvent.Ended(105 * s, 110 * s, 1), 111 * s)
        assertEquals(listOf(ClipAction.Stop(113 * s)), planner.onTick(113 * s))
    }

    @Test
    fun aCancelledWaveDiscardsItsClip() {
        val planner = ClipPlanner()
        planner.onWave(WaveEvent.Started(100 * s, 1), 101 * s)
        assertEquals(listOf(ClipAction.Discard), planner.onWave(WaveEvent.Cancelled(100 * s, 1), 103 * s))
        assertTrue(!planner.isRecording)
    }

    @Test
    fun aCancelledExtensionKeepsThePreviousClipAndItsEnd() {
        val planner = ClipPlanner()
        planner.onWave(WaveEvent.Started(100 * s, 1), 101 * s)
        planner.onWave(WaveEvent.Ended(100 * s, 110 * s, 1), 111 * s) // fin prévue à 113 s
        planner.onWave(WaveEvent.Started(111 * s, 2), 112 * s)
        assertTrue(planner.onWave(WaveEvent.Cancelled(111 * s, 2), (113.5 * s).toLong()).let { it.isNotEmpty() })
        assertTrue(!planner.isRecording)
    }

    @Test
    fun twoWavesFarApartGiveTwoClips() {
        val planner = ClipPlanner()
        planner.onWave(WaveEvent.Started(100 * s, 1), 101 * s)
        planner.onWave(WaveEvent.Ended(100 * s, 106 * s, 1), 107 * s)
        planner.onTick(110 * s)
        val second = planner.onWave(WaveEvent.Started(200 * s, 2), 201 * s)
        assertEquals(listOf(ClipAction.Start(195 * s)), second)
    }
}
