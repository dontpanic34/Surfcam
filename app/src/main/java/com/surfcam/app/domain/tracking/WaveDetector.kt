package com.surfcam.app.domain.tracking

data class WaveConfig(
    /** Fenêtre de mesure de la vitesse. */
    val speedWindowUs: Long = 800_000,
    /** Vitesse (longueurs de corps/s) à dépasser pour considérer qu'une vague démarre. */
    val startSpeed: Float = 1.0f,
    /** Vitesse en dessous de laquelle le surfeur est considéré à l'arrêt (hystérésis : plus bas que le départ). */
    val stopSpeed: Float = 0.5f,
    /** La vitesse de départ doit durer au moins ça avant de déclencher. */
    val confirmUs: Long = 600_000,
    /** Durée sous la vitesse d'arrêt avant de déclarer la fin de la vague. */
    val endGraceUs: Long = 1_500_000,
    /** Disparition du suivi (chute, écume) tolérée avant de déclarer la fin. */
    val lostGraceUs: Long = 2_000_000,
    /** Une vague plus courte que ça est annulée (surfeur qui rame vite, fausse alerte). */
    val minRideUs: Long = 3_000_000,
    /**
     * Rapport hauteur/largeur minimal pour être « debout » : un surfeur allongé qui rame (0,45 à 0,8 mesuré) ne
     * déclenche pas. Un surfeur en manœuvre est presque aussi large que haut (0,9 à 1,0), d'où un seuil à 0,9.
     */
    val minStandingAspect: Float = 0.9f,
)

sealed interface WaveEvent {
    /** Une vague est en cours depuis [takeoffUs] : le clip doit démarrer à ce moment moins la marge avant. */
    data class Started(val takeoffUs: Long, val trackId: Int) : WaveEvent

    data class Ended(val takeoffUs: Long, val endUs: Long, val trackId: Int) : WaveEvent

    /** Le mouvement détecté était trop court pour être une vague : le clip en cours est à jeter. */
    data class Cancelled(val takeoffUs: Long, val trackId: Int) : WaveEvent
}

/**
 * Décide quand une vague commence et finit, à partir de la vitesse des surfeurs suivis.
 * Machine à états : attente -> vague en cours -> fin (ou annulation si trop courte). Une seule vague à la fois.
 */
class WaveDetector(private val config: WaveConfig = WaveConfig()) {

    private val movingSince = HashMap<Int, Long>()

    private var ridingTrackId: Int? = null
    private var takeoffUs = 0L
    private var lastMovingUs = 0L
    private var lastSeenUs = 0L

    val isRiding: Boolean get() = ridingTrackId != null

    fun onFrame(tracks: List<Track>, timestampUs: Long): WaveEvent? {
        // Un suivi non vu à cette image (surfeur masqué, tombé) garde sa dernière position : on l'ignore.
        val visible = tracks.filter { it.lastSeenUs == timestampUs }
        val riding = ridingTrackId
        return if (riding == null) lookForStart(visible, timestampUs) else followRide(riding, visible, timestampUs)
    }

    fun reset() {
        movingSince.clear()
        ridingTrackId = null
    }

    private fun lookForStart(tracks: List<Track>, now: Long): WaveEvent? {
        val alive = tracks.map { it.id }.toSet()
        movingSince.keys.retainAll(alive)
        for (track in tracks) {
            val speed = track.speedBodyLengthsPerSec(config.speedWindowUs)
            val aspect = track.meanAspect(config.speedWindowUs)
            if (speed == null || speed < config.startSpeed || aspect == null || aspect < config.minStandingAspect) {
                movingSince.remove(track.id)
                continue
            }
            val since = movingSince.getOrPut(track.id) { now }
            if (now - since >= config.confirmUs) {
                ridingTrackId = track.id
                // La vitesse est mesurée sur une fenêtre : le vrai début est environ une demi-fenêtre plus tôt.
                takeoffUs = since - config.speedWindowUs / 2
                lastMovingUs = now
                lastSeenUs = now
                movingSince.clear()
                return WaveEvent.Started(takeoffUs, track.id)
            }
        }
        return null
    }

    private fun followRide(id: Int, tracks: List<Track>, now: Long): WaveEvent? {
        val track = tracks.firstOrNull { it.id == id }
        if (track != null) {
            lastSeenUs = now
            val speed = track.speedBodyLengthsPerSec(config.speedWindowUs)
            if (speed != null && speed >= config.stopSpeed) lastMovingUs = now
        }
        val stopped = now - lastMovingUs >= config.endGraceUs
        val lost = now - lastSeenUs >= config.lostGraceUs
        if (!stopped && !lost) return null

        ridingTrackId = null
        val end = lastMovingUs
        return if (end - takeoffUs >= config.minRideUs) {
            WaveEvent.Ended(takeoffUs, end, id)
        } else {
            WaveEvent.Cancelled(takeoffUs, id)
        }
    }
}
