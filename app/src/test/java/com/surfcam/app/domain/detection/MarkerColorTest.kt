package com.surfcam.app.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerColorTest {
    @Test
    fun `les trois manchons fluo sont reconnus chacun dans leur couleur seulement`() {
        val yellow = intArrayOf(0xDF, 0xFF, 0x3B) // jaune fluo
        val green = intArrayOf(0xA9, 0xFF, 0x45) // vert fluo
        val orange = intArrayOf(0xFF, 0x7A, 0x2A) // orange fluo
        assertTrue(MarkerColor.YELLOW.matches(yellow[0], yellow[1], yellow[2]))
        assertFalse(MarkerColor.GREEN.matches(yellow[0], yellow[1], yellow[2]))
        assertTrue(MarkerColor.GREEN.matches(green[0], green[1], green[2]))
        assertFalse(MarkerColor.YELLOW.matches(green[0], green[1], green[2]))
        assertTrue(MarkerColor.ORANGE.matches(orange[0], orange[1], orange[2]))
        assertFalse(MarkerColor.YELLOW.matches(orange[0], orange[1], orange[2]))
    }

    @Test
    fun `le sable, la peau, la mer, le ciel et le neoprene ne sont pas reconnus`() {
        val others = listOf(
            intArrayOf(0xD8, 0xC0, 0x90), // sable sec
            intArrayOf(0xC8, 0x96, 0x78), // peau bronzée
            intArrayOf(0x30, 0x70, 0x90), // mer
            intArrayOf(0x90, 0xB8, 0xE0), // ciel
            intArrayOf(0x20, 0x20, 0x20), // néoprène
            intArrayOf(0xF0, 0xF0, 0xF0), // écume
        )
        for (c in others) for (m in MarkerColor.entries) assertFalse("${m.name} ${c.toList()}", m.matches(c[0], c[1], c[2]))
    }

    @Test
    fun `le port du manchon demande assez de pixels`() {
        assertFalse(MarkerColor.isWorn(2, 500))
        assertTrue(MarkerColor.isWorn(6, 800))
        assertFalse(MarkerColor.isWorn(6, 50_000))
        assertEquals(null, MarkerColor.fromName("inconnu"))
    }
}
