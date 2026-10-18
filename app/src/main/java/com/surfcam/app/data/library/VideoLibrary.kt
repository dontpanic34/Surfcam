package com.surfcam.app.data.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import com.surfcam.app.data.storage.StorageTarget

/** Une vidéo enregistrée par l'app. [pending] : le fichier est encore en cours d'écriture ou de finalisation. */
data class LibraryVideo(
    val id: Long,
    val volume: String,
    val volumeLabel: String,
    val uri: Uri,
    val name: String,
    val modifiedMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val pending: Boolean,
) {
    val isSession: Boolean get() = name.startsWith("SurfCam_Session")

    /** Vrai si la vidéo est sur un support externe (carte SD, clé USB) plutôt que sur le téléphone. */
    val isExternal: Boolean get() = volume != StorageTarget.PHONE

    /** Identifiant unique toutes sources confondues (un même numéro peut exister sur deux supports). */
    val key: String get() = "$volume:$id"
}

/** Liste les vidéos du dossier Movies/SurfCam sur tous les supports, y compris celles qui ne sont pas encore terminées. */
class VideoLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val storage = StorageTarget(appContext)

    fun list(): List<LibraryVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.IS_PENDING,
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Movies/SurfCam%"))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Video.Media.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val result = ArrayList<LibraryVideo>()
        for (volume in storage.available()) {
            val collection = StorageTarget.videoUri(volume.name)
            try {
                resolver.query(collection, projection, args, null)?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val pendingCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        result += LibraryVideo(
                            id = id,
                            volume = volume.name,
                            volumeLabel = volume.label,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(nameCol) ?: "vidéo",
                            modifiedMs = c.getLong(dateCol) * 1000L,
                            durationMs = c.getLong(durCol),
                            sizeBytes = c.getLong(sizeCol),
                            pending = c.getInt(pendingCol) == 1,
                        )
                    }
                }
            } catch (_: Exception) {
                // Un support qui vient d'être retiré ou pas encore indexé : on l'ignore, il réapparaîtra au prochain passage.
            }
        }
        return result.sortedByDescending { it.modifiedMs }
    }

    fun delete(video: LibraryVideo): Boolean = try {
        resolver.delete(video.uri, null, null) > 0
    } catch (_: Exception) {
        false
    }

    fun thumbnail(video: LibraryVideo, width: Int, height: Int): Bitmap? = try {
        resolver.loadThumbnail(video.uri, Size(width, height), null)
    } catch (_: Exception) {
        null
    }

    /**
     * Copie une vidéo d'un support externe vers le téléphone (dossier Movies/SurfCam). L'original reste en place.
     * [onProgress] reçoit une valeur de 0 à 1. Renvoie false si la copie a échoué : le fichier partiel est alors supprimé.
     */
    fun copyToPhone(video: LibraryVideo, onProgress: (Float) -> Unit): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, video.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(StorageTarget.videoUri(StorageTarget.PHONE), values) ?: return false
        return try {
            val total = video.sizeBytes.coerceAtLeast(1)
            var copied = 0L
            val buffer = ByteArray(1 shl 20)
            resolver.openInputStream(video.uri)!!.use { input ->
                resolver.openOutputStream(target)!!.use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        onProgress((copied.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(target, null, null)
            false
        }
    }
}
