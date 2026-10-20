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

    /**
     * Image au format P010 (HDR 10 bits, BT.2020, plage limitée) au lieu de YUV 8 bits : chaque échantillon fait deux octets
     * et son octet de poids fort est une valeur 8 bits correcte, qu'on lit seule.
     */
    var tenBit = false

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
        // Le repérage du manchon n'est pas encore disponible avec l'analyse en 10 bits (HDR).
        if (srcWidth == 0 || tenBit) return 0 to 0
        val x0 = box.left.toInt().coerceIn(0, srcWidth - 1)
        val x1 = box.right.toInt().coerceIn(x0 + 1, srcWidth)
        val y0 = box.top.toInt().coerceIn(0, srcHeight - 1)
        val y1 = box.bottom.toInt().coerceIn(y0 + 1, srcHeight)
        var matching = 0
        for (y in y0 until y1) {
            val yBase = y * yRowStride
            val uvBase = (y shr 1) * uvRowStride
            for (x in x0 until x1) {
                val off = if (tenBit) 1 else 0
                val yy = (yBytes[yBase + x * yPixelStride + off].toInt() and 0xFF).toFloat()
                val uvIndex = uvBase + (x shr 1) * uvPixelStride + off
                val u = (uBytes[uvIndex].toInt() and 0xFF) - 128f
                val v = (vBytes[uvIndex].toInt() and 0xFF) - 128f
                val r: Int
                val g: Int
                val b: Int
                if (tenBit) {
                    val yn = (yy - 16f) * 1.16438f
                    r = clamp(yn + 1.67867f * v).toInt()
                    g = clamp(yn - 0.18733f * u - 0.65042f * v).toInt()
                    b = clamp(yn + 2.14177f * u).toInt()
                } else {
                    r = clamp(yy + 1.402f * v).toInt()
                    g = clamp(yy - 0.344136f * u - 0.714136f * v).toInt()
                    b = clamp(yy + 1.772f * u).toInt()
                }
                if (color.matches(r, g, b)) matching++
            }
        }
        return matching to (x1 - x0) * (y1 - y0)
    }

    private var loggedP010 = false

    /**
     * Version 10 bits (P010) : on ne recopie pas les plans en bloc (une lecture hors des limites d'un tampon du système fait
     * tomber l'application sans rapport) ; chaque échantillon est lu avec une vérification des bornes.
     */
    private fun convertP010(image: Image, out: FloatArray, lb: Letterbox) {
        val width = lb.inputWidth
        val area = width * lb.inputHeight
        java.util.Arrays.fill(out, 0, 3 * area, PAD)
        val planes = image.planes
        if (planes.size < 3) throw IllegalStateException("P010 : ${planes.size} plan(s) seulement")
        val yb = planes[0].buffer
        val ub = planes[1].buffer
        val vb = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uvRow = planes[1].rowStride
        val uvPix = planes[1].pixelStride
        if (!loggedP010) {
            loggedP010 = true
            com.surfcam.app.data.engine.Trail.mark(
                "P010 ${image.width}x${image.height} Y rs=$yRow ps=$yPix lim=${yb.limit()} · U rs=$uvRow ps=$uvPix lim=${ub.limit()} · V rs=${planes[2].rowStride} ps=${planes[2].pixelStride} lim=${vb.limit()}",
            )
        }
        val yLimit = yb.limit()
        val uLimit = ub.limit()
        val vLimit = vb.limit()
        srcWidth = lb.sourceWidth
        srcHeight = lb.sourceHeight
        if (columns.size != lb.scaledWidth) {
            columns = IntArray(lb.scaledWidth) { x -> min((x / lb.scale).toInt(), lb.sourceWidth - 1) }
        }
        for (row in 0 until lb.scaledHeight) {
            val sy = min((row / lb.scale).toInt(), lb.sourceHeight - 1)
            val dstBase = (row + lb.offsetY) * width + lb.offsetX
            for (col in 0 until lb.scaledWidth) {
                val sx = columns[col]
                val yi = sy * yRow + sx * yPix + 1
                val uvi = (sy shr 1) * uvRow + (sx shr 1) * uvPix + 1
                val y = if (yi < yLimit) (yb.get(yi).toInt() and 0xFF).toFloat() else 16f
                val u = (if (uvi < uLimit) (ub.get(uvi).toInt() and 0xFF) else 128) - 128f
                val v = (if (uvi < vLimit) (vb.get(uvi).toInt() and 0xFF) else 128) - 128f
                val dst = dstBase + col
                // BT.2020, plage limitée (16 à 235) : on étend avant de convertir en RVB.
                val yn = (y - 16f) * 1.16438f
                out[dst] = clamp(yn + 1.67867f * v) * INV_255
                out[area + dst] = clamp(yn - 0.18733f * u - 0.65042f * v) * INV_255
                out[2 * area + dst] = clamp(yn + 2.14177f * u) * INV_255
            }
        }
    }

    fun convert(image: Image, out: FloatArray, lb: Letterbox) {
        if (tenBit) {
            convertP010(image, out, lb)
            return
        }
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
                val off = if (tenBit) 1 else 0
                val y = (yBytes[yBase + sx * yPixel + off].toInt() and 0xFF).toFloat()
                val uvIndex = uvBase + (sx shr 1) * uvPixel + off
                val u = (uBytes[uvIndex].toInt() and 0xFF) - 128f
                val v = (vBytes[uvIndex].toInt() and 0xFF) - 128f
                val dst = dstBase + col
                if (tenBit) {
                    // BT.2020, plage limitée (16 à 235) : on étend avant de convertir en RVB.
                    val yn = (y - 16f) * 1.16438f
                    out[dst] = clamp(yn + 1.67867f * v) * INV_255
                    out[area + dst] = clamp(yn - 0.18733f * u - 0.65042f * v) * INV_255
                    out[2 * area + dst] = clamp(yn + 2.14177f * u) * INV_255
                } else {
                    out[dst] = clamp(y + 1.402f * v) * INV_255
                    out[area + dst] = clamp(y - 0.344136f * u - 0.714136f * v) * INV_255
                    out[2 * area + dst] = clamp(y + 1.772f * u) * INV_255
                }
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
