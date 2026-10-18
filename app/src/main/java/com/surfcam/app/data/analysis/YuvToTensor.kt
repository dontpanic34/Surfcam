package com.surfcam.app.data.analysis

import android.media.Image
import com.surfcam.app.domain.detection.Letterbox
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Convertit une image caméra YUV 4:2:0 en tenseur flottant RGB [3][hauteur][largeur] pour le modèle, en la
 * plaçant dans l'entrée (bandes grises autour) selon le [Letterbox]. Les tampons sont réutilisés d'une image
 * à l'autre : aucune allocation par image.
 */
class YuvToTensor {

    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private var columns = IntArray(0)

    fun convert(image: Image, out: FloatArray, lb: Letterbox) {
        val width = lb.inputWidth
        val area = width * lb.inputHeight
        java.util.Arrays.fill(out, 0, 3 * area, PAD)

        val planes = image.planes
        yBytes = readPlane(planes[0].buffer, yBytes)
        uBytes = readPlane(planes[1].buffer, uBytes)
        vBytes = readPlane(planes[2].buffer, vBytes)
        val yRow = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        val uvRow = planes[1].rowStride
        val uvPixel = planes[1].pixelStride

        if (columns.size != lb.scaledWidth) {
            columns = IntArray(lb.scaledWidth) { x -> min((x / lb.scale).toInt(), lb.sourceWidth - 1) }
        }
        for (row in 0 until lb.scaledHeight) {
            val sy = min((row / lb.scale).toInt(), lb.sourceHeight - 1)
            val yBase = sy * yRow
            val uvBase = (sy shr 1) * uvRow
            val dstBase = (row + lb.offsetY) * width + lb.offsetX
            for (col in 0 until lb.scaledWidth) {
                val sx = columns[col]
                val y = (yBytes[yBase + sx * yPixel].toInt() and 0xFF).toFloat()
                val uvIndex = uvBase + (sx shr 1) * uvPixel
                val u = (uBytes[uvIndex].toInt() and 0xFF) - 128f
                val v = (vBytes[uvIndex].toInt() and 0xFF) - 128f
                val dst = dstBase + col
                out[dst] = clamp(y + 1.402f * v) * INV_255
                out[area + dst] = clamp(y - 0.344136f * u - 0.714136f * v) * INV_255
                out[2 * area + dst] = clamp(y + 1.772f * u) * INV_255
            }
        }
    }

    private fun readPlane(buffer: ByteBuffer, store: ByteArray): ByteArray {
        val size = buffer.remaining()
        val target = if (store.size >= size) store else ByteArray(size)
        buffer.get(target, 0, size)
        return target
    }

    private fun clamp(value: Float): Float = if (value < 0f) 0f else if (value > 255f) 255f else value

    private companion object {
        const val INV_255 = 1f / 255f

        // Gris neutre des bandes ajoutées autour de l'image (114/255), comme à l'entraînement du modèle.
        const val PAD = 114f / 255f
    }
}
