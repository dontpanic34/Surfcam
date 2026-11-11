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
 * Exporte une journée entière dans un dossier choisi une fois pour toutes (dossier du Drive, carte SD, clé USB…) : vagues,
 * rejets, sessions, rapports et journaux de capteurs, rangés par sous-dossier. Ne supprime jamais rien sur le téléphone.
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

    private fun isWave(name: String): Boolean {
        val p = VideoNaming.parse(name) ?: return false
        return !p.isSession && !p.isFilm && !p.isMontage
    }

    /** Un fichier à exporter : sous-dossier de destination, nom, taille, jour auquel il appartient. */
    class Item(val folder: String, val name: String, val size: Long, val day: java.time.LocalDate, val alwaysResend: Boolean = false, val open: () -> java.io.InputStream?)

    /** Ce qu'il y a à exporter pour un jour donné, pour la liste des jours. */
    class DaySummary(val day: java.time.LocalDate, val waves: Int, val rejects: Int, val sessions: Int, val reports: Int, val bytes: Long)

    private val dayInName = Regex("(20\\d{2})-(\\d{2})-(\\d{2})")

    /**
     * Jour auquel appartient un fichier : la date écrite dans son nom (celle de la prise de vue), sinon sa date de modification.
     * La date de modification seule ne suffit pas : un fichier déplacé plus tard vers la carte SD la voit changer.
     */
    private fun dayOf(name: String, modifiedMs: Long): java.time.LocalDate {
        dayInName.find(name)?.let { m ->
            try {
                return java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } catch (_: Exception) {
            }
        }
        return java.time.Instant.ofEpochMilli(modifiedMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }

    /** Les jours où l'appli a enregistré quelque chose, du plus récent au plus ancien. */
    fun days(): List<DaySummary> = everything(null).groupBy { it.day }.map { (day, items) ->
        DaySummary(
            day,
            waves = items.count { it.folder == "Vagues" && it.name.endsWith(".mp4") },
            rejects = items.count { it.folder == "Rejets" && it.name.endsWith(".mp4") },
            sessions = items.count { it.folder == "Sessions" },
            reports = items.count { it.name.endsWith(".txt") },
            bytes = items.sumOf { it.size.coerceAtLeast(0L) },
        )
    }.sortedByDescending { it.day }

    fun everything(day: java.time.LocalDate?): List<Item> {
        val library = VideoLibrary(appContext)
        val videos = (library.list() + library.listRejected()).filter { !it.pending && (day == null || dayOf(it.name, it.modifiedMs) == day) }
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
            val d = dayOf(v.name, v.modifiedMs)
            items += Item(folder, "$base.mp4", v.sizeBytes, d) { resolver.openInputStream(v.uri) }
            if (folder == "Vagues" || folder == "Rejets") {
                findDownload("$base - rapport.txt")?.let { report -> items += Item(folder, "$base - rapport.txt", sizeOf(report), d) { resolver.openInputStream(report) } }
            }
        }
        // Rapports de session et journaux de capteurs (ceux qui permettent de rejouer la session) : dans Téléchargements et
        // dans le dossier privé de l'appli ; chaque nom n'est pris qu'une fois.
        for ((prefix, folder, privateDir) in listOf(Triple("SurfCam-rapport-", "Rapports", "rapports"), Triple("SurfCam-journal-", "Journaux", "journaux"), Triple("SurfCam-note-", "Notes vocales", "notes"))) {
            val names = HashSet<String>()
            try {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        val d = dayOf(name, cursor.getLong(3) * 1000L)
                        if (day != null && d != day) continue
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                        if (names.add(name)) items += Item(folder, name, cursor.getLong(2), d) { resolver.openInputStream(uri) }
                    }
                }
            } catch (_: Exception) { }
            java.io.File(appContext.filesDir, privateDir).listFiles()?.filter { it.isFile && it.name !in names }?.forEach { file ->
                val d = dayOf(file.name, file.lastModified())
                if (day == null || d == day) items += Item(folder, file.name, file.length(), d) { file.inputStream() }
            }
        }
        // Les vignettes des silhouettes (une par vague, gardée ou rejetée) : elles servent à juger et à améliorer le tri.
        java.io.File(appContext.filesDir, "silhouettes").listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.forEach { file ->
            val d = dayOf(file.name, file.lastModified())
            if (day == null || d == day) items += Item("Silhouettes", file.name, file.length(), d) { file.inputStream() }
        }
        // Les vagues que l'utilisateur a désignées comme les siennes (« Trier mes vagues »), un petit fichier par jour : il dit
        // qui est sur chaque clip, ce qui sert ensuite à mesurer et à entraîner la reconnaissance.
        val mine = MyWaves(appContext).mine()
        for ((d, clips) in items.filter { it.folder == "Vagues" && it.name.endsWith(".mp4") }.groupBy { it.day }) {
            val names = clips.map { it.name.removeSuffix(".mp4") }
            val text = buildString {
                append("Vagues du $d désignées par l'utilisateur comme les siennes (${names.count { it in mine }} sur ${names.size})\n")
                for (n in names.sorted()) append(if (n in mine) "MOI    " else "autre  ").append(n).append('\n')
            }.toByteArray()
            items += Item("Rapports", "SurfCam-mes-vagues-$d.txt", text.size.toLong(), d, alwaysResend = true) { java.io.ByteArrayInputStream(text) }
        }
        // Un jour où un film de session assemblé existe, ses morceaux de 2 minutes ne sont pas renvoyés en double.
        val filmDays = items.filter { it.folder == "Sessions" && VideoNaming.parse(it.name)?.isFilm == true }.map { it.day }.toSet()
        val kept = items.filter { it.folder != "Sessions" || it.day !in filmDays || VideoNaming.parse(it.name)?.isFilm == true }
        val order = listOf("Rapports", "Journaux", "Notes vocales", "Silhouettes", "Vagues", "Rejets", "Sessions", "Autres")
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
                if (!item.alwaysResend && old != null && old.second >= item.size && old.second > 0L) { skipped++; continue }
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
                val mime = if (item.name.endsWith(".mp4")) "video/mp4" else if (item.name.endsWith(".jpg")) "image/jpeg" else if (item.name.endsWith(".m4a")) "audio/mp4" else "text/plain"
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
