package com.surfcam.app.data.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Note vocale pour signaler un défaut en direct : enregistrée en AAC, repérée dans le rapport de session, puis copiée dans
 * Téléchargements/SurfCam pour être partagée avec le rapport.
 */
object VoiceNote {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var name = ""
    private var startedAt = 0L

    val recording: Boolean get() = recorder != null

    @Synchronized
    fun start(context: Context): Boolean {
        if (recorder != null) return true
        return try {
            name = "SurfCam-note-" + SimpleDateFormat("yyyy-MM-dd_HH'h'mm'm'ss", Locale.US).format(Date()) + ".m4a"
            val dir = File(context.filesDir, "notes").apply { mkdirs() }
            val f = File(dir, name)
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(32_000)
            r.setMaxDuration(120_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = SystemClock.elapsedRealtime()
            SessionLog.ev("NOTE", "note vocale commencée : $name")
            true
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            SessionLog.ev("NOTE", "note vocale impossible : ${e.message}")
            false
        }
    }

    /** Arrête la note et la copie dans Téléchargements/SurfCam. Renvoie son nom, ou null en cas d'échec. */
    @Synchronized
    fun stop(context: Context): String? {
        val r = recorder ?: return null
        recorder = null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        try {
            r.stop()
        } catch (_: Exception) {
        }
        r.release()
        val f = file ?: return null
        SessionLog.ev("NOTE", "note vocale terminée : $name (%.0f s)".format(seconds))
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SurfCam")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            val prefs = context.getSharedPreferences("rapport", Context.MODE_PRIVATE)
            val list = (prefs.getString("notes", "") ?: "").split("|").filter { it.isNotBlank() } + uri.toString()
            prefs.edit().putString("notes", list.takeLast(10).joinToString("|")).apply()
        } catch (_: Exception) {
        }
        return name
    }

    fun recentNotes(context: Context): List<Uri> =
        (context.getSharedPreferences("rapport", Context.MODE_PRIVATE).getString("notes", "") ?: "")
            .split("|").filter { it.isNotBlank() }.map(Uri::parse)
}
