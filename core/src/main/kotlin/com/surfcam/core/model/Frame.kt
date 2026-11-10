package com.surfcam.core.model

import com.surfcam.core.geometry.CameraModel

/** Rectangle en pixels de l'image analysée (origine en haut à gauche). */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
}

/** Une personne vue par le détecteur sur une image. */
data class Detection(
    val box: Box,
    val confidence: Double,
    /** Part (0 à 1) de la silhouette couverte par la couleur du manchon ; 0 si pas de manchon choisi. */
    val markerScore: Double = 0.0,
)

/** Une tache de la couleur du manchon, repérée sans détection de personne (utile en vue large, où la silhouette est trop petite). */
data class MarkerBlob(val x: Double, val y: Double, val pixels: Int)

/** Ce que le cœur reçoit pour chaque image analysée. */
data class Frame(
    /** Heure de prise de vue (pas de traitement), en microsecondes. */
    val timeUs: Long,
    /** Cap de la caméra à cet instant (degrés, positif à droite). */
    val headingDeg: Double,
    /** Élévation de l'axe de la caméra (degrés), 0 si inconnue. */
    val pitchDeg: Double = 0.0,
    /** Géométrie de cette image (champ selon l'objectif et le zoom). */
    val camera: CameraModel,
    /** Zoom affiché (×1, ×2, ×5…). */
    val zoom: Double,
    val detections: List<Detection>,
    val blobs: List<MarkerBlob> = emptyList(),
)

/**
 * Une détection placée dans le repère fixe de la plage : azimut et élévation absolus (degrés), taille angulaire.
 * C'est sur ces grandeurs, et non sur des pixels, que travaillent le pisteur et la détection du surf : un pivotement du gimbal
 * ne déplace plus rien.
 */
data class Observation(
    val timeUs: Long,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    /** Hauteur angulaire de la silhouette (degrés). */
    val heightDeg: Double,
    /** Largeur angulaire de la silhouette (degrés). */
    val widthDeg: Double,
    val confidence: Double,
    val markerScore: Double,
    /** Distance au bord de l'image, en fraction de la demi-largeur (0 = au bord, 1 = au centre). */
    val edgeMargin: Double,
    val source: Detection,
) {
    /** Longueur de corps : la plus grande dimension (hauteur debout, largeur allongé). */
    val bodyDeg: Double get() = maxOf(heightDeg, widthDeg)
    val aspect: Double get() = if (widthDeg > 0) heightDeg / widthDeg else 0.0
}

object WorldProjection {
    fun observe(frame: Frame, detection: Detection): Observation {
        val cam = frame.camera
        val b = detection.box
        val az = frame.headingDeg + cam.azimuthOffsetDeg(b.centerX)
        val el = frame.pitchDeg + cam.elevationOffsetDeg(b.centerY)
        val h = cam.elevationOffsetDeg(b.top) - cam.elevationOffsetDeg(b.bottom)
        val w = cam.azimuthOffsetDeg(b.right) - cam.azimuthOffsetDeg(b.left)
        val half = cam.widthPx / 2.0
        val margin = ((half - kotlin.math.abs(b.centerX - half)) / half).coerceIn(0.0, 1.0)
        return Observation(frame.timeUs, az, el, h, w, detection.confidence, detection.markerScore, margin, detection)
    }

    fun observeAll(frame: Frame): List<Observation> = frame.detections.map { observe(frame, it) }

    /** Azimut absolu d'une tache de couleur. */
    fun blobAzimuth(frame: Frame, blob: MarkerBlob): Double = frame.headingDeg + frame.camera.azimuthOffsetDeg(blob.x)
}
