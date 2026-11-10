package com.surfcam.app.domain.tracking

import kotlin.math.abs

class LegacyWaveDetector(private val config: WaveConfig = WaveConfig()) {

    private val movingSince = HashMap<Int, Long>()

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
        ridingTrackId = null
    }

    private fun lookForStart(tracks: List<Track>, now: Long): WaveEvent? {
        val alive = tracks.map { it.id }.toSet()
        movingSince.keys.retainAll(alive)
        for (track in tracks) {
            val speed = track.speedBodyLengthsPerSec(config.speedWindowUs)
            val aspect = track.meanAspect(config.speedWindowUs)
            val share = track.horizontalShare(config.speedWindowUs) ?: 0f
            if (share < config.minHorizontalShare) {
                movingSince.remove(track.id)
                continue
            }
            if (speed == null || speed < config.startSpeed || aspect == null || aspect < config.minStandingAspect ||
                track.confidence < config.minTriggerConfidence
            ) {
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
                triggerSpeed = speed
                triggerAspect = aspect
                confidenceSum = 0f
                heightSum = 0f
                samples = 0
                movingSince.clear()
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
        val stopped = now - lastMovingUs >= config.endGraceUs
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
        val leftWhileMoving = false
        val longEnough = duration >= config.minRideUs || (leftWhileMoving && duration >= config.minRideLeavingUs)
        return if (longEnough) {
            WaveEvent.Ended(takeoffUs, end, id, stats)
        } else {
            WaveEvent.Cancelled(takeoffUs, id, stats, endedByLoss = lost && !stopped)
        }
    }
}
