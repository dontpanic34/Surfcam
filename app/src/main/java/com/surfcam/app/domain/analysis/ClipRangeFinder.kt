package com.surfcam.app.domain.analysis

import com.surfcam.app.domain.clip.ClipAction
import com.surfcam.app.domain.clip.ClipPlanner
import com.surfcam.app.domain.tracking.Detection
import com.surfcam.app.domain.tracking.SurferTracker
import com.surfcam.app.domain.tracking.TrackerConfig
import com.surfcam.app.domain.tracking.WaveConfig
import com.surfcam.app.domain.tracking.WaveDetector

/** Un passage à garder dans une session déjà filmée, en microsecondes depuis le début de la session. */
data class ClipRange(val startUs: Long, val endUs: Long)

/**
 * Retrouve les vagues dans une session déjà enregistrée : mêmes suivi, détection de vague et marges (5 s avant, 3 s
 * après) que pendant l'enregistrement, mais le résultat est une liste de passages à découper plutôt que des ordres
 * d'enregistrement. On lui donne les personnes trouvées image après image, dans l'ordre du temps.
 */
class ClipRangeFinder(
    waveConfig: WaveConfig = WaveConfig(),
    frameHeight: Int = 720,
    preRollUs: Long = 5_000_000,
    postRollUs: Long = 3_000_000,
) {
    private val tracker = SurferTracker(TrackerConfig(frameHeight = frameHeight))
    private val detector = WaveDetector(waveConfig)
    private val planner = ClipPlanner(preRollUs, postRollUs)
    private var openFromUs: Long? = null

    fun onFrame(detections: List<Detection>, timestampUs: Long): List<ClipRange> {
        val tracks = tracker.update(detections, timestampUs)
        val event = detector.onFrame(tracks, timestampUs)
        val actions = (if (event != null) planner.onWave(event, timestampUs) else emptyList()) + planner.onTick(timestampUs)
        return actions.mapNotNull(::apply)
    }

    /** Fin de la vidéo : un passage encore ouvert est fermé à [endUs]. */
    fun finish(endUs: Long): List<ClipRange> {
        val from = openFromUs ?: return emptyList()
        openFromUs = null
        return if (endUs > from) listOf(ClipRange(from, endUs)) else emptyList()
    }

    private fun apply(action: ClipAction): ClipRange? = when (action) {
        is ClipAction.Start -> {
            openFromUs = maxOf(0L, action.fromUs)
            null
        }
        is ClipAction.Stop -> openFromUs?.let { ClipRange(it, action.atUs) }.also { openFromUs = null }
        ClipAction.Discard -> {
            openFromUs = null
            null
        }
    }
}
