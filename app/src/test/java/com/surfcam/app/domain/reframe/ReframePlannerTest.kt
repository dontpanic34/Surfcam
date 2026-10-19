package com.surfcam.app.domain.reframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReframePlannerTest {
    private fun box(cx: Float, cy: Float, h: Float, riding: Boolean = true) =
        PathBox(cx - h * 0.3f, cy - h / 2f, cx + h * 0.3f, cy + h / 2f, riding)

    /** Un surfeur qui traverse l'image de gauche (0,2) à droite (0,8) en 6 s. */
    private fun traverse(): List<PathFrame> =
        (0..30).map { i -> PathFrame(i * 200L, listOf(box(0.2f + 0.6f * i / 30f, 0.55f, 0.08f))) }

    @Test
    fun `la fenetre garde le surfeur dedans pendant toute la traversee`() {
        val path = ReframePlanner.plan(traverse())
        for (f in traverse()) {
            val c = path.at(f.tMs)
            val b = f.boxes[0]
            assertTrue("t=${f.tMs}", b.centerX >= c.cx - c.width / 2f && b.centerX <= c.cx + c.width / 2f)
            assertTrue("t=${f.tMs}", b.centerY >= c.cy - c.width / 2f && b.centerY <= c.cy + c.width / 2f)
        }
    }

    @Test
    fun `un petit surfeur donne un zoom fort borne a 4x`() {
        val path = ReframePlanner.plan(traverse())
        val mid = path.at(3_000)
        assertEquals(0.27, mid.width.toDouble(), 0.05) // 0,08 / 0,30
        assertTrue(mid.width >= 0.25f)
    }

    @Test
    fun `un gros surfeur ne zoome pas`() {
        val frames = (0..10).map { PathFrame(it * 200L, listOf(box(0.5f, 0.5f, 0.6f))) }
        assertEquals(1.0, ReframePlanner.plan(frames).at(1_000).width.toDouble(), 0.001)
    }

    @Test
    fun `le mouvement est lisse sans saut`() {
        val path = ReframePlanner.plan(traverse())
        var previous = path.at(0)
        var t = 100L
        while (t <= 6_000) {
            val c = path.at(t)
            assertTrue("saut de ${c.cx - previous.cx} à t=$t", abs(c.cx - previous.cx) < 0.03f)
            previous = c
            t += 100
        }
    }

    @Test
    fun `avant le depart on suit le surfeur le plus proche de celui qui surfe`() {
        val rider = box(0.7f, 0.5f, 0.1f)
        val paddlingRider = box(0.68f, 0.5f, 0.1f, riding = false)
        val other = box(0.2f, 0.5f, 0.1f, riding = false)
        val frames = listOf(
            PathFrame(0, listOf(paddlingRider, other)),
            PathFrame(200, listOf(paddlingRider, other)),
            PathFrame(400, listOf(rider, other)),
            PathFrame(600, listOf(rider, other)),
        )
        val c = ReframePlanner.plan(frames).at(0)
        assertTrue("cx=${c.cx}", c.cx > 0.5f)
    }

    @Test
    fun `sans surfeur le cadre est l'image entiere`() {
        val c = ReframePlanner.plan(emptyList()).at(500)
        assertEquals(1.0, c.width.toDouble(), 0.0001)
        assertEquals(0.5, c.cx.toDouble(), 0.0001)
    }

    private fun abs(x: Float) = kotlin.math.abs(x)
}
