package com.surfcam.app.domain.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowControllerTest {
    @Test
    fun `au centre on ne bouge pas`() {
        assertEquals(0f, FollowController.desiredSpeedDegPerSec(0.8f), 0f)
        assertEquals(0f, FollowController.desiredSpeedDegPerSec(-1.4f), 0f)
    }

    @Test
    fun `la vitesse suit le signe de l'ecart et part de zero`() {
        val right = FollowController.desiredSpeedDegPerSec(5f)
        val left = FollowController.desiredSpeedDegPerSec(-5f)
        assertTrue(right > 0f && left < 0f)
        assertEquals(right, -left, 0.0001f)
        assertEquals(4.2f, right, 0.01f) // (5 − 1,5) × 1,2
    }

    @Test
    fun `la vitesse est plafonnee`() {
        assertEquals(25f, FollowController.desiredSpeedDegPerSec(90f), 0f)
        assertEquals(-25f, FollowController.desiredSpeedDegPerSec(-90f), 0f)
    }

    @Test
    fun `on suit celui qui surfe de preference`() {
        val a = FollowBox(0.2f, 0.10f, false)
        val b = FollowBox(0.8f, 0.05f, true)
        assertEquals(b, FollowController.chooseTarget(listOf(a, b), 0.2f))
    }

    @Test
    fun `sans surfeur en vague on garde la continuite puis le plus grand`() {
        val a = FollowBox(0.2f, 0.10f, false)
        val b = FollowBox(0.8f, 0.05f, false)
        assertEquals(b, FollowController.chooseTarget(listOf(a, b), 0.75f))
        assertEquals(a, FollowController.chooseTarget(listOf(a, b), null))
        assertNull(FollowController.chooseTarget(emptyList(), 0.5f))
    }

    @Test
    fun `champ de vision horizontal`() {
        // Focale de 920 px sur 1280 px de large : environ 70 degrés.
        assertEquals(70.0, FollowController.horizontalFovDeg(920f, 1280).toDouble(), 1.0)
        // ×5 : focale 4600 px, environ 15,8 degrés.
        assertEquals(15.8, FollowController.horizontalFovDeg(4600f, 1280).toDouble(), 0.3)
    }

    @Test
    fun `conversion en unites du protocole avec calibrage et inversion`() {
        assertEquals(500, FollowController.toProtocolUnits(10f, 0.02f, false))
        assertEquals(-500, FollowController.toProtocolUnits(10f, 0.02f, true))
        assertEquals(0, FollowController.toProtocolUnits(10f, 0f, false))
        assertEquals(3000, FollowController.toProtocolUnits(500f, 0.02f, false))
    }
}
