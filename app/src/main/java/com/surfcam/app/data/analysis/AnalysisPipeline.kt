package com.surfcam.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
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
    /** Nombre d'images analysées depuis le début de la session. */
    val frames: Int = 0,
    /** Personnes trouvées par le modèle sur la dernière image, avant le suivi. */
    val personsInFrame: Int = 0,
    val bestConfidence: Float = 0f,
    /** Vignette de ce que le modèle voit (rafraîchie environ une fois par seconde), pour le diagnostic. */
    val thumbnail: Bitmap? = null,
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

        /** Nombre d'images reçues de la caméra jusqu'ici, même si elles n'ont pas pu être analysées. */
        fun onReceived(count: Int)
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
    private var frames = 0
    private var received = 0
    private var thumbnail: Bitmap? = null

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
        var stage = "reception"
        try {
            received++
            if (received == 1 || received % HEARTBEAT_EVERY == 0) listener.onReceived(received)
            val model = detector
            if (released || model == null) return
            val timestampUs = image.timestamp / 1_000
            if (timestampUs - lastProcessedUs < MIN_INTERVAL_US) return
            lastProcessedUs = timestampUs

            val started = SystemClock.elapsedRealtime()
            stage = "conversion de l'image"
            converter.convert(image, input, letterbox)
            stage = "modele"
            val detections = model.detect(input, letterbox, timestampUs)
            stage = "suivi"
            frames++
            if (frames % THUMBNAIL_EVERY == 1) thumbnail = makeThumbnail()
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
                    frames = frames,
                    personsInFrame = detections.size,
                    bestConfidence = detections.maxOfOrNull { it.confidence } ?: 0f,
                    thumbnail = thumbnail,
                ),
            )
        } catch (t: Throwable) {
            released = true
            listener.onStatus("Analyse arrêtée ($stage) : ${t.javaClass.simpleName} ${t.message ?: ""}".trim())
        } finally {
            image.close()
        }
    }

    /** Vignette 320×180 de l'image telle que le modèle la reçoit (échantillonnée, sans les bandes grises). */
    private fun makeThumbnail(): Bitmap {
        val w = THUMB_WIDTH
        val h = THUMB_HEIGHT
        val step = WIDTH / w
        val modelWidth = OnnxPersonDetector.MODEL_WIDTH
        val area = modelWidth * OnnxPersonDetector.MODEL_HEIGHT
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = (y * step + letterbox.offsetY) * modelWidth
            for (x in 0 until w) {
                val i = row + x * step + letterbox.offsetX
                val r = (input[i] * 255f).toInt().coerceIn(0, 255)
                val g = (input[area + i] * 255f).toInt().coerceIn(0, 255)
                val b = (input[2 * area + i] * 255f).toInt().coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val THUMB_WIDTH = 320
        const val THUMB_HEIGHT = 180
        const val THUMBNAIL_EVERY = 5
        const val HEARTBEAT_EVERY = 15
        const val WIDTH = CaptureConfig.ANALYSIS_WIDTH
        const val HEIGHT = CaptureConfig.ANALYSIS_HEIGHT
        const val MIN_INTERVAL_US = CaptureConfig.ANALYSIS_INTERVAL_MS * 1_000L
    }
}
