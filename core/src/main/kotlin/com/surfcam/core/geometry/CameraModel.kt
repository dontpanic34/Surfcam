package com.surfcam.core.geometry

import kotlin.math.atan
import kotlin.math.tan

/**
 * Géométrie d'une image d'analyse : passe des pixels aux angles et inversement (modèle sténopé, sans distorsion).
 *
 * Convention de tout le cœur : un angle horizontal (azimut) est positif vers la droite, comme le cap du gimbal et la rotation
 * mesurée au gyroscope ; un angle vertical (élévation) est positif vers le haut. Le centre de l'image est l'axe optique.
 */
data class CameraModel(
    val widthPx: Int,
    val heightPx: Int,
    /** Champ horizontal de l'image analysée, en degrés (zoom compris). */
    val hfovDeg: Double,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "taille d'image invalide" }
        require(hfovDeg > 0.1 && hfovDeg < 170.0) { "champ horizontal invalide : $hfovDeg°" }
    }

    /** Focale en pixels de l'image d'analyse. */
    val focalPx: Double = widthPx / 2.0 / tan(Math.toRadians(hfovDeg / 2.0))

    val vfovDeg: Double get() = 2.0 * Math.toDegrees(atan(heightPx / 2.0 / focalPx))

    /** Pixels par degré au centre de l'image. */
    val pxPerDegAtCenter: Double get() = focalPx * Math.PI / 180.0

    /** Angle horizontal (degrés, positif à droite) d'un point de colonne [x] par rapport à l'axe optique. */
    fun azimuthOffsetDeg(x: Double): Double = Math.toDegrees(atan((x - widthPx / 2.0) / focalPx))

    /** Angle vertical (degrés, positif en haut) d'un point de ligne [y] par rapport à l'axe optique. */
    fun elevationOffsetDeg(y: Double): Double = Math.toDegrees(atan((heightPx / 2.0 - y) / focalPx))

    /** Colonne de l'image où tombe un angle horizontal relatif à l'axe. */
    fun xForAzimuthOffset(deg: Double): Double = widthPx / 2.0 + focalPx * tan(Math.toRadians(deg))

    /** Ligne de l'image où tombe un angle vertical relatif à l'axe. */
    fun yForElevationOffset(deg: Double): Double = heightPx / 2.0 - focalPx * tan(Math.toRadians(deg))

    /** Taille angulaire d'une longueur de [px] pixels au centre de l'image. */
    fun pxToDeg(px: Double): Double = px / pxPerDegAtCenter

    fun degToPx(deg: Double): Double = deg * pxPerDegAtCenter

    /** Le point d'azimut relatif [deg] est-il dans l'image, avec une marge de [marginFraction] de la demi-largeur ? */
    fun containsAzimuthOffset(deg: Double, marginFraction: Double = 0.0): Boolean =
        kotlin.math.abs(deg) <= hfovDeg / 2.0 * (1.0 - marginFraction)

    /** Le même capteur avec un autre zoom (le champ se resserre d'autant). */
    fun zoomedBy(factor: Double): CameraModel {
        val half = Math.toDegrees(atan(tan(Math.toRadians(hfovDeg / 2.0)) / factor))
        return copy(hfovDeg = 2.0 * half)
    }

    companion object {
        /** Champ horizontal d'une focale [focalMm] sur un capteur de [sensorWidthMm] de large. */
        fun hfovFromLens(focalMm: Double, sensorWidthMm: Double): Double =
            2.0 * Math.toDegrees(atan(sensorWidthMm / 2.0 / focalMm))
    }
}
