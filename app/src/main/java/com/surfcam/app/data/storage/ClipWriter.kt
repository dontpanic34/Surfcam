package com.surfcam.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.surfcam.app.domain.model.EncodedSample
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedClip(val name: String, val durationSec: Float, val sizeBytes: Long)

/**
 * Écrit des images déjà encodées dans un MP4, sans réencoder, dans Movies/SurfCam (visible dans la galerie).
 * Le premier échantillon écrit doit être une image clé ; les échantillons dont l'horodatage ne progresse pas sont ignorés.
 */
class ClipWriter(
    context: Context,
    format: MediaFormat,
    orientationHint: Int,
    namePrefix: String = "SurfCam",
    nameSuffix: String = "",
    /** Volume MediaStore où créer le fichier (le téléphone par défaut, ou une carte SD / clé USB). */
    volume: String = StorageTarget.PHONE,
) {
    private val resolver = context.contentResolver
    private val name = namePrefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + nameSuffix + ".mp4"
    private val uri: Uri
    private val descriptor: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val track: Int
    private val info = MediaCodec.BufferInfo()

    private var baseUs = -1L
    private var lastUs = -1L
    private var written = 0

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(StorageTarget.videoUri(volume), values)
            ?: throw IllegalStateException("Impossible de créer le fichier vidéo")
        try {
            descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Impossible d'ouvrir le fichier vidéo")
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationHint)
            track = muxer.addTrack(format)
            muxer.start()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun write(sample: EncodedSample) {
        if (baseUs < 0) {
            if (!sample.isKeyFrame) return
            baseUs = sample.ptsUs
        }
        val t = sample.ptsUs - baseUs
        if (t <= lastUs) return
        lastUs = t
        val flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        info.set(0, sample.data.size, t, flags)
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
        written++
    }

    /** Termine le fichier ; renvoie null (et supprime le fichier) si rien n'a pu être écrit, ou si [discard]. */
    fun close(discard: Boolean = false): SavedClip? {
        val ok = try {
            muxer.stop()
            true
        } catch (_: IllegalStateException) {
            false
        }
        muxer.release()
        descriptor.close()
        if (discard || !ok || written == 0) {
            resolver.delete(uri, null, null)
            return null
        }
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        return SavedClip(name, lastUs / 1_000_000f, size)
    }
}
