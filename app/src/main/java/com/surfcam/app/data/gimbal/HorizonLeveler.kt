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

    /** Retouche manuelle de [degrees] (signé, dans le sens de l'inclinaison affichée). */
    fun nudge(degrees: Float) {
        link.rollBy(-degrees * prefs.getInt("rollSign", 1), 0.5f)
    }

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
        return "Horizon : %.1f° avant, %.1f° après.".format(start, end).replace('.', ',') +
            if (abs(end) > 1f) " Le gimbal n'a pas suivi l'ordre de roulis : réglage possible dans l'appli DJI Mimo." else " C'est droit."
    }
}
