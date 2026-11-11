package com.surfcam.app.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * « Filmer tout le monde, trier après » : pendant chaque vague, l'appli garde une vignette de la silhouette du surfeur ; après
 * la session, l'utilisateur désigne ses vagues en touchant les vignettes. Rien ici ne touche aux vidéos elles-mêmes.
 */
class MyWaves(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "silhouettes")
    private val prefs = appContext.getSharedPreferences("mes_vagues", Context.MODE_PRIVATE)

    private fun key(clipName: String) = clipName.removeSuffix(".mp4")

    /** Enregistre la vignette de la silhouette du surfeur pour le clip [clipName]. */
    fun saveSilhouette(clipName: String, bitmap: Bitmap) {
        try {
            dir.mkdirs()
            File(dir, key(clipName) + ".jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (_: Exception) {
        }
    }

    fun silhouette(clipName: String): Bitmap? = try {
        File(dir, key(clipName) + ".jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (_: Exception) {
        null
    }

    /** Une vague rejetée puis récupérée change de nom : sa vignette et son étiquette suivent. */
    fun rename(oldName: String, newName: String) {
        try {
            File(dir, key(oldName) + ".jpg").takeIf { it.exists() }?.renameTo(File(dir, key(newName) + ".jpg"))
        } catch (_: Exception) {
        }
        if (isMine(oldName)) {
            setMine(oldName, false)
            setMine(newName, true)
        }
    }

    fun mine(): Set<String> = prefs.getStringSet("moi", emptySet()) ?: emptySet()

    fun isMine(clipName: String): Boolean = key(clipName) in mine()

    fun setMine(clipName: String, mine: Boolean) {
        val set = HashSet(mine())
        if (mine) set += key(clipName) else set -= key(clipName)
        prefs.edit().putStringSet("moi", set).apply()
    }
}
