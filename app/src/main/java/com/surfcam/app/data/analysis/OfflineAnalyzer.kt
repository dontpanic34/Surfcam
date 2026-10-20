package com.surfcam.app.data.analysis

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import com.surfcam.app.data.library.SessionPart
import com.surfcam.app.domain.analysis.ClipRange
import com.surfcam.app.domain.analysis.ClipRangeFinder
import com.surfcam.app.domain.detection.FrameThrottle
import com.surfcam.app.domain.detection.Letterbox
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.tracking.BoundingBox
import com.surfcam.app.domain.tracking.WaveConfig

/**
 * Retrouve les vagues dans une session déjà filmée : les morceaux sont décodés dans l'ordre, environ trois images par
 * seconde passent dans le même modèle et la même détection que pendant l'enregistrement. Compter à peu près une heure
 * d'analyse par heure de session. Se met en pause tant que le téléphone est trop chaud.
 */
class OfflineAnalyzer(private val context: Context) {

    @Volatile
    var cancelled = false

    /**
     * @param onProgress fraction de la session analysée (0 à 1) et nombre de passages trouvés
     * @return les passages trouvés ; leur nombre est celui de [onProgress]
     */
    fun analyse(parts: List<SessionPart>, waveConfig: WaveConfig, onProgress: (Float, Int) -> Unit): Result<List<ClipRange>> {
        val totalUs = parts.sumOf { it.durationUs }.coerceAtLeast(1L)
        val finder = ClipRangeFinder(waveConfig, CaptureConfig.ANALYSIS_HEIGHT)
        val found = mutableListOf<ClipRange>()
        val converter = YuvToTensor()
        val input = FloatArray(3 * OnnxPersonDetector.MODEL_WIDTH * OnnxPersonDetector.MODEL_HEIGHT)
        val throttle = FrameThrottle(INTERVAL_US)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var letterbox: Letterbox? = null
        val detector = try {
            OnnxPersonDetector(context)
        } catch (t: Throwable) {
            return Result.failure(IllegalStateException("Modèle de détection indisponible : ${t.message}"))
        }
        try {
            var partStartUs = 0L
            for (part in parts) {
                if (cancelled) break
                val extractor = MediaExtractor()
                var codec: MediaCodec? = null
                try {
                    extractor.setDataSource(context, part.uri, null)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    } ?: throw IllegalStateException("Pas d'image dans un morceau")
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    val firstPts = extractor.sampleTime.coerceAtLeast(0L)
                    codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone && !cancelled) {
                        if (!inputDone) {
                            val i = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (i >= 0) {
                                val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (out < 0) continue
                        val ts = partStartUs + (info.presentationTimeUs - firstPts)
                        if (info.size > 0 && throttle.accept(ts)) {
                            val image = codec.getOutputImage(out)
                            if (image != null) {
                                try {
                                    val lb = letterbox ?: Letterbox(image.width, image.height, OnnxPersonDetector.MODEL_WIDTH, OnnxPersonDetector.MODEL_HEIGHT).also { letterbox = it }
                                    waitIfHot(power)
                                    for (range in analyseFrame(image, lb, converter, input, detector, finder, ts)) found += range
                                } finally {
                                    image.close()
                                }
                                onProgress((ts.toFloat() / totalUs).coerceIn(0f, 1f), found.size)
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                } finally {
                    try {
                        codec?.stop()
                    } catch (_: Exception) {
                    }
                    codec?.release()
                    extractor.release()
                }
                partStartUs += part.durationUs
            }
            if (!cancelled) found += finder.finish(totalUs)
            onProgress(1f, found.size)
            return Result.success(found)
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            detector.close()
        }
    }

    private fun analyseFrame(
        image: Image,
        lb: Letterbox,
        converter: YuvToTensor,
        input: FloatArray,
        detector: OnnxPersonDetector,
        finder: ClipRangeFinder,
        tsUs: Long,
    ): List<ClipRange> {
        converter.convert(image, input, lb)
        // Le modèle rend des pixels de la vidéo (4K) ; la détection de vague est réglée pour une image de 1280 de large.
        val k = CaptureConfig.ANALYSIS_WIDTH.toFloat() / lb.sourceWidth
        val detections = detector.detect(input, lb, tsUs).map {
            it.copy(box = BoundingBox(it.box.left * k, it.box.top * k, it.box.right * k, it.box.bottom * k))
        }
        return finder.onFrame(detections, tsUs)
    }

    /** Laisse refroidir le téléphone : l'analyse est longue et ne presse pas. */
    private fun waitIfHot(power: PowerManager) {
        if (Build.VERSION.SDK_INT < 29) return
        while (!cancelled && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) Thread.sleep(5_000)
    }

    private companion object {
        const val INTERVAL_US = 333_000L
        const val TIMEOUT_US = 10_000L
    }
}
