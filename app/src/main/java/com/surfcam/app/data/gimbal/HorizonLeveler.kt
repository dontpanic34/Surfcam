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

    /** Moyenne de l'inclinaison sur 0,5 s (le capteur de gravité tremble un peu) ; null si pas de mesure. */
    private suspend fun settle(read: () -> Float?): Float? {
        val values = ArrayList<Float>()
        repeat(10) {
            read()?.let { values += it }
            delay(50)
        }
        return if (values.isEmpty()) null else values.sorted()[values.size / 2]
    }

    /**
     * Mise à niveau en boucle fermée : mesure (gimbal immobile), ordre de roulis proportionnel à l'écart, nouvelle mesure. Le sens
     * et l'amplitude réelle de l'ordre (degrés obtenus par degré commandé) sont appris et gardés pour les fois suivantes. S'arrête
     * sous 0,3°, ou si le gimbal ne réagit pas deux fois de suite. Vérifie ensuite que le niveau tient (un gimbal qui revient
     * seul à sa propre référence est signalé). Chaque étape est écrite dans le rapport via [log].
     * @return un compte rendu en français
     */
    suspend fun run(read: () -> Float?, log: (String) -> Unit = {}): String {
        var sign = prefs.getInt("rollSign", 1)
        var gain = prefs.getFloat("rollGain", 1f)
        val start = settle(read) ?: return "Le téléphone n'a pas de capteur d'inclinaison utilisable."
        var before = start
        var noResponse = 0
        log("niveau : départ %+.2f° (sens appris %d, amplitude %.2f)".format(start, sign, gain))
        for (i in 1..10) {
            if (abs(before) < 0.3f) break
            val cmd = (-before / gain).coerceIn(-6f, 6f)
            link.rollBy(cmd * sign, 0.8f)
            delay(1_600)
            val after = settle(read) ?: break
            val moved = after - before
            log("niveau : essai $i, ordre %+.2f° → inclinaison %+.2f° (a bougé de %+.2f°)".format(cmd, after, moved))
            when {
                abs(moved) < 0.15f -> {
                    noResponse++
                    if (noResponse >= 2) break
                }
                (moved > 0) != (cmd > 0) -> {
                    sign = -sign
                    prefs.edit().putInt("rollSign", sign).apply()
                    log("niveau : le gimbal tourne dans l'autre sens, sens inversé")
                    noResponse = 0
                }
                else -> {
                    gain = (abs(moved) / abs(cmd)).coerceIn(0.3f, 2f)
                    prefs.edit().putFloat("rollGain", gain).apply()
                    noResponse = 0
                }
            }
            before = after
        }
        delay(3_000)
        val held = settle(read) ?: before
        lastEndDeg = held
        val drift = held - before
        log("niveau : fin %+.2f°, 3 s plus tard %+.2f°".format(before, held))
        return when {
            noResponse >= 2 -> "Horizon : %.1f°. Le gimbal ne répond pas à l'ordre de roulis : fais « Réglage horizontal » dans l'appli DJI Mimo.".format(held)
            abs(drift) > 0.5f -> "Horizon : %.1f° → %.1f°, mais le gimbal revient seul vers %.1f° : sa référence est penchée (réglage horizontal dans DJI Mimo).".format(start, before, held)
            abs(held) <= 0.5f -> "Horizon droit : %.1f° → %.1f°.".format(start, held)
            else -> "Horizon : %.1f° → %.1f°.".format(start, held)
        }.replace('.', ',')
    }
}
