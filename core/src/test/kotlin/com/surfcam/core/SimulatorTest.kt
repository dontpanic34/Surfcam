package com.surfcam.core

import com.surfcam.core.director.DirectorState
import com.surfcam.core.director.Subject
import com.surfcam.core.sim.Simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scénarios simulés : chaque test tourne une session de 30 min et vérifie que le pilote fait son travail. Les seuils sont des
 * portes de non-régression : une modification qui les fait tomber doit être expliquée.
 */
class SimulatorTest {

    private fun report(name: String, r: Simulator.Result, me: Boolean) {
        val (hitUser, totalUser) = r.capturedRides { it.isUser }
        val (hitAll, totalAll) = r.capturedRides { true }
        val framed = if (r.userFramedShare.isEmpty()) "-" else "%.0f %%".format(100 * r.userFramedShare.average())
        println("== $name")
        println("   vagues de l'utilisateur filmées : $hitUser / $totalUser · cadré pendant ses vagues : $framed")
        println("   toutes vagues filmées : $hitAll / $totalAll · clips : ${r.clips.size} · rejetés : ${r.rejected}")
        println("   premier accrochage : ${r.firstAcquireSec?.let { "%.0f s".format(it) } ?: "jamais"} · changements de zoom : ${r.zoomChanges}")
        println("   temps par état : " + r.stateSeconds.entries.sortedBy { it.key.ordinal }.joinToString { "${it.key.label} %.0f s".format(it.value) })
        if (me) r.missedUserRides.forEach { println("   RATÉE $it") }
    }

    @Test
    fun meFindsAndFollowsTheUser() {
        val results = (1..5).map { seed -> Simulator(Simulator.Scenario(seed = seed, subject = Subject.ME)).run() }
        results.forEachIndexed { i, r -> report("moi, graine ${i + 1}", r, true) }
        val hit = results.sumOf { it.capturedRides { r -> r.isUser }.first }
        val total = results.sumOf { it.capturedRides { r -> r.isUser }.second }
        println("TOTAL moi : $hit / $total vagues de l'utilisateur filmées")
        assertTrue("l'utilisateur doit être trouvé à chaque session", results.all { it.firstAcquireSec != null })
        assertEquals("le pilote ne change jamais de zoom", 0, results.sumOf { it.zoomChanges })
        assertTrue("au moins 70 % des vagues de l'utilisateur filmées ($hit/$total)", hit >= 0.7 * total)
        val others = results.sumOf { r -> r.clips.size - r.capturedRides { it.isUser }.first }
        // Depuis le repli « tout le monde » (spec : un clip en trop est acceptable, une vague manquée non), les vagues des autres
        // sont filmées quand l'utilisateur est perdu : on les compte sans les interdire.
        println("clips qui ne sont pas l'utilisateur : $others")
    }

    @Test
    fun meWithDrawnPeakZoneNeverChangesZoom() {
        // L'utilisateur trace la zone du pic (±12°) avant de lancer : la recherche balaie cette zone au téléobjectif, sans dézoom.
        val results = (1..5).map { seed -> Simulator(Simulator.Scenario(seed = seed, subject = Subject.ME, peakZoneHalfWidthDeg = 12.0)).run() }
        results.forEachIndexed { i, r -> report("moi + zone du pic, graine ${i + 1}", r, false) }
        val hit = results.sumOf { it.capturedRides { r -> r.isUser }.first }
        val total = results.sumOf { it.capturedRides { r -> r.isUser }.second }
        println("TOTAL moi + zone : $hit / $total vagues de l'utilisateur filmées")
        assertTrue("l'utilisateur doit être trouvé à chaque session", results.all { it.firstAcquireSec != null })
        assertTrue("au moins 70 % des vagues de l'utilisateur filmées ($hit/$total)", hit >= 0.7 * total)
        assertEquals("le pilote ne change jamais de zoom", 0, results.sumOf { it.zoomChanges })
    }

    @Test
    fun meWithoutMarkerFallsBackToEveryone() {
        // Spec : par défaut tout le monde ; sans manchon reconnu, on ne doit pas passer la session sans rien filmer.
        val r = Simulator(Simulator.Scenario(seed = 3, subject = Subject.ME, userWearsMarker = false, durationSec = 15 * 60.0)).run()
        report("moi sans manchon", r, false)
        val (hit, total) = r.capturedRides { true }
        assertTrue("sans manchon reconnu, les vagues sont filmées quand même ($hit/$total)", hit >= 0.4 * total)
        assertEquals("le pilote ne change jamais de zoom", 0, r.zoomChanges)
        assertTrue((r.stateSeconds[DirectorState.SEARCH] ?: 0.0) > 0)
    }

    @Test
    fun everyoneFilmsRides() {
        val results = (1..5).map { seed -> Simulator(Simulator.Scenario(seed = seed, subject = Subject.EVERYONE)).run() }
        results.forEachIndexed { i, r -> report("tout le monde, graine ${i + 1}", r, i == 0) }
        val hit = results.sumOf { it.capturedRides { true }.first }
        val total = results.sumOf { it.capturedRides { true }.second }
        val hitF = results.sumOf { it.capturedRides { r -> r.filmable }.first }
        val totalF = results.sumOf { it.capturedRides { r -> r.filmable }.second }
        val clips = results.sumOf { it.clips.size }
        println("TOTAL tout le monde : $hit / $total vagues filmées ; filmables (départ dans le champ, caméra libre) : $hitF / $totalF ; $clips clips")
        assertEquals("le pilote ne change jamais de zoom", 0, results.sumOf { it.zoomChanges })
        assertTrue("au moins 80 % des vagues filmables filmées ($hitF/$totalF)", hitF >= 0.8 * totalF)
        assertTrue("au moins 85 % des clips sont de vraies vagues ($hit/$clips)", hit >= 0.85 * clips)
    }
}
