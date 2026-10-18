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
    /**
     * 60 images/s au lieu de 30. Le téléobjectif du Pixel ne filme pas à 60 i/s : à 60, le zoom reste sur l'objectif
     * principal (recadrage numérique). À 30, le zoom peut basculer sur le téléobjectif vers ×5.
     */
    val fps60: Boolean = false,
    /** Volume MediaStore où enregistrer : "external_primary" (téléphone) ou le nom d'une carte SD / clé USB branchée. */
    val storage: String = "external_primary",
    /** Détection automatique des vagues (clips créés tout seuls). */
    val detection: Boolean = true,
    /** Écran noir après 1 minute sans toucher pendant une session (économie d'énergie) ; un toucher le réveille. */
    val sleepScreen: Boolean = true,
    /** Silence total (« Ne pas déranger ») pendant la session : pas d'appel ni de notification. */
    val doNotDisturb: Boolean = true,
) {
    val lens: Lens get() = if (tele) Lens.TELE else Lens.MAIN

    /** Place approximative prise par une heure de session complète, en Go (débit vidéo × 3600 s). */
    fun gigabytesPerHour(): Int {
        val mbps = when {
            hd -> if (tele || !fps60) 15 else 25
            else -> if (tele || !fps60) 40 else 60
        }
        return Math.round(mbps * 3600f / 8f / 1000f)
    }
}
