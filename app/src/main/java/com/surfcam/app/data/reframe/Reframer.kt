package com.surfcam.app.data.reframe

import android.content.Context
import android.graphics.Matrix
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.domain.reframe.CropPath
import java.io.File

/**
 * Produit une version recadrée d'un clip : une caméra virtuelle suit le surfeur et zoome (trajectoire de [ReframePlanner]),
 * sortie en 1080p H.265 sans le son. À appeler depuis le thread principal. Le fichier est écrit dans le cache de l'app ;
 * c'est à l'appelant de le ranger dans les vidéos.
 */
class Reframer(private val context: Context) {

    private var transformer: Transformer? = null
    private val progressHolder = ProgressHolder()

    val outputFile: File = File(context.cacheDir, "reframe.mp4")

    /** Avancement de 0 à 100, ou -1 si inconnu. */
    fun progress(): Int {
        val t = transformer ?: return -1
        return if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) progressHolder.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    fun start(video: LibraryVideo, path: CropPath, onDone: (Boolean, String?) -> Unit) {
        outputFile.delete()
        val crop = MatrixTransformation { presentationTimeUs ->
            val c = path.at(presentationTimeUs / 1_000)
            val s = 1f / c.width
            // Coordonnées écran normalisées (−1 à 1, y vers le haut) : on centre la fenêtre sur le surfeur puis on agrandit.
            Matrix().apply {
                setScale(s, s)
                postTranslate(-(2f * c.cx - 1f) * s, (2f * c.cy - 1f) * s)
            }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(video.uri))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(crop, Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build()
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(24_000_000).build())
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone(true, null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    onDone(false, exportException.message ?: "Échec du recadrage")
                }
            })
            .build()
        transformer = t
        t.start(item, outputFile.absolutePath)
    }
}
