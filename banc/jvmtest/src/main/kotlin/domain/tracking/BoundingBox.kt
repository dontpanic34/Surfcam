package com.surfcam.app.domain.tracking

import kotlin.math.max
import kotlin.math.min

/** Rectangle en pixels de l'image analysée (origine en haut à gauche). */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    fun iou(other: BoundingBox): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (area + other.area - inter)
    }
}

/** Une personne repérée par le détecteur dans une image, à l'instant [timestampUs]. */
data class Detection(
    val box: BoundingBox,
    val confidence: Float,
    val timestampUs: Long,
    /** Vrai si la silhouette porte le manchon de couleur de l'utilisateur sur cette image. */
    val wearsMarker: Boolean = false,
)
