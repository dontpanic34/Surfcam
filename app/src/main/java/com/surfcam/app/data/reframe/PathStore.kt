package com.surfcam.app.data.reframe

import android.content.Context
import com.surfcam.app.domain.reframe.PathBox
import com.surfcam.app.domain.reframe.PathFrame
import org.json.JSONArray
import java.io.File

/**
 * Chemin du surfeur pendant un clip (une liste d'images avec les cadres vus), gardé à côté de l'app pour pouvoir recadrer
 * le clip plus tard. Un fichier par clip, nommé comme lui.
 */
class PathStore(context: Context) {
    private val dir = File(context.filesDir, "paths").also { it.mkdirs() }

    private fun file(clipName: String) = File(dir, clipName.removeSuffix(".mp4") + ".json")

    fun has(clipName: String): Boolean = file(clipName).exists()

    fun delete(clipName: String) {
        file(clipName).delete()
    }

    fun save(clipName: String, frames: List<PathFrame>) {
        val array = JSONArray()
        frames.forEach { f ->
            val boxes = JSONArray()
            f.boxes.forEach { b ->
                boxes.put(JSONArray().put(b.left.toDouble()).put(b.top.toDouble()).put(b.right.toDouble()).put(b.bottom.toDouble()).put(if (b.riding) 1 else 0))
            }
            array.put(JSONArray().put(f.tMs).put(boxes))
        }
        file(clipName).writeText(array.toString())
    }

    fun load(clipName: String): List<PathFrame>? {
        val f = file(clipName)
        if (!f.exists()) return null
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val row = array.getJSONArray(i)
                val boxes = row.getJSONArray(1)
                PathFrame(
                    row.getLong(0),
                    (0 until boxes.length()).map { j ->
                        val b = boxes.getJSONArray(j)
                        PathBox(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), b.getInt(4) == 1)
                    },
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
