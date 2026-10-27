package com.surfcam.app.data.gimbal

import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Redresse l'horizon avec l'axe de roulis du gimbal : on mesure l'inclinaison du téléphone (capteur de gravité, fournie
 * par [read]), on ordonne un petit pivotement, on remesure, et on recommence. Si l'écart ne diminue pas, le sens était
 * faux : on l'inverse et on s'en souvient. S'arrête sous 0,4° ou après six essais.
 */
class HorizonLeveler(private val link: OsmoMobileLink, private val prefs: SharedPreferences) {

    /**
     * Met le téléphone en paysage avec l'axe de roulis, comme le double appui sur M : s'il est tenu en portrait, on le
     * fait pivoter de 90° (dans l'un ou l'autre sens, les deux donnent un paysage). Renvoie vrai si le téléphone est en
     * paysage à la fin. [isPortrait] : vrai si le téléphone est physiquement debout, null si inconnu.
     */
    suspend fun toLandscape(isPortrait: () -> Boolean?): Boolean {
        if (isPortrait() != true) return true
        val sign = prefs.getInt("landSign", 1)
        link.rollBy(90f * sign, 2.0f)
        delay(3_500)
        if (isPortrait() == false) return true
        // Rien n'a bougé dans ce sens : on essaie l'autre.
        link.rollBy(-90f * sign, 2.0f)
        delay(3_500)
        val ok = isPortrait() == false
        if (ok) prefs.edit().putInt("landSign", -sign).apply()
        return ok
    }

    /** Retouche manuelle de [degrees] (signé, dans le sens de l'inclinaison affichée). */
    fun nudge(degrees: Float) {
        link.rollBy(-degrees * prefs.getInt("rollSign", 1), 0.5f)
    }

    /** Inclinaison restante (degrés) à la fin du dernier redressement, null avant le premier. */
    var lastEndDeg: Float? = null
        private set

    /** @return un compte rendu en français */
    suspend fun run(read: () -> Float?): String {
        var sign = prefs.getInt("rollSign", 1)
        val start = read() ?: return "Le téléphone n'a pas de capteur d'inclinaison utilisable."
        var before = start
        repeat(6) {
            if (abs(before) < 0.4f) return@repeat
            link.rollBy((-before * 0.8f).coerceIn(-5f, 5f) * sign, 1.0f)
            delay(2_500)
            val after = read() ?: before
            if (abs(after) > abs(before) - 0.2f) {
                sign = -sign
                prefs.edit().putInt("rollSign", sign).apply()
            }
            before = after
        }
        val end = read() ?: before
        lastEndDeg = end
        return "Horizon : %.1f° avant, %.1f° après.".format(start, end).replace('.', ',') +
            if (abs(end) > 1f) " Le gimbal n'a pas suivi l'ordre de roulis : réglage possible dans l'appli DJI Mimo." else " C'est droit."
    }
}
