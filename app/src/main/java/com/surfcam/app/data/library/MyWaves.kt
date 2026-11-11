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

    /**
     * Signature de couleur d'une silhouette : répartition des couleurs de ce qui n'est ni l'eau (bleu), ni l'écume (blanc), ni
     * le néoprène (noir) — donc surtout la planche, la peau, un lycra ou un manchon. Deux vagues du même surfeur ont en général
     * des signatures proches. null si la vignette manque ou ne montre presque que de l'eau.
     */
    fun signature(clipName: String): FloatArray? {
        val bmp = silhouette(clipName) ?: return null
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val hist = FloatArray(BINS * BINS * BINS)
        var kept = 0
        // Moitié centrale de la vignette : le surfeur y est, les bords sont surtout de l'eau.
        for (y in h / 4 until h * 3 / 4) for (x in w / 4 until w * 3 / 4) {
            val p = pixels[y * w + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val water = b - r > 18 && b > 80
            val foam = min > 170
            val wetsuit = max < 60
            if (water || foam || wetsuit) continue
            hist[(r * BINS / 256) * BINS * BINS + (g * BINS / 256) * BINS + (b * BINS / 256)] += 1f
            kept++
        }
        if (kept < 40) return null
        for (i in hist.indices) hist[i] /= kept
        return hist
    }

    fun mine(): Set<String> = prefs.getStringSet("moi", emptySet()) ?: emptySet()

    fun isMine(clipName: String): Boolean = key(clipName) in mine()

    companion object {
        private const val BINS = 5

        /** Ressemblance de deux signatures, de 0 (rien de commun) à 1 (mêmes couleurs dans les mêmes proportions). */
        fun resemblance(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += minOf(a[i], b[i])
            return s
        }
    }

    fun setMine(clipName: String, mine: Boolean) {
        val set = HashSet(mine())
        if (mine) set += key(clipName) else set -= key(clipName)
        prefs.edit().putStringSet("moi", set).apply()
    }
}
