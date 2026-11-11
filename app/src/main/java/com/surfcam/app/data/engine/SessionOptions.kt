package com.surfcam.app.data.engine

/**
 * Choix faits avant de démarrer une session. La prise de vue n'est pas un choix : toujours le téléobjectif optique ×5, en 1080p
 * à 30 images/s, sans zoom numérique (voir SPEC_SURFCAM_V2.md, « Zoom »).
 */
data class SessionOptions(
    /** Enregistrer toute la session, en fichiers de 2 minutes, en plus des clips de vagues (désactivé par défaut). */
    val fullSession: Boolean = false,
    /** Volume MediaStore où enregistrer : "external_primary" (téléphone) ou le nom d'une carte SD / clé USB branchée. */
    val storage: String = "external_primary",
    /** Écran noir après 1 minute sans toucher pendant une session (économie d'énergie) ; un toucher le réveille. */
    val sleepScreen: Boolean = true,
    /** Silence total (« Ne pas déranger ») pendant la session : pas d'appel ni de notification. */
    val doNotDisturb: Boolean = true,
    /** Fige l'exposition après convergence. Par défaut elle reste automatique pour suivre les nuages. */
    val lockExposure: Boolean = false,
    /** Le gimbal DJI enregistré fait pivoter le téléphone pour garder le surfeur au centre. */
    val followGimbal: Boolean = false,
    /** La LED du flash clignote brièvement toutes les 15 s pendant la session, pour voir de loin que ça filme. */
    val ledBlink: Boolean = true,
    /** Qui filmer : tout le monde, ou seulement moi (repéré par la couleur de mon manchon, en ×5 optique). */
    val subject: Subject = Subject.EVERYONE,
    /** Couleur du manchon que l'utilisateur porte pour être repéré parmi les surfeurs ; null = aucune. */
    val markerColor: com.surfcam.app.domain.detection.MarkerColor? = null,
) {
    /** Place approximative prise par une heure de session complète, en Go (1080p, 30 images/s, 50 Mb/s). */
    fun gigabytesPerHour(): Int = Math.round(50 * 3600f / 8f / 1000f)
}

enum class Subject { EVERYONE, ME }
