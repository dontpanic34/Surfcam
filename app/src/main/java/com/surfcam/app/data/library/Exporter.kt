package com.surfcam.app.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.surfcam.app.data.engine.SessionLog
import com.surfcam.app.domain.naming.VideoNaming

/**
 * Exporte les vagues dans un dossier choisi une fois pour toutes (carte SD, dossier synchronisé avec Drive, clé USB…), avec
 * pour chaque vague son petit rapport du même nom, et le rapport de la session une seule fois.
 */
class Exporter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("export", Context.MODE_PRIVATE)

    fun folder(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)

    fun folderLabel(): String? = folder()?.let { Uri.decode(it.lastPathSegment ?: it.toString()) }

    /** Mémorise le dossier choisi (et garde le droit d'y écrire après le redémarrage). */
    fun setFolder(tree: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
        }
        prefs.edit().putString("tree", tree.toString()).apply()
    }

    private fun done(): Set<String> = prefs.getStringSet("done", emptySet()) ?: emptySet()

    private fun markDone(name: String) {
        prefs.edit().putStringSet("done", done() + name).apply()
    }

    private fun isWave(name: String): Boolean {
        val p = VideoNaming.parse(name) ?: return false
        return !p.isSession && !p.isFilm && !p.isMontage
    }

    /** Les vagues pas encore exportées, de la plus ancienne à la plus récente. */
    fun pending(all: List<LibraryVideo>): List<LibraryVideo> =
        all.filter { !it.pending && isWave(it.name) && it.name.removeSuffix(".mp4") !in done() }.sortedBy { it.modifiedMs }

    /** Les vagues de la dernière session, même déjà exportées. */
    fun lastSession(all: List<LibraryVideo>): List<LibraryVideo> {
        val names = SessionLog.sessionFiles(appContext).toSet()
        return all.filter { !it.pending && isWave(it.name) && it.name.removeSuffix(".mp4") in names }.sortedBy { it.modifiedMs }
    }

    /** Copie les vagues [videos] et leurs rapports dans le dossier d'export. Renvoie un message de bilan. */
    fun export(videos: List<LibraryVideo>, deleteAfter: Boolean = false, onProgress: (Int, Int) -> Unit): String {
        val tree = folder() ?: return "Choisis d'abord le dossier d'export."
        val parent = try {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (e: Exception) {
            return "Dossier d'export illisible : choisis-le de nouveau."
        }
        var copied = 0
        var reports = 0
        var failure: String? = null
        var deleted = 0
        val library = VideoLibrary(appContext)
        for ((i, v) in videos.withIndex()) {
            onProgress(i, videos.size)
            try {
                val target = copy(v.uri, parent, v.name.removeSuffix(".mp4") + ".mp4", "video/mp4")
                markDone(v.name.removeSuffix(".mp4"))
                copied++
                // Suppression du téléphone seulement si la copie fait exactement la taille de l'original.
                val sourceSize = resolver.openFileDescriptor(v.uri, "r")?.use { it.statSize } ?: -1L
                val verified = sourceSize > 0 && sizeOf(target) == sourceSize
                val report = findDownload(v.name.removeSuffix(".mp4") + " - rapport.txt")
                if (report != null) {
                    copy(report, parent, v.name.removeSuffix(".mp4") + " - rapport.txt", "text/plain")
                    reports++
                }
                if (deleteAfter && verified && library.delete(v)) deleted++
            } catch (e: Exception) {
                failure = "${e.javaClass.simpleName} : ${e.message}"
            }
        }
        // Le rapport complet de la session, une seule fois.
        SessionLog.lastExport(appContext)?.let { (uri, name) ->
            if (name !in done()) {
                try {
                    copy(uri, parent, name, "text/plain")
                    markDone(name)
                } catch (_: Exception) {
                }
            }
        }
        onProgress(videos.size, videos.size)
        return buildString {
            append("$copied vague(s) exportée(s) avec $reports rapport(s)")
            if (deleteAfter) append(", $deleted supprimée(s) du téléphone après vérification de la copie")
            if (failure != null) append(". Un fichier n'a pas pu être copié ($failure)")
            append(".")
        }
    }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun copy(source: Uri, parent: Uri, displayName: String, mime: String): Uri {
        val target = DocumentsContract.createDocument(resolver, parent, mime, displayName) ?: throw IllegalStateException("création impossible : $displayName")
        resolver.openInputStream(source)?.use { input ->
            resolver.openOutputStream(target, "w")?.use { output -> input.copyTo(output) } ?: throw IllegalStateException("écriture impossible : $displayName")
        } ?: throw IllegalStateException("lecture impossible : $displayName")
        return target
    }

    private fun findDownload(displayName: String): Uri? = try {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(displayName), null,
        )?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
        }
    } catch (_: Exception) {
        null
    }
}
