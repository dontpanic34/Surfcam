package com.surfcam.app.data.gimbal

import android.content.Context

/**
 * Une seule liaison Bluetooth avec le gimbal pour toute l'application : l'accueil, l'écran de test et le suivi pendant
 * la session s'en servent tous. Le gimbal n'accepte qu'une connexion à la fois, il ne faut donc pas en ouvrir plusieurs.
 */
object GimbalHub {
    @Volatile
    private var link: OsmoMobileLink? = null

    fun link(context: Context): OsmoMobileLink =
        link ?: synchronized(this) { link ?: OsmoMobileLink(context.applicationContext).also { link = it } }

    /** Vrai si un gimbal a déjà été choisi : l'appli s'y connecte et le suit alors toute seule. */
    fun hasSavedGimbal(context: Context): Boolean =
        context.getSharedPreferences("gimbal", Context.MODE_PRIVATE).getString("address", null) != null
}
