package com.surfcam.app.data.library

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size

/** Une vidéo enregistrée par l'app. [pending] : le fichier est encore en cours d'écriture ou de finalisation. */
data class LibraryVideo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val modifiedMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val pending: Boolean,
) {
    val isSession: Boolean get() = name.startsWith("SurfCam_Session")
}

/** Liste les vidéos du dossier Movies/SurfCam, y compris celles qui ne sont pas encore terminées. */
class VideoLibrary(context: Context) {

    private val resolver = context.applicationContext.contentResolver

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
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use { c ->
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
                    uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    name = c.getString(nameCol) ?: "vidéo",
                    modifiedMs = c.getLong(dateCol) * 1000L,
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                    pending = c.getInt(pendingCol) == 1,
                )
            }
        }
        return result
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
}
