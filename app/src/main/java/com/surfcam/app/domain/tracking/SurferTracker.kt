package com.surfcam.app.domain.tracking

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class TrackerConfig(
    /** Confiance minimale pour ouvrir un nouveau suivi. */
    val minConfidence: Float = 0.25f,
    /** Distance maximale d'association, en longueurs de corps, entre deux images consécutives. */
    val maxJumpInBodyLengths: Float = 1.2f,
    /** Tolérance supplémentaire par seconde écoulée depuis la dernière détection (image ratée, surfeur masqué). */
    val extraJumpPerSecond: Float = 2.0f,
    /** Lissage de la vitesse estimée, utilisée pour prédire la position suivante. */
    val velocitySmoothing: Float = 0.6f,
    /** Un suivi sans détection depuis plus longtemps que ça est abandonné. */
    val maxMissingUs: Long = 2_000_000,
    /** Durée d'historique gardée par suivi, pour mesurer les vitesses. */
    val historyUs: Long = 3_000_000,
    /** Lissage de la boîte : 1 = brut, proche de 0 = très lissé. */
    val boxSmoothing: Float = 0.6f,
    /** Hauteur de l'image analysée, en pixels ; 0 = ne pas filtrer le bord bas. */
    val frameHeight: Int = 0,
    /**
     * Les personnes dont la boîte touche le bord bas de l'image (fraction de la hauteur) sont ignorées :
     * ce sont des gens sur la plage, au premier plan, pas des surfeurs dans l'eau.
     */
    val bottomMarginFraction: Float = 0.03f,
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

    /** Confiance du détecteur, lissée sur les dernières images. */
    var confidence: Float = first.confidence
        internal set

    /** Vitesse estimée du centre de la boîte, en pixels par seconde. */
    var velocityX: Float = 0f
        internal set
    var velocityY: Float = 0f
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
     * Vitesse sur les [windowUs] dernières microsecondes, en longueurs de corps par seconde.
     * La longueur de corps est la plus grande dimension de la boîte (hauteur debout, largeur allongé) :
     * la vitesse ne dépend ni de la distance à la caméra, ni de la posture.
     * Renvoie null tant que l'historique est trop court pour la mesurer.
     */
    fun speedBodyLengthsPerSec(windowUs: Long): Float? {
        val last = points.lastOrNull() ?: return null
        val first = points.firstOrNull { last.timestampUs - it.timestampUs <= windowUs } ?: return null
        val dt = (last.timestampUs - first.timestampUs) / 1_000_000f
        if (dt < windowUs / 1_000_000f * 0.5f) return null
        val meanLength = points.filter { it.timestampUs >= first.timestampUs }
            .map { max(it.height, it.width) }.average().toFloat()
        if (meanLength <= 0f) return null
        return hypot(last.x - first.x, last.y - first.y) / meanLength / dt
    }

    /**
     * Rapport hauteur/largeur moyen de la boîte sur les [windowUs] dernières microsecondes :
     * environ 1,5 et plus pour quelqu'un de debout, moins de 0,8 pour quelqu'un d'allongé sur sa planche.
     */
    fun meanAspect(windowUs: Long): Float? {
        val last = points.lastOrNull() ?: return null
        val recent = points.filter { last.timestampUs - it.timestampUs <= windowUs && it.width > 0f }
        if (recent.isEmpty()) return null
        return recent.map { it.height / it.width }.average().toFloat()
    }
}

/**
 * Associe, image après image, les détections aux surfeurs déjà suivis (plus proche voisin, borné par la taille
 * du corps). Les identifiants évitent de passer d'un surfeur à un autre quand ils se croisent.
 */
class SurferTracker(private val config: TrackerConfig = TrackerConfig()) {

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun update(allDetections: List<Detection>, timestampUs: Long): List<Track> {
        val detections = if (config.frameHeight > 0) {
            val limit = config.frameHeight * (1f - config.bottomMarginFraction)
            allDetections.filter { it.box.bottom < limit }
        } else {
            allDetections
        }
        val candidates = mutableListOf<Triple<Float, Track, Detection>>()
        for (track in tracks) {
            val dt = (timestampUs - track.lastSeenUs) / 1_000_000f
            val predictedX = track.box.centerX + track.velocityX * dt
            val predictedY = track.box.centerY + track.velocityY * dt
            val gate = config.maxJumpInBodyLengths + config.extraJumpPerSecond * dt
            for (det in detections) {
                val length = max(max(track.box.height, track.box.width), max(det.box.height, det.box.width)).coerceAtLeast(1f)
                val fromPrediction = hypot(predictedX - det.box.centerX, predictedY - det.box.centerY)
                val fromLastPosition = hypot(track.box.centerX - det.box.centerX, track.box.centerY - det.box.centerY)
                val cost = min(fromPrediction, fromLastPosition) / length
                if (cost <= gate) candidates += Triple(cost, track, det)
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
            val dtSec = (timestampUs - track.lastSeenUs) / 1_000_000f
            if (dtSec > 0f) {
                val v = config.velocitySmoothing
                track.velocityX = v * (det.box.centerX - old.centerX) / dtSec + (1 - v) * track.velocityX
                track.velocityY = v * (det.box.centerY - old.centerY) / dtSec + (1 - v) * track.velocityY
            }
            track.box = BoundingBox(
                a * det.box.left + (1 - a) * old.left,
                a * det.box.top + (1 - a) * old.top,
                a * det.box.right + (1 - a) * old.right,
                a * det.box.bottom + (1 - a) * old.bottom,
            )
            track.lastSeenUs = timestampUs
            track.confidence = 0.5f * det.confidence + 0.5f * track.confidence
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
