package com.surfcam.app.domain.buffer

import com.surfcam.app.domain.model.EncodedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedRingBufferTest {

    private val second = 1_000_000L

    // 10 images par seconde, une image clé par seconde.
    private fun fill(buffer: EncodedRingBuffer, seconds: Int) {
        for (i in 0 until seconds * 10) {
            buffer.add(EncodedSample(ByteArray(100), i * second / 10, i % 10 == 0))
        }
    }

    @Test
    fun keepsAtLeastTheWindowAndStartsOnAKeyFrame() {
        val buffer = EncodedRingBuffer(8 * second)
        fill(buffer, 30)

        val snap = buffer.snapshotFrom(0)
        assertTrue(snap.first().isKeyFrame)
        val span = snap.last().ptsUs - snap.first().ptsUs
        assertTrue("span=$span", span >= 8 * second)
        assertTrue("span=$span", span <= 9 * second)
    }

    @Test
    fun memoryStaysBounded() {
        val buffer = EncodedRingBuffer(8 * second)
        fill(buffer, 600)
        assertTrue(buffer.sizeBytes() <= 100L * 10 * 10)
    }

    @Test
    fun snapshotStartsOnTheKeyFrameBeforeRequestedTime() {
        val buffer = EncodedRingBuffer(8 * second)
        fill(buffer, 30)
        val newest = buffer.newestPtsUs()!!

        val snap = buffer.snapshotFrom(newest - 5 * second)
        assertTrue(snap.first().isKeyFrame)
        assertTrue(snap.first().ptsUs <= newest - 5 * second)
        assertTrue(newest - snap.first().ptsUs < 6 * second + second / 10)
        assertEquals(newest, snap.last().ptsUs)
    }

    @Test
    fun snapshotOfEmptyBufferIsEmpty() {
        assertTrue(EncodedRingBuffer(8 * second).snapshotFrom(0).isEmpty())
    }
}
