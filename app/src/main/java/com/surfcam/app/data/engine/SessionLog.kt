package com.surfcam.app.data.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapport de session pour le débogage : une ligne par événement avec l'heure réelle (à comparer à celle des vidéos) et le
 * temps écoulé depuis le départ. Écrit au fil de l'eau (survit à un plantage), puis copié dans Téléchargements/SurfCam à
 * l'arrêt. L'utilisateur envoie ce fichier avec les vidéos : on y lit ce que l'appli voyait et décidait.
 */
object SessionLog {
    private var file: File? = null
    private var t0 = 0L
    private var name = ""
    private val clock = SimpleDateFormat("HH:mm:ss.S", Locale.US)
    private val entries = ArrayList<Pair<Long, String>>()
    private val header = ArrayList<String>()
    private const val MAX_ENTRIES = 40_000

    @Synchronized
    fun start(context: Context, header: String) {
        val dir = File(context.filesDir, "rapports").apply { mkdirs() }
        // On ne garde que les dix derniers rapports internes.
        dir.listFiles()?.sortedBy { it.name }?.dropLast(9)?.forEach { it.delete() }
        name = "SurfCam-rapport-" + SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.US).format(Date()) + ".txt"
        file = File(dir, name)
        t0 = SystemClock.elapsedRealtime()
        entries.clear()
        this.header.clear()
        context.getSharedPreferences("rapport", Context.MODE_PRIVATE).edit().remove("files").remove("clipReports").apply()
        write("RAPPORT DE SESSION · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        write(header)
        this.header += entries.map { it.second }
        write("Chaque ligne : heure réelle (comme dans le nom des vidéos) · temps depuis le départ · type · détail")
    }

    @Synchronized
    fun ev(type: String, text: String) {
        if (file == null) return
        write("%-7s %s".format(type, text))
    }

    private fun write(text: String) {
        val now = System.currentTimeMillis()
        val rel = (SystemClock.elapsedRealtime() - t0) / 1000f
        val line = "${clock.format(Date(now))} (+%7.1f s)  %s".format(rel, text)
        if (entries.size < MAX_ENTRIES) entries += now to line
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    /** Termine le rapport et en met une copie dans Téléchargements/SurfCam, que l'utilisateur peut partager. */
    @Synchronized
    fun end(context: Context) {
        val f = file ?: return
        write("FIN     Session terminée")
        file = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SurfCam")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            context.getSharedPreferences("rapport", Context.MODE_PRIVATE).edit().putString("lastUri", uri.toString()).putString("lastName", name).apply()
        } catch (_: Exception) {
        }
    }

    fun lastExport(context: Context): Pair<Uri, String>? {
        val p = context.getSharedPreferences("rapport", Context.MODE_PRIVATE)
        val uri = p.getString("lastUri", null)?.let(Uri::parse) ?: return null
        return uri to (p.getString("lastName", null) ?: "rapport")
    }

    /** Retient le nom d'un fichier créé pendant la session (clip, partie de session), pour le partage groupé. */
    @Synchronized
    fun noteFile(context: Context, fileName: String) {
        val prefs = context.getSharedPreferences("rapport", Context.MODE_PRIVATE)
        val list = (prefs.getString("files", "") ?: "").split("|").filter { it.isNotBlank() } + fileName.removeSuffix(".mp4")
        prefs.edit().putString("files", list.joinToString("|")).apply()
    }

    fun sessionFiles(context: Context): List<String> =
        (context.getSharedPreferences("rapport", Context.MODE_PRIVATE).getString("files", "") ?: "").split("|").filter { it.isNotBlank() }

    /**
     * À l'enregistrement d'un clip : un petit rapport du même nom, avec seulement les lignes de la session qui
     * correspondent à ce clip (de quelques secondes avant son début à juste après sa fin). Mis dans
     * Téléchargements/SurfCam/Rapports, à côté des autres rapports.
     */
    @Synchronized
    fun clipSaved(context: Context, clipName: String, durationSec: Float) {
        noteFile(context, clipName)
        val endMs = System.currentTimeMillis()
        val fromMs = endMs - (durationSec * 1000).toLong() - 4_000L
        val body = buildString {
            append("RAPPORT DU CLIP ${clipName.removeSuffix(".mp4")} (%.1f s)\n".format(durationSec))
            append("Extrait du rapport de session ${name}. Les heures sont celles de la session ; le clip commence à l'heure de son nom.\n\n")
            header.forEach { append(it).append('\n') }
            append("\n")
            entries.filter { it.first in fromMs..(endMs + 500) }.forEach { append(it.second).append('\n') }
        }
        try {
            val display = clipName.removeSuffix(".mp4") + " - rapport.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, display)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SurfCam/Rapports")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            val prefs = context.getSharedPreferences("rapport", Context.MODE_PRIVATE)
            val list = (prefs.getString("clipReports", "") ?: "").split("|").filter { it.isNotBlank() } + uri.toString()
            prefs.edit().putString("clipReports", list.joinToString("|")).apply()
        } catch (_: Exception) {
        }
    }

    fun clipReportUris(context: Context): List<Uri> =
        (context.getSharedPreferences("rapport", Context.MODE_PRIVATE).getString("clipReports", "") ?: "").split("|").filter { it.isNotBlank() }.map(Uri::parse)
}
