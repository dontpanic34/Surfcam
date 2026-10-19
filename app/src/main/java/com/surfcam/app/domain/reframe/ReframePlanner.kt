package com.surfcam.app.domain.reframe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Cadre d'un surfeur sur une image, en fractions de la largeur et de la hauteur de l'image (0 à 1). */
data class PathBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val riding: Boolean) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Les surfeurs vus à un instant du clip ([tMs] depuis le début du clip). */
data class PathFrame(val tMs: Long, val boxes: List<PathBox>)

/** Fenêtre de recadrage : centre et largeur en fractions de l'image. La hauteur suit (même format que l'image). */
data class CropSample(val tMs: Long, val cx: Float, val cy: Float, val width: Float)

class CropPath(private val samples: List<CropSample>) {
    val isEmpty: Boolean get() = samples.isEmpty()

    fun at(tMs: Long): CropSample {
        if (samples.isEmpty()) return CropSample(tMs, 0.5f, 0.5f, 1f)
        if (tMs <= samples.first().tMs) return samples.first()
        if (tMs >= samples.last().tMs) return samples.last()
        var i = samples.indexOfLast { it.tMs <= tMs }
        i = i.coerceIn(0, samples.size - 2)
        val a = samples[i]
        val b = samples[i + 1]
        val f = (tMs - a.tMs).toFloat() / max(1L, b.tMs - a.tMs)
        return CropSample(tMs, a.cx + (b.cx - a.cx) * f, a.cy + (b.cy - a.cy) * f, a.width + (b.width - a.width) * f)
    }
}

/**
 * Calcule, à partir du chemin du surfeur pendant un clip, la trajectoire d'une caméra virtuelle : elle suit le surfeur et
 * zoome pour qu'il reste d'une taille lisible. Le clip étant entièrement connu, le lissage se fait dans les deux sens du
 * temps : pas de retard, pas de secousses, comme un cadreur qui connaîtrait la vague à l'avance.
 */
object ReframePlanner {
    /** Part de la hauteur de la fenêtre que le surfeur doit occuper. */
    private const val TARGET_HEIGHT = 0.30f
    private const val TARGET_WIDTH = 0.45f
    private const val MIN_WIDTH = 0.25f
    private const val STEP_MS = 100L
    private const val ALPHA_POSITION = 0.25f
    private const val ALPHA_ZOOM = 0.08f

    fun plan(frames: List<PathFrame>): CropPath {
        val usable = frames.filter { it.boxes.isNotEmpty() }.sortedBy { it.tMs }
        if (usable.isEmpty()) return CropPath(emptyList())
        val chosen = chooseTargets(usable)
        val end = frames.maxOf { it.tMs }
        val start = frames.minOf { it.tMs }
        val steps = ((end - start) / STEP_MS).toInt() + 1

        // Mesures brutes aux instants où l'on a un surfeur, puis grille régulière par interpolation.
        val times = usable.map { it.tMs }
        val cxRaw = chosen.map { it.centerX }
        val cyRaw = chosen.map { it.centerY }
        val wRaw = chosen.map { b -> min(1f, max(MIN_WIDTH, max(b.height / TARGET_HEIGHT, b.width / TARGET_WIDTH))) }
        val cx = FloatArray(steps) { interpolate(times, cxRaw, start + it * STEP_MS) }
        val cy = FloatArray(steps) { interpolate(times, cyRaw, start + it * STEP_MS) }
        val w = FloatArray(steps) { interpolate(times, wRaw, start + it * STEP_MS) }
        smooth(cx, ALPHA_POSITION)
        smooth(cy, ALPHA_POSITION)
        smooth(w, ALPHA_ZOOM)

        return CropPath(
            List(steps) { i ->
                val width = w[i].coerceIn(MIN_WIDTH, 1f)
                CropSample(
                    start + i * STEP_MS,
                    cx[i].coerceIn(width / 2f, 1f - width / 2f),
                    cy[i].coerceIn(width / 2f, 1f - width / 2f),
                    width,
                )
            },
        )
    }

    /** Un surfeur par image : celui qui surfe ; avant et après, celui qui est le plus proche de lui. */
    private fun chooseTargets(frames: List<PathFrame>): List<PathBox> {
        val n = frames.size
        val result = arrayOfNulls<PathBox>(n)
        for (i in 0 until n) {
            result[i] = frames[i].boxes.filter { it.riding }.maxByOrNull { it.width * it.height }
        }
        val firstRiding = result.indexOfFirst { it != null }
        if (firstRiding < 0) {
            // Personne n'est marqué en train de surfer : on suit le plus grand, en gardant la continuité.
            var previous: PathBox? = null
            for (i in 0 until n) {
                val boxes = frames[i].boxes
                val pick = previous?.let { p -> boxes.minByOrNull { dist(it, p) } } ?: boxes.maxByOrNull { it.width * it.height }!!
                result[i] = pick
                previous = pick
            }
        } else {
            var previous = result[firstRiding]!!
            for (i in firstRiding - 1 downTo 0) {
                val pick = frames[i].boxes.minByOrNull { dist(it, previous) }!!
                result[i] = pick
                previous = pick
            }
            previous = result[firstRiding]!!
            for (i in firstRiding + 1 until n) {
                val pick = result[i] ?: frames[i].boxes.minByOrNull { dist(it, previous) }!!
                result[i] = pick
                previous = pick
            }
        }
        return result.map { it!! }
    }

    private fun dist(a: PathBox, b: PathBox): Float = abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)

    private fun interpolate(times: List<Long>, values: List<Float>, t: Long): Float {
        if (t <= times.first()) return values.first()
        if (t >= times.last()) return values.last()
        val i = times.indexOfLast { it <= t }.coerceIn(0, times.size - 2)
        val f = (t - times[i]).toFloat() / max(1L, times[i + 1] - times[i])
        return values[i] + (values[i + 1] - values[i]) * f
    }

    /** Lissage dans les deux sens : l'image n'est ni en retard ni en avance sur le surfeur. */
    private fun smooth(values: FloatArray, alpha: Float) {
        for (i in 1 until values.size) values[i] = values[i - 1] + alpha * (values[i] - values[i - 1])
        for (i in values.size - 2 downTo 0) values[i] = values[i + 1] + alpha * (values[i] - values[i + 1])
    }
}
