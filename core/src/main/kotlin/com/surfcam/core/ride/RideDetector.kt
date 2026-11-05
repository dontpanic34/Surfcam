package com.surfcam.core.ride

import com.surfcam.core.tracking.Track
import com.surfcam.core.tracking.TrackPoint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Réglages de la détection du surf. Ils sont fixes et numérotés : on les change sur le banc, jamais en direct sur le téléphone
 * (l'ancien apprentissage en ligne relevait ses seuils en ne voyant que les vagues déjà gardées, et faisait reculer l'appli).
 */
data class RideConfig(
    val version: String = "R1",
    /** Fenêtre de mesure de la vitesse au déclenchement. */
    val speedWindowUs: Long = 800_000,
    /** Vitesse (longueurs de corps par seconde, repère fixe) à dépasser pour voter « départ ». */
    val startSpeed: Double = 1.5,
    /** Part horizontale du déplacement pour voter « départ » (un surfeur assis monte et descend avec la houle). */
    val minHorizontalShare: Double = 0.6,
    /** Rapport hauteur/largeur minimal pour voter « départ » (un surfeur allongé qui rame est plus large que haut). */
    val minStandingAspect: Double = 0.9,
    /** Vote : il faut [votesNeeded] images favorables parmi celles des [voteWindowUs] dernières, étalées sur au moins [voteSpanUs]. */
    val votesNeeded: Int = 3,
    val voteWindowUs: Long = 2_000_000,
    val voteSpanUs: Long = 500_000,
    /** Vitesse sous laquelle le surfeur est considéré arrêté, et durée pendant laquelle il doit le rester. */
    val stopSpeed: Double = 0.7,
    val endGraceUs: Long = 1_500_000,
    /** Disparition tolérée (écume, chute, détection manquée) avant de clore la course. */
    val lostGraceUs: Long = 3_000_000,
    /** Décision sur la course entière : durée et trajet (en longueurs de corps) minimaux. */
    val minRideUs: Long = 2_000_000,
    val minPathBodies: Double = 4.0,
    /** Un surfeur perdu en plein mouvement près du bord (sorti du champ, avalé par l'écume) : exigences réduites. */
    val minRideLeavingUs: Long = 1_200_000,
    val minPathBodiesLeaving: Double = 2.5,
    val leavingEdgeMargin: Double = 0.25,
)

/** Ce qui caractérise une course, pour décider et pour le rapport. */
data class RideFeatures(
    val durationUs: Long,
    /** Longueur du trajet, en longueurs de corps. */
    val pathBodies: Double,
    /** Déplacement net début → fin, en longueurs de corps. */
    val netBodies: Double,
    /** Part des images où la silhouette est « debout ». */
    val standingShare: Double,
    /** Vitesse médiane (longueurs de corps/s) pendant la course. */
    val medianSpeed: Double,
    /** Taille angulaire médiane du surfeur (degrés). */
    val bodyDeg: Double,
    val points: Int,
    val endedByLoss: Boolean,
    /** Distance au bord de l'image au dernier point vu (0 = au bord, 1 = au centre). */
    val lastEdgeMargin: Double,
)

sealed interface RideEvent {
    val trackId: Int

    /** Départ détecté : le clip doit commencer à [takeoffUs] moins la marge avant. */
    data class Started(override val trackId: Int, val takeoffUs: Long, val triggerSpeed: Double, val triggerAspect: Double) : RideEvent

    /** Course terminée : [kept] dit si c'était une vague surfée ; [reason] explique le rejet. */
    data class Ended(
        override val trackId: Int,
        val takeoffUs: Long,
        val endUs: Long,
        val features: RideFeatures,
        val kept: Boolean,
        val reason: String,
    ) : RideEvent
}

/**
 * Détecte les vagues surfées à partir des pistes en repère fixe. Une seule course suivie à la fois (celle qu'on filme).
 *
 * Deux temps :
 * 1. **Déclenchement** rapide, pour que le clip remonte avant le take-off : un vote sur les dernières images (vitesse, part
 *    horizontale, posture). Une image ratée ou un virage n'efface pas les votes déjà acquis.
 * 2. **Décision** à la fin de la course, sur la course entière : durée et longueur du trajet. Un surfeur qui rame vite deux
 *    secondes ne parcourt pas le trajet d'une vague.
 */
class RideDetector(val config: RideConfig = RideConfig()) {

    private data class Vote(val timeUs: Long, val ok: Boolean)

    private val votes = HashMap<Int, ArrayDeque<Vote>>()

    var ridingTrackId: Int? = null
        private set
    private var takeoffUs = 0L
    private var lastMovingUs = 0L
    private var lastSeenUs = 0L

    val isRiding: Boolean get() = ridingTrackId != null

    /**
     * Piste qui commence peut-être une vague (au moins un vote favorable récent), sans confirmation encore : le pilote peut
     * déjà tourner la caméra vers elle pour ne pas la laisser sortir du champ pendant la confirmation.
     */
    var candidateTrackId: Int? = null
        private set

    /** Dernières mesures par piste (pour le rapport et le diagnostic) : vitesse, part horizontale, posture, votes. */
    val lastMeasures = HashMap<Int, String>()

    /**
     * À appeler à chaque image analysée avec les pistes candidates (toutes, ou seulement celle de l'utilisateur en mode « moi »).
     */
    fun onFrame(tracks: List<Track>, timeUs: Long): RideEvent? {
        val riding = ridingTrackId
        return if (riding == null) lookForStart(tracks, timeUs) else follow(riding, tracks, timeUs)
    }

    /** Abandonne la course en cours sans décision (changement de mode, arrêt). */
    fun reset() {
        votes.clear()
        ridingTrackId = null
    }

    private fun lookForStart(tracks: List<Track>, now: Long): RideEvent? {
        // Les votes ne s'effacent qu'avec le temps : une image sans détection (écume, détection ratée) ne fait rien perdre.
        votes.values.removeAll { q -> q.isEmpty() || now - q.last().timeUs > config.voteWindowUs }
        var best: Pair<Track, Long>? = null
        for (t in tracks) {
            if (!t.isVisibleAt(now)) continue
            val m = measure(windowOf(t, now)) ?: continue
            val ok = m.speed >= config.startSpeed && m.horizontalShare >= config.minHorizontalShare && m.aspect >= config.minStandingAspect
            val q = votes.getOrPut(t.id) { ArrayDeque() }
            q.addLast(Vote(now, ok))
            while (q.isNotEmpty() && now - q.first().timeUs > config.voteWindowUs) q.removeFirst()
            lastMeasures[t.id] = "v %.1f h %.0f%% a %.2f votes %d/%d".format(m.speed, 100 * m.horizontalShare, m.aspect, q.count { it.ok }, q.size)
            val yes = q.filter { it.ok }
            if (yes.size >= config.votesNeeded && yes.last().timeUs - yes.first().timeUs >= config.voteSpanUs) {
                val firstYes = yes.first().timeUs
                if (best == null || firstYes < best.second) best = t to firstYes
            }
        }
        candidateTrackId = votes.entries.filter { (id, q) -> q.any { it.ok } && tracks.any { it.id == id } }
            .maxByOrNull { (_, q) -> q.count { it.ok } * 10_000_000L + q.last { it.ok }.timeUs / 1_000_000 }?.key
        val (track, firstYes) = best ?: return null
        candidateTrackId = null
        val m = measure(windowOf(track, now))!!
        ridingTrackId = track.id
        // La vitesse est mesurée sur une fenêtre : le vrai départ est environ une demi-fenêtre avant le premier vote favorable.
        takeoffUs = firstYes - config.speedWindowUs / 2
        lastMovingUs = now
        lastSeenUs = now
        votes.clear()
        return RideEvent.Started(track.id, takeoffUs, m.speed, m.aspect)
    }

    private fun follow(id: Int, tracks: List<Track>, now: Long): RideEvent? {
        val track = tracks.firstOrNull { it.id == id }
        if (track != null && track.isVisibleAt(now)) {
            lastSeenUs = now
            val speed = measure(windowOf(track, now))?.speed ?: track.speedBodyPerSec
            if (speed >= config.stopSpeed) lastMovingUs = now
        }
        val stopped = track != null && track.isVisibleAt(now) && now - lastMovingUs >= config.endGraceUs
        // Une image où la piste manque n'est pas une perte : seule une absence plus longue que lostGraceUs clôt la course.
        val lost = now - lastSeenUs >= config.lostGraceUs
        if (!stopped && !lost) return null
        ridingTrackId = null
        val end = if (stopped) lastMovingUs else lastSeenUs
        val pts = track?.points?.filter { it.timeUs in takeoffUs..end } ?: emptyList()
        val f = features(pts, takeoffUs, end, endedByLoss = lost && !stopped)
        val (kept, reason) = decide(f)
        return RideEvent.Ended(id, takeoffUs, end, f, kept, reason)
    }

    private fun decide(f: RideFeatures): Pair<Boolean, String> {
        val leaving = f.endedByLoss && f.lastEdgeMargin <= config.leavingEdgeMargin
        val minDur = if (leaving) config.minRideLeavingUs else config.minRideUs
        val minPath = if (leaving) config.minPathBodiesLeaving else config.minPathBodies
        return when {
            f.durationUs < minDur -> false to "trop courte (%.1f s < %.1f s)".format(f.durationUs / 1e6, minDur / 1e6)
            f.pathBodies < minPath -> false to "trajet trop court (%.1f corps < %.1f)".format(f.pathBodies, minPath)
            else -> true to "vague (%.1f s, %.1f corps)".format(f.durationUs / 1e6, f.pathBodies)
        }
    }

    /**
     * Points sur lesquels mesurer la vitesse : la fenêtre normale, élargie jusqu'au double si une détection manquée n'y laisse
     * qu'un seul point (à 2 images/s, une seule image ratée suffirait sinon à empêcher toute mesure).
     */
    private fun windowOf(t: Track, now: Long): List<TrackPoint> {
        val w = t.pointsSince(now - config.speedWindowUs)
        return if (w.size >= 2) w else t.pointsSince(now - 2 * config.speedWindowUs)
    }

    private data class Measure(val speed: Double, val horizontalShare: Double, val aspect: Double)

    /** Vitesse nette, part horizontale et posture sur une fenêtre de points ; null si trop court. */
    private fun measure(window: List<TrackPoint>): Measure? {
        if (window.size < 2) return null
        val a = window.first()
        val b = window.last()
        val dt = (b.timeUs - a.timeUs) / 1e6
        if (dt < config.speedWindowUs / 1e6 * 0.4 || dt > config.speedWindowUs / 1e6 * 2.2) return null
        val body = window.map { it.bodyDeg }.sorted()[window.size / 2].coerceAtLeast(1e-4)
        val dAz = b.azimuthDeg - a.azimuthDeg
        val dEl = b.elevationDeg - a.elevationDeg
        val d = hypot(dAz, dEl)
        val aspect = window.map { it.aspect }.average()
        return Measure(d / body / dt, if (d < 1e-9) 0.0 else abs(dAz) / d, aspect)
    }

    companion object {
        fun features(pts: List<TrackPoint>, startUs: Long, endUs: Long, endedByLoss: Boolean, standingAspect: Double = 0.9): RideFeatures {
            if (pts.isEmpty()) return RideFeatures(endUs - startUs, 0.0, 0.0, 0.0, 0.0, 0.0, 0, endedByLoss, 1.0)
            val body = pts.map { it.bodyDeg }.sorted()[pts.size / 2].coerceAtLeast(1e-4)
            var path = 0.0
            val speeds = ArrayList<Double>()
            for (i in 1 until pts.size) {
                val d = hypot(pts[i].azimuthDeg - pts[i - 1].azimuthDeg, pts[i].elevationDeg - pts[i - 1].elevationDeg)
                path += d
                val dt = (pts[i].timeUs - pts[i - 1].timeUs) / 1e6
                if (dt > 0) speeds += d / body / dt
            }
            val net = hypot(pts.last().azimuthDeg - pts.first().azimuthDeg, pts.last().elevationDeg - pts.first().elevationDeg)
            val standing = pts.count { it.aspect >= standingAspect }.toDouble() / pts.size
            val median = if (speeds.isEmpty()) 0.0 else speeds.sorted()[speeds.size / 2]
            return RideFeatures(
                durationUs = endUs - startUs,
                pathBodies = path / body,
                netBodies = net / body,
                standingShare = standing,
                medianSpeed = median,
                bodyDeg = body,
                points = pts.size,
                endedByLoss = endedByLoss,
                lastEdgeMargin = pts.last().edgeMargin,
            )
        }
    }
}
