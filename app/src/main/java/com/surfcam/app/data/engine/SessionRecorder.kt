package com.surfcam.app.data.engine

import android.content.Context
import android.media.MediaFormat
import com.surfcam.app.data.storage.ClipWriter
import com.surfcam.app.data.storage.SdMover
import com.surfcam.app.data.storage.StorageTarget
import com.surfcam.app.domain.model.EncodedSample
import com.surfcam.app.domain.naming.VideoNaming
import java.util.concurrent.Executors

/**
 * Enregistre toute la session en fichiers MP4 de [segmentSec] secondes (2 minutes : c'est le maximum perdu en cas de coupure brutale). Un fichier MP4 n'est lisible que s'il a
 * été refermé proprement : en cas de panne (batterie, plantage), seul le segment en cours est perdu, pas la session.
 * Chaque segment commence sur une image clé. L'écriture se fait sur son propre thread.
 */
class SessionRecorder(
    private val context: Context,
    private val orientationHint: Int,
    private val target: StorageTarget,
    private val segmentSec: Int = 120,
    /** Nom de chaque morceau : session principale, ou témoin large. */
    private val namer: (Long, Int) -> String = { ms, part -> VideoNaming.sessionPartName(ms, part) },
    private val onEvent: (parts: Int, message: String?) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "session-writer") }

    @Volatile
    var format: MediaFormat? = null

    // Touchés uniquement depuis le thread de l'exécuteur.
    private var writer: ClipWriter? = null
    private var segmentStartUs = -1L
    private var parts = 0
    private var failed = false
    private var retries = 0
    private var currentPartName = ""
    private var currentFinalVolume = StorageTarget.PHONE
    private var retryAfterUs = Long.MIN_VALUE

    fun onSample(sample: EncodedSample) {
        executor.execute { write(sample) }
    }

    fun release() {
        executor.execute { closeWriter() }
        executor.shutdown()
    }

    private fun write(sample: EncodedSample) {
        if (failed) return
        val fmt = format ?: return
        if (sample.ptsUs < retryAfterUs) return
        try {
            val dueForNewSegment = writer == null ||
                (sample.isKeyFrame && sample.ptsUs - segmentStartUs >= segmentSec * 1_000_000L)
            if (dueForNewSegment) {
                if (!sample.isKeyFrame) return
                closeWriter()
                val free = target.phoneFreeBytes()
                if (free != null && free < MIN_FREE_BYTES) {
                    failed = true
                    onEvent(parts, "Espace insuffisant : l'enregistrement complet est arrêté (les clips continuent)")
                    return
                }
                parts++
                val partName = namer(System.currentTimeMillis(), parts)
                writer = ClipWriter(context, fmt, orientationHint, partName, StorageTarget.PHONE)
                currentPartName = partName
                currentFinalVolume = target.volume
                SessionLog.noteFile(context, partName)
                segmentStartUs = sample.ptsUs
                retries = 0
                onEvent(parts, null)
            }
            writer?.write(sample)
        } catch (e: Exception) {
            try {
                closeWriter()
            } catch (_: Exception) {
            }
            writer = null
            // L'écriture se fait sur le téléphone (la copie vers la carte est faite à part par SdMover) : une erreur ici est locale.
            SessionLog.ev("stockage", "écriture de la session interrompue : ${e.javaClass.simpleName} ${e.message}")
            failed = true
            onEvent(parts, "Enregistrement complet interrompu : ${e.message}")
        }
    }

    private fun closeWriter() {
        val saved = writer?.close()
        writer = null
        if (saved != null) SdMover.enqueue(context, saved.uri, saved.name + ".mp4", currentFinalVolume, "Movies/SurfCam")
    }

    private companion object {
        const val MAX_RETRIES = 6
        const val RETRY_DELAY_US = 3_000_000L
        // En dessous de 900 Mo libres, on n'ouvre pas de nouveau segment (le garde-fou du moteur coupe à 800 Mo).
        const val MIN_FREE_BYTES = 900L * 1024 * 1024
    }
}
