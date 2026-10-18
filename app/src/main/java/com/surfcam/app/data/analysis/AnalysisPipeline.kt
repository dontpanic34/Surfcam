package com.surfcam.app.data.analysis

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.surfcam.app.domain.clip.ClipAction
import com.surfcam.app.domain.clip.ClipPlanner
import com.surfcam.app.domain.detection.Letterbox
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.tracking.SurferTracker
import com.surfcam.app.domain.tracking.TrackerConfig
import com.surfcam.app.domain.tracking.WaveDetector

/** Un surfeur suivi, en coordonnées relatives (0 à 1) de l'image d'analyse, pour dessiner son cadre. */
data class OverlayBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val id: Int, val riding: Boolean)

data class AnalysisSnapshot(
    val boxes: List<OverlayBox>,
    val inferenceMs: Long,
    val riding: Boolean,
    val recording: Boolean,
)

/**
 * Chaîne d'analyse : une image réduite (1280×720) sort de la caméra, passe dans le modèle, puis dans le suivi,
 * la détection de vague et le planificateur de clips. Tourne sur son propre thread, en ne gardant que l'image la
 * plus récente : si le modèle est lent, des images sont sautées, jamais empilées. Chaque image est refermée
 * dans tous les cas pour ne pas bloquer la caméra.
 */
class AnalysisPipeline(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onClipAction(action: ClipAction)
        fun onSnapshot(snapshot: AnalysisSnapshot)

        /** Message sur l'état de l'analyse ; null quand tout va bien. */
        fun onStatus(message: String?)
    }

    private val thread = HandlerThread("analysis").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3)

    private val letterbox = Letterbox(WIDTH, HEIGHT, OnnxPersonDetector.MODEL_WIDTH, OnnxPersonDetector.MODEL_HEIGHT)
    private val converter = YuvToTensor()
    private val input = FloatArray(3 * OnnxPersonDetector.MODEL_WIDTH * OnnxPersonDetector.MODEL_HEIGHT)
    private val tracker = SurferTracker(TrackerConfig(frameHeight = HEIGHT))
    private val waveDetector = WaveDetector()
    private val planner = ClipPlanner(
        preRollUs = CaptureConfig.PRE_ROLL_SEC * 1_000_000L,
        postRollUs = CaptureConfig.POST_ROLL_SEC * 1_000_000L,
    )

    @Volatile
    private var detector: OnnxPersonDetector? = null

    @Volatile
    private var released = false
    private var lastProcessedUs = Long.MIN_VALUE

    /** Surface à brancher sur la caméra pour alimenter l'analyse. */
    val surface: Surface get() = reader.surface

    init {
        handler.post { loadModel() }
        reader.setOnImageAvailableListener({ onImage() }, handler)
    }

    fun release() {
        released = true
        reader.setOnImageAvailableListener(null, null)
        handler.post {
            detector?.close()
            detector = null
            reader.close()
            thread.quitSafely()
        }
    }

    private fun loadModel() {
        try {
            detector = OnnxPersonDetector(context)
            listener.onStatus(null)
        } catch (t: Throwable) {
            listener.onStatus("Détection indisponible : ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun onImage() {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val model = detector
            if (released || model == null) return
            val timestampUs = image.timestamp / 1_000
            if (timestampUs - lastProcessedUs < MIN_INTERVAL_US) return
            lastProcessedUs = timestampUs

            val started = SystemClock.elapsedRealtime()
            converter.convert(image, input, letterbox)
            val detections = model.detect(input, letterbox, timestampUs)
            val tracks = tracker.update(detections, timestampUs)
            val event = waveDetector.onFrame(tracks, timestampUs)
            val actions = (if (event != null) planner.onWave(event, timestampUs) else emptyList()) + planner.onTick(timestampUs)
            actions.forEach(listener::onClipAction)

            val ridingId = waveDetector.ridingId
            listener.onSnapshot(
                AnalysisSnapshot(
                    boxes = tracks.filter { it.lastSeenUs == timestampUs }.map {
                        OverlayBox(
                            it.box.left / WIDTH, it.box.top / HEIGHT, it.box.right / WIDTH, it.box.bottom / HEIGHT,
                            it.id, it.id == ridingId,
                        )
                    },
                    inferenceMs = SystemClock.elapsedRealtime() - started,
                    riding = waveDetector.isRiding,
                    recording = planner.isRecording,
                ),
            )
        } catch (t: Throwable) {
            released = true
            listener.onStatus("Analyse arrêtée : ${t.message ?: t.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    private companion object {
        const val WIDTH = CaptureConfig.ANALYSIS_WIDTH
        const val HEIGHT = CaptureConfig.ANALYSIS_HEIGHT
        const val MIN_INTERVAL_US = CaptureConfig.ANALYSIS_INTERVAL_MS * 1_000L
    }
}
