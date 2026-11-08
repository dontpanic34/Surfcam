package com.surfcam.app.domain.tracking

data class WaveConfig(
    /** Fenêtre de mesure de la vitesse. */
    val speedWindowUs: Long = 800_000,
    /** Vitesse (longueurs de corps/s) à dépasser pour considérer qu'une vague démarre. */
    val startSpeed: Float = 1.5f,
    /** Vitesse en dessous de laquelle le surfeur est considéré à l'arrêt (hystérésis : plus bas que le départ). */
    val stopSpeed: Float = 0.8f,
    /** La vitesse de départ doit durer au moins ça avant de déclencher. */
    val confirmUs: Long = 600_000,
    /** Durée sous la vitesse d'arrêt avant de déclarer la fin de la vague. */
    val endGraceUs: Long = 1_500_000,
    /** Disparition du suivi (chute, écume) tolérée avant de déclarer la fin. */
    val lostGraceUs: Long = 4_000_000,
    /** Une vague plus courte que ça est annulée (surfeur qui rame vite, fausse alerte). */
    val minRideUs: Long = 0, // la durée ne décide plus : une vague courte (chute) reste une vague
    /** Une preuve de départ (vitesse, posture…) n'est pas effacée par une image sans détection plus courte que cela. */
    val startGapUs: Long = 1_000_000,
    /** Au-delà, ce n'est pas un surfeur mais deux personnes confondues par le suivi (saut d'identité) : pas de départ. */
    val maxStartSpeed: Float = 9f,
    /**
     * Rapport hauteur/largeur minimal pour être « debout » : un surfeur allongé qui rame (0,45 à 0,8 mesuré) ne
     * déclenche pas. Un surfeur en manœuvre est presque aussi large que haut (0,9 à 1,0), d'où un seuil à 0,9.
     */
    val minStandingAspect: Float = 0.9f,
    /** Confiance minimale du détecteur pour déclencher (0 = pas d'exigence) ; relevée par l'apprentissage si besoin. */
    val minTriggerConfidence: Float = 0f,
    /**
     * Part horizontale du déplacement pour déclencher : une vague se surfe le long de la vague, alors qu'un surfeur assis qui
     * monte et descend sur la houle bouge presque seulement à la verticale.
     */
    val minHorizontalShare: Float = 0.6f,
    /**
     * Un surfeur qui disparaît en plein mouvement (il sort de l'image à ×5, il est avalé par l'écume) a surfé une vague même si
     * on ne l'a suivi que peu de temps : la durée minimale tombe à [minRideLeavingUs] s'il bougeait encore moins de
     * [leaveGraceUs] avant de disparaître.
     */
    val minRideLeavingUs: Long = 0,
    val leaveGraceUs: Long = 800_000,
)

/**
 * Ce qui caractérise une vague détectée : de quoi apprendre, plus tard, si elle était bonne ou fausse. Les valeurs de
 * déclenchement sont celles que le détecteur a comparées à ses seuils, ce qui permet de les régler après coup.
 */
data class RideStats(
    val durationUs: Long,
    /** Vitesse (longueurs de corps/s) mesurée au moment du déclenchement. */
    val triggerSpeed: Float,
    /** Rapport hauteur/largeur de la boîte au moment du déclenchement. */
    val triggerAspect: Float,
    /** Confiance moyenne du détecteur pendant la vague. */
    val meanConfidence: Float,
    /** Hauteur moyenne du surfeur à l'image, en pixels de l'image d'analyse. */
    val meanHeightPx: Float,
)

sealed interface WaveEvent {
    /** Une vague est en cours depuis [takeoffUs] : le clip doit démarrer à ce moment moins la marge avant. */
    data class Started(
        val takeoffUs: Long,
        val trackId: Int,
        /** Mesures au déclenchement (pour le rapport) : vitesse en longueurs de corps/s, part horizontale, rapport h/l, confiance, hauteur de boîte. */
        val speed: Float = 0f,
        val horizontalShare: Float = 0f,
        val aspect: Float = 0f,
        val confidence: Float = 0f,
        val boxHeight: Float = 0f,
    ) : WaveEvent

    data class Ended(val takeoffUs: Long, val endUs: Long, val trackId: Int, val stats: RideStats? = null) : WaveEvent

    /** Le mouvement détecté était trop court pour être une vague : le clip en cours est à jeter. */
    data class Cancelled(
        val takeoffUs: Long,
        val trackId: Int,
        val stats: RideStats? = null,
        /** Le suivi s'est arrêté parce que le surfeur a disparu (et non parce qu'il a ralenti). */
        val endedByLoss: Boolean = false,
    ) : WaveEvent
}

/**
 * Décide quand une vague commence et finit, à partir de la vitesse des surfeurs suivis.
 * Machine à états : attente -> vague en cours -> fin (ou annulation si trop courte). Une seule vague à la fois.
 */
class WaveDetector(private val config: WaveConfig = WaveConfig()) {

    private val movingSince = HashMap<Int, Long>()
    private val lastEvidence = HashMap<Int, Long>()

    private var ridingTrackId: Int? = null
    private var takeoffUs = 0L
    private var lastMovingUs = 0L
    private var lastSeenUs = 0L

    // Mesures de la vague en cours (pour RideStats).
    private var triggerSpeed = 0f
    private var triggerAspect = 0f
    private var confidenceSum = 0f
    private var heightSum = 0f
    private var samples = 0

    val isRiding: Boolean get() = ridingTrackId != null

    /** Numéro de suivi du surfeur sur sa vague, null en attente. */
    val ridingId: Int? get() = ridingTrackId

    fun onFrame(tracks: List<Track>, timestampUs: Long): WaveEvent? {
        // Un suivi non vu à cette image (surfeur masqué, tombé) garde sa dernière position : on l'ignore.
        val visible = tracks.filter { it.lastSeenUs == timestampUs }
        val riding = ridingTrackId
        return if (riding == null) lookForStart(visible, timestampUs) else followRide(riding, visible, timestampUs)
    }

    fun reset() {
        movingSince.clear()
        lastEvidence.clear()
        ridingTrackId = null
    }

    private fun lookForStart(tracks: List<Track>, now: Long): WaveEvent? {
        val alive = tracks.map { it.id }.toSet()
        // Une piste non visible garde sa préparation de départ pendant startGapUs (une détection manquée ne repart pas de zéro).
        movingSince.keys.retainAll { it in alive || now - (lastEvidence[it] ?: 0L) <= config.startGapUs }
        lastEvidence.keys.retainAll(movingSince.keys)
        for (track in tracks) {
            val speed = track.speedBodyLengthsPerSec(config.speedWindowUs)
            val aspect = track.meanAspect(config.speedWindowUs)
            val share = track.horizontalShare(config.speedWindowUs) ?: 0f
            if (share < config.minHorizontalShare) {
                movingSince.remove(track.id)
                continue
            }
            if (speed == null || speed < config.startSpeed || speed > config.maxStartSpeed || aspect == null || aspect < config.minStandingAspect ||
                track.confidence < config.minTriggerConfidence
            ) {
                movingSince.remove(track.id)
                continue
            }
            lastEvidence[track.id] = now
            val since = movingSince.getOrPut(track.id) { now }
            if (now - since >= config.confirmUs) {
                ridingTrackId = track.id
                // La vitesse est mesurée sur une fenêtre : le vrai début est environ une demi-fenêtre plus tôt.
                takeoffUs = since - config.speedWindowUs / 2
                lastMovingUs = now
                lastSeenUs = now
                triggerSpeed = speed
                triggerAspect = aspect
                confidenceSum = 0f
                heightSum = 0f
                samples = 0
                movingSince.clear()
                lastEvidence.clear()
                return WaveEvent.Started(takeoffUs, track.id, speed, share, aspect, track.confidence, track.box.height)
            }
        }
        return null
    }

    private fun followRide(id: Int, tracks: List<Track>, now: Long): WaveEvent? {
        val track = tracks.firstOrNull { it.id == id }
        if (track != null) {
            lastSeenUs = now
            confidenceSum += track.confidence
            heightSum += track.box.height
            samples++
            val speed = track.speedBodyLengthsPerSec(config.speedWindowUs)
            if (speed != null && speed >= config.stopSpeed) lastMovingUs = now
        }
        // Un surfeur qu'on ne voit plus n'est pas « arrêté » : sa fin se décide à la disparition (lostGraceUs), pas au ralentissement.
        val stopped = track != null && now - lastMovingUs >= config.endGraceUs
        val lost = now - lastSeenUs >= config.lostGraceUs
        if (!stopped && !lost) return null

        ridingTrackId = null
        val end = lastMovingUs
        val stats = RideStats(
            durationUs = end - takeoffUs,
            triggerSpeed = triggerSpeed,
            triggerAspect = triggerAspect,
            meanConfidence = if (samples > 0) confidenceSum / samples else 0f,
            meanHeightPx = if (samples > 0) heightSum / samples else 0f,
        )
        val duration = end - takeoffUs
        val leftWhileMoving = lost && !stopped && lastSeenUs - lastMovingUs <= config.leaveGraceUs
        val longEnough = duration >= config.minRideUs || (leftWhileMoving && duration >= config.minRideLeavingUs)
        return if (longEnough) {
            WaveEvent.Ended(takeoffUs, end, id, stats)
        } else {
            WaveEvent.Cancelled(takeoffUs, id, stats, endedByLoss = lost && !stopped)
        }
    }
}
