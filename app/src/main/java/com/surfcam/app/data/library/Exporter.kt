package com.surfcam.app.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.surfcam.app.data.engine.SessionLog
import com.surfcam.app.domain.naming.VideoNaming
import java.util.Locale

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
                // L'export conserve toujours l'original sur le téléphone, même si deleteAfter est demandé.
                val report = findDownload(v.name.removeSuffix(".mp4") + " - rapport.txt")
                if (report != null) {
                    copy(report, parent, v.name.removeSuffix(".mp4") + " - rapport.txt", "text/plain")
                    reports++
                }
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
            if (deleteAfter) append(". La copie originale est conservée sur le téléphone")
            if (failure != null) append(". Un fichier n'a pas pu être copié ($failure)")
            append(".")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Export complet rétabli depuis la V1 : conserve vagues, rejets, rapports et sessions sans supprimer les sources.
    // ---------------------------------------------------------------------------------------------------------------

    class Item(val folder: String, val name: String, val size: Long, val open: () -> java.io.InputStream?)

    fun everything(today: Boolean): List<Item> = everything(if (today) java.time.LocalDate.now() else null)

    fun everything(day: java.time.LocalDate?): List<Item> {
        val library = VideoLibrary(appContext)
        val zone = java.time.ZoneId.systemDefault()
        val start = (day ?: java.time.LocalDate.now()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = (day ?: java.time.LocalDate.now()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun inDay(ms: Long) = day == null || ms in start until end
        val videos = (library.list() + library.listRejected()).filter { !it.pending && inDay(it.modifiedMs) }
            .groupBy { it.name }.values.map { copies -> copies.maxBy { it.sizeBytes } }.sortedBy { it.modifiedMs }
        val items = ArrayList<Item>()
        for (v in videos) {
            val base = v.name.removeSuffix(".mp4")
            val folder = when {
                VideoNaming.isReject(v.name) -> "Rejets"
                v.isSession || VideoNaming.parse(v.name)?.isFilm == true -> "Sessions"
                isWave(v.name) -> "Vagues"
                else -> "Autres"
            }
            items += Item(folder, "$base.mp4", v.sizeBytes) { resolver.openInputStream(v.uri) }
            if (folder == "Vagues" || folder == "Rejets") {
                findDownload("$base - rapport.txt")?.let { report -> items += Item(folder, "$base - rapport.txt", sizeOf(report)) { resolver.openInputStream(report) } }
            }
        }
        val names = HashSet<String>()
        try {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("SurfCam-rapport-%"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (!inDay(cursor.getLong(3) * 1000L)) continue
                    val name = cursor.getString(1) ?: continue
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    if (names.add(name)) items += Item("Rapports", name, cursor.getLong(2)) { resolver.openInputStream(uri) }
                }
            }
        } catch (_: Exception) { }
        java.io.File(appContext.filesDir, "rapports").listFiles()?.filter { it.isFile && it.name !in names && inDay(it.lastModified()) }?.forEach { file ->
            items += Item("Rapports", file.name, file.length()) { file.inputStream() }
        }
        val hasFilm = items.any { it.folder == "Sessions" && VideoNaming.parse(it.name)?.isFilm == true }
        val kept = if (hasFilm) items.filter { it.folder != "Sessions" || VideoNaming.parse(it.name)?.isFilm == true } else items
        val order = listOf("Vagues", "Rejets", "Rapports", "Sessions", "Autres")
        return kept.sortedBy { order.indexOf(it.folder) }
    }

    class AllResult(val sent: Int, val skipped: Int, val failed: List<String>, val bytes: Long)

    fun exportAll(items: List<Item>, onProgress: (Int, Int, String) -> Unit): AllResult {
        val tree = folder() ?: return AllResult(0, 0, listOf("Choisis d'abord le dossier d'export."), 0)
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { return AllResult(0, 0, listOf("Dossier d'export illisible : choisis-le de nouveau."), 0) }
        var sent = 0
        var skipped = 0
        var bytes = 0L
        val failed = ArrayList<String>()
        val folderIds = HashMap<String, String>()
        val existing = HashMap<String, Map<String, Pair<String, Long>>>()
        for ((i, item) in items.withIndex()) {
            onProgress(i, items.size, item.name)
            try {
                val dirId = folderIds.getOrPut(item.folder) { ensureFolder(tree, rootId, item.folder) }
                val here = existing.getOrPut(item.folder) { children(tree, dirId) }
                val old = here[item.name]
                if (old != null && old.second >= item.size && old.second > 0L) { skipped++; continue }
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
                val mime = if (item.name.endsWith(".mp4")) "video/mp4" else "text/plain"
                val target = old?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
                    ?: DocumentsContract.createDocument(resolver, dirUri, mime, item.name) ?: throw IllegalStateException("création impossible")
                val input = item.open() ?: throw IllegalStateException("lecture impossible")
                val copied = input.use { src -> resolver.openOutputStream(target, "wt")?.use { out -> src.copyTo(out) } ?: throw IllegalStateException("écriture impossible") }
                val targetSize = sizeOf(target)
                if (targetSize > 0 && item.size > 0 && targetSize != item.size && targetSize != copied) throw IllegalStateException("taille différente après copie")
                sent++
                bytes += copied
            } catch (e: Exception) {
                failed += "${item.folder}/${item.name} : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                if ((e.message ?: "").contains("ENOSPC")) break
            }
        }
        onProgress(items.size, items.size, "")
        return AllResult(sent, skipped, failed, bytes)
    }

    private fun children(tree: Uri, dirDocId: String): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        try {
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirDocId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE),
                null, null, null)?.use { c -> while (c.moveToNext()) out[c.getString(1) ?: continue] = c.getString(0) to (if (c.isNull(2)) 0L else c.getLong(2)) }
        } catch (_: Exception) { }
        return out
    }

    private fun ensureFolder(tree: Uri, parentId: String, name: String): String {
        children(tree, parentId)[name]?.let { return it.first }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IllegalStateException("création du dossier impossible : $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) { -1L }

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
