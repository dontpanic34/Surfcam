package com.surfcam.app.domain.zoom

import com.surfcam.app.domain.geometry.DistanceEstimator
import kotlin.math.roundToInt

data class AutoZoomConfig(
    /** Durée sur laquelle on médiane la taille des surfeurs avant de décider. */
    val windowMs: Long = 15_000,
    /** Nombre minimal d'observations dans la fenêtre. */
    val minSamples: Int = 5,
    /** Délai minimal entre deux changements de zoom : le cadrage ne saute pas sans cesse. */
    val minChangeIntervalMs: Long = 25_000,
    /** Sans aucun surfeur pendant ce temps, on revient au grand champ pour en chercher. */
    val searchAfterMs: Long = 60_000,
    /** On ne bouge pas tant que le zoom voulu reste entre ces deux rapports du zoom actuel. */
    val keepBelowRatio: Float = 0.75f,
    val keepAboveRatio: Float = 1.35f,
    /** Zoom voulu entre ces bornes : on prend le téléobjectif optique ×5 plutôt qu'un zoom numérique. */
    val opticalFrom: Float = 3.5f,
    val opticalTo: Float = 7.5f,
    val opticalZoom: Float = 5f,
)

/**
 * Décide du zoom tout seul : serré (×5) quand les surfeurs sont petits, large (×1) quand ils sont gros ou qu'il n'y en a
 * plus en vue. Ne bouge jamais pendant une vague, ni trop souvent ; la taille observée est normalisée à ×1 pour rester
 * comparable après un changement de zoom.
 */
class AutoZoomController(private val config: AutoZoomConfig = AutoZoomConfig()) {

    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lastSeenMs = Long.MIN_VALUE
    private var lastChangeMs = Long.MIN_VALUE / 2

    /**
     * @param surferHeightPx hauteur médiane des surfeurs vus sur cette image (null s'il n'y en a aucun)
     * @param busy vague en cours ou clip en cours d'enregistrement : on ne touche à rien
     * @return le nouveau zoom à appliquer, ou null pour ne rien changer
     */
    fun onFrame(
        nowMs: Long,
        surferHeightPx: Float?,
        frameHeightPx: Float,
        zoom: Float,
        minZoom: Float,
        maxZoom: Float,
        busy: Boolean,
    ): Float? {
        if (surferHeightPx != null && surferHeightPx >= 2f) {
            samples.addLast(nowMs to surferHeightPx / zoom)
            lastSeenMs = nowMs
        }
        while (samples.isNotEmpty() && nowMs - samples.first().first > config.windowMs) samples.removeFirst()
        if (busy || nowMs - lastChangeMs < config.minChangeIntervalMs) return null

        if (samples.isEmpty()) {
            val seenLongAgo = lastSeenMs == Long.MIN_VALUE || nowMs - lastSeenMs >= config.searchAfterMs
            if (seenLongAgo && zoom > minZoom + 0.3f && lastSeenMs != Long.MIN_VALUE) return change(nowMs, minZoom)
            return null
        }
        if (samples.size < config.minSamples) return null

        val heightAt1x = samples.map { it.second }.sorted()[samples.size / 2]
        val wanted = DistanceEstimator.TARGET_HEIGHT_FRACTION * frameHeightPx / heightAt1x
        val ratio = wanted / zoom
        if (ratio in config.keepBelowRatio..config.keepAboveRatio) return null

        val target = when {
            wanted in config.opticalFrom..config.opticalTo -> config.opticalZoom
            else -> (wanted * 2f).roundToInt() / 2f
        }.coerceIn(minZoom, maxZoom)
        if (kotlin.math.abs(target - zoom) < 0.01f) return null
        return change(nowMs, target)
    }

    private fun change(nowMs: Long, zoom: Float): Float {
        lastChangeMs = nowMs
        return zoom
    }

    /** Un réglage manuel de l'utilisateur : on repart de zéro et on lui laisse la main un moment. */
    fun onManualChange(nowMs: Long) {
        lastChangeMs = nowMs + MANUAL_HOLD_MS - config.minChangeIntervalMs
        samples.clear()
    }

    private companion object {
        const val MANUAL_HOLD_MS = 120_000L
    }
}
