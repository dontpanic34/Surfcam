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
    /** "auto" : clip gardé par l'appli ; "rejet" : clip rejeté mais conservé à part ; "récupéré" : rejet que l'utilisateur a gardé. */
    val source: String = "auto",
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
                    source = o.optString("source", "auto"),
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

    /** Mémorise un clip rejeté par l'appli (gardé à part) avec ses mesures : s'il est récupéré, ce sera un exemple de vraie vague. */
    @Synchronized
    fun recordRejected(clipName: String, stats: RideStats) {
        val all = records().filter { it.clipName != clipName } + FeedbackRecord(clipName, stats, null, "rejet")
        // On borne la mémoire : au plus 300 rejets non jugés.
        val rejects = all.filter { it.source == "rejet" && it.good == null }
        write(if (rejects.size > 300) all.filter { it !in rejects.take(rejects.size - 300) } else all)
    }

    /** L'utilisateur a récupéré un rejet : il devient une vraie vague connue (exemple positif), sous son nouveau nom. */
    @Synchronized
    fun markRecovered(rejectName: String, newName: String) {
        val all = records()
        val r = all.firstOrNull { it.clipName == rejectName.removeSuffix(".mp4") || it.clipName == rejectName } ?: return
        write(all.filter { it !== r } + FeedbackRecord(newName, r.stats, true, "récupéré"))
    }

    /** Les mesures d'un rejet (pour les afficher à côté du clip). */
    @Synchronized
    fun statsOf(clipName: String): RideStats? = records().firstOrNull { it.clipName == clipName.removeSuffix(".mp4") }?.stats

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
            // Plancher : à ×5 les surfeurs assis qui montent et descendent sur la houle bougent déjà à ~1 longueur de corps/s.
            startSpeed = prefs.getFloat(KEY_SPEED, base.startSpeed).coerceIn(base.startSpeed, 2.2f),
            minStandingAspect = prefs.getFloat(KEY_ASPECT, base.minStandingAspect).coerceAtMost(base.minStandingAspect),
            // Plafonds : l'apprentissage ne voit que les clips déjà acceptés (jamais ceux que la détection a écartés) ; livré à lui-même,
            // il relevait sans cesse les seuils (durée 4,8 s, confiance 0,44 le 05/11) et écartait de vraies vagues.
            minRideUs = prefs.getLong(KEY_DURATION, base.minRideUs).coerceIn(0L, base.minRideUs),
            minTriggerConfidence = prefs.getFloat(KEY_CONFIDENCE, base.minTriggerConfidence).coerceAtMost(0.35f),
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
                    .put("good", r.good ?: JSONObject.NULL)
                    .put("source", r.source),
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
