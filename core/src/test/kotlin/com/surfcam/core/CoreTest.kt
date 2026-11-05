package com.surfcam.core

import com.surfcam.core.clip.ClipAction
import com.surfcam.core.clip.ClipPlanner
import com.surfcam.core.geometry.CameraModel
import com.surfcam.core.heading.GimbalOrder
import com.surfcam.core.heading.HeadingTimeline
import com.surfcam.core.model.Box
import com.surfcam.core.model.Detection
import com.surfcam.core.model.Frame
import com.surfcam.core.model.WorldProjection
import com.surfcam.core.ride.RideDetector
import com.surfcam.core.ride.RideEvent
import com.surfcam.core.tracking.Hungarian
import com.surfcam.core.tracking.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    private val tele = CameraModel(1280, 720, 16.4)

    @Test
    fun cameraRoundTrip() {
        for (x in listOf(0.0, 100.0, 640.0, 1000.0, 1280.0)) {
            assertEquals(x, tele.xForAzimuthOffset(tele.azimuthOffsetDeg(x)), 1e-6)
        }
        assertEquals(8.2, tele.azimuthOffsetDeg(1280.0), 1e-6)
        // ≈ 78 px par degré au centre : la valeur utilisée par l'appli actuelle.
        assertEquals(78.0, tele.pxPerDegAtCenter, 1.0)
        val wide = CameraModel(1280, 720, 70.0)
        assertEquals(wide.zoomedBy(5.0).hfovDeg, 15.9, 0.3)
    }

    @Test
    fun headingInterpolatesAndReconstructs() {
        val h = HeadingTimeline()
        h.add(0, 0.0)
        h.add(1_000_000, 10.0)
        assertEquals(5.0, h.at(500_000)!!, 1e-9)
        assertEquals(10.0, h.at(2_000_000)!!, 1e-9)
        // Gyroscope tous les 0,5 s, un ordre de +4,6° en 1,6 s à t = 1 s : le cap reconstitué suit la rampe entre deux relevés.
        val gyro = listOf(0L to 0.0, 500_000L to 0.0, 1_000_000L to 0.0, 1_500_000L to 1.4, 2_000_000L to 2.9, 2_500_000L to 4.3, 3_000_000L to 4.6, 3_500_000L to 4.6)
        val r = HeadingTimeline.reconstruct(gyro, listOf(GimbalOrder(1_000_000, 4.6, 1_600_000)))
        assertEquals(0.0, r.at(900_000)!!, 0.05)
        assertEquals(2.2, r.at(1_750_000)!!, 0.2)
        assertEquals(4.6, r.at(3_200_000)!!, 0.1)
    }

    @Test
    fun hungarianFindsGlobalOptimum() {
        // Au plus proche un par un, la ligne 0 prendrait la colonne 0 (1) et la ligne 1 la colonne 1 (10) : total 11.
        // L'optimum est 0→1 (2) et 1→0 (2) : total 4.
        val a = Hungarian.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 10.0)))
        assertEquals(1, a[0])
        assertEquals(0, a[1])
        val forbidden = Hungarian.solve(arrayOf(doubleArrayOf(Double.POSITIVE_INFINITY, 3.0), doubleArrayOf(Double.POSITIVE_INFINITY, 1.0)))
        assertEquals(-1, forbidden[0])
        assertEquals(1, forbidden[1])
    }

    private fun frame(tUs: Long, heading: Double, vararg boxes: Pair<Double, Double>, h: Double = 60.0, aspect: Double = 1.5): Frame =
        Frame(tUs, heading, 0.0, tele, 5.0, boxes.map { (x, y) -> Detection(Box(x - h / aspect / 2, y - h / 2, x + h / aspect / 2, y + h / 2), 0.8) })

    @Test
    fun gimbalStepDoesNotBreakTheTrack() {
        // Un surfeur immobile à l'azimut +2° ; le gimbal pivote de 0 à +6° en une seconde : à l'image il saute de ~470 px.
        val tracker = Tracker()
        var id: Int? = null
        for (i in 0..10) {
            val heading = (i * 0.6).coerceAtMost(6.0)
            val x = tele.xForAzimuthOffset(2.0 - heading)
            val tracks = tracker.update(WorldProjection.observeAll(frame(i * 500_000L, heading, x to 360.0)), i * 500_000L)
            assertEquals(1, tracks.size)
            if (id == null) id = tracks[0].id else assertEquals(id, tracks[0].id)
        }
    }

    @Test
    fun sizeMismatchBlocksIdentitySwap() {
        // Cas du clip « Vague 9 » : le surfeur en vague (grande boîte) disparaît une image ; une petite silhouette voisine
        // ne doit pas reprendre son identité.
        val tracker = Tracker()
        val t0 = tracker.update(WorldProjection.observeAll(frame(0, 0.0, 600.0 to 330.0, h = 70.0)), 0)
        val big = t0.single().id
        tracker.update(WorldProjection.observeAll(frame(500_000, 0.0, 580.0 to 332.0, h = 70.0)), 500_000)
        val after = tracker.update(WorldProjection.observeAll(frame(1_000_000, 0.0, 570.0 to 330.0, h = 15.0, aspect = 1.2)), 1_000_000)
        val small = after.first { it.lastSeenUs == 1_000_000L }
        assertTrue("la petite silhouette ne doit pas reprendre la piste du surfeur", small.id != big)
    }

    @Test
    fun sittingSurferDoesNotTriggerAndRiderDoes() {
        val tracker = Tracker()
        val rides = RideDetector()
        var started: RideEvent.Started? = null
        var ended: RideEvent.Ended? = null
        // Surfeur assis (monte et descend avec la houle) pendant 20 s, puis part sur une vague à ~3 corps/s pendant 8 s.
        var t = 0L
        while (t < 40_000_000) {
            val sec = t / 1e6
            val (az, el, aspect) = when {
                sec < 20 -> Triple(1.0, 0.15 * kotlin.math.sin(sec * 1.3), 1.15)
                sec < 28 -> Triple(1.0 + (sec - 20) * 2.3, 0.0, 1.6)
                else -> Triple(1.0 + 8 * 2.3, 0.0, 1.15)
            }
            val heading = if (sec < 21) 0.0 else (az - 1.0)
            val x = tele.xForAzimuthOffset(az - heading)
            val y = tele.yForElevationOffset(el)
            val tracks = tracker.update(WorldProjection.observeAll(frame(t, heading, x to y, h = 60.0, aspect = aspect)), t)
            // Une image sur sept ratée pendant la vague : la confirmation ne doit pas repartir de zéro.
            val missed = sec in 20.0..28.0 && (t / 500_000) % 7 == 3L
            val ev = rides.onFrame(if (missed) emptyList() else tracks, t)
            if (ev is RideEvent.Started) {
                assertNull("pas de déclenchement assis", if (sec < 20) ev else null)
                started = ev
            }
            if (ev is RideEvent.Ended) ended = ev
            t += 500_000
        }
        assertNotNull("la vague doit déclencher", started)
        assertTrue("départ estimé près de 20 s : ${started!!.takeoffUs}", started!!.takeoffUs in 19_000_000..22_000_000)
        assertNotNull(ended)
        assertTrue("vague gardée : ${ended!!.reason}", ended.kept)
    }

    @Test
    fun clipPlannerDiscardsRejectedRide() {
        val p = ClipPlanner()
        val s = p.onRide(RideEvent.Started(1, 10_000_000, 2.0, 1.4), 11_000_000)
        assertEquals(ClipAction.Start(5_000_000), s.single())
        val f = com.surfcam.core.ride.RideFeatures(1_000_000, 1.0, 1.0, 1.0, 1.0, 0.5, 3, false, 1.0)
        val e = p.onRide(RideEvent.Ended(1, 10_000_000, 11_000_000, f, false, "trop courte"), 12_000_000)
        assertTrue(e.single() is ClipAction.Discard)
    }
}
