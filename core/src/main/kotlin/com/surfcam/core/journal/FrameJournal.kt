package com.surfcam.core.journal

import com.surfcam.core.geometry.CameraModel
import com.surfcam.core.model.Box
import com.surfcam.core.model.Detection
import com.surfcam.core.model.Frame
import com.surfcam.core.model.MarkerBlob
import java.io.BufferedReader
import java.io.Writer
import java.util.Locale

/**
 * Journal de capteurs d'une session : une ligne par image analysée, avec tout ce que le cerveau a reçu (cap à l'heure de la
 * prise de vue, champ, zoom, détections, taches de couleur), plus des lignes d'événements libres (ordres du gimbal, gyroscope,
 * roulis, chaleur, batterie, décisions). Chaque session enregistrée devient ainsi un cas de banc : on la rejoue sur ordinateur
 * avec exactement les entrées du téléphone, et on mesure l'effet d'une modification avant de l'envoyer à l'eau.
 *
 * Format texte, une ligne par enregistrement, champs séparés par « ; » :
 * - `F;t_us;cap;tangage;largeur;hauteur;champ_h;zoom;n;x1,y1,x2,y2,conf,manchon|…;blobs x,y,px|…`
 * - `E;t_us;type;texte libre`
 */
object FrameJournal {
    const val HEADER = "# SurfCam journal de capteurs v1"

    fun writeFrame(w: Writer, f: Frame) {
        val dets = f.detections.joinToString("|") { d ->
            val b = d.box
            String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f,%.3f,%.4f", b.left, b.top, b.right, b.bottom, d.confidence, d.markerScore)
        }
        val blobs = f.blobs.joinToString("|") { String.format(Locale.US, "%.1f,%.1f,%d", it.x, it.y, it.pixels) }
        w.write(String.format(Locale.US, "F;%d;%.3f;%.3f;%d;%d;%.4f;%.2f;%d;%s;%s\n",
            f.timeUs, f.headingDeg, f.pitchDeg, f.camera.widthPx, f.camera.heightPx, f.camera.hfovDeg, f.zoom, f.detections.size, dets, blobs))
    }

    fun writeEvent(w: Writer, timeUs: Long, type: String, text: String) {
        w.write("E;$timeUs;$type;${text.replace('\n', ' ')}\n")
    }

    sealed interface Entry {
        data class FrameEntry(val frame: Frame) : Entry
        data class Event(val timeUs: Long, val type: String, val text: String) : Entry
    }

    fun read(r: BufferedReader): Sequence<Entry> = r.lineSequence().mapNotNull { parse(it) }

    fun parse(line: String): Entry? {
        if (line.isBlank() || line.startsWith("#")) return null
        val p = line.split(';')
        return when (p[0]) {
            "F" -> {
                val cam = CameraModel(p[4].toInt(), p[5].toInt(), p[6].toDouble())
                val dets = if (p.size > 9 && p[9].isNotEmpty()) p[9].split('|').map { d ->
                    val v = d.split(',').map { it.toDouble() }
                    Detection(Box(v[0], v[1], v[2], v[3]), v[4], v.getOrElse(5) { 0.0 })
                } else emptyList()
                val blobs = if (p.size > 10 && p[10].isNotEmpty()) p[10].split('|').map { b ->
                    val v = b.split(',')
                    MarkerBlob(v[0].toDouble(), v[1].toDouble(), v[2].toInt())
                } else emptyList()
                Entry.FrameEntry(Frame(p[1].toLong(), p[2].toDouble(), p[3].toDouble(), cam, p[7].toDouble(), dets, blobs))
            }
            "E" -> Entry.Event(p[1].toLong(), p[2], p.drop(3).joinToString(";"))
            else -> null
        }
    }
}
