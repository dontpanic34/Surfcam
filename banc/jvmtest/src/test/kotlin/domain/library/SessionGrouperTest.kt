package com.surfcam.app.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGrouperTest {
    private val min = 60_000L
    private fun part(key: String, atMin: Long, n: Int) = MediaInfo(key, atMin * min, 2 * min, MediaInfo.Kind.PART, n)
    private fun wave(key: String, atMin: Long) = MediaInfo(key, atMin * min, 20_000, MediaInfo.Kind.WAVE)
    private fun film(key: String, atMin: Long, durMin: Long) = MediaInfo(key, atMin * min, durMin * min, MediaInfo.Kind.FILM)

    @Test
    fun `les morceaux consecutifs forment une session et les vagues en sont les chapitres`() {
        val items = listOf(part("p1", 0, 1), part("p2", 2, 2), part("p3", 4, 3), wave("w1", 3), wave("w2", 4))
        val g = SessionGrouper.group(items)
        assertEquals(1, g.sessions.size)
        assertEquals(listOf("p1", "p2", "p3"), g.sessions[0].parts.map { it.key })
        assertEquals(listOf("w1", "w2"), g.sessions[0].waves.map { it.key })
        assertTrue(g.looseWaves.isEmpty())
        assertEquals(3 * min, g.sessions[0].offsetOf(g.sessions[0].waves[0]))
    }

    @Test
    fun `un numero de morceau qui revient a 1 commence une autre session`() {
        val items = listOf(part("a1", 0, 1), part("a2", 2, 2), part("b1", 4, 1), part("b2", 6, 2))
        val g = SessionGrouper.group(items)
        assertEquals(2, g.sessions.size)
        assertEquals(listOf("b1", "b2"), g.sessions[0].parts.map { it.key }) // la plus récente d'abord
    }

    @Test
    fun `un trou de plus de dix minutes separe deux sessions`() {
        val g = SessionGrouper.group(listOf(part("a", 0, 1), part("b", 30, 2)))
        assertEquals(2, g.sessions.size)
    }

    @Test
    fun `une vague hors de toute session reste seule`() {
        val g = SessionGrouper.group(listOf(part("a", 100, 1), wave("w", 10)))
        assertEquals(listOf("w"), g.looseWaves.map { it.key })
        assertTrue(g.sessions[0].waves.isEmpty())
    }

    @Test
    fun `un film assemble se rattache a sa session`() {
        val g = SessionGrouper.group(listOf(part("p1", 0, 1), part("p2", 2, 2), film("f", 0, 4)))
        assertEquals(1, g.sessions.size)
        assertNotNull(g.sessions[0].film)
        assertEquals(2, g.sessions[0].parts.size)
    }

    @Test
    fun `un film de vagues n'est pas un chapitre`() {
        val m = MediaInfo("m", 10 * min, 3 * min, MediaInfo.Kind.MONTAGE)
        val g = SessionGrouper.group(listOf(part("p1", 0, 1), part("p2", 2, 2), m))
        assertEquals(listOf("m"), g.looseWaves.map { it.key })
        assertTrue(g.sessions[0].waves.isEmpty())
    }

    @Test
    fun `un film dont les morceaux sont supprimes reste une session`() {
        val g = SessionGrouper.group(listOf(film("f", 0, 90), wave("w", 20)))
        assertEquals(1, g.sessions.size)
        assertNull(g.sessions[0].parts.firstOrNull())
        assertEquals(listOf("w"), g.sessions[0].waves.map { it.key })
    }
}
