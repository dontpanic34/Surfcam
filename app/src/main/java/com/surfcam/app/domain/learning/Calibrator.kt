package com.surfcam.app.domain.learning

import com.surfcam.app.domain.tracking.RideStats
import com.surfcam.app.domain.tracking.WaveConfig

/** Une vague détectée et le jugement de l'utilisateur : bonne vague, ou fausse alerte. */
data class LabeledRide(val stats: RideStats, val good: Boolean)

data class CalibrationResult(
    val config: WaveConfig,
    val goodCount: Int,
    val badCount: Int,
    /** Changements de réglage appliqués, en clair, pour les montrer à l'utilisateur. */
    val changes: List<String>,
    /** Pourquoi rien n'a bougé, le cas échéant. */
    val note: String?,
)

/**
 * Règle les seuils de la détection d'après les jugements de l'utilisateur : pour chaque critère (vitesse, posture,
 * durée, confiance), cherche le seuil qui garde le plus de bonnes vagues en écartant le plus de fausses alertes.
 * Les changements sont plafonnés à l'intérieur de bornes raisonnables et à 70 % de l'écart suggéré, pour qu'un
 * seul retour n'emporte pas tout. Un jeu de réglages n'est adopté que s'il ne fait pas moins bien sur les exemples.
 */
object Calibrator {

    const val MIN_EXAMPLES = 4

    private data class Feature(
        val label: String,
        val value: (RideStats) -> Float,
        val get: (WaveConfig) -> Float,
        val set: (WaveConfig, Float) -> WaveConfig,
        val min: Float,
        val max: Float,
    )

    private val features = listOf(
        Feature("vitesse minimale", { it.triggerSpeed }, { it.startSpeed }, { c, v -> c.copy(startSpeed = v) }, 0.8f, 2.5f),
        Feature("posture debout minimale", { it.triggerAspect }, { it.minStandingAspect }, { c, v -> c.copy(minStandingAspect = v) }, 0.8f, 1.6f),
        Feature("durée minimale (s)", { it.durationUs / 1_000_000f }, { it.minRideUs / 1_000_000f }, { c, v -> c.copy(minRideUs = (v * 1_000_000f).toLong()) }, 2.5f, 8f),
        Feature("confiance minimale", { it.meanConfidence }, { it.minTriggerConfidence }, { c, v -> c.copy(minTriggerConfidence = v) }, 0f, 0.6f),
    )

    fun calibrate(base: WaveConfig, examples: List<LabeledRide>): CalibrationResult {
        val good = examples.count { it.good }
        val bad = examples.size - good
        if (examples.size < MIN_EXAMPLES) {
            return CalibrationResult(base, good, bad, emptyList(), "Il faut au moins $MIN_EXAMPLES exemples (tu en as ${examples.size}).")
        }
        if (bad == 0) return CalibrationResult(base, good, bad, emptyList(), "Aucune fausse alerte signalée : rien à corriger.")
        if (good == 0) return CalibrationResult(base, good, bad, emptyList(), "Aucune bonne vague signalée : impossible de savoir quoi garder.")

        var config = base
        val changes = mutableListOf<String>()
        for (f in features) {
            val goods = examples.filter { it.good }.map { f.value(it.stats) }
            val bads = examples.filter { !it.good }.map { f.value(it.stats) }
            val suggested = bestThreshold(goods, bads) ?: continue
            val current = f.get(config)
            val target = (current + 0.7f * (suggested - current)).coerceIn(f.min, f.max)
            if (kotlin.math.abs(target - current) < 0.03f * maxOf(1f, current)) continue
            config = f.set(config, target)
            changes += "${f.label} : ${format(current)} → ${format(target)}"
        }
        if (changes.isEmpty()) return CalibrationResult(base, good, bad, emptyList(), "Les critères actuels ne séparent pas mieux tes exemples : réglages inchangés.")

        // On n'adopte les nouveaux réglages que s'ils font au moins aussi bien que les anciens sur les exemples.
        return if (score(config, examples) >= score(base, examples)) {
            CalibrationResult(config, good, bad, changes, null)
        } else {
            CalibrationResult(base, good, bad, emptyList(), "Les nouveaux réglages auraient fait moins bien sur tes exemples : réglages inchangés.")
        }
    }

    /** Vrai si une vague aurait été retenue avec ces réglages. */
    fun passes(config: WaveConfig, s: RideStats): Boolean =
        s.triggerSpeed >= config.startSpeed &&
            s.triggerAspect >= config.minStandingAspect &&
            s.durationUs >= config.minRideUs &&
            s.meanConfidence >= config.minTriggerConfidence

    /** Part de bonnes vagues gardées moins 1,5 fois la part de fausses alertes gardées. */
    private fun score(config: WaveConfig, examples: List<LabeledRide>): Float {
        val goods = examples.filter { it.good }
        val bads = examples.filter { !it.good }
        val keptGood = goods.count { passes(config, it.stats) }.toFloat() / goods.size
        val keptBad = if (bads.isEmpty()) 0f else bads.count { passes(config, it.stats) }.toFloat() / bads.size
        return keptGood - 1.5f * keptBad
    }

    /** Seuil (valeur >= seuil : on garde) qui garde les bonnes et écarte les fausses ; null si aucun ne sépare clairement. */
    private fun bestThreshold(goods: List<Float>, bads: List<Float>): Float? {
        val all = (goods + bads).distinct().sorted()
        if (all.size < 2) return null
        var bestScore = Float.NEGATIVE_INFINITY
        var best: Float? = null
        for (i in 0 until all.size - 1) {
            val t = (all[i] + all[i + 1]) / 2f
            val keptGood = goods.count { it >= t }.toFloat() / goods.size
            val keptBad = bads.count { it >= t }.toFloat() / bads.size
            val s = keptGood - 1.5f * keptBad
            if (keptGood >= 0.75f && s > bestScore) {
                bestScore = s
                best = t
            }
        }
        // Utile seulement s'il écarte vraiment des fausses alertes (score > ce que donne « tout garder »).
        val keepAll = 1f - 1.5f
        return if (best != null && bestScore > keepAll + 0.15f) best else null
    }

    private fun format(v: Float): String = "%.2f".format(v)
}
