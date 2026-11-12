package com.surfcam.core

import com.surfcam.core.director.Subject
import com.surfcam.core.journal.FrameJournal
import com.surfcam.core.ride.RideEvent
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Rejeu d'une vraie session : lit le journal de capteurs (variable d'environnement SURFCAM_JOURNAL), le fait passer dans le
 * cerveau, et liste pour chaque piste ce que le détecteur de vagues a mesuré. Sert à trouver les vagues qui n'ont pas
 * déclenché et à comprendre pourquoi (vitesse, part horizontale, posture, nombre de votes). Ignoré sans journal.
 */
class ReplayJournalTest {

    private class Stat(val id: Int, val firstUs: Long) {
        var lastUs = firstUs
        var points = 0
        var maxV = 0.0
        var framesFast = 0
        var framesFastHoriz = 0
        var framesAllOk = 0
        var maxVotes = 0
        var triggered = false
        var bodyPx = 0.0
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var bestWindowBodies = 0.0
        var bestWindowUs = 0L
        var bestX = 0.0
        var bestY = 0.0
        val reasons = HashMap<String, Int>()
    }

    @Test
    fun replay() {
        val path = System.getenv("SURFCAM_JOURNAL")
        assumeTrue("pas de journal à rejouer", path != null && File(path).exists())
        val clockStart = System.getenv("SURFCAM_CLOCK") // "t_us=hh:mm:ss.s" pour afficher l'heure réelle
        val (refUs, refSec) = clockStart?.split('=')?.let { (t, h) ->
            val hp = h.split(':').map { it.toDouble() }
            t.toLong() to hp[0] * 3600 + hp[1] * 60 + hp[2]
        } ?: (0L to 0.0)
        fun clock(t: Long): String {
            val s = refSec + (t - refUs) / 1e6
            return "%02d:%02d:%04.1f".format((s / 3600).toInt(), (s / 60 % 60).toInt(), s % 60)
        }
        val brain = SurfBrain(Subject.EVERYONE, 0.0)
        val stats = HashMap<Int, Stat>()
        val measure = Regex("v ([0-9.,]+) h ([0-9]+)% a ([0-9.,]+) votes (\\d+)/(\\d+)")
        var frames = 0
        File(path!!).bufferedReader().use { r ->
            for (e in FrameJournal.read(r)) {
                if (e !is FrameJournal.Entry.FrameEntry) continue
                val f = e.frame
                frames++
                val out = brain.onFrame(f)
                val ev = out.rideEvent
                if (ev is RideEvent.Started) stats[ev.trackId]?.triggered = true
                for (t in out.tracks) {
                    if (!t.isVisibleAt(f.timeUs)) continue
                    val s = stats.getOrPut(t.id) { Stat(t.id, f.timeUs) }
                    s.lastUs = f.timeUs
                    s.points++
                    val p = t.points.last()
                    s.bodyPx = max(s.bodyPx, f.camera.degToPx(p.heightDeg))
                    s.minX = minOf(s.minX, p.azimuthDeg / p.bodyDeg.coerceAtLeast(1e-4))
                    s.maxX = maxOf(s.maxX, p.azimuthDeg / p.bodyDeg.coerceAtLeast(1e-4))
                    // Plus grand déplacement horizontal sur 4 s, en longueurs de corps.
                    val win = t.points.filter { it.timeUs >= f.timeUs - 4_000_000 }
                    if (win.size >= 2) {
                        val body = win.map { it.bodyDeg }.sorted()[win.size / 2].coerceAtLeast(1e-4)
                        val d = abs(win.last().azimuthDeg - win.first().azimuthDeg) / body
                        if (d > s.bestWindowBodies) {
                            s.bestWindowBodies = d; s.bestWindowUs = f.timeUs
                            s.bestX = f.camera.xForAzimuthOffset(p.azimuthDeg - f.headingDeg) / f.camera.widthPx
                            s.bestY = f.camera.yForElevationOffset(p.elevationDeg) / f.camera.heightPx
                        }
                    }
                    val m = brain.rides.lastMeasures[t.id]?.let { measure.find(it) } ?: continue
                    val v = m.groupValues[1].replace(',', '.').toDouble()
                    val h = m.groupValues[2].toDouble() / 100
                    val a = m.groupValues[3].replace(',', '.').toDouble()
                    val votes = m.groupValues[4].toInt()
                    s.maxV = max(s.maxV, v)
                    s.maxVotes = max(s.maxVotes, votes)
                    val fast = v >= brain.rides.config.startSpeed
                    val horiz = h >= brain.rides.config.minHorizontalShare
                    val stand = a >= brain.rides.config.minStandingAspect
                    if (fast) s.framesFast++
                    if (fast && horiz) s.framesFastHoriz++
                    if (fast && horiz && stand) s.framesAllOk++
                    if (fast && !(horiz && stand)) {
                        val why = (if (!horiz) "pas horizontal " else "") + (if (!stand) "pas debout" else "")
                        s.reasons[why.trim()] = (s.reasons[why.trim()] ?: 0) + 1
                    }
                }
            }
        }
        println("== rejeu : $frames images, ${stats.size} pistes")
        // Comparaison de réglages du déclenchement (SURFCAM_VOTES="3:500,2:300" = votes nécessaires : étalement minimal en ms).
        System.getenv("SURFCAM_VOTES")?.split(',')?.forEach { spec ->
            val (n, span) = spec.split(':').map { it.trim().toInt() }
            val b = SurfBrain(Subject.EVERYONE, 0.0, rideConfig = com.surfcam.core.ride.RideConfig(votesNeeded = n, voteSpanUs = span * 1000L))
            val lines = ArrayList<String>()
            File(path).bufferedReader().use { r ->
                for (e in FrameJournal.read(r)) {
                    if (e !is FrameJournal.Entry.FrameEntry) continue
                    when (val ev = b.onFrame(e.frame).rideEvent) {
                        is RideEvent.Started -> lines += "${clock(ev.takeoffUs)} départ #${ev.trackId}"
                        is RideEvent.Ended -> lines += "   fin #${ev.trackId} : ${if (ev.kept) "gardée" else "rejetée"} (${ev.reason})"
                        null -> Unit
                    }
                }
            }
            println("== réglage $n votes étalés sur $span ms : ${lines.count { "départ" in it }} départs")
            lines.forEach { println("   $it") }
        }
        val moved = stats.values.filter { it.bestWindowBodies >= 3.0 && it.points >= 4 }.sortedBy { it.bestWindowUs }
        println("== pistes qui ont parcouru au moins 3 longueurs de corps en 4 s : ${moved.size} (déclenchées : ${moved.count { it.triggered }})")
        for (s in moved) {
            println(
                "%s x%.2f y%.2f #%d %s · vue %.1f s (%d pts) · haut. %.0f px · trajet max %.1f corps/4 s · v max %.1f · images rapides %d (dont horizontales %d, debout aussi %d) · votes max %d%s".format(
                    clock(s.bestWindowUs), s.bestX, s.bestY, s.id, if (s.triggered) "DÉCLENCHÉE" else "manquée  ", (s.lastUs - s.firstUs) / 1e6, s.points, s.bodyPx,
                    s.bestWindowBodies, s.maxV, s.framesFast, s.framesFastHoriz, s.framesAllOk, s.maxVotes,
                    if (s.reasons.isEmpty()) "" else " · refus : " + s.reasons.entries.joinToString { "${it.key} ×${it.value}" },
                ),
            )
        }
    }
}
