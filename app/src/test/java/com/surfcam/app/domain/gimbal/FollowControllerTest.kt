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

    @Test
    fun `le surfeur au manchon est suivi avant tous les autres, meme plus petit`() {
        val boxes = listOf(FollowBox(0.3f, 0.30f, riding = true), FollowBox(0.7f, 0.10f, riding = false, marked = true))
        assertEquals(0.7f, FollowController.chooseTarget(boxes, null)!!.centerX, 0f)
    }

    @Test
    fun `le pas d'angle est nul dans la zone morte, proportionnel au dela, et plafonne`() {
        assertEquals(0f, FollowController.stepDegrees(2f), 0f)
        assertEquals(4f, FollowController.stepDegrees(5f), 0.001f) // 5 × 0,8
        assertEquals(-4f, FollowController.stepDegrees(-5f), 0.001f)
        assertEquals(15f, FollowController.stepDegrees(60f), 0f)
        assertEquals(-15f, FollowController.stepDegrees(-60f), 0f)
    }

    @Test
    fun `la duree du pivotement croit avec le pas, entre 0,5 s et 1,2 s`() {
        assertEquals(0.5f, FollowController.moveSeconds(0f), 0f)
        assertTrue(FollowController.moveSeconds(10f) > FollowController.moveSeconds(4f))
        assertEquals(0.875f, FollowController.moveSeconds(15f), 0.001f)
        assertEquals(1.2f, FollowController.moveSeconds(90f), 0f)
    }

    @Test
    fun `l'anticipation avance l'ordre dans le sens de la marche du surfeur`() {
        // Au centre mais qui file à 6 °/s vers la droite : on pivote déjà vers la droite.
        val ahead = FollowController.stepWithLead(0f, 6f, 1.2f)
        assertTrue(ahead > 0f)
        assertEquals(0f, FollowController.stepWithLead(0f, 0.5f, 1.2f), 0f) // bruit de mesure
        assertTrue(FollowController.stepWithLead(0f, -6f, 1.2f) < 0f)
    }

    @Test
    fun `les styles vont du plus doux au plus reactif`() {
        val smooth = FollowController.FollowStyle.SMOOTH
        val fast = FollowController.FollowStyle.REACTIVE
        assertTrue(smooth.step.deadbandDeg > fast.step.deadbandDeg)
        assertTrue(smooth.step.gain < fast.step.gain)
        assertTrue(smooth.step.maxStepDeg < fast.step.maxStepDeg)
        assertTrue(smooth.settleSeconds > fast.settleSeconds)
        assertTrue(smooth.moveScale > fast.moveScale)
        assertEquals(FollowController.FollowStyle.NORMAL, FollowController.FollowStyle.fromName("inconnu"))
    }

    @Test
    fun `la zone de surf borne la position du gimbal`() {
        assertEquals(10f, FollowController.clampToZone(0f, 10f, 45f), 0f)
        assertEquals(5f, FollowController.clampToZone(40f, 10f, 45f), 0f)
        assertEquals(0f, FollowController.clampToZone(45f, 10f, 45f), 0f)
        assertEquals(-10f, FollowController.clampToZone(40f, -10f, 45f), 0f) // revenir vers le centre reste permis
    }

    @Test
    fun `le gimbal ne bouge que près du bord de l'image`() {
        // Champ de 15° : le centre libre va jusqu'à ±4,5° (30 %).
        assertEquals(0f, FollowController.edgeStep(3f, 0f, 1f, 15f, 0.30f, 15f), 0f)
        assertEquals(0f, FollowController.edgeStep(-4f, 0f, 1f, 15f, 0.30f, 15f), 0f)
        assertEquals(6f, FollowController.edgeStep(6f, 0f, 1f, 15f, 0.30f, 15f), 0f)
        // Un surfeur qui file vers le bord déclenche le pivotement avant d'y arriver.
        assertEquals(7f, FollowController.edgeStep(3f, 4f, 1f, 15f, 0.30f, 15f), 0f)
        assertEquals(15f, FollowController.edgeStep(40f, 0f, 1f, 15f, 0.30f, 15f), 0f)
    }

    @Test
    fun `jamais de pas a l'oppose du surfeur ni sur une vitesse aberrante`() {
        // Surfeur à gauche (-3°) mais vitesse de +30°/s : pas de saut vers la droite.
        assertEquals(0f, FollowController.edgeStep(-3f, 30f, 1f, 15f, 0.30f, 15f), 0f)
        // Surfeur quasi centré, vitesse énorme : la vitesse est bornée, pas de grand pas.
        assertEquals(0f, FollowController.edgeStep(1f, 30f, 1f, 15f, 0.30f, 15f), 0f)
    }
}
