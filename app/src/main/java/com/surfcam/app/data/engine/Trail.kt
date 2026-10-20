package com.surfcam.app.data.engine

import android.content.Context
import java.io.File

/**
 * Journal des étapes du démarrage, écrit sur le disque à chaque ligne : il survit à un arrêt brutal de l'application
 * (plantage du système caméra ou d'un codec) qui ne laisse aucun rapport. Au lancement suivant on voit la dernière
 * étape atteinte.
 */
object Trail {
    private var file: File? = null

    @Synchronized
    fun init(context: Context): String? {
        val f = File(context.filesDir, "trail.txt")
        file = f
        val lines = if (f.exists()) f.readLines() else emptyList()
        val lastStart = lines.indexOfLast { it.contains("DEMARRAGE") }
        val lastEnd = lines.indexOfLast { it.contains("FIN") }
        f.writeText("")
        return if (lastStart >= 0 && lastStart > lastEnd) {
            "L'application s'est arrêtée brutalement après le démarrage, sans rapport (probablement le système caméra ou un codec).\n" +
                "Dernières étapes atteintes :\n" + lines.drop(lastStart).takeLast(30).joinToString("\n")
        } else null
    }

    @Synchronized
    fun mark(step: String) {
        try {
            file?.appendText("%tT %s\n".format(System.currentTimeMillis(), step))
        } catch (_: Exception) {
        }
    }
}
