package com.surfcam.core.tracking

import com.surfcam.core.model.Observation
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TrackerConfig(
    /** Confiance minimale du détecteur pour ouvrir une nouvelle piste. */
    val minConfidenceToOpen: Double = 0.30,
    /** Écart type de l'accélération d'un surfeur (degrés/s²) : laisse le filtre suivre un virage ou un départ. */
    val accelSigmaDegPerSec2: Double = 2.5,
    /** Bruit de mesure de la position : part de la longueur de corps (centre de boîte qui tremble) plus un plancher. */
    val measurementNoiseBodyFraction: Double = 0.18,
    val measurementNoiseFloorDeg: Double = 0.02,
    /** Vitesse initiale inconnue : écart type a priori (degrés/s). */
    val initialVelocitySigmaDegPerSec: Double = 4.0,
    /** Porte d'association : distance de Mahalanobis au carré (χ² à 2 degrés de liberté ; 13,8 ≈ 99,9 %). */
    val gateChi2: Double = 13.8,
    /** Porte élargie de la seconde chance réservée à la piste de l'utilisateur, et absence maximale pour y avoir droit. */
    val userSecondChanceChi2: Double = 30.0,
    val userSecondChanceMaxMissingUs: Long = 2_000_000,
    /** Rapport de tailles accepté entre la piste et la détection (une silhouette ne double pas de taille d'une image à l'autre). */
    val maxSizeRatio: Double = 2.2,
    /** Poids du désaccord de taille dans le coût d'association. */
    val sizeCostWeight: Double = 4.0,
    /** Une piste sans détection depuis plus longtemps est fermée. */
    val maxMissingUs: Long = 4_000_000,
    /** Une piste vue une seule fois et perdue depuis plus longtemps est fermée (fausse détection). */
    val tentativeMissingUs: Long = 1_000_000,
    /** Historique gardé par piste. */
    val historyUs: Long = 30_000_000,
    /** Manchon : part de la silhouette au-dessus de laquelle le manchon est « vu » sur cette image. */
    val markerSeenScore: Double = 0.004,
    /** Manchon : en dessous de cette hauteur (pixels), le manchon est trop petit pour qu'on puisse conclure à son absence. */
    val markerVisibleMinHeightPx: Double = 14.0,
)

/** Un point observé de la trajectoire d'une piste (repère fixe). */
data class TrackPoint(
    val timeUs: Long,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val heightDeg: Double,
    val widthDeg: Double,
    val confidence: Double,
    val edgeMargin: Double,
) {
    val bodyDeg: Double get() = max(heightDeg, widthDeg)
    val aspect: Double get() = if (widthDeg > 0) heightDeg / widthDeg else 0.0
}

/**
 * Filtre de Kalman à vitesse constante sur un axe (position, vitesse), en degrés. Deux filtres indépendants (azimut, élévation)
 * suffisent : les deux axes ne sont pas couplés pour un surfeur vu de la plage.
 */
class AxisFilter(position: Double, positionVar: Double, velocityVar: Double) {
    var x = position
        private set
    var v = 0.0
        private set
    // Covariance [[pxx, pxv], [pxv, pvv]]
    var pxx = positionVar
        private set
    private var pxv = 0.0
    var pvv = velocityVar
        private set

    fun predict(dt: Double, q: Double) {
        if (dt <= 0) return
        x += v * dt
        // P = F P Fᵀ + Q, F = [[1, dt],[0, 1]], Q d'accélération blanche (densité q).
        val dt2 = dt * dt
        val nxx = pxx + 2 * dt * pxv + dt2 * pvv + q * dt2 * dt / 3.0
        val nxv = pxv + dt * pvv + q * dt2 / 2.0
        val nvv = pvv + q * dt
        pxx = nxx; pxv = nxv; pvv = nvv
    }

    /** Variance de l'innovation pour une mesure de variance [r]. */
    fun innovationVar(r: Double): Double = pxx + r

    fun update(z: Double, r: Double) {
        val s = pxx + r
        val kx = pxx / s
        val kv = pxv / s
        val y = z - x
        x += kx * y
        v += kv * y
        val nxx = (1 - kx) * pxx
        val nxv = (1 - kx) * pxv
        val nvv = pvv - kv * pxv
        pxx = nxx; pxv = nxv; pvv = nvv
    }

    fun predictedAt(dt: Double): Double = x + v * dt
}

class Track internal constructor(val id: Int, first: Observation, config: TrackerConfig) {
    internal val az: AxisFilter
    internal val el: AxisFilter

    var lastSeenUs: Long = first.timeUs
        internal set
    val firstSeenUs: Long = first.timeUs
    var hits: Int = 1
        internal set
    /** Heure de la dernière prédiction (le filtre a été avancé jusque-là). */
    internal var filterTimeUs: Long = first.timeUs

    /** Taille du corps lissée (degrés). */
    var bodyDeg: Double = first.bodyDeg
        internal set
    var heightDeg: Double = first.heightDeg
        internal set
    var widthDeg: Double = first.widthDeg
        internal set
    var confidence: Double = first.confidence
        internal set
    var edgeMargin: Double = first.edgeMargin
        internal set

    /** Preuve cumulée que cette silhouette porte le manchon (log-cote) : > 0 = plutôt oui. */
    var markerLogOdds: Double = 0.0
        internal set
    val markerProbability: Double get() = 1.0 / (1.0 + kotlin.math.exp(-markerLogOdds))

    /**
     * Silhouette de l'utilisateur : devient vrai quand la preuve du manchon est forte, redevient faux si la silhouette est vue
     * nettement plusieurs fois sans manchon (l'identité a glissé sur quelqu'un d'autre). Une seule piste à la fois.
     */
    var isUser: Boolean = false
        internal set

    private val historyUs = config.historyUs
    val points = ArrayDeque<TrackPoint>()

    init {
        val r = measurementVar(first, config)
        az = AxisFilter(first.azimuthDeg, r, config.initialVelocitySigmaDegPerSec * config.initialVelocitySigmaDegPerSec)
        el = AxisFilter(first.elevationDeg, r, (config.initialVelocitySigmaDegPerSec / 2).let { it * it })
        record(first)
    }

    val azimuthDeg: Double get() = az.x
    val elevationDeg: Double get() = el.x
    val velocityAzDegPerSec: Double get() = az.v
    val velocityElDegPerSec: Double get() = el.v

    /** Vitesse estimée en longueurs de corps par seconde (repère fixe : la rotation du gimbal n'y entre pas). */
    val speedBodyPerSec: Double get() = sqrt(az.v * az.v + el.v * el.v) / bodyDeg.coerceAtLeast(1e-4)

    fun isVisibleAt(timeUs: Long) = lastSeenUs == timeUs

    internal fun record(o: Observation) {
        points.addLast(TrackPoint(o.timeUs, o.azimuthDeg, o.elevationDeg, o.heightDeg, o.widthDeg, o.confidence, o.edgeMargin))
        while (points.size > 1 && o.timeUs - points.first().timeUs > historyUs) points.removeFirst()
    }

    /** Points observés depuis [sinceUs]. */
    fun pointsSince(sinceUs: Long): List<TrackPoint> = points.filter { it.timeUs >= sinceUs }

    companion object {
        internal fun measurementVar(o: Observation, c: TrackerConfig): Double {
            val s = c.measurementNoiseBodyFraction * o.bodyDeg + c.measurementNoiseFloorDeg
            return s * s
        }
    }
}

/**
 * Pisteur de surfeurs dans le repère fixe de la plage (azimut, élévation en degrés).
 *
 * Différences avec l'ancien pisteur en pixels :
 * - un pivotement du gimbal ne déplace plus les pistes (les positions sont absolues) ;
 * - la position est prédite par un filtre de Kalman, dont l'incertitude grandit avec le temps sans détection : la porte s'élargit
 *   d'elle-même quand le surfeur disparaît dans l'écume, sans devenir énorme le reste du temps ;
 * - toutes les associations d'une image sont décidées ensemble (méthode hongroise) plutôt qu'au plus proche une par une ;
 * - une détection beaucoup plus grande ou plus petite que la piste ne peut pas la reprendre (échange d'identité typique).
 */
class Tracker(val config: TrackerConfig = TrackerConfig()) {

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    val active: List<Track> get() = tracks.toList()

    fun track(id: Int): Track? = tracks.firstOrNull { it.id == id }

    fun update(observations: List<Observation>, timeUs: Long): List<Track> {
        val q = config.accelSigmaDegPerSec2 * config.accelSigmaDegPerSec2
        for (t in tracks) {
            val dt = (timeUs - t.filterTimeUs) / 1e6
            if (dt > 0) {
                t.az.predict(dt, q)
                t.el.predict(dt, q)
                t.filterTimeUs = timeUs
            }
        }
        val cost = Array(tracks.size) { DoubleArray(observations.size) }
        for ((i, t) in tracks.withIndex()) {
            for ((j, o) in observations.withIndex()) {
                val ratio = o.bodyDeg / t.bodyDeg.coerceAtLeast(1e-6)
                if (ratio > config.maxSizeRatio || ratio < 1.0 / config.maxSizeRatio) {
                    cost[i][j] = Double.POSITIVE_INFINITY
                    continue
                }
                val r = Track.measurementVar(o, config)
                val sa = t.az.innovationVar(r)
                val se = t.el.innovationVar(r)
                val da = o.azimuthDeg - t.az.x
                val de = o.elevationDeg - t.el.x
                val d2 = da * da / sa + de * de / se
                if (d2 > config.gateChi2) {
                    cost[i][j] = Double.POSITIVE_INFINITY
                    continue
                }
                val lr = ln(ratio)
                cost[i][j] = d2 + config.sizeCostWeight * lr * lr
            }
        }
        val assignment = Hungarian.solve(cost)
        val used = BooleanArray(observations.size)
        for ((i, t) in tracks.withIndex()) {
            val j = assignment[i]
            if (j < 0) continue
            used[j] = true
            val o = observations[j]
            val r = Track.measurementVar(o, config)
            t.az.update(o.azimuthDeg, r)
            t.el.update(o.elevationDeg, r)
            t.lastSeenUs = timeUs
            t.hits++
            val a = 0.5
            t.bodyDeg = a * o.bodyDeg + (1 - a) * t.bodyDeg
            t.heightDeg = a * o.heightDeg + (1 - a) * t.heightDeg
            t.widthDeg = a * o.widthDeg + (1 - a) * t.widthDeg
            t.confidence = 0.5 * o.confidence + 0.5 * t.confidence
            t.edgeMargin = o.edgeMargin
            updateMarker(t, o)
            t.record(o)
        }
        // Seconde chance pour la piste de l'utilisateur : si elle n'a rien trouvé avec la porte normale (départ brusque, sortie
        // d'écume), une détection libre proche de sa position prédite la reprend avec une porte plus large plutôt que d'ouvrir
        // une piste anonyme qui ne porterait pas la preuve du manchon.
        for ((i, t) in tracks.withIndex()) {
            if (!t.isUser || assignment[i] >= 0 || timeUs - t.lastSeenUs > config.userSecondChanceMaxMissingUs) continue
            var bestJ = -1
            var bestD2 = config.userSecondChanceChi2
            for ((j, o) in observations.withIndex()) {
                if (used[j]) continue
                val ratio = o.bodyDeg / t.bodyDeg.coerceAtLeast(1e-6)
                if (ratio > config.maxSizeRatio * 1.5 || ratio < 1.0 / (config.maxSizeRatio * 1.5)) continue
                val r = Track.measurementVar(o, config)
                val da = o.azimuthDeg - t.az.x
                val de = o.elevationDeg - t.el.x
                val d2 = da * da / t.az.innovationVar(r) + de * de / t.el.innovationVar(r)
                if (d2 < bestD2) {
                    bestD2 = d2
                    bestJ = j
                }
            }
            if (bestJ < 0) continue
            used[bestJ] = true
            val o = observations[bestJ]
            val r = Track.measurementVar(o, config)
            t.az.update(o.azimuthDeg, r)
            t.el.update(o.elevationDeg, r)
            t.lastSeenUs = timeUs
            t.hits++
            t.bodyDeg = 0.5 * o.bodyDeg + 0.5 * t.bodyDeg
            t.heightDeg = 0.5 * o.heightDeg + 0.5 * t.heightDeg
            t.widthDeg = 0.5 * o.widthDeg + 0.5 * t.widthDeg
            t.confidence = 0.5 * o.confidence + 0.5 * t.confidence
            t.edgeMargin = o.edgeMargin
            updateMarker(t, o)
            t.record(o)
        }
        for ((j, o) in observations.withIndex()) {
            if (!used[j] && o.confidence >= config.minConfidenceToOpen) {
                val t = Track(nextId++, o, config)
                updateMarker(t, o)
                tracks += t
            }
        }
        tracks.removeAll {
            val missing = timeUs - it.lastSeenUs
            missing > config.maxMissingUs || (it.hits < 2 && missing > config.tentativeMissingUs)
        }
        // Une seule silhouette « utilisateur » : la mieux prouvée, en pénalisant celles qu'on ne voit plus.
        val users = tracks.filter { it.isUser }
        if (users.size > 1) {
            val keep = users.maxByOrNull { it.markerLogOdds - (timeUs - it.lastSeenUs) / 1e6 }
            for (u in users) if (u !== keep) u.isUser = false
        }
        return tracks.toList()
    }

    private fun updateMarker(t: Track, o: Observation) {
        val heightPx = o.source.box.height
        when {
            o.markerScore >= config.markerSeenScore -> t.markerLogOdds = min(t.markerLogOdds + 1.2, 6.0)
            // Silhouette bien visible sans le manchon : preuve contre (plus forte si elle est grande).
            heightPx >= config.markerVisibleMinHeightPx * 2 -> t.markerLogOdds = max(t.markerLogOdds - 0.6, -4.0)
            heightPx >= config.markerVisibleMinHeightPx -> t.markerLogOdds = max(t.markerLogOdds - 0.3, -4.0)
            else -> Unit // silhouette trop petite : l'absence de couleur ne prouve rien
        }
        if (!t.isUser && t.markerLogOdds >= 2.5) t.isUser = true
        if (t.isUser && t.markerLogOdds < 0.0) t.isUser = false
    }

    fun reset() {
        tracks.clear()
    }

    /** Distance angulaire (degrés) entre la position prédite d'une piste et un azimut donné. */
    fun azimuthGap(track: Track, azimuthDeg: Double) = abs(track.azimuthDeg - azimuthDeg)
}
