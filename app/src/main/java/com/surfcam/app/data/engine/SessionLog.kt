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

    @Synchronized
    fun start(context: Context, header: String) {
        val dir = File(context.filesDir, "rapports").apply { mkdirs() }
        // On ne garde que les dix derniers rapports internes.
        dir.listFiles()?.sortedBy { it.name }?.dropLast(9)?.forEach { it.delete() }
        name = "SurfCam-rapport-" + SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.US).format(Date()) + ".txt"
        file = File(dir, name)
        t0 = SystemClock.elapsedRealtime()
        write("RAPPORT DE SESSION · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        write(header)
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
        try {
            file?.appendText("${clock.format(Date(now))} (+%7.1f s)  %s\n".format(rel, text))
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
}
