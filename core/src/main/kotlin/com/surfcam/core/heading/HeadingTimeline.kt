package com.surfcam.core.heading

/**
 * Cap horizontal de la caméra au cours du temps (degrés, positif vers la droite, 0 = direction au départ du suivi).
 *
 * Sur le téléphone, chaque mesure du gyroscope ajoute un point (cap cumulé, à l'horloge du capteur) ; l'analyse demande ensuite
 * le cap **à l'heure où l'image a été prise**, pas à l'heure où elle est traitée : avec 250 à 450 ms d'inférence pendant un
 * pivotement à 3 °/s, l'écart serait de 1 à 1,5°, soit une centaine de pixels au téléobjectif.
 *
 * Les temps sont en microsecondes, sur une même horloge que les images (horloge monotone de démarrage du téléphone).
 */
class HeadingTimeline(private val capacity: Int = 8192) {

    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private var start = 0
    private var size = 0

    val isEmpty: Boolean @Synchronized get() = size == 0

    val lastTimeUs: Long? @Synchronized get() = if (size == 0) null else times[(start + size - 1) % capacity]

    val lastHeadingDeg: Double? @Synchronized get() = if (size == 0) null else values[(start + size - 1) % capacity]

    /** Ajoute une mesure ; les mesures doivent arriver dans l'ordre du temps (une mesure plus ancienne est ignorée). */
    @Synchronized
    fun add(timeUs: Long, headingDeg: Double) {
        if (size > 0) {
            val last = times[(start + size - 1) % capacity]
            if (timeUs <= last) return
        }
        if (size == capacity) {
            start = (start + 1) % capacity
            size--
        }
        val i = (start + size) % capacity
        times[i] = timeUs
        values[i] = headingDeg
        size++
    }

    /**
     * Cap à l'instant [timeUs], interpolé linéairement entre les deux mesures qui l'encadrent. Avant la première mesure ou
     * après la dernière, renvoie la mesure la plus proche (pas d'extrapolation). Null si aucune mesure.
     */
    @Synchronized
    fun at(timeUs: Long): Double? {
        if (size == 0) return null
        val first = times[start]
        val lastIndex = (start + size - 1) % capacity
        if (timeUs <= first) return values[start]
        if (timeUs >= times[lastIndex]) return values[lastIndex]
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[(start + mid) % capacity] <= timeUs) lo = mid else hi = mid
        }
        val a = (start + lo) % capacity
        val b = (start + hi) % capacity
        val span = (times[b] - times[a]).toDouble()
        if (span <= 0.0) return values[b]
        val f = (timeUs - times[a]) / span
        return values[a] + (values[b] - values[a]) * f
    }

    /** Vitesse de rotation moyenne (degrés par seconde) sur l'intervalle qui finit à [timeUs] et dure [windowUs]. */
    @Synchronized
    fun rateDegPerSec(timeUs: Long, windowUs: Long = 200_000): Double? {
        val a = at(timeUs - windowUs) ?: return null
        val b = at(timeUs) ?: return null
        return (b - a) / (windowUs / 1e6)
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }

    companion object {
        /**
         * Reconstitue un cap fin pour une session déjà enregistrée, à partir de ce que contient son rapport :
         * des relevés du gyroscope espacés (toutes les ~0,5 s) et les ordres envoyés au gimbal (angle, heure de départ, durée).
         *
         * Pendant un ordre, le cap suit une rampe entre sa valeur au départ et l'angle commandé ; entre deux ordres, il reste
         * constant. Chaque relevé du gyroscope recale ensuite la courbe : l'écart mesuré est réparti linéairement jusqu'au
         * relevé suivant, ce qui corrige à la fois la dérive et un ordre mal exécuté.
         */
        fun reconstruct(
            gyroSamples: List<Pair<Long, Double>>,
            orders: List<GimbalOrder>,
            stepUs: Long = 20_000,
        ): HeadingTimeline {
            require(gyroSamples.isNotEmpty()) { "aucun relevé du gyroscope" }
            val samples = gyroSamples.sortedBy { it.first }
            val sortedOrders = orders.sortedBy { it.startUs }
            val t0 = samples.first().first
            val t1 = samples.last().first
            // 1) Courbe commandée seule (part de 0).
            fun commanded(t: Long): Double {
                var h = 0.0
                for (o in sortedOrders) {
                    if (o.startUs >= t) break
                    val f = ((t - o.startUs).toDouble() / o.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    h += o.deltaDeg * f
                }
                return h
            }
            // 2) Écart (mesuré − commandé) à chaque relevé, interpolé entre relevés.
            val offsets = samples.map { it.first to (it.second - commanded(it.first)) }
            fun offset(t: Long): Double {
                if (t <= offsets.first().first) return offsets.first().second
                if (t >= offsets.last().first) return offsets.last().second
                var lo = 0
                var hi = offsets.size - 1
                while (hi - lo > 1) {
                    val mid = (lo + hi) ushr 1
                    if (offsets[mid].first <= t) lo = mid else hi = mid
                }
                val (ta, va) = offsets[lo]
                val (tb, vb) = offsets[hi]
                return va + (vb - va) * (t - ta).toDouble() / (tb - ta).coerceAtLeast(1)
            }
            val n = ((t1 - t0) / stepUs + 2).toInt()
            val timeline = HeadingTimeline(n.coerceAtLeast(2))
            var t = t0
            while (t <= t1 + stepUs) {
                timeline.add(t, commanded(t) + offset(t))
                t += stepUs
            }
            return timeline
        }
    }
}

/** Un ordre de rotation relative envoyé au gimbal : [deltaDeg] (positif à droite) en [durationUs], à partir de [startUs]. */
data class GimbalOrder(val startUs: Long, val deltaDeg: Double, val durationUs: Long)
