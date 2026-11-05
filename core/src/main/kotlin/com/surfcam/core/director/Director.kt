package com.surfcam.core.director

import com.surfcam.core.heading.GimbalOrder
import com.surfcam.core.model.Frame
import com.surfcam.core.model.WorldProjection
import com.surfcam.core.tracking.Track
import kotlin.math.abs

/** Qui filmer. */
enum class Subject {
    /** L'utilisateur, reconnu par son manchon de couleur : l'appli le cherche, le suit en permanence, filme ses vagues. */
    ME,

    /** N'importe quel surfeur qui part sur une vague : l'appli attend au pic et suit celui qui surfe. */
    EVERYONE,
}

enum class DirectorState(val label: String) {
    WAIT_AT_PEAK("Attente au pic"),
    SEARCH("Recherche large"),
    ACQUIRE("Accrochage"),
    FOLLOW("Suivi"),
    LOST("Cible perdue"),
}

data class DirectorConfig(
    /** Zoom de suivi et de tournage (téléobjectif). */
    val followZoom: Double = 5.0,
    /** Zoom de recherche : assez large pour couvrir le line-up, assez serré pour voir le manchon. */
    val searchZoom: Double = 2.0,
    /** Mode moi : sans voir l'utilisateur depuis ce délai, on part le chercher (si aucun clip ne tourne). */
    val searchAfterUs: Long = 8_000_000,
    /** Mode moi, au démarrage : délai avant la première recherche si l'utilisateur n'est pas dans le champ. */
    val initialSearchAfterUs: Long = 4_000_000,
    /** Une cible perdue est encore visée là où elle devait aller pendant ce délai. */
    val coastUs: Long = 1_500_000,
    /** Mode tout le monde : après une vague, délai avant de revenir au pic. */
    val returnToPeakAfterUs: Long = 4_000_000,
    /** Recherche : temps passé sur chaque direction (il faut plusieurs images pour voir le manchon). */
    val searchDwellUs: Long = 2_500_000,
    /** Recherche : écart entre deux directions, en fraction du champ de recherche. */
    val searchStepFraction: Double = 0.7,
    /** Recherche : nombre de directions de part et d'autre du pic. */
    val searchSidePositions: Int = 2,
    /** Accrochage : la cible doit être confirmée (manchon vu au zoom de suivi) dans ce délai. */
    val confirmTimeoutUs: Long = 8_000_000,
    /** Accrochage : une cible qui n'est plus vue depuis ce délai est abandonnée. */
    val acquireLostUs: Long = 3_000_000,
    /** Une direction où l'accrochage a échoué est ignorée pendant ce délai (fausse tache de couleur). */
    val ignoreUs: Long = 60_000_000,
    val ignoreRadiusDeg: Double = 3.0,
    /** Taille minimale (pixels) d'une tache de couleur pour lancer un accrochage. */
    val minBlobPixels: Int = 6,
    /** Accrochage : part du demi-champ dans laquelle la cible doit être centrée avant de zoomer. */
    val centeredFraction: Double = 0.3,
)

/** Ce que le pilote demande à l'appli pour cette image. */
data class Command(
    val state: DirectorState,
    /** Ordre à envoyer au gimbal maintenant, s'il y en a un. */
    val gimbalOrder: GimbalOrder?,
    /** Zoom voulu. L'appli ne le change que s'il diffère du zoom actuel. */
    val zoom: Double,
    /** Piste suivie, s'il y en a une. */
    val targetTrackId: Int?,
    /** Cap visé (degrés), pour l'affichage et le rapport. */
    val aimDeg: Double,
    val note: String,
)

/**
 * Le pilote : décide à chaque image où pointer le gimbal et à quel zoom filmer.
 *
 * Mode moi : attente → recherche large (dézoom, balayage autour du pic, repérage du manchon) → accrochage (pivot, zoom, le
 * manchon doit être confirmé au téléobjectif) → suivi permanent (assis, à la rame, en vague) → perdu (on vise où il devait aller,
 * puis on recherche).
 *
 * Mode tout le monde : attente au pic → suivi du surfeur qui part → retour au pic.
 *
 * Règle d'or : **jamais de changement de zoom pendant qu'un clip est en cours** (vague, ou marge avant/après) : le
 * changement d'objectif coupe l'image et se verrait dans le clip.
 */
class Director(
    val subject: Subject,
    initialPeakDeg: Double,
    val config: DirectorConfig = DirectorConfig(),
) {
    val peak = PeakEstimator(initialPeakDeg)
    private val gimbal = GimbalPlanner()

    var state: DirectorState = DirectorState.WAIT_AT_PEAK
        private set
    private var stateSinceUs = Long.MIN_VALUE
    private var targetId: Int? = null
    private var targetLastSeenUs = 0L
    private var userLastSeenUs = Long.MIN_VALUE
    private var lastRideEndUs = Long.MIN_VALUE
    private var zoom = config.followZoom
    private var startUs = Long.MIN_VALUE

    // Recherche
    private var searchIndex = 0
    private var searchPositionSinceUs = 0L

    // Accrochage
    private var acquireDeg = 0.0
    private var acquireTrackId: Int? = null
    private var acquireLastSeenUs = 0L
    private val ignored = ArrayList<Pair<Double, Long>>()

    /**
     * @param frame l'image analysée (cap, géométrie, détections, taches de couleur)
     * @param tracks pistes à jour (repère fixe)
     * @param currentHeadingDeg cap réel maintenant (gyroscope), pour calculer les ordres
     * @param ridingTrackId piste en train de surfer (détecteur de vagues), null sinon
     * @param clipActive un clip est en cours (vague ou marges) : zoom verrouillé
     */
    fun onFrame(frame: Frame, tracks: List<Track>, currentHeadingDeg: Double, ridingTrackId: Int?, clipActive: Boolean): Command {
        val now = frame.timeUs
        if (startUs == Long.MIN_VALUE) {
            startUs = now
            stateSinceUs = now
            userLastSeenUs = now
        }
        ignored.removeAll { now - it.second > config.ignoreUs }
        val zoomLocked = clipActive || ridingTrackId != null
        return when (subject) {
            Subject.EVERYONE -> everyone(frame, tracks, currentHeadingDeg, ridingTrackId, zoomLocked)
            Subject.ME -> me(frame, tracks, currentHeadingDeg, ridingTrackId, zoomLocked)
        }
    }

    /** L'utilisateur désigne la direction actuelle comme pic. */
    fun setPeakHere(headingDeg: Double, timeUs: Long) = peak.setByUser(headingDeg, timeUs)

    /** Un départ de vague gardé, pour réestimer le pic. */
    fun onRideKept(takeoffAzimuthDeg: Double, timeUs: Long) {
        peak.addTakeoff(takeoffAzimuthDeg, timeUs)
        lastRideEndUs = timeUs
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Mode tout le monde
    // ---------------------------------------------------------------------------------------------------------------------

    private fun everyone(frame: Frame, tracks: List<Track>, heading: Double, ridingId: Int?, zoomLocked: Boolean): Command {
        val now = frame.timeUs
        if (!zoomLocked) zoom = config.followZoom
        if (ridingId != null) {
            val t = tracks.firstOrNull { it.id == ridingId }
            if (t != null) {
                targetId = t.id
                if (t.isVisibleAt(now)) targetLastSeenUs = now
                lastRideEndUs = now
                enter(DirectorState.FOLLOW, now)
                return aimAtTrack(frame, t, heading, GimbalStyle.RIDE, "vague de #${t.id}")
            }
        }
        // Après une vague : on reste un moment là où elle s'est finie (le surfeur peut repartir), puis retour au pic.
        if (state == DirectorState.FOLLOW && now - lastRideEndUs < config.returnToPeakAfterUs) {
            return hold(heading, "fin de vague, on attend avant de revenir au pic")
        }
        targetId = null
        if (!zoomLocked) peak.update(now)
        enter(DirectorState.WAIT_AT_PEAK, now)
        return aimAt(now, heading, peak.peakDeg, 0.0, frame.camera.hfovDeg, GimbalStyle.REPOSITION, null, "attente au pic")
    }

    // ---------------------------------------------------------------------------------------------------------------------
    // Mode moi
    // ---------------------------------------------------------------------------------------------------------------------

    private fun me(frame: Frame, tracks: List<Track>, heading: Double, ridingId: Int?, zoomLocked: Boolean): Command {
        val now = frame.timeUs
        // La piste de l'utilisateur : celle qui porte le manchon (la plus sûre s'il y en a plusieurs).
        val user = tracks.filter { it.isUser }.maxByOrNull { it.markerLogOdds }
        if (user != null && user.isVisibleAt(now)) userLastSeenUs = now

        // Une vague en cours prime sur tout.
        if (ridingId != null) {
            val t = tracks.firstOrNull { it.id == ridingId }
            if (t != null) {
                targetId = t.id
                enter(DirectorState.FOLLOW, now)
                return aimAtTrack(frame, t, heading, GimbalStyle.RIDE, "en vague")
            }
        }

        if (user != null && (state != DirectorState.SEARCH || user.isVisibleAt(now))) {
            targetId = user.id
            targetLastSeenUs = user.lastSeenUs
            val missing = now - user.lastSeenUs
            if (zoom < config.followZoom && !zoomLocked) {
                // Repéré en vue large : on centre puis on zoome.
                enter(DirectorState.ACQUIRE, now)
                return acquireStep(frame, heading, user.azimuthDeg, user.velocityAzDegPerSec, "manchon reconnu, on zoome")
            }
            if (missing <= config.coastUs) {
                enter(DirectorState.FOLLOW, now)
                return aimAtTrack(frame, user, heading, GimbalStyle.FOLLOW, if (missing == 0L) "suivi" else "suivi (prédit)")
            }
            if (missing < config.searchAfterUs || zoomLocked) {
                enter(DirectorState.LOST, now)
                return hold(heading, "perdu depuis %.1f s, on garde le cadre".format(missing / 1e6))
            }
        }

        when (state) {
            DirectorState.ACQUIRE -> {
                val cmd = acquire(frame, tracks, heading, zoomLocked)
                if (cmd != null) return cmd
            }
            DirectorState.SEARCH -> {
                if (!zoomLocked) return search(frame, tracks, heading)
            }
            else -> Unit
        }

        val limit = if (userLastSeenUs == startUs) config.initialSearchAfterUs else config.searchAfterUs
        if (!zoomLocked && now - userLastSeenUs >= limit) {
            enter(DirectorState.SEARCH, now)
            searchIndex = 0
            searchPositionSinceUs = now
            return search(frame, tracks, heading)
        }
        enter(DirectorState.WAIT_AT_PEAK, now)
        if (!zoomLocked) zoom = config.followZoom
        return aimAt(now, heading, peak.peakDeg, 0.0, frame.camera.hfovDeg, GimbalStyle.REPOSITION, null, "attente au pic, manchon pas encore vu")
    }

    private fun search(frame: Frame, tracks: List<Track>, heading: Double): Command {
        val now = frame.timeUs
        zoom = config.searchZoom
        // Candidats : une silhouette qui montre le manchon, sinon une tache de couleur assez grosse.
        val marked = tracks.filter { it.isVisibleAt(now) && it.markerLogOdds > 0 && !isIgnored(it.azimuthDeg) }
            .maxByOrNull { it.markerLogOdds }
        if (marked != null) {
            startAcquire(now, marked.azimuthDeg, marked.id)
            return acquireStep(frame, heading, marked.azimuthDeg, marked.velocityAzDegPerSec, "manchon sur #${marked.id}")
        }
        if (frame.zoom <= config.searchZoom * 1.05) {
            val blob = frame.blobs.filter { it.pixels >= config.minBlobPixels }
                .map { WorldProjection.blobAzimuth(frame, it) to it.pixels }
                .filter { !isIgnored(it.first) }
                .maxByOrNull { it.second }
            if (blob != null) {
                startAcquire(now, blob.first, null)
                return acquireStep(frame, heading, blob.first, 0.0, "tache de couleur (${blob.second} px)")
            }
        }
        // Balayage : pic, puis alternativement à gauche et à droite, de plus en plus loin.
        val positions = searchPositions()
        if (now - searchPositionSinceUs >= config.searchDwellUs) {
            searchIndex = (searchIndex + 1) % positions.size
            searchPositionSinceUs = now
        }
        val hfov = frame.camera.hfovDeg.coerceAtLeast(1.0)
        val target = peak.peakDeg + positions[searchIndex] * config.searchStepFraction * hfov
        return aimAt(now, heading, target, 0.0, hfov, GimbalStyle.REPOSITION, null, "recherche, direction ${searchIndex + 1}/${positions.size}")
    }

    private fun searchPositions(): List<Int> {
        val list = arrayListOf(0)
        for (k in 1..config.searchSidePositions) {
            list += -k
            list += k
        }
        return list
    }

    private fun startAcquire(now: Long, deg: Double, trackId: Int?) {
        enter(DirectorState.ACQUIRE, now)
        acquireDeg = deg
        acquireTrackId = trackId
        acquireLastSeenUs = now
    }

    private fun acquire(frame: Frame, tracks: List<Track>, heading: Double, zoomLocked: Boolean): Command? {
        val now = frame.timeUs
        // On suit la piste candidate si elle existe, sinon la tache de couleur la plus proche de la direction visée.
        val t = acquireTrackId?.let { id -> tracks.firstOrNull { it.id == id } }
            ?: tracks.filter { it.isVisibleAt(now) && abs(it.azimuthDeg - acquireDeg) < config.ignoreRadiusDeg && it.markerLogOdds > 0 }
                .maxByOrNull { it.markerLogOdds }
        if (t != null) {
            acquireTrackId = t.id
            acquireDeg = t.azimuthDeg
            if (t.isVisibleAt(now)) acquireLastSeenUs = now
        } else {
            val blob = frame.blobs.filter { it.pixels >= config.minBlobPixels / 2 }
                .map { WorldProjection.blobAzimuth(frame, it) }
                .minByOrNull { abs(it - acquireDeg) }
            if (blob != null && abs(blob - acquireDeg) < config.ignoreRadiusDeg * 2) {
                acquireDeg = blob
                acquireLastSeenUs = now
            }
        }
        val failed = now - acquireLastSeenUs > config.acquireLostUs || now - stateSinceUs > config.confirmTimeoutUs
        if (failed) {
            ignored += acquireDeg to now
            enter(DirectorState.SEARCH, now)
            searchPositionSinceUs = now
            return if (zoomLocked) hold(heading, "accrochage abandonné") else search(frame, tracks, heading)
        }
        return acquireStep(frame, heading, acquireDeg, t?.velocityAzDegPerSec ?: 0.0, "accrochage")
    }

    /** Centre la cible ; une fois centrée, resserre le zoom d'un cran. */
    private fun acquireStep(frame: Frame, heading: Double, deg: Double, rate: Double, note: String): Command {
        val now = frame.timeUs
        val hfov = frame.camera.hfovDeg
        val error = deg - heading
        if (abs(error) <= config.centeredFraction * hfov / 2.0 && abs(frame.zoom - zoom) < 0.05 && zoom < config.followZoom) {
            zoom = when {
                zoom < config.searchZoom -> config.searchZoom
                else -> config.followZoom
            }
        }
        return aimAt(now, heading, deg, rate, hfov, GimbalStyle.REPOSITION, acquireTrackId, note)
    }

    private fun isIgnored(deg: Double) = ignored.any { abs(it.first - deg) < config.ignoreRadiusDeg }

    // ---------------------------------------------------------------------------------------------------------------------

    private fun aimAtTrack(frame: Frame, t: Track, heading: Double, style: GimbalStyle, note: String): Command {
        val now = frame.timeUs
        val dt = (now - t.filterTimeUs) / 1e6
        val predicted = t.azimuthDeg + t.velocityAzDegPerSec * dt.coerceAtLeast(0.0)
        return aimAt(now, heading, predicted, t.velocityAzDegPerSec, frame.camera.hfovDeg, style, t.id, note)
    }

    private fun aimAt(now: Long, heading: Double, deg: Double, rate: Double, hfov: Double, style: GimbalStyle, trackId: Int?, note: String): Command {
        val order = gimbal.plan(now, heading, deg, rate, hfov, style)
        return Command(state, order, zoom, trackId, deg, note)
    }

    private fun hold(heading: Double, note: String) = Command(state, null, zoom, targetId, heading, note)

    private fun enter(s: DirectorState, now: Long) {
        if (s != state) {
            state = s
            stateSinceUs = now
        }
    }
}
