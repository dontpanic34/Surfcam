package com.surfcam.app.domain.tracking

import kotlin.math.hypot
import kotlin.math.max

data class TrackerConfig(
    /** Confiance minimale pour ouvrir un nouveau suivi. */
    val minConfidence: Float = 0.25f,
    /** Distance maximale d'association, en hauteurs de corps : au-delà, c'est une autre personne. */
    val maxJumpInBodyHeights: Float = 1.5f,
    /** Un suivi sans détection depuis plus longtemps que ça est abandonné. */
    val maxMissingUs: Long = 2_000_000,
    /** Durée d'historique gardée par suivi, pour mesurer les vitesses. */
    val historyUs: Long = 3_000_000,
    /** Lissage de la boîte : 1 = brut, proche de 0 = très lissé. */
    val boxSmoothing: Float = 0.6f,
)

/** Un point de la trajectoire d'un surfeur. */
data class TrackPoint(val timestampUs: Long, val x: Float, val y: Float, val height: Float, val width: Float)

/** Un surfeur suivi dans le temps. */
class Track(val id: Int, first: Detection, private val historyUs: Long) {
    var box: BoundingBox = first.box
        internal set
    var lastSeenUs: Long = first.timestampUs
        internal set
    val firstSeenUs: Long = first.timestampUs
    var hits: Int = 1
        internal set

    private val points = ArrayDeque<TrackPoint>()

    init {
        record(first.timestampUs, first.box)
    }

    internal fun record(timestampUs: Long, b: BoundingBox) {
        points.addLast(TrackPoint(timestampUs, b.centerX, b.centerY, b.height, b.width))
        while (points.size > 1 && timestampUs - points.first().timestampUs > historyUs) points.removeFirst()
    }

    /**
     * Vitesse sur les [windowUs] dernières microsecondes, en hauteurs de corps par seconde.
     * Normalisée par la taille du surfeur : elle ne dépend donc pas de sa distance à la caméra.
     * Renvoie null tant que l'historique est trop court pour la mesurer.
     */
    fun speedBodyHeightsPerSec(windowUs: Long): Float? {
        val last = points.lastOrNull() ?: return null
        val first = points.firstOrNull { last.timestampUs - it.timestampUs <= windowUs } ?: return null
        val dt = (last.timestampUs - first.timestampUs) / 1_000_000f
        if (dt < windowUs / 1_000_000f * 0.5f) return null
        val meanHeight = points.filter { it.timestampUs >= first.timestampUs }.map { it.height }.average().toFloat()
        if (meanHeight <= 0f) return null
        return hypot(last.x - first.x, last.y - first.y) / meanHeight / dt
    }
}

/**
 * Associe, image après image, les détections aux surfeurs déjà suivis (plus proche voisin, borné par la taille
 * du corps). Les identifiants évitent de passer d'un surfeur à un autre quand ils se croisent.
 */
class SurferTracker(private val config: TrackerConfig = TrackerConfig()) {

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun update(detections: List<Detection>, timestampUs: Long): List<Track> {
        val candidates = mutableListOf<Triple<Float, Track, Detection>>()
        for (track in tracks) {
            for (det in detections) {
                val scale = max(track.box.height, det.box.height).coerceAtLeast(1f)
                val cost = hypot(track.box.centerX - det.box.centerX, track.box.centerY - det.box.centerY) / scale
                if (cost <= config.maxJumpInBodyHeights) candidates += Triple(cost, track, det)
            }
        }
        val usedTracks = HashSet<Int>()
        val usedDetections = HashSet<Detection>()
        for ((_, track, det) in candidates.sortedBy { it.first }) {
            if (track.id in usedTracks || det in usedDetections) continue
            usedTracks += track.id
            usedDetections += det
            val a = config.boxSmoothing
            val old = track.box
            track.box = BoundingBox(
                a * det.box.left + (1 - a) * old.left,
                a * det.box.top + (1 - a) * old.top,
                a * det.box.right + (1 - a) * old.right,
                a * det.box.bottom + (1 - a) * old.bottom,
            )
            track.lastSeenUs = timestampUs
            track.hits++
            track.record(timestampUs, det.box)
        }
        for (det in detections) {
            if (det !in usedDetections && det.confidence >= config.minConfidence) {
                tracks += Track(nextId++, det, config.historyUs)
            }
        }
        tracks.removeAll { timestampUs - it.lastSeenUs > config.maxMissingUs }
        return tracks.toList()
    }

    fun reset() {
        tracks.clear()
    }
}
