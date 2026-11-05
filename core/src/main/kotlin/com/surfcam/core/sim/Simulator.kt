package com.surfcam.core.sim

import com.surfcam.core.SurfBrain
import com.surfcam.core.clip.ClipAction
import com.surfcam.core.director.DirectorState
import com.surfcam.core.director.Subject
import com.surfcam.core.geometry.CameraModel
import com.surfcam.core.model.Box
import com.surfcam.core.model.Detection
import com.surfcam.core.model.Frame
import com.surfcam.core.model.MarkerBlob
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random

/**
 * Simulateur de session : un line-up, des surfeurs qui attendent, rament et prennent des vagues, l'utilisateur avec son manchon,
 * un gimbal qui exécute (imparfaitement) les ordres, un zoom qui coupe l'image quand il change d'objectif, et un détecteur qui
 * rate les petites silhouettes. Sert à mettre au point le pilote (recherche, accrochage, suivi) avant d'aller à l'eau.
 *
 * Tout est en angles vus depuis la plage : azimut (degrés, positif à droite) et élévation. Les tailles dépendent de la distance.
 * Ce n'est pas une vérité physique : c'est un banc d'essai reproductible (graine fixe), dont les ordres de grandeur viennent des
 * sessions réelles (surfeurs de 15 à 100 px à ×5, 2 images analysées par seconde, pas de gimbal de 3 à 8°).
 */
class Simulator(val scenario: Scenario) {

    data class Scenario(
        val seed: Int = 1,
        val durationSec: Double = 30 * 60.0,
        val subject: Subject = Subject.ME,
        val others: Int = 6,
        /** Le pic réel au départ, et sa dérive (degrés par heure) — la marée le déplace. */
        val peakDeg: Double = 0.0,
        val peakDriftDegPerHour: Double = 6.0,
        /** Pic désigné par l'utilisateur au départ (peut être faux). */
        val userPeakGuessDeg: Double = 0.0,
        /** Étalement des départs autour du pic (degrés). */
        val takeoffSpreadDeg: Double = 4.0,
        val distanceM: Double = 110.0,
        /** Intervalle moyen entre deux vagues pour un surfeur (secondes). */
        val meanWaveIntervalSec: Double = 150.0,
        /** L'utilisateur porte-t-il le manchon ? */
        val userWearsMarker: Boolean = true,
        /** Probabilité que le manchon soit visible sur une image où l'utilisateur est vu (bras hors de l'eau). */
        val markerVisibleShare: Double = 0.6,
        /** Une bouée orange fixe (fausse tache de couleur), à cet azimut ; null = pas de bouée. */
        val buoyDeg: Double? = 9.0,
        /** Champ horizontal à ×1 (objectif principal). */
        val wideHfovDeg: Double = 70.0,
        /** Cadence d'analyse (images par seconde) et latence d'inférence. */
        val analysisHz: Double = 2.0,
        val inferenceLatencySec: Double = 0.4,
        /** Coupure d'image quand le zoom change d'objectif (secondes). */
        val lensSwitchGapSec: Double = 0.8,
        /** Erreur d'exécution du gimbal (fraction de l'angle commandé, écart type). */
        val gimbalErrorSigma: Double = 0.03,
        val startHeadingDeg: Double = 0.0,
    )

    enum class Activity { SIT, PADDLE_OUT, RIDE, WIPEOUT }

    class Surfer(val id: Int, val isUser: Boolean, var az: Double, var el: Double) {
        var activity = Activity.SIT
        var activityUntil = 0.0
        var vaz = 0.0
        var nextWaveAt = 0.0
        var rideStart = 0.0
        var aspect = 1.2
        var bobPhase = 0.0
    }

    /** Une vague surfée (vérité terrain). */
    data class TrueRide(val surferId: Int, val isUser: Boolean, val startSec: Double, var endSec: Double) {
        /** Au départ, le surfeur était dans le champ et aucun clip ne tournait : la vague était filmable. */
        var filmable: Boolean = false
    }

    data class Result(
        val rides: List<TrueRide>,
        val clips: List<Pair<Double, Double>>,
        val rejected: Int,
        /** Pour chaque vague de l'utilisateur : part de la vague où il était dans le cadre. */
        val userFramedShare: List<Double>,
        val stateSeconds: Map<DirectorState, Double>,
        val firstAcquireSec: Double?,
        val zoomChangesDuringClips: Int,
        val log: List<String>,
        /** Une ligne par vague de l'utilisateur ratée : ce qui s'est passé. */
        val missedUserRides: List<String> = emptyList(),
    ) {
        fun capturedRides(filter: (TrueRide) -> Boolean): Pair<Int, Int> {
            val wanted = rides.filter(filter).filter { it.endSec - it.startSec >= 3.0 }
            val used = HashSet<Int>()
            var hit = 0
            for (r in wanted) {
                val idx = clips.indices.firstOrNull { i ->
                    i !in used && overlap(clips[i], r.startSec to r.endSec) / (r.endSec - r.startSec) >= 0.5
                }
                if (idx != null) {
                    used += idx
                    hit++
                }
            }
            return hit to wanted.size
        }

        private fun overlap(a: Pair<Double, Double>, b: Pair<Double, Double>) = max(0.0, min(a.second, b.second) - max(a.first, b.first))
    }

    private val rnd = Random(scenario.seed)
    private var userDetectedAt: Pair<Double, Double>? = null

    fun run(): Result {
        val s = scenario
        val surfers = ArrayList<Surfer>()
        val user = Surfer(0, true, s.peakDeg + rnd.nextDouble(-6.0, 6.0), 0.0)
        user.nextWaveAt = 60.0 + rnd.nextDouble() * s.meanWaveIntervalSec
        surfers += user
        for (i in 1..s.others) {
            surfers += Surfer(i, false, s.peakDeg + rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(-0.3, 0.3)).also {
                it.nextWaveAt = rnd.nextDouble() * s.meanWaveIntervalSec * 1.5
                it.bobPhase = rnd.nextDouble() * 6.28
            }
        }
        val brain = SurfBrain(s.subject, s.userPeakGuessDeg)
        val truth = ArrayList<TrueRide>()
        val openRides = HashMap<Int, TrueRide>()

        var heading = s.startHeadingDeg
        var orderFrom = heading
        var orderTo = heading
        var orderStart = 0.0
        var orderEnd = 0.0
        var zoom = 5.0
        var lensGapUntil = 0.0
        val dt = 0.05
        var nextAnalysis = 0.0
        val clips = ArrayList<Pair<Double, Double>>()
        var clipStart: Double? = null
        var rejected = 0
        val stateSeconds = HashMap<DirectorState, Double>()
        var firstAcquire: Double? = null
        var zoomChangesDuringClips = 0
        val log = ArrayList<String>()
        var lastState: DirectorState? = null
        val framed = java.util.IdentityHashMap<TrueRide, IntArray>() // [vu dans le cadre, total]
        val events = ArrayList<Pair<Double, String>>()
        val states = ArrayList<Pair<Double, String>>()

        var t = 0.0
        while (t < s.durationSec) {
            val peakNow = s.peakDeg + s.peakDriftDegPerHour * t / 3600.0
            // --- Monde ---
            for (sf in surfers) stepSurfer(sf, t, dt, peakNow, truth, openRides)
            for (r in openRides.values) if (r.startSec == t) {
                val sf = surfers.first { it.id == r.surferId }
                r.filmable = abs(sf.az - heading) < camera(zoom).hfovDeg / 2 * 0.9 && clipStart == null && t >= lensGapUntil
            }
            // --- Gimbal ---
            heading = if (t >= orderEnd) orderTo else orderFrom + (orderTo - orderFrom) * ((t - orderStart) / (orderEnd - orderStart))
            val cam = camera(zoom)
            // Cadrage de l'utilisateur pendant ses vagues
            for (r in openRides.values) if (r.isUser) {
                val c = framed.getOrPut(r) { IntArray(2) }
                c[1]++
                if (abs(user.az - heading) < cam.hfovDeg / 2 * 0.9 && t >= lensGapUntil) c[0]++
            }
            // --- Analyse ---
            if (t >= nextAnalysis) {
                nextAnalysis = t + 1.0 / s.analysisHz * rnd.nextDouble(0.85, 1.15)
                if (t >= lensGapUntil) {
                    userDetectedAt = null
                    val frame = shoot(t, heading, cam, zoom, surfers)
                    // La décision arrive après l'inférence : le cap a pu bouger entre-temps.
                    val decisionT = t + s.inferenceLatencySec
                    val headingAtDecision = if (decisionT >= orderEnd) orderTo else orderFrom + (orderTo - orderFrom) * ((decisionT - orderStart) / (orderEnd - orderStart))
                    val out = brain.onFrame(frame, headingAtDecision)
                    for (a in out.clipActions) when (a) {
                        is ClipAction.Start -> if (clipStart == null) clipStart = a.fromUs / 1e6
                        is ClipAction.Stop -> {
                            clipStart?.let { clips += it to a.atUs / 1e6 }
                            clipStart = null
                        }
                        is ClipAction.Discard -> {
                            clipStart = null
                            rejected++
                        }
                        else -> Unit
                    }
                    val cmd = out.command
                    out.rideEvent?.let { e ->
                        events += t to when (e) {
                            is com.surfcam.core.ride.RideEvent.Started -> "départ #${e.trackId} (take-off %.1f s)".format(e.takeoffUs / 1e6)
                            is com.surfcam.core.ride.RideEvent.Ended -> "fin #${e.trackId} ${if (e.kept) "gardée" else "rejetée"} : ${e.reason}"
                        }
                    }
                    val ut = out.tracks.firstOrNull { it.isUser }
                    if (openRides[0] != null) {
                        val owner = userDetectedAt?.let { (ux, _) -> out.tracks.filter { it.lastSeenUs == frame.timeUs }.minByOrNull { abs(frame.camera.xForAzimuthOffset(it.azimuthDeg - frame.headingDeg) - ux) } }
                        events += t to "détecté=${userDetectedAt != null} → piste ${owner?.let { "#${it.id}(odds %.1f)".format(it.markerLogOdds) } ?: "-"} · « moi » ${ut?.id} ${ut?.let { brain.rides.lastMeasures[it.id] } ?: ""}"
                    }
                    val near = out.tracks.filter { abs(it.azimuthDeg - user.az) < 1.0 }.joinToString(",") { "#${it.id}(odds %.1f vu il y a %.1fs)".format(it.markerLogOdds, (frame.timeUs - it.lastSeenUs) / 1e6) }
                    states += t to "${cmd.state.label}/×%.0f cap %.1f utilisateur %.1f · piste « moi » ${ut?.let { "#${it.id} az %.1f".format(it.azimuthDeg) } ?: "aucune"} · pistes près de lui : ${near.ifEmpty { "aucune" }}".format(zoom, heading, user.az)
                    if (cmd.state != lastState) {
                        log += "%7.1f s  %s → %s (%s)".format(t, lastState?.label ?: "-", cmd.state.label, cmd.note)
                        lastState = cmd.state
                    }
                    if (firstAcquire == null && cmd.state == DirectorState.FOLLOW && s.subject == Subject.ME) firstAcquire = t
                    cmd.gimbalOrder?.let { o ->
                        val actual = o.deltaDeg * (1 + rnd.nextDouble() * 0 + gaussian() * s.gimbalErrorSigma)
                        orderStart = decisionT
                        orderFrom = headingAtDecision
                        orderTo = headingAtDecision + actual
                        orderEnd = decisionT + o.durationUs / 1e6
                    }
                    if (abs(cmd.zoom - zoom) > 0.01) {
                        val switchesLens = (zoom >= 5.0) != (cmd.zoom >= 5.0)
                        if (clipStart != null) zoomChangesDuringClips++
                        zoom = cmd.zoom
                        if (switchesLens) lensGapUntil = decisionT + s.lensSwitchGapSec
                    }
                    stateSeconds[cmd.state] = (stateSeconds[cmd.state] ?: 0.0) + 1.0 / s.analysisHz
                }
            }
            t += dt
        }
        for (a in brain.finish((t * 1e6).toLong())) if (a is ClipAction.Stop) clipStart?.let { clips += it to a.atUs / 1e6 }
        for (r in openRides.values) r.endSec = t
        val userShares = truth.filter { it.isUser && it.endSec - it.startSec >= 3.0 }.map { r ->
            val c = framed[r] ?: return@map 0.0
            if (c[1] == 0) 0.0 else c[0].toDouble() / c[1]
        }
        val result = Result(truth, clips, rejected, userShares, stateSeconds, firstAcquire, zoomChangesDuringClips, log)
        val captured = HashSet<TrueRide>()
        run {
            val used = HashSet<Int>()
            for (r in truth.filter { it.isUser && it.endSec - it.startSec >= 3.0 }) {
                val idx = clips.indices.firstOrNull { i -> i !in used && max(0.0, min(clips[i].second, r.endSec) - max(clips[i].first, r.startSec)) / (r.endSec - r.startSec) >= 0.5 }
                if (idx != null) { used += idx; captured += r }
            }
        }
        if (s.subject == Subject.EVERYONE) {
            val used = HashSet<Int>()
            for (r in truth.filter { it.filmable && it.endSec - it.startSec >= 3.0 }) {
                val idx = clips.indices.firstOrNull { i -> i !in used && max(0.0, min(clips[i].second, r.endSec) - max(clips[i].first, r.startSec)) / (r.endSec - r.startSec) >= 0.5 }
                if (idx != null) { used += idx; captured += r }
            }
        }
        val wanted: (TrueRide) -> Boolean = if (s.subject == Subject.ME) { r -> r.isUser } else { r -> r.filmable }
        val missed = truth.filter { wanted(it) && it.endSec - it.startSec >= 3.0 && it !in captured }.map { r ->
            val st = states.lastOrNull { it.first <= r.startSec + 1.5 }?.second ?: "?"
            val ev = events.filter { it.first in (r.startSec - 2)..(r.endSec + 5) && (s.subject == Subject.ME || !it.second.startsWith("détecté")) }.joinToString(" | ") { "%.1f %s".format(it.first, it.second) }
            val c = framed[r]
            "surfeur ${r.surferId} vague %.1f→%.1f s, cadrée %s · au départ : %s · événements : %s".format(r.startSec, r.endSec, c?.let { "%.0f %%".format(100.0 * it[0] / it[1].coerceAtLeast(1)) } ?: "?", st, ev.ifEmpty { "aucun" })
        }
        return result.copy(missedUserRides = missed)
    }

    private fun camera(zoom: Double): CameraModel = CameraModel(1280, 720, scenario.wideHfovDeg).zoomedBy(zoom)

    private fun stepSurfer(sf: Surfer, t: Double, dt: Double, peak: Double, truth: MutableList<TrueRide>, open: MutableMap<Int, TrueRide>) {
        val s = scenario
        when (sf.activity) {
            Activity.SIT -> {
                sf.aspect = 1.15
                sf.bobPhase += dt * 1.3
                sf.el = 0.15 * kotlin.math.sin(sf.bobPhase)
                // Lent retour vers le pic (on se replace), petit flottement.
                sf.az += (peak - sf.az) * 0.002 * dt + gaussian() * 0.02 * kotlin.math.sqrt(dt)
                if (t >= sf.nextWaveAt) {
                    sf.activity = Activity.RIDE
                    val dir = if (rnd.nextBoolean()) 1.0 else -1.0
                    sf.vaz = dir * rnd.nextDouble(2.0, 4.5) // °/s à ~110 m : 4 à 9 m/s
                    sf.rideStart = t
                    sf.activityUntil = t + rnd.nextDouble(5.0, 14.0)
                    val r = TrueRide(sf.id, sf.isUser, t, t)
                    truth += r
                    open[sf.id] = r
                }
            }
            Activity.RIDE -> {
                sf.aspect = 1.5 + 0.2 * kotlin.math.sin(t * 2.0)
                // Accélération au départ, virages (la vitesse oscille), léger rapprochement de la plage.
                val ramp = min(1.0, (t - sf.rideStart) / 1.2)
                sf.az += sf.vaz * ramp * (0.8 + 0.3 * kotlin.math.sin(t * 1.7)) * dt
                sf.el -= 0.04 * dt
                if (t >= sf.activityUntil) {
                    open.remove(sf.id)?.endSec = t
                    sf.activity = Activity.WIPEOUT
                    sf.activityUntil = t + rnd.nextDouble(1.0, 2.5)
                }
            }
            Activity.WIPEOUT -> {
                if (t >= sf.activityUntil) {
                    sf.activity = Activity.PADDLE_OUT
                    sf.activityUntil = t + rnd.nextDouble(25.0, 60.0)
                }
            }
            Activity.PADDLE_OUT -> {
                sf.aspect = 0.55
                val target = peak + gaussian() * 1.0
                sf.az += sign(target - sf.az) * min(abs(target - sf.az), 0.25 * dt)
                sf.el += (0.0 - sf.el) * 0.05 * dt
                if (t >= sf.activityUntil) {
                    sf.activity = Activity.SIT
                    sf.nextWaveAt = t + s.meanWaveIntervalSec * rnd.nextDouble(0.5, 1.5)
                }
            }
        }
    }

    /** Ce que voit le détecteur sur une image. */
    private fun shoot(t: Double, heading: Double, cam: CameraModel, zoom: Double, surfers: List<Surfer>): Frame {
        val s = scenario
        val dets = ArrayList<Detection>()
        val blobs = ArrayList<MarkerBlob>()
        val bodyM = 1.6
        val bodyDegFull = Math.toDegrees(atan(bodyM / s.distanceM))
        for (sf in surfers) {
            if (sf.activity == Activity.WIPEOUT) continue
            val rel = sf.az - heading
            if (abs(rel) > cam.hfovDeg / 2) continue
            val heightDeg = when (sf.activity) {
                Activity.PADDLE_OUT -> bodyDegFull * 0.35
                Activity.SIT -> bodyDegFull * 0.55
                else -> bodyDegFull * 0.85
            }
            val widthDeg = heightDeg / sf.aspect
            val hPx = cam.degToPx(heightDeg)
            val pDetect = when {
                hPx < 6 -> 0.03
                hPx < 10 -> 0.25
                hPx < 16 -> 0.6
                hPx < 30 -> 0.85
                else -> 0.93
            } * (if (sf.activity == Activity.RIDE) 0.9 else 1.0)
            val cx = cam.xForAzimuthOffset(rel) + gaussian() * 0.08 * hPx
            val cy = cam.yForElevationOffset(sf.el) + gaussian() * 0.08 * hPx
            val wPx = cam.degToPx(widthDeg) * (1 + gaussian() * 0.08)
            val hPxN = hPx * (1 + gaussian() * 0.08)
            // Manchon : environ 25 cm de couleur sur le bras.
            val markerPx = cam.degToPx(Math.toDegrees(atan(0.25 / s.distanceM)))
            val markerVisible = sf.isUser && s.userWearsMarker && rnd.nextDouble() < s.markerVisibleShare && markerPx >= 1.2
            if (markerVisible) blobs += MarkerBlob(cx, cy - hPxN * 0.2, max(1, (markerPx * markerPx * 0.6).toInt()))
            if (sf.isUser) userDetectedAt = null
            if (rnd.nextDouble() < pDetect) {
                if (sf.isUser) userDetectedAt = cx to cy
                val score = if (markerVisible) (markerPx * markerPx * 0.6) / max(1.0, wPx * hPxN) else 0.0
                dets += Detection(Box(cx - wPx / 2, cy - hPxN / 2, cx + wPx / 2, cy + hPxN / 2), rnd.nextDouble(0.45, 0.9), score)
            }
        }
        // Fausse détection occasionnelle (écume, oiseau) et bouée orange fixe.
        if (rnd.nextDouble() < 0.03) {
            val x = rnd.nextDouble(0.0, 1280.0)
            val y = rnd.nextDouble(250.0, 500.0)
            dets += Detection(Box(x - 6, y - 10, x + 6, y + 10), rnd.nextDouble(0.3, 0.5))
        }
        s.buoyDeg?.let { b ->
            val rel = b - heading
            if (abs(rel) < cam.hfovDeg / 2) {
                val px = cam.degToPx(Math.toDegrees(atan(0.5 / s.distanceM)))
                if (px >= 1.5) blobs += MarkerBlob(cam.xForAzimuthOffset(rel), cam.yForElevationOffset(-0.3), max(1, (px * px * 0.7).toInt()))
            }
        }
        return Frame((t * 1e6).toLong(), heading, 0.0, cam, zoom, dets, blobs)
    }

    private fun gaussian(): Double {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
