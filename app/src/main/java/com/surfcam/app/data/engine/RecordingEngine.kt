package com.surfcam.app.data.engine

import android.content.Context
import android.media.MediaFormat
import android.os.PowerManager
import android.view.Surface
import com.surfcam.app.data.camera.Camera2Source
import com.surfcam.app.data.camera.CameraProbe
import com.surfcam.app.data.camera.Lens
import com.surfcam.app.data.encoder.HevcEncoder
import com.surfcam.app.data.storage.SavedClip
import com.surfcam.app.domain.buffer.EncodedRingBuffer
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.model.EncodedSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

enum class Phase { IDLE, PREVIEW, STARTING, RUNNING, ERROR }

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val config: CaptureConfig? = null,
    val zoom: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val locked: Boolean = false,
    val lens: Lens = Lens.MAIN,
    val lensInfo: String? = null,
    val maxFpsAtSize: Int = 0,
    val bufferedSec: Float = 0f,
    val bufferedMb: Int = 0,
    val clipActive: Boolean = false,
    val lastClip: String? = null,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val message: String? = null,
)

/**
 * Orchestre la capture : caméra 4K -> encodeur H.265 -> tampon circulaire de 8 s -> clips MP4.
 * Possédé par l'application (et non par l'écran), pour continuer à tourner quand l'écran est verrouillé.
 */
class RecordingEngine(private val context: Context) {

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainExecutor = Executor { it.run() }

    private var camera: Camera2Source? = null
    private var encoder: HevcEncoder? = null
    private var ring: EncodedRingBuffer? = null
    private var recorder: ClipRecorder? = null
    private var previewSurface: Surface? = null
    private var sampleCount = 0L

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _state.update { it.copy(thermalStatus = status) }
    }

    /** [displayRotationDegrees] : rotation de l'écran (0, 90, 180, 270) au moment du démarrage. */
    @Synchronized
    fun start(displayRotationDegrees: Int, lens: Lens) {
        if (_state.value.phase == Phase.STARTING || _state.value.phase == Phase.RUNNING) return
        // Libère l'aperçu (ou les restes d'une session en erreur) avant de rouvrir la caméra pour filmer.
        releaseCamera()
        teardownPipeline()
        _state.value = EngineState(phase = Phase.STARTING, lens = lens, thermalStatus = power.currentThermalStatus)
        power.removeThermalStatusListener(thermalListener)
        power.addThermalStatusListener(mainExecutor, thermalListener)

        val source = Camera2Source(context)
        try {
            val firstTry = tryStart(source, 60, displayRotationDegrees, lens)
            if (!firstTry) {
                teardownPipeline()
                if (!tryStart(source, 30, displayRotationDegrees, lens)) {
                    throw IllegalStateException("L'encodeur H.265 refuse la 4K sur ce téléphone")
                }
            }
            camera = source
        } catch (e: Exception) {
            source.release()
            teardownPipeline()
            fail(e.message ?: "Démarrage impossible")
        }
    }

    @Synchronized
    fun stop() {
        if (_state.value.phase == Phase.IDLE || _state.value.phase == Phase.PREVIEW) return
        power.removeThermalStatusListener(thermalListener)
        releaseCamera()
        teardownPipeline()
        _state.update { it.copy(phase = Phase.IDLE, locked = false, clipActive = false, bufferedSec = 0f, bufferedMb = 0, message = null) }
    }

    /** Ouvre la caméra en aperçu seul, sans rien enregistrer, pour cadrer avant de démarrer la session. */
    @Synchronized
    fun startPreview(lens: Lens) {
        val current = _state.value
        if (current.phase == Phase.STARTING || current.phase == Phase.RUNNING || current.phase == Phase.ERROR) return
        if (current.phase == Phase.PREVIEW && current.lens == lens) return
        releaseCamera()

        val source = Camera2Source(context)
        try {
            val probe = source.probe(30, lens)
            camera = source
            _state.value = EngineState(
                phase = Phase.PREVIEW,
                lens = lens,
                minZoom = probe.minZoom,
                maxZoom = probe.maxZoom,
            )
            source.open(probe, null, previewSurface, 1f, object : Camera2Source.Listener {
                override fun onLockChanged(locked: Boolean) = Unit

                override fun onLensInfo(text: String) {
                    _state.update { it.copy(lensInfo = text) }
                }

                override fun onError(message: String) {
                    _state.update { it.copy(phase = Phase.IDLE, message = message) }
                }
            })
        } catch (e: Exception) {
            source.release()
            camera = null
            _state.value = EngineState(phase = Phase.IDLE, lens = lens, message = e.message ?: "Aperçu impossible")
        }
    }

    @Synchronized
    fun stopPreview() {
        if (_state.value.phase != Phase.PREVIEW) return
        releaseCamera()
        _state.value = EngineState(phase = Phase.IDLE, lens = _state.value.lens)
    }

    @Synchronized
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        camera?.setPreviewSurface(surface)
    }

    @Synchronized
    fun setZoom(ratio: Float) {
        val s = _state.value
        val clamped = ratio.coerceIn(s.minZoom, s.maxZoom)
        _state.update { it.copy(zoom = clamped) }
        camera?.setZoom(clamped)
    }

    @Synchronized
    fun relock() {
        camera?.relock()
    }

    /** Démarre un clip : les [CaptureConfig.PRE_ROLL_SEC] dernières secondes sont reprises du tampon. */
    @Synchronized
    fun startClip() {
        val started = recorder?.start(CaptureConfig.PRE_ROLL_SEC) == true
        if (started) {
            _state.update { it.copy(clipActive = true, message = null) }
        } else {
            _state.update { it.copy(message = "Clip impossible pour l'instant") }
        }
    }

    /** Termine le clip après [CaptureConfig.POST_ROLL_SEC] secondes supplémentaires. */
    @Synchronized
    fun finishClip() {
        recorder?.finish(CaptureConfig.POST_ROLL_SEC * 1_000L)
    }

    private fun tryStart(source: Camera2Source, maxFps: Int, rotationDegrees: Int, lens: Lens): Boolean {
        val probe: CameraProbe = source.probe(maxFps, lens)
        val config = probe.config
        val buffer = EncodedRingBuffer(config.bufferWindowSec * 1_000_000L)
        // Caméra arrière : le sens du fichier dépend de l'orientation du capteur et de celle de l'écran.
        val hint = (probe.sensorOrientation - rotationDegrees + 360) % 360
        val clips = ClipRecorder(context, buffer, hint) { saved, error -> onClipResult(saved, error) }
        ring = buffer
        recorder = clips
        sampleCount = 0

        val enc = HevcEncoder(config, object : HevcEncoder.Listener {
            override fun onFormat(format: MediaFormat) {
                clips.format = format
            }

            override fun onSample(sample: EncodedSample) {
                buffer.add(sample)
                clips.onLiveSample(sample)
                if (++sampleCount % STATS_EVERY == 0L) publishBufferStats(buffer)
            }

            override fun onError(error: Exception) {
                fail("Encodeur arrêté : ${error.message}")
            }
        })
        val encoderSurface = try {
            enc.start()
        } catch (e: Exception) {
            enc.stop()
            return false
        }
        encoder = enc

        _state.update {
            it.copy(
                phase = Phase.RUNNING,
                config = config,
                maxFpsAtSize = probe.maxFpsAtSize,
                zoom = 1f,
                minZoom = probe.minZoom,
                maxZoom = probe.maxZoom,
            )
        }
        source.open(probe, encoderSurface, previewSurface, 1f, object : Camera2Source.Listener {
            override fun onLockChanged(locked: Boolean) {
                _state.update { it.copy(locked = locked) }
            }

            override fun onLensInfo(text: String) {
                _state.update { it.copy(lensInfo = text) }
            }

            override fun onError(message: String) {
                fail(message)
            }
        })
        return true
    }

    private fun publishBufferStats(buffer: EncodedRingBuffer) {
        val sec = buffer.spanUs() / 1_000_000f
        val mb = (buffer.sizeBytes() / (1024 * 1024)).toInt()
        _state.update { it.copy(bufferedSec = sec, bufferedMb = mb) }
    }

    private fun onClipResult(saved: SavedClip?, error: String?) {
        _state.update {
            it.copy(
                clipActive = false,
                lastClip = saved?.let { s -> "${s.name} (${"%.1f".format(s.durationSec)} s, ${s.sizeBytes / (1024 * 1024)} Mo)" } ?: it.lastClip,
                message = error,
            )
        }
    }

    private fun releaseCamera() {
        camera?.releaseBlocking()
        camera = null
    }

    private fun teardownPipeline() {
        encoder?.stop()
        encoder = null
        recorder?.release()
        recorder = null
        ring?.clear()
        ring = null
    }

    private fun fail(message: String) {
        _state.update { it.copy(phase = Phase.ERROR, message = message, clipActive = false) }
    }

    private companion object {
        const val STATS_EVERY = 30L
    }
}
