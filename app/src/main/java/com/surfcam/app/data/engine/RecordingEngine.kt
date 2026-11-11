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
import com.surfcam.app.data.gimbal.GimbalLog
import com.surfcam.app.domain.gimbal.FollowBox
import com.surfcam.app.domain.gimbal.FollowController
import com.surfcam.app.domain.clip.ClipAction
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.model.EncodedSample
import com.surfcam.app.domain.tracking.RideStats
import kotlinx.coroutines.launch
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
    /** Faux quand l'image est recadrée numériquement (zoom au-delà de l'objectif actif). */
    val opticalZoom: Boolean = true,
    /** Faux si la stabilisation optique n'est pas appliquée. */
    val stabilized: Boolean = true,
    /** Pose, sensibilité et mise au point réellement appliquées (diagnostic). */
    val captureInfo: String? = null,
    /** État du HDR 10 bits, en clair. */
    val hdrText: String? = null,
    /** Focale en pixels de l'image d'analyse à zoom ×1 : permet d'estimer la distance d'un surfeur. */
    val focalPx1x: Float = 0f,
    val focusMode: Camera2Source.FocusMode = Camera2Source.FocusMode.AUTO,
    /** État du suivi par gimbal (null si non utilisé pour cette session). */
    val gimbalStatus: String? = null,
    val followEnabled: Boolean = false,
    /** Identifiant du surfeur que le suivi a choisi (cadre jaune à l'écran), -1 si aucun. */
    val followTargetId: Int = -1,
    /** Dernière décision du suivi, en clair (aide au réglage). */
    val followNote: String = "",
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
    val fullSession: Boolean = false,
    /** Numéro du fichier de la session complète en cours d'écriture (0 = aucun). */
    val sessionPart: Int = 0,
    /** Raison de l'arrêt automatique de la dernière session (espace, chaleur, batterie) ; null sinon. */
    val endedReason: String? = null,
    /** Bilan de la dernière session : ce qui a été enregistré (affiché une fois la session arrêtée). */
    val lastSummary: String? = null,
    val lastClip: String? = null,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    /** Niveau d'allègement appliqué à cause de la chaleur : 0 aucun, 1 léger, 2 fort. */
    val thermalLevel: Int = 0,
    /** Début de la session (ms) et nombre de vagues déjà enregistrées, pour l'affichage en cours de session. */
    val startedAtMs: Long = 0L,
    val waves: Int = 0,
    val message: String? = null,
)

/**
 * Orchestre la capture : caméra 4K -> encodeur H.265 -> tampon circulaire de 8 s -> clips MP4.
 * Possédé par l'application (et non par l'écran), pour continuer à tourner quand l'écran est verrouillé.
 */
class RecordingEngine(private val context: Context) {

    init {
        com.surfcam.app.data.storage.StorageDiag.init(context)
        com.surfcam.app.data.storage.SdMover.resume(context)
    }

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Mémoire de l'apprentissage : jugements de l'utilisateur sur les clips automatiques, et réglages qui en découlent. */
    private var follower: GimbalFollower? = null

    @Volatile
    private var clipStartPtsUs = -1L

    val learning = LearningRepository(context)
    private val myWaves = com.surfcam.app.data.library.MyWaves(context)

    /** Où les vidéos sont écrites ; l'écran y lit aussi la liste des supports branchés. */
    val storage = StorageTarget(context).also { target ->
        target.onFallback = { message ->
            _state.update { it.copy(message = message) }
            alert("SurfCam : stockage externe indisponible", message)
        }
    }

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainExecutor = Executor { it.run() }

    private var camera: Camera2Source? = null
    private var encoder: HevcEncoder? = null
    private var ring: EncodedRingBuffer? = null
    private var recorder: ClipRecorder? = null
    private var analysis: AnalysisPipeline? = null
    private var lastRotation = 90
    private var onlyMarkedFollow = false
    private var lastOptions: SessionOptions? = null
    private var session: SessionRecorder? = null
    private var previewSurface: Surface? = null
    private var sampleCount = 0L
    private var desiredZoom = 1f
    private var clipsSaved = 0
    private var waveCounter = 0
    private var currentClipAuto = false
    private val rideStats = mutableListOf<RideStats>()
    @Volatile
    private var pendingRejectStats: RideStats? = null
    private val safetyHandler = Handler(Looper.getMainLooper())
    private val ledHandler = Handler(Looper.getMainLooper())
    private var ledEnabled = false

    /**
     * Voyant d'enregistrement : la LED du flash donne un éclair de 0,15 s toutes les 6 s en attente, et un double éclair
     * toutes les 3 s pendant qu'une vague est enregistrée. Elle s'éteint quand le téléphone chauffe fort ou que la
     * batterie est basse.
     */
    private var ledCount = 0
    private val ledTick = object : Runnable {
        override fun run() {
            if (!ledEnabled) return
            val s = _state.value
            val battery = batteryPercent()
            val ok = s.phase == Phase.RUNNING && s.thermalLevel < 2 && (battery == null || battery.first > 15 || battery.second)
            ledCount++
            // En attente : un éclair court toutes les 6 s. Pendant une vague : un double éclair toutes les 3 s.
            if (ok && s.clipActive) {
                pulseLed()
                ledHandler.postDelayed({ pulseLed() }, 350)
            } else if (ok && ledCount % 2 == 0) {
                pulseLed()
            }
            ledHandler.postDelayed(this, 3_000)
        }
    }

    private fun pulseLed() {
        camera?.setTorch(true)
        ledHandler.postDelayed({ camera?.setTorch(false) }, 150)
    }
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
        lastRotation = displayRotationDegrees
        lastOptions = options
        SessionLog.start(
            context,
            "Options : 1080p 30 i/s téléobjectif ×5 optique · HDR 10 bits demandé=${options.hdr} · session complète=${options.fullSession} " +
                "qui=${options.subject}/${options.markerColor ?: "-"} stockage=${options.storage} " +
                "expo verrouillée=${options.lockExposure} LED=${options.ledBlink}",
        )
        learning.currentConfig().let { SessionLog.ev("réglages", "détection de vague : vitesse de départ ${it.startSpeed} longueurs de corps/s, arrêt sous ${it.stopSpeed}, part horizontale ≥ ${it.minHorizontalShare}, rapport h/l ≥ ${it.minStandingAspect}, durée minimale ${it.minRideUs / 1_000_000f} s, confiance ≥ ${it.minTriggerConfidence}") }
        Trail.mark("DEMARRAGE 1080p 30 i/s téléobjectif")
        clipsSaved = 0
        waveCounter = 0
        activeIntervalMs = CaptureConfig.ANALYSIS_INTERVAL_MS
        // Le support choisi doit être branché ; sinon on enregistre sur le téléphone, en le disant.
        val storageMessage = if (options.storage == StorageTarget.PHONE || storage.available().any { it.name == options.storage }) {
            storage.select(options.storage)
            SessionLog.ev("stockage", "support choisi : ${options.storage} (${storage.labelOf(options.storage)}) · supports montés : ${storage.available().joinToString { it.name + " = " + it.label }}")
            null
        } else {
            // La carte n'est pas prête : on filme quand même, les fichiers attendent sur le téléphone puis partent sur la carte (SdMover).
            storage.select(options.storage)
            SessionLog.ev("stockage", "SUPPORT CHOISI PAS PRÊT (${options.storage}) : les fichiers seront écrits sur le téléphone puis envoyés sur la carte dès qu'elle sera là · supports montés : ${storage.available().joinToString { it.name + " = " + it.label }}")
            "La carte n'est pas prête : les vidéos sont gardées sur le téléphone puis envoyées sur la carte dès qu'elle revient"
        }
        _state.value = EngineState(
            phase = Phase.STARTING,
            lens = Lens.TELE,
            zoom = desiredZoom,
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
            val firstTry = tryStart(source, 30, displayRotationDegrees, options)
            if (!firstTry) {
                teardownPipeline()
                if (!tryStart(source, 30, displayRotationDegrees, options)) {
                    throw IllegalStateException("L'encodeur vidéo H.265 n'a pas démarré")
                }
            }
            camera = source
            beginBatteryLog()
            if (options.storage != StorageTarget.PHONE) com.surfcam.app.data.storage.StorageDiag.monitor(context, options.storage, 90)
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
        Trail.mark("FIN")
        SessionLog.ev("bilan", summary.replace("\n", " | "))
        SessionLog.end(context)
        _peakZone.value = null
        _state.update { it.copy(phase = Phase.IDLE, lastSummary = summary, locked = false, clipActive = false, bufferedSec = 0f, bufferedMb = 0, analysis = null, analysisStatus = null, analysisReceived = 0, sessionPart = 0, message = null) }
    }

    /** Aperçu avant de lancer : objectif principal, de ×1 à ×2, pour viser à l'œil et tracer la zone du pic. Rien n'est enregistré. */
    @Synchronized
    fun startPreview() {
        val lens = Lens.MAIN
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
            previewPipeline.markerColor = OptionsStore(context).load().markerColor
            analysis = previewPipeline
            val previewMax = minOf(probe.maxZoom, PREVIEW_MAX_ZOOM)
            val zoom = desiredZoom.coerceIn(probe.minZoom, previewMax)
            _state.value = EngineState(
                phase = Phase.PREVIEW,
                lens = lens,
                zoom = zoom,
                minZoom = probe.minZoom,
                maxZoom = previewMax,
                focalPx1x = probe.focalPx1x,
                endedReason = current.endedReason,
                lastSummary = current.lastSummary,
            )
            source.open(probe, null, previewSurface, previewPipeline.surface, zoom, object : Camera2Source.Listener {
                override fun onLockChanged(locked: Boolean) = Unit

                override fun onZoomKind(optical: Boolean) {
                    _state.update { it.copy(opticalZoom = optical) }
                }

                override fun onStabilization(active: Boolean) {
                SessionLog.ev("camera", "stabilisation ${if (active) "active" else "INACTIVE"}")
                    _state.update { it.copy(stabilized = active) }
                }

                override fun onCaptureInfo(text: String) {
                    _state.update { it.copy(captureInfo = text) }
                }

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
        val clamped = ratio.coerceIn(s.minZoom, if (s.phase == Phase.PREVIEW) minOf(s.maxZoom, PREVIEW_MAX_ZOOM) else s.maxZoom)
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

    /** Change la couleur du manchon recherché, même pendant l'aperçu ou une session. */
    fun setMarkerColor(color: com.surfcam.app.domain.detection.MarkerColor?) {
        analysis?.markerColor = color
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
        val started = recorder?.start(CaptureConfig.PRE_ROLL_SEC, clipsSaved + 1) == true
        if (started) {
            clipStartPtsUs = recorder?.lastStartPtsUs ?: -1L
            currentClipAuto = false
            _state.update { it.copy(clipActive = true, message = null) }
        } else {
            _state.update { it.copy(message = "Clip impossible pour l'instant") }
        }
    }

    /**
     * Zone du pic tracée dans l'aperçu : bords gauche et droit, en degrés par rapport à l'axe de la caméra au moment du tracé
     * (négatif = à gauche). Elle vaut pour la session lancée ensuite sans bouger le trépied, puis est oubliée.
     */
    private val _peakZone = MutableStateFlow<Pair<Double, Double>?>(null)
    val peakZone: StateFlow<Pair<Double, Double>?> = _peakZone.asStateFlow()

    /** L'utilisateur a tracé le pic sur l'aperçu, de [nxLeft] à [nxRight] (0 = bord gauche de l'image, 1 = bord droit). */
    fun setPeakZoneFromPreview(nxLeft: Float, nxRight: Float) {
        val st = _state.value
        val focal = st.focalPx1x * st.zoom
        if (st.phase != Phase.PREVIEW || focal <= 0f) return
        fun deg(nx: Float) = Math.toDegrees(kotlin.math.atan(((nx.coerceIn(0f, 1f) - 0.5f) * CaptureConfig.ANALYSIS_WIDTH / focal).toDouble()))
        val left = deg(minOf(nxLeft, nxRight))
        val right = deg(maxOf(nxLeft, nxRight))
        if (right - left < 1.0) return
        _peakZone.value = left to right
    }

    fun clearPeakZone() {
        _peakZone.value = null
    }

    /** Position à l'écran (0 à 1) d'une direction de la zone du pic, pour la dessiner sur l'aperçu au zoom courant. */
    fun previewXOf(deg: Double): Float? {
        val st = _state.value
        val focal = st.focalPx1x * st.zoom
        if (focal <= 0f) return null
        return (0.5 + kotlin.math.tan(Math.toRadians(deg)) * focal / CaptureConfig.ANALYSIS_WIDTH).toFloat()
    }

    /** Le bouton « Ici le pic » : la direction actuelle du gimbal devient le point de retour entre deux vagues. */
    fun markPeak() {
        follower?.setPeakHere()
        val now = android.os.SystemClock.elapsedRealtimeNanos() / 1_000
        analysis?.brain?.director?.setPeakHere(follower?.headingAtUs(now) ?: 0.0, now)
        SessionLog.ev("PIC", "L'utilisateur désigne la direction actuelle comme étant le pic")
    }

    /** Le bouton « Problème » : repère dans le rapport l'instant où l'utilisateur voit quelque chose d'anormal. */
    fun markProblem() {
        val st = _state.value
        SessionLog.ev("MARQUE", "L'UTILISATEUR SIGNALE UN PROBLÈME · cible=#${st.followTargetId} · ${st.followNote} · gimbal : ${st.gimbalStatus ?: "-"} · zoom ×${"%.1f".format(st.zoom)}")
    }

    /** Termine le clip après [CaptureConfig.POST_ROLL_SEC] secondes supplémentaires. */
    @Synchronized
    fun finishClip() {
        recorder?.finish(CaptureConfig.POST_ROLL_SEC * 1_000L)
    }

    private fun tryStart(source: Camera2Source, maxFps: Int, rotationDegrees: Int, options: SessionOptions): Boolean {
        // Toujours le téléobjectif optique, en 1080p : si le téléphone ne le donne pas, la sonde échoue et la session ne démarre
        // pas (jamais de repli silencieux sur l'objectif principal recadré).
        val probe: CameraProbe = source.probe(maxFps, Lens.TELE, hd = true, hdr = options.hdr)
        SessionLog.ev("objectif", "téléobjectif optique : caméra ${probe.cameraId}${probe.physicalId?.let { " (objectif physique $it)" } ?: ""} · ${probe.config.width}×${probe.config.height} à ${probe.config.fps} i/s · zoom numérique impossible (plage ×%.1f à ×%.1f)".format(probe.minZoom, probe.maxZoom))
        val config = probe.config
        _state.update { it.copy(hdrText = probe.hdrText) }
        SessionLog.ev("objectif", "HDR 10 bits : ${probe.hdrText}")
        Trail.mark("sonde ok ${config.width}x${config.height} ${config.fps}i/s 10bits=${config.tenBit} · HDR ${probe.hdrText}")
        val buffer = EncodedRingBuffer(config.bufferWindowSec * 1_000_000L)
        // Caméra arrière : le sens du fichier dépend de l'orientation du capteur et de celle de l'écran.
        val hint = (probe.sensorOrientation - rotationDegrees + 360) % 360
        val clips = ClipRecorder(context, buffer, hint, storage, { saved, error -> onClipResult(saved, error) }, { kept -> analysis?.takeRideSilhouette()?.let { myWaves.saveSilhouette(kept.name, it) }; pendingRejectStats?.let { learning.recordRejected(kept.name, it) }; pendingRejectStats = null; SessionLog.clipSaved(context, kept.name, kept.durationSec); SessionLog.ev("clip", "rejeté (fausse alerte) mais gardé dans « Vagues rejetées » : ${kept.name} (%.1f s, ${kept.sizeBytes / (1024 * 1024)} Mo) sur ${storage.labelOf(storage.volume)}".format(kept.durationSec)) })
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

        val pipeline = AnalysisPipeline(context, object : AnalysisPipeline.Listener {
            // Appelé depuis le thread d'analyse : pas de verrou du moteur ici, pour éviter tout blocage à l'arrêt.
            override fun onClipAction(action: ClipAction) {
                SessionLog.ev("clip", "décision auto : ${action::class.simpleName}")
                when (action) {
                    is ClipAction.Start -> if (clips.startFrom(action.fromUs, clipsSaved + 1)) {
                        clipStartPtsUs = clips.lastStartPtsUs
                        currentClipAuto = true
                        synchronized(rideStats) { rideStats.clear() }
                        _state.update { it.copy(clipActive = true, message = null) }
                    }
                    is ClipAction.Hold -> clips.hold(action.atUs)
                    ClipAction.Resume -> clips.resume()
                    is ClipAction.Stop -> clips.finish(0)
                    ClipAction.Discard -> {
                        synchronized(rideStats) { rideStats.clear() }
                        clips.discard()
                    }
                }
            }

            override fun onRejectStats(stats: RideStats?) {
                pendingRejectStats = stats
            }

            override fun onSnapshot(snapshot: AnalysisSnapshot) {
                _state.update { it.copy(analysis = snapshot) }
                follower?.let { f ->
                    val focal = _state.value.focalPx1x * _state.value.zoom
                    if (focal > 0f) {
                        f.onFrame(
                            snapshot.boxes.filter { !onlyMarkedFollow || it.marked }.map { FollowBox((it.left + it.right) / 2f, it.bottom - it.top, it.riding, it.marked, it.id) },
                            FollowController.horizontalFovDeg(focal, CaptureConfig.ANALYSIS_WIDTH),
                        )
                    }
                }
                follower?.setClipActive(_state.value.clipActive)
                logFrame(snapshot, follower)
                val targetId = follower?.targetId
                    ?: FollowController.chooseTarget(snapshot.boxes.filter { !onlyMarkedFollow || it.marked }.map { FollowBox((it.left + it.right) / 2f, it.bottom - it.top, it.riding, it.marked, it.id) }, null)?.id
                    ?: -1
                if (analysis?.brain != null) { logFrame(snapshot, follower); return }
                val note = GimbalLog.last
                if (_state.value.followTargetId != targetId || _state.value.followNote != note) _state.update { it.copy(followTargetId = targetId, followNote = note) }
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

            override fun onBrain(output: com.surfcam.core.BrainOutput) {
                val cmd = output.command
                cmd.gimbalOrder?.let { o ->
                    follower?.executeOrder(o.deltaDeg, o.durationUs / 1e6, "${cmd.state.label} : ${cmd.note}")
                    journalWriter?.let { w -> synchronized(w) { com.surfcam.core.journal.FrameJournal.writeEvent(w, o.startUs, "ordre", "%+.2f° en %.2f s · ${cmd.state.label} · ${cmd.note}".format(o.deltaDeg, o.durationUs / 1e6)) } }
                }
                // Le zoom demandé par le cerveau est ignoré : la session tourne sur le téléobjectif, sans zoom possible.
                val note = "${cmd.state.label} · ${cmd.note}"
                if (_state.value.followNote != note) {
                    if (cmd.state.label != lastBrainState) SessionLog.ev("pilote", note)
                    lastBrainState = cmd.state.label
                    _state.update { it.copy(followNote = note, followTargetId = cmd.targetTrackId ?: -1) }
                }
            }
        }, learning.currentConfig(), config.tenBit)
        pipeline?.markerColor = options.markerColor
        pipeline?.onlyMarked = options.subject == Subject.ME && options.markerColor != null
        onlyMarkedFollow = options.subject == Subject.ME && options.markerColor != null
        Trail.mark("analyse créée")
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
                if (sampleCount == 0L) Trail.mark("premier échantillon encodé")
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
        Trail.mark("encodeur démarré")

        val startZoom = probe.minZoom
        _state.update {
            it.copy(
                phase = Phase.RUNNING,
                startedAtMs = System.currentTimeMillis(),
                waves = 0,
                focusMode = Camera2Source.FocusMode.AUTO,
                config = config,
                maxFpsAtSize = probe.maxFpsAtSize,
                zoom = startZoom,
                minZoom = probe.minZoom,
                maxZoom = probe.maxZoom,
                focalPx1x = probe.focalPx1x,
            )
        }
        source.lockExposure = options.lockExposure
        follower?.stop()
        follower = null
        if (com.surfcam.app.data.gimbal.GimbalHub.hasSavedGimbal(context)) {
            val f = GimbalFollower(context)
            val problem = f.start()
            if (problem == null) {
                follower = f
                f.searchEnabled = options.markerColor != null
                // Le suivi réclame une analyse plus fréquente : le retard de mesure se voit directement sur le gimbal.
                pipeline?.setBaseIntervalMs(FOLLOW_ANALYSIS_INTERVAL_MS)
                activeIntervalMs = FOLLOW_ANALYSIS_INTERVAL_MS
                _state.update { it.copy(gimbalStatus = f.status(), followEnabled = true) }
            } else {
                _state.update { it.copy(gimbalStatus = problem, followEnabled = false) }
            }
        } else {
            _state.update { it.copy(gimbalStatus = null, followEnabled = false) }
        }
        setupBrain(options)
        SessionLog.ev("détecteur", com.surfcam.app.data.analysis.OnnxPersonDetector.choiceReport(context) ?: "modèle sur le processeur (pas encore chronométré)")
        context.getSharedPreferences("gimbal", Context.MODE_PRIVATE).getString("lastLeveling", null)?.lines()?.forEach { SessionLog.ev("niveau", it) }
        if (follower != null) autoLevel(displayRotationDegreesForLevel)
        // Horizon surveillé pendant toute la session : mesuré, écrit dans le rapport, et retouché entre deux clips s'il dérive.
        sessionGravity?.stop()
        rollBeforeNudge = null
        nudgesThatWorsened = 0
        horizonGivenUp = false
        sessionGravity = com.surfcam.app.data.gimbal.GravityRoll(context, displayRotationDegreesForLevel).also { it.start() }
        lastRelevelMs = android.os.SystemClock.elapsedRealtime()
        source.open(probe, encoderSurface, previewSurface, pipeline?.surface, startZoom, object : Camera2Source.Listener {
            override fun onLockChanged(locked: Boolean) {
                _state.update { it.copy(locked = locked) }
            }

            override fun onStabilization(active: Boolean) {
                _state.update { it.copy(stabilized = active) }
            }

            override fun onCaptureInfo(text: String) {
                _state.update { it.copy(captureInfo = text) }
            }

            override fun onHdrRefused() {
                SessionLog.ev("camera", "HDR refusé par la caméra : redémarrage en 8 bits")
                // Relance aussitôt la session en 8 bits : le HDR est désormais coupé par la caméra elle-même.
                val rotation = lastRotation
                val opts = lastOptions ?: return
                Thread({
                    synchronized(this@RecordingEngine) {
                        releaseCamera()
                        teardownPipeline()
                        _state.update { it.copy(phase = Phase.IDLE) }
                        start(rotation, opts)
                    }
                }, "hdr-restart").start()
            }

            override fun onLensInfo(text: String) {
                _state.update { it.copy(lensInfo = text) }
                // Preuve dans le rapport : l'objectif réellement actif et sa focale, à chaque changement.
                SessionLog.ev("objectif", "actif : $text")
            }

            override fun onZoomKind(optical: Boolean) {
                _state.update { it.copy(opticalZoom = optical) }
                if (!optical) SessionLog.ev("objectif", "ATTENTION : l'image ne vient pas du téléobjectif optique")
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

    private fun onClipResult(saved: SavedClip?, error: String?) {
        saved?.let { SessionLog.clipSaved(context, it.name, it.durationSec) }
        SessionLog.ev("clip", if (saved != null) "enregistré : ${saved.name} (%.1f s, ${saved.sizeBytes / (1024 * 1024)} Mo) sur ${storage.labelOf(storage.volume)}".format(saved.durationSec) else if (error != null) "échec : $error" else "clip rejeté (fausse alerte)")
        if (saved != null) sessionGravity?.rollDeg?.let { SessionLog.ev("niveau", "horizon à la fin du clip ${saved.name} : %+.1f°".format(it)) }
        if (saved != null) clipsSaved++
        if (saved != null) analysis?.takeRideSilhouette()?.let { myWaves.saveSilhouette(saved.name, it) }
        // Un clip automatique réussi est mémorisé avec ses caractéristiques : l'utilisateur pourra le juger.
        val stats = synchronized(rideStats) { rideStats.firstOrNull().also { rideStats.clear() } }
        if (saved != null && currentClipAuto && stats != null) learning.record(saved.name, stats)
        _state.update {
            it.copy(
                clipActive = false,
                waves = clipsSaved,
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
    private fun freeBytes(): Long = (if (storage.isExternal) storage.phoneFreeBytes() else storage.freeBytes()) ?: Long.MAX_VALUE

    private fun startSafetyChecks() {
        safetyRunning = true
        severeSinceMs = 0L
        ledEnabled = lastOptions?.ledBlink == true
        ledHandler.removeCallbacks(ledTick)
        if (ledEnabled) ledHandler.postDelayed(ledTick, 5_000)
        thermalLevel = 0
        coolSinceMs = 0L
        lowSpaceWarned = false
        safetyHandler.removeCallbacks(safetyTick)
        safetyHandler.postDelayed(safetyTick, SAFETY_PERIOD_MS)
    }

    private fun stopSafetyChecks() {
        safetyRunning = false
        safetyHandler.removeCallbacks(safetyTick)
        ledEnabled = false
        ledHandler.removeCallbacks(ledTick)
        camera?.setTorch(false)
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

    /** Allègement progressif quand le téléphone chauffe : on garde la session et la détection, on baisse la charge. */
    private var thermalLevel = 0
    private var coolSinceMs = 0L

    private fun checkThermal(status: Int) {
        if (_state.value.phase != Phase.RUNNING) return
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) {
            safeStop("Surchauffe critique du téléphone")
            return
        }
        val now = System.currentTimeMillis()
        val wanted = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 1
            else -> 0
        }
        if (wanted > thermalLevel) {
            coolSinceMs = 0L
            applyThermalLevel(wanted)
        } else if (wanted < thermalLevel) {
            // On ne remonte la qualité qu'après 90 s de fraîcheur, pour éviter de chauffer de nouveau aussitôt.
            if (coolSinceMs == 0L) coolSinceMs = now
            if (now - coolSinceMs > 90_000L) {
                coolSinceMs = 0L
                applyThermalLevel(wanted)
            }
        } else {
            coolSinceMs = 0L
        }
        if (wanted == 2) {
            if (severeSinceMs == 0L) severeSinceMs = now
            else if (now - severeSinceMs > SEVERE_LIMIT_MS) safeStop("Le téléphone est resté trop chaud pendant 10 minutes")
        } else {
            severeSinceMs = 0L
        }
    }

    private var activeIntervalMs = CaptureConfig.ANALYSIS_INTERVAL_MS

    private var lastFrameLogMs = 0L
    private var lastTickLogMs = 0L

    /** Une ligne du rapport à chaque demi-seconde : toutes les personnes vues, la cible, le cap du gimbal ; plus un état général toutes les 10 s. */
    private fun logFrame(snapshot: AnalysisSnapshot, f: GimbalFollower?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFrameLogMs >= 500) {
            lastFrameLogMs = now
            val st = _state.value
            val boxes = snapshot.boxes.joinToString(" ") {
                "#${it.id}(x%.2f y%.2f h%.2f%s%s)".format((it.left + it.right) / 2f, (it.top + it.bottom) / 2f, it.bottom - it.top, if (it.riding) " VAGUE" else "", if (it.marked) " MANCHON" else "")
            }
            val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() / 1000L - snapshot.timestampUs) / 1000L
            val timing = if (snapshot.timestampUs > 0 && ageMs in 0..5000) "âge de l'image à la décision ${ageMs}ms (capture→fin d'inférence)" else "horloge de capture non comparable (ts=${snapshot.timestampUs}µs)"
            SessionLog.ev(
                "image",
                "n=${snapshot.frames} personnes=${snapshot.personsInFrame} conf=%.2f inférence ${snapshot.inferenceMs}ms · $timing · retenus: ${boxes.ifEmpty { "aucun" }} · cible=#${f?.targetId ?: -1} · cap commandé %+.1f° · gyroscope %+.1f° (%+.1f °/s) · zoom ×%.1f".format(snapshot.bestConfidence, f?.yawCmdDeg ?: 0f, f?.gyroYawDeg ?: 0f, f?.gyroRateDegPerSec ?: 0f, st.zoom),
            )
        }
        if (now - lastTickLogMs >= 10_000) {
            lastTickLogMs = now
            val st = _state.value
            val b = batteryPercent()
            SessionLog.ev(
                "état",
                "batterie=${b?.first ?: "?"}%${if (b?.second == true) " (charge)" else ""} · courant≈${currentMilliamps()} mA · chaleur niveau ${st.thermalLevel} · ${st.captureInfo ?: "-"} · ${st.gimbalStatus ?: "sans gimbal"} · vagues=${st.waves} · clip actif=${st.clipActive} · horizon ${sessionGravity?.rollDeg?.let { "%+.1f°".format(it) } ?: "?"}",
            )
            keepHorizonLevel(now, st.clipActive)
        }
    }

    private var sessionGravity: com.surfcam.app.data.gimbal.GravityRoll? = null
    private var lastRelevelMs = 0L

    @Volatile
    private var levelingBusy = false

    /**
     * Retouche l'horizon s'il a dérivé de plus de [HORIZON_TOLERANCE_DEG], jamais pendant un clip ni pendant la mise à niveau du
     * démarrage, et au plus une fois toutes les 20 s. Un seul petit ordre de roulis, avec le sens et l'amplitude appris.
     */
    private fun keepHorizonLevel(nowMs: Long, clipActive: Boolean) {
        val roll = sessionGravity?.rollDeg ?: return
        if (follower == null || levelingBusy || clipActive || horizonGivenUp) return
        val prefs = context.getSharedPreferences("gimbal", Context.MODE_PRIVATE)
        // Garde-fou : la retouche précédente a-t-elle aggravé l'inclinaison ? Une fois : le sens appris était faux, on
        // l'inverse. Deux fois : on arrête les retouches pour cette session plutôt que de pencher de plus en plus.
        val before = rollBeforeNudge
        if (before != null && nowMs - lastRelevelMs >= 8_000) {
            rollBeforeNudge = null
            if (kotlin.math.abs(roll) > kotlin.math.abs(before) + 0.3f) {
                nudgesThatWorsened++
                if (nudgesThatWorsened >= 2) {
                    horizonGivenUp = true
                    SessionLog.ev("niveau", "les retouches aggravent l'inclinaison (%+.1f° → %+.1f°) : retouches arrêtées pour cette session".format(before, roll))
                    return
                }
                prefs.edit().putInt("rollSign", -prefs.getInt("rollSign", 1)).apply()
                SessionLog.ev("niveau", "la retouche a aggravé l'inclinaison (%+.1f° → %+.1f°) : sens de roulis inversé".format(before, roll))
            } else {
                nudgesThatWorsened = 0
            }
        }
        if (kotlin.math.abs(roll) <= HORIZON_TOLERANCE_DEG || nowMs - lastRelevelMs < 20_000) return
        val link = com.surfcam.app.data.gimbal.GimbalHub.link(context)
        if (!link.state.value.connected) return
        lastRelevelMs = nowMs
        rollBeforeNudge = roll
        val sign = prefs.getInt("rollSign", 1)
        val gain = prefs.getFloat("rollGain", 1f).coerceIn(0.3f, 2f)
        val cmd = (-roll / gain).coerceIn(-3f, 3f)
        link.rollBy(cmd * sign, 0.8f)
        SessionLog.ev("niveau", "horizon penché de %+.1f° pendant la session : retouche de %+.1f°".format(roll, cmd))
    }

    private var rollBeforeNudge: Float? = null
    private var nudgesThatWorsened = 0
    private var horizonGivenUp = false

    private fun applyThermalLevel(level: Int) {
        SessionLog.ev("chaleur", "niveau $level (statut système ${_state.value.thermalStatus})")
        thermalLevel = level
        _state.update { it.copy(thermalLevel = level) }
        // La chaleur ne déclenche aucun mode d'économie qui réduirait l'image ou le suivi.
        // Android peut arrêter la session si la température atteint un seuil critique.
        val message = if (level > 0) {
            "Le téléphone chauffe : mets-le à l'ombre. La qualité et le suivi ne sont pas réduits."
        } else {
            "Le téléphone a refroidi. La qualité et le suivi restent inchangés."
        }
        _state.update { it.copy(message = message) }
        if (level > 0) alert("SurfCam : le téléphone chauffe", message)
    }

    /** Arrête la session proprement : les fichiers en cours sont refermés avant de couper, puis l'utilisateur est prévenu. */
    private fun safeStop(reason: String) {
        SessionLog.ev("arrêt", reason)
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

    /** Courant instantané tiré de la batterie, en mA (signe ignoré : il varie selon les téléphones) ; -1 si inconnu. */
    private fun currentMilliamps(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val micro = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (micro == Int.MIN_VALUE || micro == 0) -1 else kotlin.math.abs(micro) / 1000
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

    private var journalWriter: java.io.Writer? = null
    private val displayRotationDegreesForLevel: Int get() = lastRotation
    private val levelScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    /**
     * Mise à niveau automatique au début de chaque session, dès que le gimbal est connecté : boucle fermée sur le capteur de
     * gravité (HorizonLeveler). Chaque étape est écrite dans le rapport ; le résultat s'affiche sur l'écran de tournage.
     */
    private fun autoLevel(rotationDegrees: Int) {
        val link = com.surfcam.app.data.gimbal.GimbalHub.link(context)
        val prefs = context.getSharedPreferences("gimbal", Context.MODE_PRIVATE)
        val gravity = com.surfcam.app.data.gimbal.GravityRoll(context, rotationDegrees)
        levelScope.launch {
            levelingBusy = true
            try {
                gravity.start()
                // Attendre la liaison (2 s au plus) et une première mesure.
                var waited = 0
                while ((!link.state.value.connected || gravity.rollDeg == null) && waited < 20) { kotlinx.coroutines.delay(100); waited++ }
                if (!link.state.value.connected) { SessionLog.ev("niveau", "gimbal non connecté : pas de mise à niveau"); return@launch }
                val report = com.surfcam.app.data.gimbal.HorizonLeveler(link, prefs).run({ gravity.rollDeg }) { SessionLog.ev("niveau", it) }
                SessionLog.ev("niveau", report)
                _state.update { it.copy(message = report) }
            } catch (e: Exception) {
                SessionLog.ev("niveau", "mise à niveau interrompue : ${e.message}")
            } finally {
                gravity.stop()
                levelingBusy = false
                lastRelevelMs = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
    private var lastBrainState = ""
    private var journalFile: java.io.File? = null

    /** Copie le journal de capteurs dans Téléchargements/SurfCam/Journaux, pour l'envoyer et rejouer la session sur ordinateur. */
    private fun exportJournal(file: java.io.File) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/SurfCam/Journaux")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            context.getSharedPreferences("rapport", Context.MODE_PRIVATE).edit().putString("journalUri", uri.toString()).apply()
            SessionLog.ev("journal", "journal de capteurs copié dans Téléchargements/SurfCam/Journaux/${file.name} (${file.length() / 1024} Ko)")
        } catch (e: Exception) {
            SessionLog.ev("journal", "copie du journal impossible : ${e.message}")
        }
    }

    /** Branche le nouveau cerveau (:core) sur l'analyse : cap gyroscope horodaté, optique, journal de capteurs. */
    private fun setupBrain(options: SessionOptions) {
        val p = analysis ?: return
        val subject = if (options.subject == Subject.ME && options.markerColor != null) com.surfcam.core.director.Subject.ME else com.surfcam.core.director.Subject.EVERYONE
        val brain = com.surfcam.core.SurfBrain(subject, 0.0)
        // Zone du pic tracée avant de lancer : le cap 0 du cerveau est l'axe de la caméra au démarrage, le même qu'à l'aperçu.
        val zone = _peakZone.value
        if (zone != null) {
            brain.director.setPeakZone(zone.first, zone.second, android.os.SystemClock.elapsedRealtimeNanos() / 1_000)
            SessionLog.ev("PIC", "zone du pic tracée : de %+.1f° à %+.1f° (largeur %.1f°) ; la caméra attend au centre et ne cherche que dans cette zone".format(zone.first, zone.second, zone.second - zone.first))
        } else {
            SessionLog.ev("PIC", "aucune zone tracée : le pic est la direction visée au lancement")
        }
        p.brain = brain
        p.onlyMarked = false
        follower?.externalControl = true
        p.headingAt = { t: Long -> follower?.headingAtUs(t) }
        p.opticsProvider = {
            val st = _state.value
            val focal = st.focalPx1x * st.zoom
            if (focal > 0f) FollowController.horizontalFovDeg(focal, CaptureConfig.ANALYSIS_WIDTH).toDouble() to OPTICAL_ZOOM else null
        }
        try {
            val dir = java.io.File(context.filesDir, "journaux").apply { mkdirs() }
            val name = "SurfCam-journal-" + java.text.SimpleDateFormat("yyyy-MM-dd_HH'h'mm", java.util.Locale.US).format(java.util.Date()) + ".txt"
            val f = java.io.File(dir, name)
            journalFile = f
            val w = java.io.BufferedWriter(java.io.FileWriter(f), 64 * 1024)
            w.write(com.surfcam.core.journal.FrameJournal.HEADER + "\n")
            w.write("# sujet=$subject manchon=${options.markerColor} gimbal=${follower != null}\n")
            journalWriter = w
            p.journal = w
        } catch (e: Exception) {
            SessionLog.ev("journal", "impossible de créer le journal de capteurs : ${e.message}")
        }
        SessionLog.ev("cerveau", "nouveau cerveau actif · sujet $subject · règles ${p.brain?.rides?.config?.version}")
    }

    private fun teardownPipeline() {
        sessionGravity?.stop()
        sessionGravity = null
        journalWriter?.let { w -> try { synchronized(w) { w.flush(); w.close() } } catch (_: Exception) {} }
        journalWriter = null
        journalFile?.let { exportJournal(it) }
        journalFile = null
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

        /** Le téléobjectif vaut ×5 par rapport à l'objectif principal : c'est le zoom annoncé au cerveau, il ne change jamais. */
        const val OPTICAL_ZOOM = 5.0

        /** Au-delà de cette inclinaison (degrés), l'horizon est retouché entre deux clips. */
        const val HORIZON_TOLERANCE_DEG = 0.7f

        /** Avant de lancer, l'aperçu sur l'objectif principal va de ×1 à ×2, pour viser à l'œil ; rien de cela n'est enregistré. */
        const val PREVIEW_MAX_ZOOM = 2f
        const val SAFETY_PERIOD_MS = 5_000L

        // Espace : on coupe à 800 Mo (demandé), on prévient à 1,5 Go, on refuse de démarrer sous 1,5 Go.
        const val STOP_FREE_BYTES = 800L * 1024 * 1024
        const val WARN_FREE_BYTES = 1_500L * 1024 * 1024
        const val MIN_START_FREE_BYTES = 1_500L * 1024 * 1024
        const val STOP_BATTERY_PERCENT = 3
        const val SEVERE_LIMIT_MS = 600_000L
        const val FOLLOW_ANALYSIS_INTERVAL_MS = 250L
        const val REJECTS_MAX_BYTES = 1_500_000_000L
        const val ALERT_CHANNEL = "alertes"
        const val ALERT_ID = 2
    }
}
