package com.surfcam.app.domain.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveDetectorTest {

    private val second = 1_000_000L
    private val frameUs = 100_000L // 10 images par seconde

    private fun surfer(
        x: Float,
        y: Float = 300f,
        height: Float = 60f,
        tsUs: Long,
        confidence: Float = 0.8f,
        width: Float = height / 2,
    ) = Detection(BoundingBox(x - width / 2, y - height / 2, x + width / 2, y + height / 2), confidence, tsUs)

    /** Rejoue un scénario : [position] donne l'abscisse du surfeur (en pixels) à chaque seconde, null = absent. */
    private fun replay(
        seconds: Float,
        height: Float = 60f,
        width: Float = height / 2,
        y: Float = 300f,
        trackerConfig: TrackerConfig = TrackerConfig(),
        position: (Float) -> Float?,
    ): List<Pair<Long, WaveEvent>> {
        val tracker = SurferTracker(trackerConfig)
        val detector = WaveDetector()
        val events = mutableListOf<Pair<Long, WaveEvent>>()
        var t = 0L
        while (t <= (seconds * second).toLong()) {
            val x = position(t / second.toFloat())
            val detections = if (x == null) emptyList() else listOf(surfer(x, y = y, height = height, width = width, tsUs = t))
            detector.onFrame(tracker.update(detections, t), t)?.let { events += t to it }
            t += frameUs
        }
        return events
    }

    @Test
    fun waveIsDetectedWhenSurferTakesOff() {
        // Attend 3 s, puis file à 2 corps/s (120 px/s pour un surfeur de 60 px) pendant 5 s, puis s'arrête.
        val events = replay(14f) { t ->
            when {
                t < 3f -> 100f
                t < 8f -> 100f + (t - 3f) * 120f
                else -> 700f
            }
        }
        val started = events.map { it.second }.filterIsInstance<WaveEvent.Started>().single()
        val ended = events.map { it.second }.filterIsInstance<WaveEvent.Ended>().single()
        assertEquals(3 * second.toDouble(), started.takeoffUs.toDouble(), 0.7 * second)
        assertEquals(8 * second.toDouble(), ended.endUs.toDouble(), 0.7 * second)
        assertTrue(ended.endUs - ended.takeoffUs > 4 * second)
    }

    @Test
    fun startIsReportedQuicklyEnoughToKeepThePreRoll() {
        val events = replay(8f) { t -> if (t < 3f) 100f else 100f + (t - 3f) * 120f }
        val (reportedAt, event) = events.first()
        assertTrue(event is WaveEvent.Started)
        // Détecté moins de 2 s après le vrai départ : le tampon de 8 s couvre largement ce retard.
        assertTrue("détecté à ${reportedAt / second.toFloat()} s", reportedAt - 3 * second < 2 * second)
    }

    @Test
    fun paddlingSlowlyNeverTriggers() {
        // 0,3 corps/s pendant 30 s : de la rame.
        val events = replay(30f) { t -> 100f + t * 18f }
        assertTrue(events.isEmpty())
    }

    @Test
    fun waitingInTheWaterNeverTriggers() {
        val events = replay(30f) { t -> 100f + (t % 2f) * 5f }
        assertTrue(events.isEmpty())
    }

    @Test
    fun shortBurstIsCancelledNotKept() {
        // Une seconde et demie rapide puis arrêt : trop court pour une vague.
        val events = replay(12f) { t ->
            when {
                t < 3f -> 100f
                t < 4.5f -> 100f + (t - 3f) * 120f
                else -> 280f
            }
        }.map { it.second }
        assertTrue(events.any { it is WaveEvent.Cancelled })
        assertTrue(events.none { it is WaveEvent.Ended })
    }

    @Test
    fun waveEndsWhenSurferDisappears() {
        // Il file puis tombe dans l'écume : plus de détection.
        val events = replay(14f) { t ->
            when {
                t < 2f -> 100f
                t < 7f -> 100f + (t - 2f) * 120f
                else -> null
            }
        }.map { it.second }
        val ended = events.filterIsInstance<WaveEvent.Ended>().single()
        assertEquals(7 * second.toDouble(), ended.endUs.toDouble(), 1.0 * second)
    }

    @Test
    fun detectionDoesNotDependOnDistanceToTheSurfer() {
        // Même mouvement en corps/s, mais surfeur 4 fois plus petit : le résultat est identique.
        fun run(height: Float) = replay(12f, height = height) { t ->
            if (t < 3f) 100f else 100f + (t - 3f) * 2f * height
        }.map { it.second }.filterIsInstance<WaveEvent.Started>().size

        assertEquals(1, run(200f))
        assertEquals(1, run(50f))
        assertEquals(1, run(25f))
    }

    @Test
    fun surferLyingOnTheBoardNeverTriggersEvenIfFast() {
        // Allongé (plus large que haut) qui file à 2 longueurs de corps/s : il rame, il ne surfe pas.
        val events = replay(12f, height = 15f, width = 30f) { t ->
            if (t < 3f) 100f else 100f + (t - 3f) * 60f
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun personOnTheBeachCutByTheBottomEdgeIsIgnored() {
        // Une personne de 65 px dont la boîte touche le bas de l'image (1080) marche vite sur le sable.
        val config = TrackerConfig(frameHeight = 1080)
        val events = replay(12f, height = 65f, width = 65f, y = 1080f - 32f, trackerConfig = config) { t ->
            100f + t * 150f
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun fastRiderKeepsOneTrackAtLowFrameRateEvenWithMissedFrames() {
        // Analyse à 5 images/s, surfeur à 4 longueurs de corps/s (0,8 longueur par image), une image ratée sur 5.
        val tracker = SurferTracker()
        val ids = mutableSetOf<Int>()
        for (i in 0 until 40) {
            val t = i * 200_000L
            if (i % 5 == 3) { tracker.update(emptyList(), t); continue }
            val tracks = tracker.update(listOf(surfer(100f + i * 48f, height = 60f, tsUs = t)), t)
            ids += tracks.map { it.id }
        }
        assertEquals(1, ids.size)
    }

    @Test
    fun surferInManeuverWiderThanTallStillCounts() {
        // Boîte presque carrée (0,95) pendant la manœuvre : ne doit pas faire perdre le départ.
        val events = replay(12f, height = 57f, width = 60f) { t ->
            if (t < 3f) 100f else 100f + (t - 3f) * 3f * 60f
        }
        assertTrue(events.any { it.second is WaveEvent.Started })
    }

    @Test
    fun trackerKeepsTheSameIdAndDropsLostTracks() {
        val tracker = SurferTracker()
        var id = -1
        for (i in 0 until 20) {
            val t = i * frameUs
            val tracks = tracker.update(listOf(surfer(100f + i * 3f, tsUs = t)), t)
            assertEquals(1, tracks.size)
            if (id < 0) id = tracks.single().id
            assertEquals(id, tracks.single().id)
        }
        // Absent plus de 2 s : le suivi est abandonné.
        val tracks = tracker.update(emptyList(), 20 * frameUs + 3 * second)
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun twoSurfersKeepSeparateIds() {
        val tracker = SurferTracker()
        val ids = mutableSetOf<Int>()
        for (i in 0 until 20) {
            val t = i * frameUs
            val tracks = tracker.update(
                listOf(surfer(100f, y = 200f, tsUs = t), surfer(900f - i * 5f, y = 500f, tsUs = t)),
                t,
            )
            ids += tracks.map { it.id }
            assertEquals(2, tracks.size)
        }
        assertEquals(2, ids.size)
        assertNotNull(ids.firstOrNull())
        assertNull(null)
    }
}
