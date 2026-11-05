package com.surfcam.core.director

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Où se trouve le pic (la zone où partent les vagues), en azimut absolu.
 *
 * Part du cap désigné par l'utilisateur, puis se recale sur les départs de vague observés : médiane pondérée, les départs récents
 * comptant plus (demi-vie réglable). Le pic ne bouge que si plusieurs départs concordent, et jamais pendant une vague.
 */
class PeakEstimator(
    initialDeg: Double,
    private val halfLifeUs: Long = 15 * 60 * 1_000_000L,
    /** Poids du cap choisi par l'utilisateur, en « nombre de départs » équivalents. */
    private val userWeight: Double = 2.0,
    /** Le pic publié ne change que si l'estimation s'en écarte de plus de ça (degrés). */
    private val hysteresisDeg: Double = 3.0,
    private val minTakeoffs: Int = 3,
) {
    private data class Sample(val timeUs: Long, val azimuthDeg: Double, val weight: Double)

    private val samples = ArrayList<Sample>()
    private var userDeg = initialDeg
    private var userTimeUs = 0L

    var peakDeg: Double = initialDeg
        private set

    /** L'utilisateur désigne la direction actuelle comme pic : elle redevient la référence. */
    fun setByUser(azimuthDeg: Double, timeUs: Long) {
        userDeg = azimuthDeg
        userTimeUs = timeUs
        samples.clear()
        peakDeg = azimuthDeg
    }

    fun addTakeoff(azimuthDeg: Double, timeUs: Long, weight: Double = 1.0) {
        samples += Sample(timeUs, azimuthDeg, weight)
    }

    /** Recalcule le pic (à appeler entre deux vagues). */
    fun update(nowUs: Long): Double {
        if (samples.size < minTakeoffs) return peakDeg
        val decay = ln(2.0) / halfLifeUs
        val weighted = samples.map { it.azimuthDeg to it.weight * exp(-decay * (nowUs - it.timeUs)) } +
            (userDeg to userWeight * exp(-decay * (nowUs - userTimeUs)))
        val sorted = weighted.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        var acc = 0.0
        var median = sorted.last().first
        for ((az, w) in sorted) {
            acc += w
            if (acc >= total / 2) {
                median = az
                break
            }
        }
        if (abs(median - peakDeg) > hysteresisDeg) peakDeg = median
        return peakDeg
    }
}
