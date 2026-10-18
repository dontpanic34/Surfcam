package com.surfcam.app.data.engine

import com.surfcam.app.data.camera.Lens

/** Choix faits avant de démarrer une session. */
data class SessionOptions(
    /** Téléobjectif optique 5× plutôt que l'objectif principal. */
    val tele: Boolean = false,
    /** Enregistrer toute la session, en fichiers de 2 minutes, en plus des clips de vagues. */
    val fullSession: Boolean = true,
    /** 1080p au lieu de la 4K : environ 2,5 fois moins de place et de chauffe. */
    val hd: Boolean = false,
    /** Détection automatique des vagues (clips créés tout seuls). */
    val detection: Boolean = true,
) {
    val lens: Lens get() = if (tele) Lens.TELE else Lens.MAIN

    /** Place approximative prise par une heure de session complète, en Go (débit vidéo × 3600 s). */
    fun gigabytesPerHour(): Int {
        val mbps = when {
            hd -> if (tele) 15 else 25
            else -> if (tele) 40 else 60
        }
        return Math.round(mbps * 3600f / 8f / 1000f)
    }
}
