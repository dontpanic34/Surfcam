package com.surfcam.app.data.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.surfcam.app.domain.model.CaptureConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Objectif choisi pour la session : le principal, ou le téléobjectif optique (changement = nouvelle session). */
enum class Lens { MAIN, TELE }

/** Ce que le téléphone sait faire, et la caméra retenue pour filmer. */
data class CameraProbe(
    val cameraId: String,
    /** Si non nul : objectif physique à utiliser à l'intérieur de la caméra logique [cameraId]. */
    val physicalId: String?,
    /** Objectif téléobjectif utilisable par bascule en cours de session (null si absent ou si le téléobjectif est déjà forcé). */
    val telePhysicalId: String?,
    val videoStreamUseCases: Boolean,
    /** Cadence maximale annoncée par l'objectif pour cette définition (peut être inférieure à 60). */
    val maxFpsAtSize: Int,
    val config: CaptureConfig,
    val fpsRange: Range<Int>,
    val sensorOrientation: Int,
    val minZoom: Float,
    val maxZoom: Float,
    /** Focale de l'objectif exprimée en pixels de l'image d'analyse, à zoom ×1 (sert à estimer la distance des surfeurs). */
    val focalPx1x: Float = 0f,
)

/**
 * Caméra arrière pilotée directement avec Camera2 : la 4K part vers l'encodeur, un aperçu léger vers l'écran.
 * Mise au point verrouillée à l'infini ; exposition et balance des blancs laissées converger puis verrouillées,
 * pour que les reflets de l'eau ne fassent pas varier l'image.
 */
class Camera2Source(context: Context) {

    interface Listener {
        fun onLockChanged(locked: Boolean)
        fun onLensInfo(text: String)
        fun onError(message: String)

        /** Le flux d'analyse a été refusé par la caméra : la session continue sans lui. */
        fun onAnalysisUnavailable() = Unit
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var characteristics: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    // Nul en mode aperçu seul (avant la session) : la caméra ne filme alors que vers l'écran.
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var analysisSurface: Surface? = null
    private var analysisDropped = false
    private var fpsRange: Range<Int>? = null
    private var listener: Listener? = null
    private var physicalId: String? = null

    // Bascule automatique sur le téléobjectif à partir de ×5 : la caméra ne le fait pas toute seule pour une app tierce.
    // Possible seulement à 30 i/s ou moins (le téléobjectif ne filme pas à 60 i/s) et si le téléobjectif n'est pas déjà forcé.
    private var telePhysicalId: String? = null
    private var teleSwitchAllowed = false
    private var teleActive = false
    private var lastLensSwitchMs = 0L
    private var useStreamUseCases = false
    private var retriedWithoutUseCases = false
    private var lastLensInfo = ""

    private var zoom = 1f
    private var locked = false

    /** Faux (défaut) : exposition et balance des blancs restent automatiques toute la session, comme l'appli vidéo du Pixel. */
    @Volatile
    var lockExposure = false
    private var lockPending = false
    private var framesSinceChange = 0
    private var closed = false

    /** Choisit la caméra, la définition et la cadence ; [maxFps] permet de retomber à 30 si 60 échoue. */
    fun probe(maxFps: Int, lens: Lens, hd: Boolean = false): CameraProbe {
        var best: Pair<String, CameraCharacteristics>? = null
        var bestZoom = 0f
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val maxRatio = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
            if (maxRatio > bestZoom) {
                bestZoom = maxRatio
                best = id to c
            }
        }
        val (id, logical) = best ?: throw IllegalStateException("Aucune caméra arrière")

        // Téléobjectif : on cible l'objectif physique le plus long. Le zoom 5× de la caméra logique
        // n'est que numérique dans ce mode de capture, il ne bascule pas sur le téléobjectif.
        var cameraId = id
        var physical: String? = null
        var sizeChars = logical
        val mainEquiv = equivFocal(logical)
        val teleCandidate = logical.physicalCameraIds
            .mapNotNull { pid -> runCatching { pid to manager.getCameraCharacteristics(pid) }.getOrNull() }
            .maxByOrNull { equivFocal(it.second) }
            ?.takeIf { equivFocal(it.second) >= 2.5f * mainEquiv }
        if (lens == Lens.TELE) {
            val tele = teleCandidate ?: throw IllegalStateException("Pas de téléobjectif optique accessible sur ce téléphone")
            sizeChars = tele.second
            if (tele.first in manager.cameraIdList) cameraId = tele.first else physical = tele.first
        }
        // Réglages de contrôle (exposition, cadence) : ceux de la caméra réellement ouverte.
        val chars = if (cameraId == id) logical else sizeChars

        val map = sizeChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Caméra sans configuration de flux")
        val sizes = map.getOutputSizes(MediaCodec::class.java)
            ?: throw IllegalStateException("La caméra ne sait pas filmer vers l'encodeur")
        val size = pickSize(sizes, hd)

        val minFrameNs = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
        val sizeMaxFps = if (minFrameNs > 0) (1_000_000_000L / minFrameNs).toInt() else 30
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val wantedFps = if (maxFps >= 60 && sizeMaxFps >= 59 && ranges.any { it.upper >= 60 }) 60 else 30
        val range = ranges.filter { it.upper == wantedFps }.let { r ->
            r.firstOrNull { it.lower == wantedFps } ?: r.maxByOrNull { it.lower }
        } ?: ranges.maxByOrNull { it.upper } ?: Range(30, 30)

        val fps = range.upper
        val bitrate = when {
            size.width >= 3840 && fps >= 60 -> 60_000_000
            size.width >= 3840 -> 40_000_000
            fps >= 60 -> 25_000_000
            else -> 15_000_000
        }
        val zoomRange = if (lens == Lens.TELE) null else chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val useCases = chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
        val videoUseCase = useCases?.contains(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()) == true
        characteristics = chars
        return CameraProbe(
            cameraId = cameraId,
            physicalId = physical,
            telePhysicalId = if (lens == Lens.MAIN) teleCandidate?.first else null,
            videoStreamUseCases = videoUseCase,
            maxFpsAtSize = sizeMaxFps,
            config = CaptureConfig(size.width, size.height, fps, bitrate),
            fpsRange = range,
            sensorOrientation = sizeChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            minZoom = maxOf(1f, zoomRange?.lower ?: 1f),
            maxZoom = minOf(zoomRange?.upper ?: 1f, MAX_ZOOM),
            focalPx1x = equivFocal(chars) / 36f * CaptureConfig.ANALYSIS_WIDTH,
        )
    }

    @SuppressLint("MissingPermission")
    fun open(
        probe: CameraProbe,
        encoderSurface: Surface?,
        previewSurface: Surface?,
        analysisSurface: Surface?,
        zoom: Float,
        listener: Listener,
    ) {
        this.encoderSurface = encoderSurface
        this.previewSurface = previewSurface
        this.analysisSurface = analysisSurface
        this.analysisDropped = false
        this.fpsRange = probe.fpsRange
        this.physicalId = probe.physicalId
        this.telePhysicalId = probe.telePhysicalId
        this.teleSwitchAllowed = probe.telePhysicalId != null && probe.physicalId == null && probe.fpsRange.upper <= 30
        this.teleActive = false
        this.useStreamUseCases = probe.videoStreamUseCases
        this.retriedWithoutUseCases = false
        this.lastLensInfo = ""
        this.listener = listener
        this.zoom = zoom
        if (teleSwitchAllowed && zoom >= TELEPHOTO_SWITCH) {
            // Ouverture déjà zoomée au-delà de ×5 : on part directement sur le téléobjectif.
            teleActive = true
            physicalId = telePhysicalId
        }
        closed = false
        manager.openCamera(
            probe.cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    if (!closed) listener.onError("Caméra déconnectée")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    if (!closed) listener.onError("Erreur caméra ($error)")
                }
            },
            handler,
        )
    }

    /** Branche ou débranche l'aperçu : la session est recréée, l'encodeur garde la même surface. */
    fun setPreviewSurface(surface: Surface?) {
        handler.post {
            if (previewSurface === surface) return@post
            previewSurface = surface
            if (device != null && !closed) createSession()
        }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            zoom = ratio
            // Hystérésis : on monte sur le télé à ×5, on ne redescend que sous ×4,6 ; et jamais deux changements en moins
            // d'une seconde et demie. Sans cela, un pincement qui hésite autour de ×5 recréerait la session en boucle.
            val wantTele = teleSwitchAllowed && (if (teleActive) ratio >= TELEPHOTO_RELEASE else ratio >= TELEPHOTO_SWITCH)
            val now = android.os.SystemClock.elapsedRealtime()
            if (wantTele != teleActive && device != null && !closed && now - lastLensSwitchMs >= MIN_SWITCH_GAP_MS) {
                // On change d'objectif : la session est recréée (coupure d'une fraction de seconde), l'exposition est à refaire.
                lastLensSwitchMs = now
                teleActive = wantTele
                physicalId = if (wantTele) telePhysicalId else null
                unlock()
                createSession()
            } else {
                applyRequest()
            }
        }
    }

    /** Relâche puis refait converger l'exposition et la balance des blancs (changement de lumière). */
    fun relock() {
        handler.post {
            unlock()
            applyRequest()
        }
    }

    fun stop() {
        closed = true
        handler.post {
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            session?.close()
            session = null
            device?.close()
            device = null
        }
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    /** Ferme la caméra et attend que ce soit fait : indispensable avant de la rouvrir aussitôt après. */
    fun releaseBlocking() {
        closed = true
        val done = CountDownLatch(1)
        handler.post {
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            session?.close()
            session = null
            device?.close()
            device = null
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun unlock() {
        locked = false
        lockPending = false
        framesSinceChange = 0
        listener?.onLockChanged(false)
    }

    private fun createSession() {
        val camera = device ?: return
        session?.close()
        session = null
        val outputs = mutableListOf<OutputConfiguration>()
        encoderSurface?.let { outputs += output(it, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD) }
        previewSurface?.let { outputs += output(it, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW) }
        analysisSurface?.let { outputs += output(it, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW) }
        if (outputs.isEmpty()) return
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closed) {
                                configured.close()
                                return
                            }
                            session = configured
                            unlock()
                            applyRequest()
                        }

                        override fun onConfigureFailed(failed: CameraCaptureSession) {
                            if (closed) return
                            if (useStreamUseCases && !retriedWithoutUseCases) {
                                // Certaines combinaisons refusent les cas d'usage de flux : on réessaie sans.
                                retriedWithoutUseCases = true
                                useStreamUseCases = false
                                createSession()
                            } else if (analysisSurface != null && !analysisDropped) {
                                // Quatre flux de trop pour ce téléphone : on garde la 4K et l'aperçu, sans l'analyse.
                                analysisDropped = true
                                analysisSurface = null
                                listener?.onAnalysisUnavailable()
                                createSession()
                            } else {
                                listener?.onError("Configuration de la caméra refusée")
                            }
                        }
                    },
                ),
            )
        } catch (e: Exception) {
            listener?.onError("Session caméra impossible : ${e.message}")
        }
    }

    private fun applyRequest() {
        val camera = device ?: return
        val current = session ?: return
        val chars = characteristics ?: return
        val encoder = encoderSurface
        if (encoder == null && previewSurface == null) return
        try {
            val template = if (encoder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template)
            encoder?.let { builder.addTarget(it) }
            previewSurface?.let { builder.addTarget(it) }
            analysisSurface?.let { builder.addTarget(it) }
            fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

            // Mise au point à l'infini : le surfeur est à plus de 50 m.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)

            // Caméra sur trépied : aucune stabilisation, elle déformerait le cadrage.
            if (supports(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (supports(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }

            // Sur le téléobjectif (déjà ×5 par rapport à l'objectif principal), seul l'excédent est du zoom numérique.
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (teleActive) maxOf(1f, zoom / TELEPHOTO_SWITCH) else zoom)

            if (locked) {
                if (chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                if (chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
                    builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }
            current.setRepeatingRequest(builder.build(), captureCallback, handler)
        } catch (e: Exception) {
            if (!closed) listener?.onError("Requête caméra impossible : ${e.message}")
        }
    }

    private fun supports(available: IntArray?, mode: Int): Boolean = available?.contains(mode) == true

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            reportLens(result)
            if (!lockExposure || locked || lockPending) return
            framesSinceChange++
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val converged = aeState == null ||
                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            // Convergence normale, ou verrouillage forcé si la lumière faible empêche d'y arriver.
            if ((framesSinceChange >= CONVERGENCE_FRAMES && converged) || framesSinceChange >= MAX_CONVERGENCE_FRAMES) {
                lockPending = true
                handler.post {
                    if (closed) return@post
                    locked = true
                    lockPending = false
                    applyRequest()
                    listener?.onLockChanged(true)
                }
            }
        }
    }

    private fun output(surface: Surface, streamUseCase: Int): OutputConfiguration =
        OutputConfiguration(surface).also {
            physicalId?.let { id -> it.setPhysicalCameraId(id) }
            if (useStreamUseCases) it.setStreamUseCase(streamUseCase.toLong())
        }

    /** Remonte l'objectif réellement utilisé, pour vérifier à l'écran que le zoom est bien optique. */
    private fun reportLens(result: TotalCaptureResult) {
        val active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
        val text = buildString {
            append("objectif ").append(active ?: physicalId ?: "unique")
            if (focal != null) append(" · ").append("%.1f".format(focal)).append(" mm")
        }
        if (text != lastLensInfo) {
            lastLensInfo = text
            listener?.onLensInfo(text)
        }
    }

    /** Focale équivalente 24x36 : permet de comparer les objectifs entre eux. */
    private fun equivFocal(c: CameraCharacteristics): Float {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val width = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return 0f
        return if (width > 0f) focal * 36f / width else 0f
    }

    private fun pickSize(sizes: Array<Size>, hd: Boolean): Size {
        if (hd) {
            sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
            val widescreen = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            widescreen.maxByOrNull { it.width * it.height }?.let { return it }
        }
        sizes.firstOrNull { it.width == 3840 && it.height == 2160 }?.let { return it }
        val widescreen = sizes.filter { it.width * 9 == it.height * 16 }
        return (widescreen.ifEmpty { sizes.toList() }).maxBy { it.width * it.height }
    }

    private companion object {
        // Environ deux secondes de convergence avant de verrouiller.
        const val CONVERGENCE_FRAMES = 90

        // Zoom maximal proposé (au-delà, l'image n'est plus que du recadrage numérique) et seuil du téléobjectif.
        const val MAX_ZOOM = 10f
        const val TELEPHOTO_SWITCH = 5f
        const val TELEPHOTO_RELEASE = 4.6f
        const val MIN_SWITCH_GAP_MS = 1_500L

        // Au plus environ quatre à huit secondes d'attente, selon la cadence.
        const val MAX_CONVERGENCE_FRAMES = 240
    }
}
