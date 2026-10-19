package com.surfcam.app.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceEstimatorTest {
    @Test
    fun `une personne de 35 pixels avec 1000 pixels de focale est a 50 m`() {
        assertEquals(50.0, DistanceEstimator.distanceMeters(1000f, 35f)!!.toDouble(), 0.01)
    }

    @Test
    fun `sans hauteur exploitable pas de distance`() {
        assertNull(DistanceEstimator.distanceMeters(1000f, 1f))
        assertNull(DistanceEstimator.distanceMeters(0f, 40f))
    }

    @Test
    fun `arrondi a 5 m puis 10 m`() {
        assertEquals(95, DistanceEstimator.rounded(96.4f))
        assertEquals(110, DistanceEstimator.rounded(112f))
        assertEquals(5, DistanceEstimator.rounded(1f))
    }

    @Test
    fun `zoom conseille intermediaire x2`() {
        // 40 px sur 720 à ×1 : zoom voulu 2,7 → ×2.
        assertEquals(2.0, DistanceEstimator.recommendedZoom(1f, 40f, 720f, 1f, 5f).toDouble(), 0.01)
    }

    @Test
    fun `zoom conseille pour un surfeur lointain`() {
        // 16 px sur 720 à ×1 : il en faut 108 (15 %), soit un zoom de 6,75 ramené au cran optique ×5.
        assertEquals(5.0, DistanceEstimator.recommendedZoom(1f, 16f, 720f, 1f, 5f).toDouble(), 0.01)
        // Déjà assez grand : on ne zoome pas davantage que le minimum.
        assertEquals(1.0, DistanceEstimator.recommendedZoom(1f, 200f, 720f, 1f, 5f).toDouble(), 0.01)
    }
}
