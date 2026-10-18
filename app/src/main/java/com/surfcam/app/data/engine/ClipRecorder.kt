package com.surfcam.app.data.engine

import android.content.Context
import android.media.MediaFormat
import com.surfcam.app.data.storage.ClipWriter
import com.surfcam.app.data.storage.SavedClip
import com.surfcam.app.domain.buffer.EncodedRingBuffer
import com.surfcam.app.domain.model.EncodedSample
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Enregistre un clip : d'abord le contenu du tampon circulaire (le début de la vague, déjà passé),
 * puis les images qui arrivent ensuite, jusqu'à [finish]. Toute l'écriture se fait sur un thread unique.
 */
class ClipRecorder(
    private val context: Context,
    private val ring: EncodedRingBuffer,
    private val orientationHint: Int,
    private val onResult: (SavedClip?, String?) -> Unit,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "clip-writer")
    }

    @Volatile
    var format: MediaFormat? = null

    @Volatile
    var active = false
        private set

    /** Durée réellement reprise du tampon au début du dernier clip (diagnostic). */
    @Volatile
    var lastPreRollSec = 0f
        private set

    // Touché uniquement depuis le thread de l'exécuteur.
    private var writer: ClipWriter? = null

    /** Démarre un clip qui reprend les [preRollSec] dernières secondes du tampon (déclenchement manuel). */
    @Synchronized
    fun start(preRollSec: Int): Boolean {
        val newest = ring.newestPtsUs() ?: return false
        return startFrom(newest - preRollSec * 1_000_000L)
    }

    /** Démarre un clip dont le contenu commence à l'instant [fromPtsUs] du flux (ou à l'image clé juste avant). */
    @Synchronized
    fun startFrom(fromPtsUs: Long): Boolean {
        if (active) return false
        val fmt = format ?: return false
        val newest = ring.newestPtsUs() ?: return false
        val snapshot = ring.snapshotFrom(fromPtsUs)
        if (snapshot.isEmpty()) return false
        lastPreRollSec = (newest - snapshot.first().ptsUs) / 1_000_000f
        active = true
        executor.execute {
            try {
                val w = ClipWriter(context, fmt, orientationHint)
                writer = w
                snapshot.forEach { w.write(it) }
            } catch (e: Exception) {
                writer = null
                active = false
                onResult(null, "Clip impossible : ${e.message}")
            }
        }
        return true
    }

    /** À appeler pour chaque nouvelle image encodée. */
    fun onLiveSample(sample: EncodedSample) {
        if (!active) return
        executor.execute {
            try {
                writer?.write(sample)
            } catch (e: Exception) {
                abort("Écriture du clip interrompue : ${e.message}")
            }
        }
    }

    /** Jette le clip en cours : le fichier est supprimé (fausse alerte). */
    fun discard() = finish(0, discard = true)

    /** Termine le clip après [postRollMs] millisecondes d'images supplémentaires. */
    @Synchronized
    fun finish(postRollMs: Long, discard: Boolean = false) {
        if (!active) return
        executor.schedule({
            val w = writer
            writer = null
            active = false
            if (w != null) {
                try {
                    onResult(w.close(discard), null)
                } catch (e: Exception) {
                    onResult(null, "Clip non finalisé : ${e.message}")
                }
            }
        }, postRollMs, TimeUnit.MILLISECONDS)
    }

    /** Termine proprement un clip en cours puis arrête le thread d'écriture. */
    fun release() {
        finish(0)
        executor.shutdown()
    }

    private fun abort(message: String) {
        val w = writer
        writer = null
        active = false
        try {
            w?.close()
        } catch (_: Exception) {
        }
        onResult(null, message)
    }
}
