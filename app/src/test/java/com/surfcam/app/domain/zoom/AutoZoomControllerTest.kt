package com.surfcam.app.domain.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoZoomControllerTest {
    private fun feed(c: AutoZoomController, startMs: Long, count: Int, height: Float?, zoom: Float, busy: Boolean = false): Float? {
        var last: Float? = null
        for (i in 0 until count) {
            c.onFrame(startMs + i * 300L, height, 720f, zoom, 1f, 10f, busy)?.let { last = it }
        }
        return last
    }

    @Test
    fun `surfeurs minuscules a x1 passe au teleobjectif x5`() {
        val c = AutoZoomController()
        assertEquals(5f, feed(c, 100_000, 10, 16f, 1f)!!, 0.01f)
    }

    @Test
    fun `surfeurs gros a x5 revient en grand champ`() {
        val c = AutoZoomController()
        // 150 px à ×5 → 30 px à ×1 : zoom voulu 3,6 → pas question de rester à ×5 ? 3,6 est optique donc 5.
        assertNull(feed(c, 100_000, 10, 150f, 5f))
        // 400 px à ×5 → 80 px à ×1 : zoom voulu 1,35 → 1,5.
        assertEquals(1.5f, feed(AutoZoomController(), 100_000, 10, 400f, 5f)!!, 0.01f)
    }

    @Test
    fun `jamais pendant une vague`() {
        val c = AutoZoomController()
        assertNull(feed(c, 100_000, 20, 16f, 1f, busy = true))
    }

    @Test
    fun `pas deux changements rapproches`() {
        val c = AutoZoomController()
        assertEquals(5f, feed(c, 100_000, 10, 16f, 1f)!!, 0.01f)
        // Les surfeurs apparaissent maintenant énormes, mais on vient de changer.
        assertNull(feed(c, 103_000, 10, 500f, 5f))
    }

    @Test
    fun `plus de surfeur pendant une minute retour au grand champ`() {
        val c = AutoZoomController()
        feed(c, 100_000, 6, 100f, 5f) // vu à ×5, taille correcte : pas de changement
        assertNull(c.onFrame(130_000, null, 720f, 5f, 1f, 10f, false))
        assertEquals(1f, c.onFrame(170_000, null, 720f, 5f, 1f, 10f, false)!!, 0.01f)
    }

    @Test
    fun `reglage manuel puis l auto se tait deux minutes`() {
        val c = AutoZoomController()
        c.onManualChange(100_000)
        assertNull(feed(c, 101_000, 20, 16f, 1f))
        assertEquals(5f, feed(c, 230_000, 10, 16f, 1f)!!, 0.01f)
    }
}
