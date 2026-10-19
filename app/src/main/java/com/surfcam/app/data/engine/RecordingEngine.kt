package com.surfcam.app.data.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaFormat
import android.os.SystemClock
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import com.surfcam.app.R
import com.surfcam.app.service.RecordingService
import com.surfcam.app.data.analysis.AnalysisPipeline
import com.surfcam.app.data.analysis.AnalysisSnapshot
import com.surfcam.app.data.camera.Camera2Source
import com.surfcam.app.data.camera.CameraProbe
import com.surfcam.app.data.camera.Lens
import com.surfcam.app.data.encoder.HevcEncoder
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.data.storage.SavedClip
import com.surfcam.app.data.storage.StorageTarget
import com.surfcam.app.domain.buffer.EncodedRingBuffer
import com.surfcam.app.data.gimbal.GimbalFollower
import com.surfcam.app.data.reframe.PathStore
import com.surfcam.app.domain.gimbal.FollowBox
import com.surfcam.app.domain.gimbal.FollowController
import com.surfcam.app.domain.clip.ClipAction
import com.surfcam.app.domain.reframe.PathBox
import com.surfcam.app.domain.reframe.PathFrame
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.model.EncodedSample
import com.surfcam.app.domain.tracking.RideStats
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
    /** Focale en pixels de l'image d'analyse à zoom ×1 : permet d'estimer la distance d'un surfeur. */
    val focalPx1x: Float = 0f,
    val focusMode: Camera2Source.FocusMode = Camera2Source.FocusMode.AUTO,
    /** État du suivi par gimbal (null si non utilisé pour cette session). */
    val gimbalStatus: String? = null,
    val followEnabled: Boolean = false,
    val maxFpsAtSize: Int = 0,
    val keyFrameIntervalSec: Float = 0f,
    /** Cadence réellement obtenue de la caméra (images par seconde), mesurée sur la vidéo encodée. */
    val actualFps: Float = 0f,
    val bufferedSec: Float = 0f,
    val bufferedMb: Int = 0,
    val clipActive: Boolean = false,
    /** Dernière image analysée (cadres de suivi) ; null tant que la détection n'a rien produit. */
    val analysis: AnalysisSnapshot? = null,
    /** Message sur la détection automatique (modèle en chargement, indisponible…). */
    val analysisStatus: String? = null,
    /** Images que la caméra a envoyées à l'analyse (0 = le flux d'analyse ne reçoit rien). */
    val analysisReceived: Int = 0,
    val detectionEnabled: Boolean = true,
    val fullSession: Boolean = false,
    /** Numéro du fichier de la session complète en cours d'écriture (0 = aucun). */
    val sessionPart: Int = 0,
    /** Raison de l'arrêt automatique de la dernière session (espace, chaleur, batterie) ; null sinon. */
    val endedReason: String? = null,
    /** Bilan de la dernière session : ce qui a été enregistré (affiché une fois la session arrêtée). */
    val lastSummary: String? = null,
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

    /** Mémoire de l'apprentissage : jugements de l'utilisateur sur les clips automatiques, et réglages qui en découlent. */
    // Chemin des surfeurs vus pendant les dernières secondes (horloge de la vidéo), pour recadrer les clips après coup.
    private class PathCapture(val encodedUs: Long, val boxes: List<PathBox>)

    private val pathCaptures = ArrayDeque<PathCapture>()
    private val pathStore = PathStore(context)

    @Volatile
    private var reframeOn = false

    private var follower: GimbalFollower? = null

    @Volatile
    private var clipStartPtsUs = -1L

    val learning = LearningRepository(context)

    /** Où les vidéos sont écrites ; l'écran y lit aussi la liste des supports branchés. */
    val storage = StorageTarget(context).also { target ->
        target.onFallback = { message ->
            _state.update { it.copy(message = message) }
            alert("SurfCam : stockage externe retiré", message)
        }
    }

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainExecutor = Executor { it.run() }

    private var camera: Camera2Source? = null
    private var encoder: HevcEncoder? = null
    private var ring: EncodedRingBuffer? = null
    private var recorder: ClipRecorder? = null
    private var analysis: AnalysisPipeline? = null
    private var session: SessionRecorder? = null
    private var previewSurface: Surface? = null
    private var sampleCount = 0L
    private var desiredZoom = 1f
    private var clipsSaved = 0
    private var waveCounter = 0
    private var currentClipAuto = false
    private val rideStats = mutableListOf<RideStats>()
    private val safetyHandler = Handler(Looper.getMainLooper())
    private var safetyRunning = false
    private var severeSinceMs = 0L
    private var lowSpaceWarned = false
    private var lastKeyFramePtsUs = -1L
    private var lastStatsPtsUs = -1L

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _state.update { it.copy(thermalStatus = status) }
        safetyHandler.post { checkThermal(status) }
    }

    private val safetyTick = object : Runnable {
        override fun run() {
            checkSafety()
            if (safetyRunning) safetyHandler.postDelayed(this, SAFETY_PERIOD_MS)
        }
    }

    /** [displayRotationDegrees] : rotation de l'écran (0, 90, 180, 270) au moment du démarrage. */
    @Synchronized
    fun start(displayRotationDegrees: Int, options: SessionOptions) {
        if (_state.value.phase == Phase.STARTING || _state.value.phase == Phase.RUNNING) return
        clipsSaved = 0
        waveCounter = 0
        // Le support choisi doit être branché ; sinon on enregistre sur le téléphone, en le disant.
        val storageMessage = if (options.storage == StorageTarget.PHONE || storage.available().any { it.name == options.storage }) {
            storage.select(options.storage)
            null
        } else {
            storage.select(StorageTarget.PHONE)
            "Le support choisi n'est pas branché : enregistrement sur le téléphone"
        }
        _state.value = EngineState(
            phase = Phase.STARTING,
            lens = options.lens,
            zoom = desiredZoom,
            detectionEnabled = options.detection,
            fullSession = options.fullSession,
            message = storageMessage,
        )
        if (freeBytes() < MIN_START_FREE_BYTES) {
            fail("Espace libre insuffisant pour démarrer : il faut au moins 1,5 Go")
            return
        }
        var source: Camera2Source? = null
        try {
            // Libère l'aperçu (ou les restes d'une session en erreur) avant de rouvrir la caméra pour filmer.
            releaseCamera()
            teardownPipeline()
            _state.update { it.copy(thermalStatus = power.currentThermalStatus) }
            unregisterThermalListener()
            power.addThermalStatusListener(mainExecutor, thermalListener)

            source = Camera2Source(context)
            val firstTry = tryStart(source, if (options.fps60) 60 else 30, displayRotationDegrees, options)
            if (!firstTry) {
                teardownPipeline()
                if (!tryStart(source, 30, displayRotationDegrees, options)) {
                    throw IllegalStateException("L'encodeur H.265 refuse la 4K sur ce téléphone")
                }
            }
            camera = source
            beginBatteryLog()
            startSafetyChecks()
        } catch (e: Throwable) {
            source?.release()
            try {
                teardownPipeline()
            } catch (_: Throwable) {
            }
            fail("${e.javaClass.simpleName} : ${e.message ?: "démarrage impossible"}")
        }
    }

    @Synchronized
    fun stop() {
        if (_state.value.phase == Phase.IDLE || _state.value.phase == Phase.PREVIEW) return
        stopSafetyChecks()
        unregisterThermalListener()
        releaseCamera()
        teardownPipeline()
        follower?.stop()
        follower = null
        val batteryLine = endBatteryLog()
        val summary = buildSummary() + (batteryLine?.let { "\n$it" } ?: "")
        _state.update { it.copy(phase = Phase.IDLE, lastSummary = summary, locked = false, clipActive = false, bufferedSec = 0f, bufferedMb = 0, analysis = null, analysisStatus = null, analysisReceived = 0, sessionPart = 0, message = null) }
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
            // Le modèle tourne aussi pendant l'aperçu : il sert à estimer la distance du surfeur et le zoom conseillé.
            analysis?.release()
            val previewPipeline = AnalysisPipeline(context, object : AnalysisPipeline.Listener {
                override fun onClipAction(action: ClipAction) = Unit
                override fun onSnapshot(snapshot: AnalysisSnapshot) {
                    _state.update { if (it.phase == Phase.PREVIEW) it.copy(analysis = snapshot) else it }
                }
                override fun onStatus(message: String?) = Unit
                override fun onReceived(count: Int) = Unit
                override fun newestEncodedPtsUs(): Long? = null
                override fun onRide(stats: RideStats) = Unit
            })
            analysis = previewPipeline
            val zoom = desiredZoom.coerceIn(probe.minZoom, probe.maxZoom)
            _state.value = EngineState(
                phase = Phase.PREVIEW,
                lens = lens,
                zoom = zoom,
                minZoom = probe.minZoom,
                maxZoom = probe.maxZoom,
                focalPx1x = probe.focalPx1x,
                endedReason = current.endedReason,
                lastSummary = current.lastSummary,
            )
            source.open(probe, null, previewSurface, previewPipeline.surface, zoom, object : Camera2Source.Listener {
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
            _state.value = EngineState(phase = Phase.IDLE, lens = lens, message = e.message ?: "Aperçu impossible", endedReason = current.endedReason, lastSummary = current.lastSummary)
        }
    }

    @Synchronized
    fun stopPreview() {
        if (_state.value.phase != Phase.PREVIEW) return
        releaseCamera()
        _state.value = EngineState(
            phase = Phase.IDLE,
            lens = _state.value.lens,
            endedReason = _state.value.endedReason,
            lastSummary = _state.value.lastSummary,
        )
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
        desiredZoom = clamped
        _state.update { it.copy(zoom = clamped) }
        camera?.setZoom(clamped)
    }

    /** Mise au point sur un point touché de l'image (0 à 1), conservée jusqu'à ce qu'on repasse en automatique. */
    @Synchronized
    fun focusAt(nx: Float, ny: Float, rotationDegrees: Int) {
        camera?.focusAt(nx, ny, rotationDegrees)
        _state.update { it.copy(focusMode = Camera2Source.FocusMode.POINT) }
    }

    @Synchronized
    fun setFocusMode(mode: Camera2Source.FocusMode) {
        camera?.setFocusMode(mode)
        _state.update { it.copy(focusMode = mode) }
    }

    /** Efface le message de fin de session ou d'arrêt automatique une fois lu. */
    fun dismissNotice() {
        _state.update { it.copy(endedReason = null, lastSummary = null, message = null) }
    }

    /** Met le suivi par gimbal en pause ou le reprend pendant la session. */
    fun setFollowEnabled(enabled: Boolean) {
        follower?.enabled = enabled
        _state.update { it.copy(followEnabled = enabled && follower != null) }
    }

    @Synchronized
    fun relock() {
        camera?.relock()
    }

    /** Démarre un clip : les [CaptureConfig.PRE_ROLL_SEC] dernières secondes sont reprises du tampon. */
    @Synchronized
    fun startClip() {
        val started = recorder?.start(CaptureConfig.PRE_ROLL_SEC, ++waveCounter) == true
        if (started) {
            clipStartPtsUs = recorder?.lastStartPtsUs ?: -1L
            currentClipAuto = false
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

    private fun tryStart(source: Camera2Source, maxFps: Int, rotationDegrees: Int, options: SessionOptions): Boolean {
        val probe: CameraProbe = source.probe(maxFps, options.lens, options.hd)
        val config = probe.config
        val buffer = EncodedRingBuffer(config.bufferWindowSec * 1_000_000L)
        // Caméra arrière : le sens du fichier dépend de l'orientation du capteur et de celle de l'écran.
        val hint = (probe.sensorOrientation - rotationDegrees + 360) % 360
        val clips = ClipRecorder(context, buffer, hint, storage) { saved, error -> onClipResult(saved, error) }
        ring = buffer
        recorder = clips
        sampleCount = 0
        lastKeyFramePtsUs = -1L
        lastStatsPtsUs = -1L

        val sessionRecorder = if (options.fullSession) {
            SessionRecorder(context, hint, storage) { part, message ->
                _state.update { it.copy(sessionPart = part, message = message ?: it.message) }
            }
        } else {
            null
        }
        session = sessionRecorder

        val pipeline = if (!options.detection) null else AnalysisPipeline(context, object : AnalysisPipeline.Listener {
            // Appelé depuis le thread d'analyse : pas de verrou du moteur ici, pour éviter tout blocage à l'arrêt.
            override fun onClipAction(action: ClipAction) {
                when (action) {
                    is ClipAction.Start -> if (clips.startFrom(action.fromUs, ++waveCounter)) {
                        clipStartPtsUs = clips.lastStartPtsUs
                        currentClipAuto = true
                        synchronized(rideStats) { rideStats.clear() }
                        _state.update { it.copy(clipActive = true, message = null) }
                    }
                    is ClipAction.Stop -> clips.finish(0)
                    ClipAction.Discard -> {
                        synchronized(rideStats) { rideStats.clear() }
                        clips.discard()
                    }
                }
            }

            override fun onSnapshot(snapshot: AnalysisSnapshot) {
                _state.update { it.copy(analysis = snapshot) }
                if (reframeOn) capturePath(snapshot)
                follower?.let { f ->
                    val focal = _state.value.focalPx1x * _state.value.zoom
                    if (focal > 0f) {
                        f.onFrame(
                            snapshot.boxes.map { FollowBox((it.left + it.right) / 2f, it.bottom - it.top, it.riding) },
                            FollowController.horizontalFovDeg(focal, CaptureConfig.ANALYSIS_WIDTH),
                        )
                    }
                }
            }

            override fun onStatus(message: String?) {
                _state.update { it.copy(analysisStatus = message ?: "Détection prête") }
            }

            override fun onReceived(count: Int) {
                _state.update { it.copy(analysisReceived = count) }
            }

            override fun newestEncodedPtsUs(): Long? = buffer.newestPtsUs()

            override fun onRide(stats: RideStats) {
                synchronized(rideStats) { rideStats += stats }
            }
        }, learning.currentConfig())
        analysis = pipeline
        _state.update {
            it.copy(analysisStatus = if (pipeline != null) "Chargement du modèle…" else "Détection désactivée")
        }

        val enc = HevcEncoder(config, object : HevcEncoder.Listener {
            override fun onFormat(format: MediaFormat) {
                clips.format = format
                sessionRecorder?.format = format
            }

            override fun onSample(sample: EncodedSample) {
                buffer.add(sample)
                clips.onLiveSample(sample)
                sessionRecorder?.onSample(sample)
                if (sample.isKeyFrame) {
                    if (lastKeyFramePtsUs >= 0) {
                        val interval = (sample.ptsUs - lastKeyFramePtsUs) / 1_000_000f
                        _state.update { it.copy(keyFrameIntervalSec = interval) }
                    }
                    lastKeyFramePtsUs = sample.ptsUs
                }
                if (++sampleCount % STATS_EVERY == 0L) {
                    publishBufferStats(buffer)
                    if (lastStatsPtsUs >= 0 && sample.ptsUs > lastStatsPtsUs) {
                        val fps = STATS_EVERY * 1_000_000f / (sample.ptsUs - lastStatsPtsUs)
                        _state.update { it.copy(actualFps = fps) }
                    }
                    lastStatsPtsUs = sample.ptsUs
                }
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

        // À 60 i/s le téléobjectif n'est pas utilisable : au-delà de ×2 ce ne serait que du zoom numérique, flou et pixelisé.
        val maxZoomForFps = if (options.fps60 && probe.config.fps >= 60 && !options.tele) minOf(probe.maxZoom, 2f) else probe.maxZoom
        val startZoom = desiredZoom.coerceIn(probe.minZoom, maxZoomForFps)
        _state.update {
            it.copy(
                phase = Phase.RUNNING,
                focusMode = Camera2Source.FocusMode.AUTO,
                config = config,
                maxFpsAtSize = probe.maxFpsAtSize,
                zoom = startZoom,
                minZoom = probe.minZoom,
                maxZoom = maxZoomForFps,
                focalPx1x = probe.focalPx1x,
            )
        }
        source.lockExposure = options.lockExposure
        reframeOn = options.reframe
        synchronized(pathCaptures) { pathCaptures.clear() }
        follower?.stop()
        follower = null
        if (options.followGimbal && options.detection) {
            val f = GimbalFollower(context)
            val problem = f.start()
            if (problem == null) {
                follower = f
                _state.update { it.copy(gimbalStatus = f.status(), followEnabled = true) }
            } else {
                _state.update { it.copy(gimbalStatus = problem, followEnabled = false) }
            }
        } else {
            _state.update { it.copy(gimbalStatus = null, followEnabled = false) }
        }
        source.open(probe, encoderSurface, previewSurface, pipeline?.surface, startZoom, object : Camera2Source.Listener {
            override fun onLockChanged(locked: Boolean) {
                _state.update { it.copy(locked = locked) }
            }

            override fun onLensInfo(text: String) {
                _state.update { it.copy(lensInfo = text) }
            }

            override fun onAnalysisUnavailable() {
                _state.update { it.copy(analysisStatus = "Détection indisponible : mode manuel seulement") }
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

    private fun buildSummary(): String {
        val parts = _state.value.sessionPart
        val clips = clipsSaved
        return when {
            parts == 0 && clips == 0 ->
                "Aucune vague détectée, donc aucune vidéo. Active « Session complète » avant de démarrer pour tout garder."
            else -> buildString {
                append("Session terminée : ")
                if (parts > 0) append("$parts fichier(s) de session")
                if (parts > 0 && clips > 0) append(" et ")
                if (clips > 0) append("$clips clip(s) de vague")
                append(" enregistré(s).")
            }
        }
    }

    private fun capturePath(snapshot: AnalysisSnapshot) {
        val encodedUs = snapshot.timestampUs - snapshot.clockOffsetMs * 1_000
        val boxes = snapshot.boxes.map { PathBox(it.left, it.top, it.right, it.bottom, it.riding) }
        synchronized(pathCaptures) {
            pathCaptures.addLast(PathCapture(encodedUs, boxes))
            while (pathCaptures.isNotEmpty() && pathCaptures.first().encodedUs < encodedUs - PATH_KEEP_US) pathCaptures.removeFirst()
        }
    }

    /** Range le chemin du surfeur pendant le clip qui vient d'être enregistré, pour pouvoir le recadrer plus tard. */
    private fun savePath(clipName: String) {
        val start = clipStartPtsUs
        val end = ring?.newestPtsUs() ?: return
        if (start < 0) return
        val frames = synchronized(pathCaptures) {
            pathCaptures.filter { it.encodedUs in (start - 1_000_000)..(end + 1_000_000) }
                .map { PathFrame((it.encodedUs - start) / 1_000, it.boxes) }
        }
        if (frames.any { it.boxes.isNotEmpty() }) pathStore.save(clipName, frames)
    }

    private fun onClipResult(saved: SavedClip?, error: String?) {
        if (saved != null) clipsSaved++
        if (saved != null && reframeOn) savePath(saved.name)
        // Un clip automatique réussi est mémorisé avec ses caractéristiques : l'utilisateur pourra le juger.
        val stats = synchronized(rideStats) { rideStats.firstOrNull().also { rideStats.clear() } }
        if (saved != null && currentClipAuto && stats != null) learning.record(saved.name, stats)
        _state.update {
            it.copy(
                clipActive = false,
                lastClip = saved?.let { s ->
                    val pre = recorder?.lastPreRollSec ?: 0f
                    "${s.name} (${"%.1f".format(s.durationSec)} s dont %.1f s avant l'appui, ${s.sizeBytes / (1024 * 1024)} Mo)".format(pre)
                } ?: it.lastClip,
                message = error,
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Garde-fous : espace, chaleur, batterie. En cas de danger, les fichiers sont refermés proprement.
    // ------------------------------------------------------------------------------------------

    // Espace libre du support d'enregistrement ; s'il est inconnu, on ne bloque pas (une erreur d'écriture le dira).
    private fun freeBytes(): Long = storage.freeBytes() ?: Long.MAX_VALUE

    private fun startSafetyChecks() {
        safetyRunning = true
        severeSinceMs = 0L
        lowSpaceWarned = false
        safetyHandler.removeCallbacks(safetyTick)
        safetyHandler.postDelayed(safetyTick, SAFETY_PERIOD_MS)
    }

    private fun stopSafetyChecks() {
        safetyRunning = false
        safetyHandler.removeCallbacks(safetyTick)
    }

    private fun checkSafety() {
        if (_state.value.phase != Phase.RUNNING) return
        val free = freeBytes()
        if (free <= STOP_FREE_BYTES) {
            safeStop("Il reste moins de 800 Mo d'espace libre")
            return
        }
        if (free <= WARN_FREE_BYTES && !lowSpaceWarned) {
            lowSpaceWarned = true
            val message = "Espace presque plein : ${free / 1_000_000} Mo libres. La session s'arrêtera à 800 Mo."
            _state.update { it.copy(message = message) }
            alert("SurfCam : espace presque plein", message)
        }
        val battery = batteryPercent()
        if (battery?.second == true) chargedDuringSession = true
        follower?.let { f -> _state.update { it.copy(gimbalStatus = f.status()) } }
        if (battery != null && battery.first <= STOP_BATTERY_PERCENT && !battery.second) {
            safeStop("Batterie presque vide (${battery.first} %)")
            return
        }
        checkThermal(_state.value.thermalStatus)
    }

    private fun checkThermal(status: Int) {
        if (_state.value.phase != Phase.RUNNING) return
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) {
            safeStop("Surchauffe critique du téléphone")
            return
        }
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            val now = System.currentTimeMillis()
            if (severeSinceMs == 0L) {
                // Premier palier : on coupe l'analyse (la partie qui chauffe le plus après l'encodage 4K) et on prévient.
                severeSinceMs = now
                analysis?.paused = true
                val message = "Le téléphone chauffe fort : la détection est coupée pour le refroidir"
                _state.update { it.copy(analysisStatus = "Détection en pause : chaleur", message = message) }
                alert("SurfCam : le téléphone chauffe", message)
            } else if (now - severeSinceMs > SEVERE_LIMIT_MS) {
                safeStop("Le téléphone est resté trop chaud pendant 2 minutes")
            }
        } else {
            severeSinceMs = 0L
        }
    }

    /** Arrête la session proprement : les fichiers en cours sont refermés avant de couper, puis l'utilisateur est prévenu. */
    private fun safeStop(reason: String) {
        _state.update { it.copy(endedReason = reason) }
        alert("SurfCam a arrêté la session", "$reason. Tes vidéos sont sauvegardées.")
        stop()
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    // Consommation de la session : charge au départ et à l'arrêt, et si le téléphone a été branché entre-temps.
    private var batteryStartPercent = -1
    private var batteryStartUah = -1L
    private var sessionStartMs = 0L

    @Volatile
    private var chargedDuringSession = false

    private fun chargeCounterUah(): Long {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val v = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (v > 0) v else -1L
    }

    private fun beginBatteryLog() {
        val b = batteryPercent()
        batteryStartPercent = b?.first ?: -1
        batteryStartUah = chargeCounterUah()
        sessionStartMs = System.currentTimeMillis()
        chargedDuringSession = b?.second == true
    }

    /** Termine le relevé : enregistre la consommation et la renvoie en une phrase (null si rien n'a été relevé). */
    private fun endBatteryLog(): String? {
        if (sessionStartMs == 0L || batteryStartPercent < 0) return null
        val end = batteryPercent() ?: return null
        val endUah = chargeCounterUah()
        val charged = chargedDuringSession || end.second
        val stats = SessionStats(
            startMs = sessionStartMs,
            durationMs = System.currentTimeMillis() - sessionStartMs,
            percentStart = batteryStartPercent,
            percentEnd = end.first,
            milliampHours = if (batteryStartUah > 0 && endUah > 0 && batteryStartUah > endUah) ((batteryStartUah - endUah) / 1000).toInt() else null,
            charged = charged,
        )
        sessionStartMs = 0L
        SessionStatsStore(context).save(stats)
        return stats.describe()
    }

    private fun batteryPercent(): Pair<Int, Boolean>? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
        return (level * 100 / scale) to charging
    }

    private fun alert(title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Alertes de session", NotificationManager.IMPORTANCE_HIGH),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = Notification.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (launch != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, 2, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        manager.notify(ALERT_ID, builder.build())
    }

    /** Android lève une exception si l'écouteur n'a jamais été ajouté ; ici ce n'est pas une erreur. */
    private fun unregisterThermalListener() {
        try {
            power.removeThermalStatusListener(thermalListener)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun releaseCamera() {
        camera?.releaseBlocking()
        camera = null
        if (_state.value.phase == Phase.PREVIEW || _state.value.phase == Phase.IDLE) {
            analysis?.release()
            analysis = null
        }
    }

    private fun teardownPipeline() {
        encoder?.stop()
        encoder = null
        analysis?.release()
        analysis = null
        session?.release()
        session = null
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
        const val PATH_KEEP_US = 25_000_000L
        const val SAFETY_PERIOD_MS = 5_000L

        // Espace : on coupe à 800 Mo (demandé), on prévient à 1,5 Go, on refuse de démarrer sous 1,5 Go.
        const val STOP_FREE_BYTES = 800L * 1024 * 1024
        const val WARN_FREE_BYTES = 1_500L * 1024 * 1024
        const val MIN_START_FREE_BYTES = 1_500L * 1024 * 1024
        const val STOP_BATTERY_PERCENT = 3
        const val SEVERE_LIMIT_MS = 120_000L
        const val ALERT_CHANNEL = "alertes"
        const val ALERT_ID = 2
    }
}
