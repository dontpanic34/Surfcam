package com.surfcam.app.domain.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class VideoNamingTest {

    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 30, 0, paris).toInstant().toEpochMilli()

    @Test
    fun clipNameStartsWithTheDateInSortableOrder() {
        assertEquals("2026-10-15 09h38 - Vague 3", VideoNaming.clipName(ms(2026, 10, 15, 9, 38), 3, paris))
    }

    @Test
    fun sessionPartNameIsPaddedAndNeverContainsAColon() {
        val name = VideoNaming.sessionPartName(ms(2026, 10, 15, 9, 40), 2, paris)
        assertEquals("2026-10-15 09h40 - Session - partie 02", name)
        assertTrue(!name.contains(":") && !name.contains("/"))
    }

    @Test
    fun the15thSortsAfterThe2ndByName() {
        val names = listOf(
            VideoNaming.clipName(ms(2026, 10, 15, 9, 38), 1, paris),
            VideoNaming.clipName(ms(2026, 10, 2, 18, 5), 1, paris),
            VideoNaming.clipName(ms(2026, 9, 28, 7, 0), 4, paris),
        )
        assertEquals(
            listOf(names[2], names[1], names[0]),
            names.sorted(),
        )
    }

    @Test
    fun displayTitleIsReadableFrench() {
        assertEquals(
            "Vague 3 · 15 octobre 2026, 9 h 38",
            VideoNaming.displayTitle("2026-10-15 09h38 - Vague 3.mp4", paris),
        )
        assertEquals(
            "Session · partie 2 · 2 février 2026, 18 h 05",
            VideoNaming.displayTitle("2026-02-02 18h05 - Session - partie 02.mp4", paris),
        )
    }

    @Test
    fun oldAndForeignNamesAreKept() {
        assertNull(VideoNaming.parse("SurfCam_20261018_202656.mp4"))
        assertEquals("SurfCam_20261018_202656", VideoNaming.displayTitle("SurfCam_20261018_202656.mp4"))
    }

    @Test
    fun shortTitlesAndReframedVersions() {
        assertEquals("Vague 4", VideoNaming.shortTitle("2026-10-19 12h47 - Vague 4.mp4", paris))
        assertEquals("Session 1", VideoNaming.shortTitle("2026-10-19 12h46 - Session - partie 01.mp4", paris))
        val reframed = VideoNaming.reframedName("2026-10-19 12h47 - Vague 4.mp4")
        assertEquals("2026-10-19 12h47 - Vague 4 (cadré)", reframed)
        assertEquals("Vague 4 · cadré", VideoNaming.shortTitle("$reframed.mp4", paris))
        assertTrue(VideoNaming.parse(reframed, paris)!!.reframed)
        // Le clip cadré se range juste après son original.
        assertTrue("2026-10-19 12h47 - Vague 4" < reframed)
    }
}
