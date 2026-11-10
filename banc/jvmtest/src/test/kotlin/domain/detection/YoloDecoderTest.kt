package com.surfcam.app.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloDecoderTest {

    /** Sortie réelle du modèle sur une image de la vidéo de test (ancres creuses), avec le résultat attendu. */
    @Test
    fun decodesARealModelOutputLikeTheReferenceImplementation() {
        val lines = javaClass.getResourceAsStream("/yolo_fixture.txt")!!.bufferedReader().readLines()
        val (anchors, inW, inH, srcW, srcH) = lines[0].split(" ").map { it.toInt() }
        val sparse = lines[1].toInt()
        val output = FloatArray(84 * anchors)
        for (i in 0 until sparse) {
            val p = lines[2 + i].split(" ")
            val a = p[0].toInt()
            for (row in 0 until 4) output[row * anchors + a] = p[1 + row].toFloat()
            output[4 * anchors + a] = p[5].toFloat() // score de la classe personne
        }
        val expectedCount = lines[2 + sparse].toInt()
        val expected = (0 until expectedCount).map { lines[3 + sparse + it].split(" ").map(String::toFloat) }

        val detections = YoloDecoder().decode(output, anchors, Letterbox(srcW, srcH, inW, inH), 0L)

        assertEquals(expectedCount, detections.size)
        for ((d, e) in detections.zip(expected)) {
            assertEquals(e[0], d.confidence, 0.01f)
            assertEquals(e[1], d.box.left, 1.5f)
            assertEquals(e[2], d.box.top, 1.5f)
            assertEquals(e[3], d.box.right, 1.5f)
            assertEquals(e[4], d.box.bottom, 1.5f)
        }
    }

    @Test
    fun duplicateBoxesAreMerged() {
        val anchors = 3
        val out = FloatArray(84 * anchors)
        // Trois ancres sur le même surfeur, avec des scores différents.
        val cx = floatArrayOf(100f, 102f, 99f)
        for (a in 0 until anchors) {
            out[a] = cx[a]; out[anchors + a] = 100f; out[2 * anchors + a] = 20f; out[3 * anchors + a] = 40f
            out[4 * anchors + a] = floatArrayOf(0.9f, 0.7f, 0.6f)[a]
        }
        val result = YoloDecoder().decode(out, anchors, Letterbox(640, 640, 640, 640), 0L)
        assertEquals(1, result.size)
        assertEquals(0.9f, result.single().confidence, 1e-6f)
    }

    @Test
    fun lowConfidenceIsDropped() {
        val out = FloatArray(84 * 2)
        out[4 * 2] = 0.1f
        assertTrue(YoloDecoder().decode(out, 2, Letterbox(640, 640, 640, 640), 0L).isEmpty())
    }

    @Test
    fun letterboxMapsBackToSourcePixels() {
        val lb = Letterbox(1920, 1080, 1280, 736)
        assertEquals(1280, lb.scaledWidth)
        assertEquals(8, lb.offsetY)
        assertEquals(960f, lb.toSourceX(640f), 0.5f)
        assertEquals(540f, lb.toSourceY(8 + 360f), 1f)
    }
}
