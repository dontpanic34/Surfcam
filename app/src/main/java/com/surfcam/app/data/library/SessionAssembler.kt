package com.surfcam.app.data.library

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.surfcam.app.data.storage.StorageTarget
import java.nio.ByteBuffer

/**
 * Met bout à bout les morceaux d'une session en un seul film MP4, sans réencoder : les images compressées sont recopiées
 * telles quelles (chaque morceau commence sur une image clé). Rapide et sans perte de qualité. Le film est écrit
 * directement dans les vidéos du téléphone ; en cas d'échec ou d'annulation, le fichier partiel est supprimé.
 */
class SessionAssembler(private val context: Context) {

    @Volatile
    var cancelled = false

    /** Place libre sur le téléphone, en octets. */
    fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    /**
     * @param parts morceaux dans l'ordre du temps
     * @return null si tout s'est bien passé, sinon le message d'erreur
     */
    fun assemble(parts: List<LibraryVideo>, displayName: String, onProgress: (Float) -> Unit): String? {
        if (parts.isEmpty()) return "Aucun morceau à assembler"
        val total = parts.sumOf { it.sizeBytes }
        if (freeBytes() < total + 1_000_000_000L) {
            return "Pas assez de place : il faut ${total / 1_000_000_000L + 1} Go libres de plus pour assembler."
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(StorageTarget.videoUri(StorageTarget.PHONE), values) ?: return "Création du fichier impossible"
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: return fail(uri, "Écriture du fichier impossible")
            descriptor.use { fd ->
                muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var firstFormat: MediaFormat? = null
                var offsetUs = 0L
                var doneBytes = 0L
                val buffer = ByteBuffer.allocate(BUFFER_BYTES)
                val info = MediaCodec.BufferInfo()
                for (part in parts) {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, part.uri, null)
                        val videoTrack = (0 until extractor.trackCount).firstOrNull {
                            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                        } ?: return fail(uri, "Pas d'image dans un morceau")
                        extractor.selectTrack(videoTrack)
                        val format = extractor.getTrackFormat(videoTrack)
                        if (firstFormat == null) {
                            firstFormat = format
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer!!.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                            trackIndex = muxer!!.addTrack(format)
                            muxer!!.start()
                            muxerStarted = true
                        } else if (format.getInteger(MediaFormat.KEY_WIDTH) != firstFormat.getInteger(MediaFormat.KEY_WIDTH) ||
                            format.getInteger(MediaFormat.KEY_HEIGHT) != firstFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        ) {
                            return fail(uri, "Les morceaux n'ont pas tous la même définition : assemblage impossible")
                        }
                        var firstPts = -1L
                        var previous = -1L
                        var last = 0L
                        var frameDelta = 33_333L
                        while (true) {
                            if (cancelled) return fail(uri, "Assemblage annulé")
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val pts = extractor.sampleTime
                            if (firstPts < 0) firstPts = pts
                            val relative = pts - firstPts
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, size, relative + offsetUs, flags)
                            muxer!!.writeSampleData(trackIndex, buffer, info)
                            if (previous >= 0 && relative > previous) frameDelta = relative - previous
                            previous = relative
                            last = relative
                            doneBytes += size
                            extractor.advance()
                            if (doneBytes % PROGRESS_EVERY < size) onProgress((doneBytes.toFloat() / total).coerceAtMost(1f))
                        }
                        offsetUs += last + frameDelta
                    } finally {
                        extractor.release()
                    }
                }
                muxer!!.stop()
                muxerStarted = false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            onProgress(1f)
            return null
        } catch (e: Exception) {
            return fail(uri, "Assemblage interrompu : ${e.message}")
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun fail(uri: android.net.Uri, message: String): String {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
        return message
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024 * 1024
        const val PROGRESS_EVERY = 8L * 1024 * 1024
    }
}
