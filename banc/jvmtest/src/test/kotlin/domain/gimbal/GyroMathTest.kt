package com.surfcam.app.domain.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GyroMathTest {
    @Test
    fun `a plat la rotation autour de la verticale est la composante z`() {
        assertEquals(0.5f, GyroMath.rateAboutUp(0f, 0f, 0.5f, 0f, 0f, 9.81f), 1e-4f)
    }

    @Test
    fun `en paysage debout la verticale est portee par l'axe x`() {
        // Téléphone tenu debout en paysage : la gravité se lit sur x (vecteur vers le haut).
        assertEquals(-0.3f, GyroMath.rateAboutUp(-0.3f, 0.02f, 0.01f, 9.81f, 0f, 0f), 0.02f)
    }

    @Test
    fun `gravite inexploitable donne zero`() {
        assertEquals(0f, GyroMath.rateAboutUp(1f, 1f, 1f, 0f, 0f, 0.1f), 0f)
    }

    @Test
    fun `un gimbal fidele donne un rapport de un`() {
        val a = GyroMath.Accuracy()
        a.add(6f, 6f); a.add(-4f, -4f)
        assertEquals(1f, a.ratio()!!, 1e-4f)
        assertEquals(0f, a.meanAbsErrorDeg()!!, 1e-4f)
        assertEquals(1f, a.signAgreement()!!, 0f)
    }

    @Test
    fun `un gimbal qui ne fait que la moitie ou tourne a l'envers se voit`() {
        val half = GyroMath.Accuracy(); half.add(8f, 4f); half.add(-6f, -3f)
        assertEquals(0.5f, half.ratio()!!, 1e-4f)
        val inverted = GyroMath.Accuracy(); inverted.add(8f, -8f)
        assertEquals(-1f, inverted.ratio()!!, 1e-4f)
        assertEquals(0f, inverted.signAgreement()!!, 0f)
    }

    @Test
    fun `sans mesure il n'y a pas de statistique`() {
        val a = GyroMath.Accuracy()
        assertNull(a.ratio())
        assertNotNull(a.summary())
    }
}
