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

    private var yRowStride = 0
    private var yPixelStride = 0
    private var uvRowStride = 0
    private var uvPixelStride = 0
    private var srcWidth = 0
    private var srcHeight = 0

    /**
     * Compte, dans la dernière image convertie et en pleine résolution, les pixels de la [box] (coordonnées de
     * l'image source) qui ont la couleur du manchon. Renvoie aussi le nombre de pixels examinés.
     */
    fun markerPixels(box: com.surfcam.app.domain.tracking.BoundingBox, color: com.surfcam.app.domain.detection.MarkerColor): Pair<Int, Int> {
        if (srcWidth == 0) return 0 to 0
        val x0 = box.left.toInt().coerceIn(0, srcWidth - 1)
        val x1 = box.right.toInt().coerceIn(x0 + 1, srcWidth)
        val y0 = box.top.toInt().coerceIn(0, srcHeight - 1)
        val y1 = box.bottom.toInt().coerceIn(y0 + 1, srcHeight)
        var matching = 0
        for (y in y0 until y1) {
            val yBase = y * yRowStride
            val uvBase = (y shr 1) * uvRowStride
            for (x in x0 until x1) {
                val yy = (yBytes[yBase + x * yPixelStride].toInt() and 0xFF).toFloat()
                val uvIndex = uvBase + (x shr 1) * uvPixelStride
                val u = (uBytes[uvIndex].toInt() and 0xFF) - 128f
                val v = (vBytes[uvIndex].toInt() and 0xFF) - 128f
                val r = clamp(yy + 1.402f * v).toInt()
                val g = clamp(yy - 0.344136f * u - 0.714136f * v).toInt()
                val b = clamp(yy + 1.772f * u).toInt()
                if (color.matches(r, g, b)) matching++
            }
        }
        return matching to (x1 - x0) * (y1 - y0)
    }

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
        yRowStride = yRow
        yPixelStride = yPixel
        uvRowStride = uvRow
        uvPixelStride = uvPixel
        srcWidth = lb.sourceWidth
        srcHeight = lb.sourceHeight

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
