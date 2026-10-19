package com.surfcam.app.data.engine

import com.surfcam.app.data.camera.Lens

/** Choix faits avant de démarrer une session. */
data class SessionOptions(
    /** Téléobjectif optique 5× plutôt que l'objectif principal. */
    val tele: Boolean = false,
    /** Enregistrer toute la session, en fichiers de 2 minutes, en plus des clips de vagues (désactivé par défaut). */
    val fullSession: Boolean = false,
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
    /** Fige l'exposition après convergence. Par défaut elle reste automatique pour suivre les nuages. */
    val lockExposure: Boolean = false,
    /** Mode recadrage : on filme large (×1) ; l'app garde le chemin du surfeur et sait recadrer chaque clip après coup. */
    val reframe: Boolean = false,
    /** Le gimbal DJI enregistré fait pivoter le téléphone pour garder le surfeur au centre. */
    val followGimbal: Boolean = false,
    /** Couleur du manchon que l'utilisateur porte pour être repéré parmi les surfeurs ; null = aucune. */
    val markerColor: com.surfcam.app.domain.detection.MarkerColor? = null,
) {
    val lens: Lens get() = if (tele) Lens.TELE else Lens.MAIN

    /** Place approximative prise par une heure de session complète, en Go (débit vidéo × 3600 s). */
    fun gigabytesPerHour(): Int {
        val mbps = when {
            hd -> if (tele || !fps60) 24 else 35
            else -> if (tele || !fps60) 50 else 70
        }
        return Math.round(mbps * 3600f / 8f / 1000f)
    }
}
