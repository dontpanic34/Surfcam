package com.surfcam.app.data.learning

import android.content.Context
import com.surfcam.app.domain.learning.CalibrationResult
import com.surfcam.app.domain.learning.Calibrator
import com.surfcam.app.domain.learning.LabeledRide
import com.surfcam.app.domain.tracking.RideStats
import com.surfcam.app.domain.tracking.WaveConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un clip créé par la détection automatique, avec ce qui le caractérise et le jugement de l'utilisateur (s'il y en a un). */
data class FeedbackRecord(
    val clipName: String,
    val stats: RideStats,
    /** true = bonne vague, false = fausse alerte, null = pas encore jugé. */
    val good: Boolean?,
)

/**
 * Mémoire de l'apprentissage : les clips automatiques et ce que l'utilisateur en a dit, et les réglages de détection qui en
 * découlent. Tout reste dans la mémoire privée de l'app, sur le téléphone.
 */
class LearningRepository(context: Context) {

    private val file = File(context.filesDir, "feedback.json")
    private val prefs = context.getSharedPreferences("learning", Context.MODE_PRIVATE)

    @Synchronized
    fun records(): List<FeedbackRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                FeedbackRecord(
                    clipName = o.getString("clip"),
                    stats = RideStats(
                        durationUs = o.getLong("duration"),
                        triggerSpeed = o.getDouble("speed").toFloat(),
                        triggerAspect = o.getDouble("aspect").toFloat(),
                        meanConfidence = o.getDouble("confidence").toFloat(),
                        meanHeightPx = o.getDouble("height").toFloat(),
                    ),
                    good = if (o.isNull("good")) null else o.getBoolean("good"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Mémorise un clip automatique qui vient d'être enregistré. */
    @Synchronized
    fun record(clipName: String, stats: RideStats) {
        write(records().filter { it.clipName != clipName } + FeedbackRecord(clipName, stats, null))
    }

    /** Enregistre le jugement de l'utilisateur sur un clip (null = retirer le jugement). */
    @Synchronized
    fun setJudgement(clipName: String, good: Boolean?) {
        write(records().map { if (it.clipName == clipName) it.copy(good = good) else it })
    }

    /** Un clip supprimé n'a plus à rester dans les exemples en attente de jugement. */
    @Synchronized
    fun forget(clipName: String) {
        val current = records()
        val record = current.firstOrNull { it.clipName == clipName } ?: return
        if (record.good == null) write(current - record)
    }

    /** Réglages de détection actuels : ceux appris, ou les réglages d'origine. */
    fun currentConfig(): WaveConfig {
        val base = WaveConfig()
        if (!prefs.contains(KEY_SPEED)) return base
        return base.copy(
            startSpeed = prefs.getFloat(KEY_SPEED, base.startSpeed),
            minStandingAspect = prefs.getFloat(KEY_ASPECT, base.minStandingAspect),
            minRideUs = prefs.getLong(KEY_DURATION, base.minRideUs),
            minTriggerConfidence = prefs.getFloat(KEY_CONFIDENCE, base.minTriggerConfidence),
        )
    }

    /** Dernier bilan de l'apprentissage, en clair, pour l'afficher. */
    fun lastReport(): String? = prefs.getString(KEY_REPORT, null)

    /** Recalcule les réglages d'après tous les jugements donnés, et les garde pour la prochaine session. */
    @Synchronized
    fun recalibrate(): CalibrationResult {
        val labeled = records().filter { it.good != null }.map { LabeledRide(it.stats, it.good!!) }
        val result = Calibrator.calibrate(WaveConfig(), labeled)
        val report = when {
            result.changes.isNotEmpty() -> "Réglages adaptés d'après ${labeled.size} exemples : " + result.changes.joinToString(" ; ")
            else -> result.note ?: "Réglages inchangés."
        }
        prefs.edit().apply {
            if (result.changes.isNotEmpty()) {
                putFloat(KEY_SPEED, result.config.startSpeed)
                putFloat(KEY_ASPECT, result.config.minStandingAspect)
                putLong(KEY_DURATION, result.config.minRideUs)
                putFloat(KEY_CONFIDENCE, result.config.minTriggerConfidence)
            }
            putString(KEY_REPORT, report)
        }.apply()
        return result
    }

    /** Efface les jugements et revient aux réglages d'origine. */
    @Synchronized
    fun reset() {
        file.delete()
        prefs.edit().clear().apply()
    }

    private fun write(list: List<FeedbackRecord>) {
        val array = JSONArray()
        list.forEach { r ->
            array.put(
                JSONObject()
                    .put("clip", r.clipName)
                    .put("duration", r.stats.durationUs)
                    .put("speed", r.stats.triggerSpeed.toDouble())
                    .put("aspect", r.stats.triggerAspect.toDouble())
                    .put("confidence", r.stats.meanConfidence.toDouble())
                    .put("height", r.stats.meanHeightPx.toDouble())
                    .put("good", r.good ?: JSONObject.NULL),
            )
        }
        file.writeText(array.toString())
    }

    private companion object {
        const val KEY_SPEED = "start_speed"
        const val KEY_ASPECT = "min_aspect"
        const val KEY_DURATION = "min_ride_us"
        const val KEY_CONFIDENCE = "min_confidence"
        const val KEY_REPORT = "report"
    }
}
