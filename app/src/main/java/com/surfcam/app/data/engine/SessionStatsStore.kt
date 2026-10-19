package com.surfcam.app.data.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ce que la session a coûté en batterie. */
data class SessionStats(
    val startMs: Long,
    val durationMs: Long,
    val percentStart: Int,
    val percentEnd: Int,
    /** Charge consommée en mAh d'après le compteur de la batterie, ou null si le téléphone ne la donne pas. */
    val milliampHours: Int?,
    /** Le téléphone a été branché pendant la session : la consommation n'est pas mesurable. */
    val charged: Boolean,
) {
    val percentUsed: Int get() = percentStart - percentEnd

    /** Pourcentage de batterie perdu par heure, ou null si ce n'est pas mesurable. */
    val percentPerHour: Float?
        get() = if (charged || durationMs < 5 * 60_000L) null else percentUsed * 3_600_000f / durationMs

    /** Phrase lisible : « Batterie : 78 % → 61 % en 1 h 32, soit environ 11 % par heure (≈ 640 mAh) ». */
    fun describe(): String {
        if (charged) return "Batterie : le téléphone était branché, la consommation n'est pas mesurable."
        val minutes = durationMs / 60_000
        val time = if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
        return buildString {
            append("Batterie : $percentStart % → $percentEnd % en $time")
            percentPerHour?.let { append(", soit environ ${"%.0f".format(it)} % par heure") }
            milliampHours?.takeIf { it > 0 }?.let { append(" (≈ $it mAh)") }
        }
    }
}

/** Mémorise la consommation de chaque session, pour l'afficher sur sa carte dans « Mes vidéos ». */
class SessionStatsStore(context: Context) {
    private val file = File(context.filesDir, "session_stats.json")

    @Synchronized
    fun all(): List<SessionStats> {
        if (!file.exists()) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                SessionStats(
                    o.getLong("start"), o.getLong("dur"), o.getInt("p0"), o.getInt("p1"),
                    if (o.isNull("mah")) null else o.getInt("mah"), o.getBoolean("charged"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(stats: SessionStats) {
        val list = (all() + stats).takeLast(200)
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("start", it.startMs).put("dur", it.durationMs).put("p0", it.percentStart).put("p1", it.percentEnd)
                    .put("mah", it.milliampHours ?: JSONObject.NULL).put("charged", it.charged),
            )
        }
        file.writeText(a.toString())
    }

    /** Consommation de la session qui a commencé à peu près à [startMs] (à 3 minutes près). */
    fun forSession(startMs: Long, all: List<SessionStats> = all()): SessionStats? =
        all.minByOrNull { kotlin.math.abs(it.startMs - startMs) }?.takeIf { kotlin.math.abs(it.startMs - startMs) <= 3 * 60_000L }
}
