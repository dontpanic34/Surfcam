package com.surfcam.app.domain.detection

import com.surfcam.app.domain.tracking.BoundingBox
import com.surfcam.app.domain.tracking.Detection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Passage de l'image de la caméra à l'entrée du modèle (mise à l'échelle sans déformation, bandes grises
 * ajoutées), et chemin inverse pour retrouver les coordonnées dans l'image d'origine.
 */
class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val inputWidth: Int, val inputHeight: Int) {
    val scale: Float = min(inputWidth.toFloat() / sourceWidth, inputHeight.toFloat() / sourceHeight)
    val scaledWidth: Int = (sourceWidth * scale).roundToInt()
    val scaledHeight: Int = (sourceHeight * scale).roundToInt()

    /** Position, dans l'entrée du modèle, du coin haut gauche de l'image mise à l'échelle. */
    val offsetX: Int = ((inputWidth - scaledWidth) / 2f - 0.1f).roundToInt()
    val offsetY: Int = ((inputHeight - scaledHeight) / 2f - 0.1f).roundToInt()

    fun toSourceX(x: Float): Float = ((x - offsetX) / scale).coerceIn(0f, sourceWidth.toFloat())
    fun toSourceY(y: Float): Float = ((y - offsetY) / scale).coerceIn(0f, sourceHeight.toFloat())
}

data class DecoderConfig(
    val confidenceThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f,
    /** Numéro de la classe cherchée dans la sortie du modèle (COCO : 0 = personne). */
    val personClass: Int = 0,
)

/**
 * Lit la sortie brute d'un YOLOv8 : un tenseur [4 + classes][ancres], rangé à plat, où les 4 premières lignes
 * sont le centre, la largeur et la hauteur de la boîte (en pixels de l'entrée du modèle), les suivantes les scores
 * des classes. Garde les personnes assez sûres puis supprime les boîtes en double (NMS).
 */
class YoloDecoder(private val config: DecoderConfig = DecoderConfig()) {

    fun decode(output: FloatArray, anchors: Int, letterbox: Letterbox, timestampUs: Long): List<Detection> {
        val scoreRow = (4 + config.personClass) * anchors
        val candidates = ArrayList<Detection>()
        for (a in 0 until anchors) {
            val score = output[scoreRow + a]
            if (score < config.confidenceThreshold) continue
            val cx = output[a]
            val cy = output[anchors + a]
            val w = output[2 * anchors + a]
            val h = output[3 * anchors + a]
            val box = BoundingBox(
                letterbox.toSourceX(cx - w / 2),
                letterbox.toSourceY(cy - h / 2),
                letterbox.toSourceX(cx + w / 2),
                letterbox.toSourceY(cy + h / 2),
            )
            candidates += Detection(box, score, timestampUs)
        }
        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(candidates: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>()
        for (c in candidates.sortedByDescending { it.confidence }) {
            if (kept.all { it.box.iou(c.box) <= config.iouThreshold }) kept += c
        }
        return kept
    }
}
