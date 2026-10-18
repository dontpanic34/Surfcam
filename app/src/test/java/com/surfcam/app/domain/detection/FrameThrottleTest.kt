package com.surfcam.app.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameThrottleTest {

    @Test
    fun firstFrameIsAlwaysAcceptedWhateverItsTimestamp() {
        // Régression : un instant de départ à Long.MIN_VALUE faisait déborder la soustraction et refusait toutes les images.
        assertTrue(FrameThrottle(200_000).accept(123_456_789_012L))
        assertTrue(FrameThrottle(200_000).accept(0L))
    }

    @Test
    fun framesCloserThanTheIntervalAreSkipped() {
        val throttle = FrameThrottle(200_000)
        assertTrue(throttle.accept(1_000_000))
        assertFalse(throttle.accept(1_033_000))
        assertFalse(throttle.accept(1_199_999))
        assertTrue(throttle.accept(1_200_000))
    }

    @Test
    fun a60FpsStreamGivesFiveAnalysedFramesPerSecond() {
        val throttle = FrameThrottle(200_000)
        var accepted = 0
        for (i in 0 until 600) { // 10 s à 60 images/s
            if (throttle.accept(5_000_000_000L + i * 16_667L)) accepted++
        }
        assertEquals(50.0, accepted.toDouble(), 2.0)
    }
}
