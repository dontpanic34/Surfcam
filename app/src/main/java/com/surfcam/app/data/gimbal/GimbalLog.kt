package com.surfcam.app.data.gimbal

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Journal des décisions du suivi : ce que l'appli voit et pourquoi le gimbal bouge ou reste immobile. Gardé en mémoire
 * (dernières lignes) et dans un fichier, effacé à chaque début de session. Sert à comprendre un comportement après coup.
 */
object GimbalLog {
    private const val MAX_LINES = 800
    private var file: File? = null
    private var t0 = 0L
    private val lines = ArrayDeque<String>()

    /** Dernière décision du suivi (affichée à l'écran pendant les tests). */
    @Volatile
    var last: String = ""
        private set

    @Synchronized
    fun start(context: Context, header: String) {
        file = File(context.filesDir, "gimbal_log.txt")
        t0 = SystemClock.elapsedRealtime()
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
        add(header)
    }

    @Synchronized
    fun add(text: String) {
        val t = (SystemClock.elapsedRealtime() - t0) / 1000f
        last = text
        com.surfcam.app.data.engine.SessionLog.ev("gimbal", text)
        val line = "%6.1f s  %s".format(t, text)
        lines.addLast(line)
        if (lines.size > MAX_LINES) lines.removeFirst()
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }

    @Synchronized
    fun tail(n: Int): String = lines.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun isEmpty() = lines.isEmpty()
}
