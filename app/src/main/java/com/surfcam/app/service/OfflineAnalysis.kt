package com.surfcam.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.surfcam.app.data.library.LibraryVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Une analyse de session en cours ou tout juste terminée. */
data class OfflineJob(
    val sessionStartMs: Long,
    val title: String,
    /** "Analyse", "Création des clips" ou "Terminé". */
    val phase: String = "Analyse",
    val progress: Float = 0f,
    val found: Int = 0,
    val startedAtMs: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    /** Bilan ou erreur, affiché à la fin. */
    val result: String? = null,
) {
    /** Minutes restantes estimées d'après la vitesse observée ; null tant que la mesure est trop courte. */
    fun etaMinutes(nowMs: Long = System.currentTimeMillis()): Int? {
        if (done || progress < 0.02f) return null
        val elapsed = nowMs - startedAtMs
        return ((elapsed / progress) * (1f - progress) / 60_000f).toInt() + 1
    }
}

/** État partagé entre le service d'analyse et la bibliothèque. */
object OfflineAnalysis {
    private val _job = MutableStateFlow<OfflineJob?>(null)
    val job: StateFlow<OfflineJob?> = _job

    @Volatile
    internal var cancelRequested = false

    internal fun set(job: OfflineJob?) {
        _job.value = job
    }

    fun cancel() {
        cancelRequested = true
    }

    fun dismiss() {
        if (_job.value?.done == true) _job.value = null
    }

    val running: Boolean get() = _job.value?.let { !it.done } == true

    /**
     * Lance l'analyse des [parts] (dans l'ordre) d'une session commencée à [sessionStartMs].
     * [existingWaves] : débuts et fins (ms, heure du téléphone) des vagues déjà enregistrées, à ne pas doubler.
     */
    fun start(context: Context, parts: List<LibraryVideo>, sessionStartMs: Long, title: String, existingWaves: List<Pair<Long, Long>>): Boolean {
        if (running || parts.isEmpty()) return false
        cancelRequested = false
        _job.value = OfflineJob(sessionStartMs, title)
        val intent = Intent(context, OfflineAnalysisService::class.java)
            .putExtra(OfflineAnalysisService.EXTRA_URIS, parts.map { it.uri.toString() }.toTypedArray())
            .putExtra(OfflineAnalysisService.EXTRA_DURATIONS, parts.map { it.durationMs }.toLongArray())
            .putExtra(OfflineAnalysisService.EXTRA_START_MS, sessionStartMs)
            .putExtra(OfflineAnalysisService.EXTRA_TITLE, title)
            .putExtra(OfflineAnalysisService.EXTRA_EXISTING, existingWaves.flatMap { listOf(it.first, it.second) }.toLongArray())
        ContextCompat.startForegroundService(context, intent)
        return true
    }
}
