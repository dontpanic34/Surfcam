package com.surfcam.app.domain.buffer

import com.surfcam.app.domain.model.EncodedSample

/**
 * Tampon circulaire de vidéo déjà encodée.
 *
 * Il garde au moins [windowUs] microsecondes d'images, et supprime les plus anciennes par groupes
 * entiers (d'une image clé à la suivante) pour que le début du tampon soit toujours une image clé :
 * c'est la condition pour pouvoir écrire un clip lisible à partir de n'importe quel instant gardé.
 */
class EncodedRingBuffer(private val windowUs: Long) {

    private val samples = ArrayDeque<EncodedSample>()
    private var totalBytes = 0L

    @Synchronized
    fun add(sample: EncodedSample) {
        samples.addLast(sample)
        totalBytes += sample.data.size
        trim()
    }

    /**
     * Images à partir de la dernière image clé située à [startPtsUs] ou avant, jusqu'à la plus récente.
     * Si aucune image clé n'est assez ancienne, part de la plus ancienne image clé gardée.
     */
    @Synchronized
    fun snapshotFrom(startPtsUs: Long): List<EncodedSample> {
        var start = 0
        for (i in samples.indices) {
            val s = samples[i]
            if (s.isKeyFrame) {
                if (s.ptsUs <= startPtsUs) start = i else break
            }
        }
        return List(samples.size - start) { samples[start + it] }.dropWhile { !it.isKeyFrame }
    }

    @Synchronized
    fun newestPtsUs(): Long? = samples.lastOrNull()?.ptsUs

    @Synchronized
    fun spanUs(): Long = if (samples.size < 2) 0L else samples.last().ptsUs - samples.first().ptsUs

    @Synchronized
    fun sizeBytes(): Long = totalBytes

    @Synchronized
    fun clear() {
        samples.clear()
        totalBytes = 0L
    }

    private fun trim() {
        val newest = samples.last().ptsUs
        while (true) {
            val nextKey = nextKeyFrameIndex()
            if (nextKey < 0 || newest - samples[nextKey].ptsUs < windowUs) return
            repeat(nextKey) { totalBytes -= samples.removeFirst().data.size }
        }
    }

    private fun nextKeyFrameIndex(): Int {
        for (i in 1 until samples.size) {
            if (samples[i].isKeyFrame) return i
        }
        return -1
    }
}
