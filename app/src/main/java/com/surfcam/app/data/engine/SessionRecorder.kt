package com.surfcam.app.data.engine

import android.content.Context
import android.media.MediaFormat
import com.surfcam.app.data.storage.ClipWriter
import com.surfcam.app.data.storage.StorageTarget
import com.surfcam.app.domain.model.EncodedSample
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
        try {
            val dueForNewSegment = writer == null ||
                (sample.isKeyFrame && sample.ptsUs - segmentStartUs >= segmentSec * 1_000_000L)
            if (dueForNewSegment) {
                if (!sample.isKeyFrame) return
                closeWriter()
                val free = target.freeBytes()
                if (free != null && free < MIN_FREE_BYTES) {
                    failed = true
                    onEvent(parts, "Espace insuffisant : l'enregistrement complet est arrêté (les clips continuent)")
                    return
                }
                parts++
                writer = ClipWriter(context, fmt, orientationHint, "SurfCam_Session", "_partie%02d".format(parts), target.volume)
                segmentStartUs = sample.ptsUs
                onEvent(parts, null)
            }
            writer?.write(sample)
        } catch (e: Exception) {
            try {
                closeWriter()
            } catch (_: Exception) {
            }
            writer = null
            if (target.isExternal) {
                // Carte retirée ou câble débranché : on continue sur le téléphone, au prochain segment.
                target.fallBackToPhone("Stockage externe retiré : la session continue sur le téléphone")
                onEvent(parts, "Stockage externe retiré : la session continue sur le téléphone")
            } else {
                failed = true
                onEvent(parts, "Enregistrement complet interrompu : ${e.message}")
            }
        }
    }

    private fun closeWriter() {
        writer?.close()
        writer = null
    }

    private companion object {
        // En dessous de 900 Mo libres, on n'ouvre pas de nouveau segment (le garde-fou du moteur coupe à 800 Mo).
        const val MIN_FREE_BYTES = 900L * 1024 * 1024
    }
}
