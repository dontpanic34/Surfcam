package com.surfcam.app.data.library

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.surfcam.app.data.storage.StorageTarget
import java.nio.ByteBuffer

/** Un morceau d'une session : où le lire et combien de temps il dure. */
data class SessionPart(val uri: Uri, val durationUs: Long)

/**
 * Découpe un passage dans une session (suite de morceaux) sans réencoder : les images compressées sont recopiées telles
 * quelles. Le clip commence sur l'image clé qui précède le début demandé (jusqu'à une seconde de marge en plus).
 */
class ClipCutter(private val context: Context) {

    /** Durée de chaque morceau, lue dans le fichier ; à défaut, [fallbackMs] donné par la bibliothèque. */
    fun measure(uris: List<Uri>, fallbackMs: List<Long>): List<SessionPart> = uris.mapIndexed { i, uri ->
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = videoTrack(extractor)
            val us = if (track >= 0 && extractor.getTrackFormat(track).containsKey(MediaFormat.KEY_DURATION)) {
                extractor.getTrackFormat(track).getLong(MediaFormat.KEY_DURATION)
            } else 0L
            SessionPart(uri, if (us > 0) us else fallbackMs.getOrElse(i) { 0L } * 1000L)
        } catch (_: Exception) {
            SessionPart(uri, fallbackMs.getOrElse(i) { 0L } * 1000L)
        } finally {
            extractor.release()
        }
    }

    /** @return null si tout s'est bien passé, sinon le message d'erreur */
    fun cut(parts: List<SessionPart>, startUs: Long, endUs: Long, displayName: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(StorageTarget.videoUri(StorageTarget.PHONE), values) ?: return "Création du fichier impossible"
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: return fail(uri, "Écriture du fichier impossible")
            descriptor.use { fd ->
                muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var baseUs = -1L
                var partStart = 0L
                val buffer = ByteBuffer.allocate(BUFFER_BYTES)
                val info = MediaCodec.BufferInfo()
                var finished = false
                for (part in parts) {
                    val from = partStart
                    partStart += part.durationUs
                    if (partStart <= startUs) continue
                    if (from >= endUs) break
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, part.uri, null)
                        val track = videoTrack(extractor)
                        if (track < 0) return fail(uri, "Pas d'image dans un morceau")
                        extractor.selectTrack(track)
                        val format = extractor.getTrackFormat(track)
                        if (trackIndex < 0) {
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer!!.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                            trackIndex = muxer!!.addTrack(format)
                            muxer!!.start()
                            started = true
                        }
                        val firstPts = extractor.sampleTime.coerceAtLeast(0L)
                        val local = (startUs - from).coerceAtLeast(0L)
                        if (local > 0) extractor.seekTo(firstPts + local, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val global = from + (extractor.sampleTime - firstPts)
                            if (global > endUs) {
                                finished = true
                                break
                            }
                            if (baseUs < 0) baseUs = global
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, size, global - baseUs, flags)
                            muxer!!.writeSampleData(trackIndex, buffer, info)
                            extractor.advance()
                        }
                    } finally {
                        extractor.release()
                    }
                    if (finished) break
                }
                if (!started) return fail(uri, "Passage introuvable dans la session")
                muxer!!.stop()
                started = false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return null
        } catch (e: Exception) {
            return fail(uri, "Découpe interrompue : ${e.message}")
        } finally {
            try {
                if (started) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun videoTrack(extractor: MediaExtractor): Int = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    } ?: -1

    private fun fail(uri: Uri, message: String): String {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
        return message
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024 * 1024
    }
}
