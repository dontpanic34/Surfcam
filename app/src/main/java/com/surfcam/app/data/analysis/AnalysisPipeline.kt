package com.surfcam.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import android.view.Surface
import com.surfcam.app.domain.clip.ClipAction
import com.surfcam.app.domain.clip.ClipPlanner
import com.surfcam.app.domain.detection.ClockOffsetEstimator
import com.surfcam.app.domain.detection.FrameThrottle
import com.surfcam.app.domain.detection.Letterbox
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.tracking.SurferTracker
import com.surfcam.app.domain.tracking.RideStats
import com.surfcam.app.domain.tracking.TrackerConfig
import com.surfcam.app.domain.tracking.WaveConfig
import com.surfcam.app.domain.tracking.WaveEvent
import com.surfcam.app.domain.tracking.WaveDetector

/** Un surfeur suivi, en coordonnées relatives (0 à 1) de l'image d'analyse, pour dessiner son cadre. */
data class OverlayBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val id: Int, val riding: Boolean, val marked: Boolean = false)

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
    /** Écart mesuré entre l'horloge des images d'analyse et celle de la vidéo, en millisecondes (diagnostic). */
    val clockOffsetMs: Long = 0,
    /** Instant de l'image analysée, dans l'horloge d'analyse (microsecondes). */
    val timestampUs: Long = 0,
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
    waveConfig: WaveConfig = WaveConfig(),
    /** HDR 10 bits : l'image d'analyse arrive alors au format P010 (le seul compatible avec un flux HDR). */
    private val tenBit: Boolean = false,
) {
    interface Listener {
        fun onClipAction(action: ClipAction)
        fun onSnapshot(snapshot: AnalysisSnapshot)

        /** Message sur l'état de l'analyse ; null quand tout va bien. */
        fun onStatus(message: String?)

        /** Nombre d'images reçues de la caméra jusqu'ici, même si elles n'ont pas pu être analysées. */
        fun onReceived(count: Int)

        /** Instant de la dernière image vidéo encodée, dans l'horloge de la vidéo (pour recaler l'horloge d'analyse). */
        fun newestEncodedPtsUs(): Long?

        /** Une vague vient de se terminer : ses caractéristiques, pour l'apprentissage. */
        fun onRide(stats: RideStats)
    }

    // Deux fils distincts : l'un ne fait que prélever et rendre les images de la caméra (rapide, quelques ms), l'autre
    // exécute le modèle (lent, environ 0,3 s). Garder une image pendant l'analyse bloquerait la caméra entière.
    private val readerThread = HandlerThread("analysis-reader").apply { start() }
    private val readerHandler = Handler(readerThread.looper)
    private val inferenceThread = HandlerThread("analysis-inference").apply { start() }
    private val inferenceHandler = Handler(inferenceThread.looper)
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, if (tenBit) ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888, 4)

    /** Vrai tant que le modèle lit le tenseur d'entrée : on ne le réécrit pas avant la fin de l'analyse. */
    private val busy = AtomicBoolean(false)

    private val letterbox = Letterbox(WIDTH, HEIGHT, OnnxPersonDetector.MODEL_WIDTH, OnnxPersonDetector.MODEL_HEIGHT)
    private val converter = YuvToTensor().also { it.tenBit = tenBit }
    private val input = FloatArray(3 * OnnxPersonDetector.MODEL_WIDTH * OnnxPersonDetector.MODEL_HEIGHT)
    private val tracker = SurferTracker(TrackerConfig(frameHeight = HEIGHT))
    private val waveDetector = WaveDetector(waveConfig)
    private val planner = ClipPlanner(
        preRollUs = CaptureConfig.PRE_ROLL_SEC * 1_000_000L,
        postRollUs = CaptureConfig.POST_ROLL_SEC * 1_000_000L,
    )

    @Volatile
    private var detector: OnnxPersonDetector? = null

    @Volatile
    private var released = false

    /** Couleur du manchon de l'utilisateur ; null = ne pas chercher. */
    @Volatile
    var markerColor: com.surfcam.app.domain.detection.MarkerColor? = null

    /** En pause (téléphone trop chaud) : les images sont reçues puis jetées sans être analysées. */
    @Volatile
    var paused = false
    private val throttle = FrameThrottle(MIN_INTERVAL_US)

    /** Ralentit l'analyse quand le téléphone chauffe : 1 = cadence normale, 2 = deux fois moins d'images, etc. */
    fun setSlowdown(factor: Int) {
        throttle.minIntervalUs = MIN_INTERVAL_US * factor.coerceIn(1, 8)
    }
    private var frames = 0
    private var received = 0

    // Les images d'analyse et la vidéo encodée viennent de la même caméra, mais rien ne garantit qu'elles partagent la
    // même horloge. On mesure l'écart sur les dernières images (médiane) et on le retranche avant de découper un clip.
    private val clock = ClockOffsetEstimator()

    @Volatile
    private var clockOffsetUs = 0L
    private var thumbnail: Bitmap? = null

    /** Surface à brancher sur la caméra pour alimenter l'analyse. */
    val surface: Surface get() = reader.surface

    init {
        inferenceHandler.post { loadModel() }
        reader.setOnImageAvailableListener({ onImage() }, readerHandler)
    }

    fun release() {
        released = true
        reader.setOnImageAvailableListener(null, null)
        readerHandler.post {
            reader.close()
            readerThread.quitSafely()
        }
        inferenceHandler.post {
            detector?.close()
            detector = null
            inferenceThread.quitSafely()
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

    /** Fil de lecture : copie l'image dans le tenseur, la rend aussitôt à la caméra, puis passe la main au modèle. */
    private fun onImage() {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            received++
            if (received == 1) com.surfcam.app.data.engine.Trail.mark("première image d'analyse reçue")
            if (received == 1 || received % HEARTBEAT_EVERY == 0) listener.onReceived(received)
            if (released || paused || detector == null || busy.get()) return
            val timestampUs = image.timestamp / 1_000
            if (!throttle.accept(timestampUs)) return

            listener.newestEncodedPtsUs()?.let { encoded ->
                clock.add(timestampUs, encoded)
                clockOffsetUs = clock.offsetUs
            }
            converter.markerColor = markerColor
            converter.convert(image, input, letterbox)
            if (received == 1) com.surfcam.app.data.engine.Trail.mark("première conversion faite")
            busy.set(true)
            inferenceHandler.post { analyse(timestampUs) }
        } catch (t: Throwable) {
            fail("conversion de l'image", t)
        } finally {
            image.close()
        }
    }

    /** Fil d'analyse : modèle, suivi, détection de vague, clips. Libère le tenseur à la fin. */
    private fun analyse(timestampUs: Long) {
        try {
            val model = detector ?: return
            val started = SystemClock.elapsedRealtime()
            val detections = try {
                val found = model.detect(input, letterbox, timestampUs)
                val marker = markerColor
                if (marker == null) found else found.map {
                    val (hits, total) = converter.markerPixels(it.box, marker)
                    it.copy(wearsMarker = com.surfcam.app.domain.detection.MarkerColor.isWorn(hits, total))
                }
            } catch (t: Throwable) {
                fail("modele", t)
                return
            }
            frames++
            if (frames == 1) com.surfcam.app.data.engine.Trail.mark("première détection terminée")
            if (frames % THUMBNAIL_EVERY == 1) thumbnail = makeThumbnail()
            val tracks = tracker.update(detections, timestampUs)
            val event = waveDetector.onFrame(tracks, timestampUs)
            if (event is WaveEvent.Ended) event.stats?.let(listener::onRide)
            val actions = (if (event != null) planner.onWave(event, timestampUs) else emptyList()) + planner.onTick(timestampUs)
            actions.forEach {
                // Le début d'un clip est une date de l'horloge d'analyse : on la convertit dans celle de la vidéo.
                listener.onClipAction(if (it is ClipAction.Start) ClipAction.Start(it.fromUs - clockOffsetUs) else it)
            }

            val ridingId = waveDetector.ridingId
            listener.onSnapshot(
                AnalysisSnapshot(
                    boxes = tracks.filter { it.lastSeenUs == timestampUs }.map {
                        OverlayBox(
                            it.box.left / WIDTH, it.box.top / HEIGHT, it.box.right / WIDTH, it.box.bottom / HEIGHT,
                            it.id, it.id == ridingId, it.isMarked,
                        )
                    },
                    inferenceMs = SystemClock.elapsedRealtime() - started,
                    riding = waveDetector.isRiding,
                    recording = planner.isRecording,
                    frames = frames,
                    personsInFrame = detections.size,
                    bestConfidence = detections.maxOfOrNull { it.confidence } ?: 0f,
                    thumbnail = thumbnail,
                    clockOffsetMs = clockOffsetUs / 1_000,
                    timestampUs = timestampUs,
                ),
            )
        } catch (t: Throwable) {
            fail("suivi", t)
        } finally {
            busy.set(false)
        }
    }

    private fun fail(stage: String, t: Throwable) {
        released = true
        listener.onStatus("Analyse arrêtée ($stage) : ${t.javaClass.simpleName} ${t.message ?: ""}".trim())
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
        const val HEARTBEAT_EVERY = 30
        const val WIDTH = CaptureConfig.ANALYSIS_WIDTH
        const val HEIGHT = CaptureConfig.ANALYSIS_HEIGHT
        const val MIN_INTERVAL_US = CaptureConfig.ANALYSIS_INTERVAL_MS * 1_000L
    }
}
