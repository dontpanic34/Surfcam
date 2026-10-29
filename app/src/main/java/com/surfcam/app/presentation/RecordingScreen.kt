package com.surfcam.app.presentation

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.surfcam.app.service.DoNotDisturb
import com.surfcam.app.domain.geometry.DistanceEstimator
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.text.style.TextAlign
import android.os.PowerManager
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.StrokeCap
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.surfcam.app.data.engine.EngineState
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.data.engine.RecordingEngine
import com.surfcam.app.data.engine.SessionOptions
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.presentation.theme.SurfColors
import com.surfcam.app.presentation.theme.SurfCamTheme
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers

private const val SLEEP_AFTER_MS = 60_000L

@Composable
fun RecordingScreen(
    engine: RecordingEngine,
    onOpenHome: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenGimbal: () -> Unit,
    options: SessionOptions,
    hasCamera: Boolean,
    crashReport: String?,
    onDismissCrash: () -> Unit,
    onOptionsChange: (SessionOptions) -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val running = state.phase == Phase.RUNNING || state.phase == Phase.STARTING
    var showInfo by rememberSaveable { mutableStateOf(false) }

    // Aperçu seul tant que l'écran est visible et qu'aucune session n'est en cours.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.phase, options.tele, resumed, hasCamera) {
        val idle = state.phase == Phase.IDLE || state.phase == Phase.PREVIEW
        if (resumed && hasCamera && idle) {
            engine.startPreview(options.lens)
        } else if (!resumed) {
            engine.stopPreview()
        }
    }

    // Écran noir après une minute sans toucher pendant une session ; un toucher le réveille.
    val view = LocalView.current
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var asleep by remember { mutableStateOf(false) }
    LaunchedEffect(running, options.sleepScreen) {
        asleep = false
        lastTouch = SystemClock.elapsedRealtime()
        if (!running || !options.sleepScreen) return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (!asleep && SystemClock.elapsedRealtime() - lastTouch >= SLEEP_AFTER_MS) asleep = true
        }
    }
    DisposableEffect(asleep) {
        val window = (view.context as? android.app.Activity)?.window
        window?.attributes = window?.attributes?.also { it.screenBrightness = if (asleep) 0.01f else -1f }
        onDispose {
            window?.attributes = window?.attributes?.also { it.screenBrightness = -1f }
        }
    }
    val deviceForSleep by rememberDeviceStatus()

    val startGuarded = { onStartSession() }

    SurfCamTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        if (showInfo) InfoDialog(state, options, onOptionsChange, engine, running, onOpenGimbal = { showInfo = false; onOpenGimbal() }) { showInfo = false }
        Surface(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.elapsedRealtime()
                    }
                }
            },
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Viewfinder(engine, state, hasCamera, options.fps60, options.reframe, Modifier.weight(1f).fillMaxHeight())
                    SideControls(
                        state, running, options, onOptionsChange, engine,
                        onOpenLibrary, onOpenHome, { showInfo = true }, startGuarded, onStopSession,
                    )
                }
                if (asleep) SleepOverlay(deviceForSleep) {
                    asleep = false
                    lastTouch = SystemClock.elapsedRealtime()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Viseur : l'image de la caméra, avec l'état de la session en pastilles par-dessus.
// ---------------------------------------------------------------------------------------------

@Composable
private fun Viewfinder(engine: RecordingEngine, state: EngineState, hasCamera: Boolean, fps60: Boolean, reframe: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.setFixedSize(CaptureConfig.PREVIEW_WIDTH, CaptureConfig.PREVIEW_HEIGHT)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = engine.setPreviewSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) = engine.setPreviewSurface(null)
                    })
                }
            },
        )

        // Pincement à deux doigts : zoom continu, comme dans l'appareil photo.
        val currentZoom by rememberUpdatedState(state.zoom)
        // Toucher l'image : mise au point sur ce point (anneau affiché 1,5 s).
        var ring by remember { mutableStateOf<Offset?>(null) }
        val rotationDegrees = when (LocalView.current.display?.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        LaunchedEffect(ring) {
            if (ring != null) {
                delay(1_500)
                ring = null
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(rotationDegrees) {
                    detectTapGestures { p ->
                        val areaH = size.width * 9f / 16f
                        val offsetY = (size.height - areaH) / 2f
                        val nx = p.x / size.width
                        val ny = (p.y - offsetY) / areaH
                        if (nx in 0f..1f && ny in 0f..1f) {
                            engine.focusAt(nx, ny, rotationDegrees)
                            ring = p
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        // Pendant la session le zoom ne change plus par un geste involontaire : seules les pastilles agissent.
                        if (zoomChange != 1f && engine.state.value.phase != Phase.RUNNING) engine.setZoom(currentZoom * zoomChange)
                    }
                },
        )
        ring?.let { p ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(SurfColors.Warn, radius = 34.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
            }
        }
        // La mise au point automatique est le cas normal : la pastille n'apparaît que si elle a été changée (toucher l'image).
        if ((state.phase == Phase.PREVIEW || state.phase == Phase.RUNNING) && state.focusMode != com.surfcam.app.data.camera.Camera2Source.FocusMode.AUTO) {
            val label = when (state.focusMode) {
                com.surfcam.app.data.camera.Camera2Source.FocusMode.AUTO -> "Focus : auto"
                com.surfcam.app.data.camera.Camera2Source.FocusMode.POINT -> "Focus : point · toucher pour repasser en auto"
                com.surfcam.app.data.camera.Camera2Source.FocusMode.INFINITY -> "Focus : infini · toucher pour repasser en auto"
            }
            InfoChip(
                label,
                SurfColors.Foam,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clickable { engine.setFocusMode(com.surfcam.app.data.camera.Camera2Source.FocusMode.AUTO) },
            )
        }

        // Cadres des surfeurs suivis par la détection, posés sur la même zone que l'image.
        state.analysis?.let { snapshot ->
            Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                for (box in snapshot.boxes) {
                    drawRect(
                        color = if (box.marked) SurfColors.Ok else if (box.riding) SurfColors.Alert else SurfColors.Aqua,
                        topLeft = Offset(box.left * size.width, box.top * size.height),
                        size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                        style = Stroke(width = if (box.riding || box.marked) 3.dp.toPx() else 1.5.dp.toPx()),
                    )
                }
                // Cadre jaune : le surfeur que l'appli suit en ce moment (aide au réglage, à retirer ensuite).
                snapshot.boxes.firstOrNull { it.id == state.followTargetId }?.let { box ->
                    drawRect(
                        color = Color(0xFFFFE600),
                        topLeft = Offset(box.left * size.width - 4.dp.toPx(), box.top * size.height - 4.dp.toPx()),
                        size = Size((box.right - box.left) * size.width + 8.dp.toPx(), (box.bottom - box.top) * size.height + 8.dp.toPx()),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }
        }

        if (!hasCamera) {
            Text(
                "Autorise l'accès à la caméra pour voir l'image",
                color = SurfColors.Muted,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // Distance estimée du surfeur le plus grand dans l'image, et zoom qui le rendrait bien lisible.
        val target = state.analysis?.boxes?.maxByOrNull { it.bottom - it.top }
        if (reframe) {
            // Mode recadrage : on filme large, pas de zoom conseillé.
        } else if (state.phase == Phase.PREVIEW && target != null && state.focalPx1x > 0f) {
            val heightPx = (target.bottom - target.top) * CaptureConfig.ANALYSIS_HEIGHT
            val distance = DistanceEstimator.distanceMeters(state.focalPx1x * state.zoom, heightPx)
            if (distance != null) {
                val advised = DistanceEstimator.recommendedZoom(
                    state.zoom, heightPx, CaptureConfig.ANALYSIS_HEIGHT.toFloat(), state.minZoom, state.maxZoom,
                )
                val change = kotlin.math.abs(advised - state.zoom) / state.zoom > 0.2f
                val hint = if (change) " · conseil : ×${advised.toInt()} (toucher pour appliquer)" else " · bonne taille"
                InfoChip(
                    "Surfeur à ≈ ${DistanceEstimator.rounded(distance)} m$hint",
                    if (change) SurfColors.Warn else SurfColors.Ok,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                        .clickable(enabled = change) { engine.setZoom(advised) },
                )
            }
        }

        val horizon by rememberHorizonAngle()
        if (hasCamera && state.phase != Phase.RUNNING) horizon?.takeIf { kotlin.math.abs(it) <= 30f }?.let { angle ->
            HorizonLevel(angle)
            Text(
                levelLabel(angle),
                color = levelColor(angle),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center).offset(y = (-16).dp),
            )
        }

        // Un point de couleur et, dessous, ce qu'il veut dire en quelques mots.
        val (label, color) = statusPill(state)
        Pill(label, color, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val device by rememberDeviceStatus()
            // Pendant la session : durée et nombre de vagues. Sinon : température et batterie, en une pastille discrète.
            if (state.phase == Phase.RUNNING) {
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val secs = ((now - state.startedAtMs).coerceAtLeast(0L) / 1000).toInt()
                InfoChip(
                    "%d:%02d:%02d · %d vague%s".format(secs / 3600, secs / 60 % 60, secs % 60, state.waves, if (state.waves > 1) "s" else ""),
                    SurfColors.Foam,
                )
            }
            device?.let { d ->
                val t = d.temperatureC
                val hot = state.thermalStatus > PowerManager.THERMAL_STATUS_LIGHT
                val low = d.batteryPercent in 0..15 && !d.charging
                // Rien à signaler pendant la session : la pastille ne s'affiche qu'en préparation ou en cas de souci.
                if (state.phase != Phase.RUNNING || hot || low || (t != null && t >= 40f)) {
                    val color = when {
                        low -> SurfColors.Alert
                        t != null && hot -> if (temperatureColor(t) == SurfColors.Ok) thermalColor(state.thermalStatus) else temperatureColor(t)
                        t != null -> temperatureColor(t)
                        else -> SurfColors.Foam
                    }
                    InfoChip((t?.let { "%.0f°".format(it) } ?: "") + (if (d.batteryPercent >= 0) " · ${d.batteryPercent} %" else ""), color)
                }
            }
        }

        val notice = banner(state)
        // Le message de fin de session se retire tout seul après quelques secondes, ou d'un toucher.
        LaunchedEffect(notice?.first) {
            if (notice != null) {
                delay(15_000)
                engine.dismissNotice()
            }
        }
        notice?.let { (text, color) ->
            Text(
                text,
                color = color,
                fontSize = 13.sp,
                maxLines = 3,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { engine.dismissNotice() }
                    .background(Color(0xE60B1620))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (fps60 && state.phase == Phase.PREVIEW) {
            InfoChip(
                "60 i/s : zoom limité à ×2 pendant la session",
                SurfColors.Warn,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            )
        }
        if (state.phase == Phase.PREVIEW || state.phase == Phase.RUNNING) {
            ZoomSelector(state, engine, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB30B1620))
            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(50))
            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(text, color = SurfColors.Foam, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC0B1620))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun statusPill(state: EngineState): Pair<String, Color> = when (state.phase) {
    Phase.IDLE -> "Prêt" to SurfColors.Muted
    Phase.PREVIEW -> "Aperçu" to SurfColors.Info
    Phase.STARTING -> "Démarrage…" to SurfColors.Warn
    Phase.ERROR -> "Erreur" to SurfColors.Alert
    Phase.RUNNING -> when {
        state.clipActive -> "Enregistrement d'une vague" to SurfColors.Alert
        state.analysis?.riding == true -> "Vague détectée" to SurfColors.Alert
        state.analysisStatus?.startsWith("Détection indisponible") == true -> "Détection indisponible" to SurfColors.Warn
        state.analysisStatus?.startsWith("Analyse arrêtée") == true -> "Détection arrêtée" to SurfColors.Alert
        state.analysisStatus == "Chargement du modèle…" -> "Chargement de la détection" to SurfColors.Warn
        state.analysisReceived == 0 -> "Aucune image reçue" to SurfColors.Warn
        state.analysis == null -> "Détection prête" to SurfColors.Warn
        else -> "Aucune vague détectée" to SurfColors.Ok
    }
}

private fun resolutionLabel(config: CaptureConfig): String {
    val name = if (config.width >= 3840) "4K" else "${config.height}p"
    return "$name · ${config.fps} i/s"
}


/** Message à afficher sur le viseur : alerte en cours, sinon arrêt automatique ou bilan de la dernière session. */
private fun banner(state: EngineState): Pair<String, Color>? {
    state.message?.let { return it to SurfColors.Warn }
    if (state.phase == Phase.IDLE || state.phase == Phase.PREVIEW) {
        state.endedReason?.let { return "Session arrêtée automatiquement : $it. Tes vidéos sont sauvegardées." to SurfColors.Warn }
        state.lastSummary?.let { return it to SurfColors.Foam }
    }
    return null
}

// ---------------------------------------------------------------------------------------------
// Commandes sur le bord droit, dans les bandes noires à côté de l'image, à la manière de l'appareil photo du Pixel.
// ---------------------------------------------------------------------------------------------

@Composable
private fun SideControls(
    state: EngineState,
    running: Boolean,
    options: SessionOptions,
    onOptionsChange: (SessionOptions) -> Unit,
    engine: RecordingEngine,
    onOpenLibrary: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    val context = LocalContext.current
    var storages by remember { mutableStateOf(engine.storage.available()) }
    LaunchedEffect(running) {
        // Sondé toutes les 3 s : brancher ou retirer une carte SD / une clé USB se voit sans rien faire.
        while (!running) {
            storages = withContext(Dispatchers.IO) { engine.storage.available() }
            delay(3_000)
        }
    }
    val dndAllowed by produceState(DoNotDisturb.isAllowed(context)) {
        while (true) {
            value = DoNotDisturb.isAllowed(context)
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier.width(112.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(36.dp, on = false, onClick = onOpenSettings) { tint ->
                // Engrenage : anneau, huit dents épaisses et trou central.
                drawCircle(tint, radius = 5.5.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                for (k in 0 until 8) {
                    val ang = k * (Math.PI / 4).toFloat()
                    drawLine(
                        tint,
                        Offset(center.x + 6.5.dp.toPx() * kotlin.math.cos(ang), center.y + 6.5.dp.toPx() * kotlin.math.sin(ang)),
                        Offset(center.x + 9.5.dp.toPx() * kotlin.math.cos(ang), center.y + 9.5.dp.toPx() * kotlin.math.sin(ang)),
                        strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Butt,
                    )
                }
            }
            if (!running) {
                RoundIcon(36.dp, on = false, onClick = onOpenHome) { tint ->
                    // Flèche vers la gauche : retour à l'accueil.
                    drawLine(tint, Offset(center.x + 8.dp.toPx(), center.y), Offset(center.x - 8.dp.toPx(), center.y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(center.x - 8.dp.toPx(), center.y), Offset(center.x - 2.dp.toPx(), center.y - 6.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(center.x - 8.dp.toPx(), center.y), Offset(center.x - 2.dp.toPx(), center.y + 6.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            if (running && state.gimbalStatus != null) {
                RoundIcon(36.dp, on = state.followEnabled, warn = state.gimbalStatus?.startsWith("Gimbal connecté") != true, onClick = { engine.setFollowEnabled(!state.followEnabled) }) { tint ->
                    drawLine(tint, Offset(center.x - 9.dp.toPx(), center.y), Offset(center.x + 9.dp.toPx(), center.y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(center.x - 9.dp.toPx(), center.y), Offset(center.x - 5.dp.toPx(), center.y - 4.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(center.x - 9.dp.toPx(), center.y), Offset(center.x - 5.dp.toPx(), center.y + 4.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(center.x + 9.dp.toPx(), center.y), Offset(center.x + 5.dp.toPx(), center.y - 4.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(center.x + 9.dp.toPx(), center.y), Offset(center.x + 5.dp.toPx(), center.y + 4.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Bouton de prise de vue : rond blanc, point turquoise au repos, carré rouge pendant la session.
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(4.dp, SurfColors.Foam, CircleShape)
                    .clickable(onClick = if (running) onStopSession else onStartSession),
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(SurfColors.Alert))
                } else {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(SurfColors.Aqua))
                }
            }
        }

        // Vignette des vidéos, en bas à droite comme dans Google Caméra.
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Color(0xFF3B5565), RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenLibrary),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val tri = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.15f)
                    lineTo(size.width * 0.28f, size.height * 0.85f)
                    lineTo(size.width * 0.85f, size.height * 0.5f)
                    close()
                }
                drawPath(tri, SurfColors.Aqua)
            }
        }
    }
}

/** Petit bouton rond dessiné à la main ; plein quand [on], contour orange quand [warn]. */
@Composable
private fun RoundIcon(
    size: androidx.compose.ui.unit.Dp,
    on: Boolean,
    warn: Boolean = false,
    onClick: () -> Unit,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val tint = if (on) SurfColors.OnAqua else SurfColors.Foam
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (on) SurfColors.Aqua else Color(0x66000000))
            .border(1.dp, if (warn) SurfColors.Warn else Color(0xFF3B5565), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.6f)) { draw(tint) }
    }
}

/** Pastille de réglage rapide : un toucher change la valeur. */
@Composable
private fun SideChip(text: String, warn: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (warn) SurfColors.Warn else SurfColors.Foam,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .then(if (wide) Modifier.width(104.dp) else Modifier.width(49.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66000000))
            .border(1.dp, if (warn) SurfColors.Warn else Color(0xFF3B5565), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
    )
}

/**
 * Sélecteur de zoom à la manière de l'appareil photo du Pixel : pastilles 0,5 / 1 / 2 / 5 / 10, et une règle qui apparaît
 * quelques secondes quand le zoom change (pincement ou pastille) et que l'on peut faire glisser pour un réglage fin.
 */
@Composable
private fun ZoomSelector(state: EngineState, engine: RecordingEngine, modifier: Modifier = Modifier) {
    val presets = listOf(0.5f, 1f, 2f, 5f, 10f).filter { it >= state.minZoom - 0.01f && it <= state.maxZoom + 0.01f }
    var rulerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.zoom) {
        rulerVisible = true
        delay(2_000)
        rulerVisible = false
    }
    val currentZoom by rememberUpdatedState(state.zoom)
    val minZ = state.minZoom
    val maxZ = state.maxZoom
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!state.stabilized) {
            Text(
                "Stabilisation inactive",
                color = SurfColors.Warn,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0B1620)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (!state.opticalZoom && state.zoom > 1.05f) {
            Text(
                "Zoom numérique : image moins nette",
                color = SurfColors.Warn,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0B1620)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (rulerVisible && maxZ > minZ) {
            Text(
                "×%.1f".format(state.zoom).replace('.', ','),
                color = SurfColors.Warn,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0B1620)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Canvas(
                Modifier
                    .width(240.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0x66000000))
                    .pointerInput(minZ, maxZ) {
                        detectHorizontalDragGestures { _, dx ->
                            if (engine.state.value.phase == Phase.RUNNING) return@detectHorizontalDragGestures
                            val span = kotlin.math.ln(maxZ / minZ)
                            engine.setZoom(currentZoom * kotlin.math.exp(-dx / size.width * span))
                        }
                    },
            ) {
                val span = kotlin.math.ln(maxZ / minZ)
                fun xOf(z: Float) = size.width * (0.5f + (kotlin.math.ln(z / currentZoom) / span) * 1.6f)
                var z = minZ
                // Graduations tous les 10 % (échelle logarithmique), plus longues sur les valeurs de référence.
                while (z <= maxZ * 1.001f) {
                    val x = xOf(z)
                    if (x in 0f..size.width) {
                        val major = presets.any { kotlin.math.abs(it - z) / it < 0.04f }
                        drawLine(
                            Color(if (major) 0xFFFFFFFF else 0x88FFFFFF),
                            Offset(x, size.height * (if (major) 0.2f else 0.35f)),
                            Offset(x, size.height * (if (major) 0.8f else 0.65f)),
                            strokeWidth = 1.2.dp.toPx(),
                        )
                    }
                    z *= 1.1f
                }
                drawLine(
                    SurfColors.Warn, Offset(size.width / 2, 2.dp.toPx()), Offset(size.width / 2, size.height - 2.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { ratio ->
                val selected = kotlin.math.abs(state.zoom - ratio) / ratio < 0.04f
                val label = if (ratio < 1f) ",5" else "${ratio.toInt()}"
                Box(
                    Modifier
                        .size(if (selected) 40.dp else 34.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color(0xFFFFD9C7) else Color.Transparent)
                        .clickable { engine.setZoom(ratio) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (selected) "$label×" else label,
                        color = if (selected) Color(0xFF3A1B0E) else Color.White,
                        fontSize = if (selected) 14.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Panneau « Infos » : aide, réglages avancés, diagnostic, test manuel, apprentissage.
// ---------------------------------------------------------------------------------------------

@Composable
internal fun InfoDialog(
    state: EngineState,
    options: SessionOptions,
    onOptionsChange: (SessionOptions) -> Unit,
    engine: RecordingEngine,
    running: Boolean,
    onOpenGimbal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    var advanced by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = if (portrait) Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f) else Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.92f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Réglages", color = SurfColors.Aqua, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("Fermer") }
                }
                if (portrait) {
                    Column(Modifier.weight(1f).padding(top = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        QualitySection(options, running, onOptionsChange)
                        WhoSection(options, running, onOptionsChange)
                        SessionSection(options, running, onOptionsChange)
                        AdvancedHeader(advanced) { advanced = !advanced }
                        if (advanced) AdvancedSections(state, options, onOptionsChange, engine, running, onOpenGimbal)
                    }
                } else if (advanced) {
                    Column(Modifier.weight(1f).padding(top = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("◂ Retour aux réglages", color = SurfColors.Aqua, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { advanced = false }.padding(8.dp))
                        AdvancedSections(state, options, onOptionsChange, engine, running, onOpenGimbal)
                    }
                } else {
                    Row(Modifier.weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) { QualitySection(options, running, onOptionsChange) }
                        Column(Modifier.weight(1f).fillMaxHeight()) { WhoSection(options, running, onOptionsChange) }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SessionSection(options, running, onOptionsChange)
                            AdvancedHeader(false) { advanced = true }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(items: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, t ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) SurfColors.Aqua else Color.Transparent)
                    .border(1.dp, if (on) SurfColors.Aqua else Color(0xFF3A5260), RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t, color = if (on) Color(0xFF05241F) else SurfColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Définition et cadence : le seul vrai choix de qualité. Le HDR reste dans Avancé. */
@Composable
internal fun QualitySection(options: SessionOptions, running: Boolean, onOptionsChange: (SessionOptions) -> Unit) {
    Section("Vidéo") {
        Text("Résolution", color = SurfColors.Muted, fontSize = 12.sp)
        Choice(listOf("4K", "1080p"), if (options.hd) 1 else 0, !running) { onOptionsChange(options.copy(hd = it == 1, hdr = if (it == 1) false else options.hdr)) }
        Text("Images par seconde", color = SurfColors.Muted, fontSize = 12.sp)
        Choice(listOf("30", "60"), if (options.fps60) 1 else 0, !running) { onOptionsChange(options.copy(fps60 = it == 1)) }
        Text(
            "4K ≈ ${options.copy(hd = false, fps60 = false).gigabytesPerHour()} Go/h · 1080p ≈ ${options.copy(hd = true, fps60 = false).gigabytesPerHour()} Go/h" +
                if (options.fps60) "\n60 i/s : chauffe plus, zoom limité à ×2 (sans téléobjectif)" else "",
            color = SurfColors.Muted, fontSize = 11.sp, lineHeight = 14.sp,
        )
        if (running) Text("Modifiable avant de démarrer.", color = SurfColors.Muted, fontSize = 11.sp)
    }
}

@Composable
internal fun WhoSection(options: SessionOptions, running: Boolean, onOptionsChange: (SessionOptions) -> Unit) {
    Section("Qui filmer") {
        val mode = when {
            options.subject == com.surfcam.app.data.engine.Subject.ME -> 2
            options.markerColor != null -> 1
            else -> 0
        }
        val firstColor = options.markerColor ?: com.surfcam.app.domain.detection.MarkerColor.ORANGE
        Choice(listOf("Tout", "Moi d'abord", "Que moi"), mode, !running) {
            onOptionsChange(
                when (it) {
                    0 -> options.copy(subject = com.surfcam.app.data.engine.Subject.EVERYONE, markerColor = null)
                    1 -> options.copy(subject = com.surfcam.app.data.engine.Subject.EVERYONE, markerColor = firstColor)
                    else -> options.copy(subject = com.surfcam.app.data.engine.Subject.ME, markerColor = firstColor)
                },
            )
        }
        if (mode != 0) {
            Text("Couleur de mon manchon", color = SurfColors.Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in com.surfcam.app.domain.detection.MarkerColor.entries) {
                    FilterChip(selected = options.markerColor == c, onClick = { onOptionsChange(options.copy(markerColor = c)) }, label = { Text(c.label) })
                }
            }
        }
        Text(
            when (mode) {
                2 -> "Filme et suit uniquement ta silhouette repérée par le manchon, en ×5 optique. Sans manchon visible, rien n'est filmé."
                1 -> "Filme tout le monde, mais le gimbal te suit toi dès que ton manchon est reconnu."
                else -> "Filme et suit tous les surfeurs."
            },
            color = SurfColors.Muted, fontSize = 11.sp, lineHeight = 14.sp,
        )
    }
}

@Composable
internal fun SessionSection(options: SessionOptions, running: Boolean, onOptionsChange: (SessionOptions) -> Unit) {
    Section("Session") {
        OptionSwitch("Garder toute la session", "", options.fullSession) { if (!running) onOptionsChange(options.copy(fullSession = it)) }
        OptionSwitch("Voyant LED", "", options.ledBlink) { onOptionsChange(options.copy(ledBlink = it)) }
        OptionSwitch("Écran noir après 1 min", "", options.sleepScreen) { onOptionsChange(options.copy(sleepScreen = it)) }
    }
}

@Composable
internal fun AdvancedHeader(open: Boolean, onToggle: () -> Unit) {
    Text(
        (if (open) "▾" else "▸") + " Avancé",
        color = SurfColors.Aqua, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle).padding(14.dp),
    )
}

@Composable
internal fun AdvancedSections(
    state: EngineState,
    options: SessionOptions,
    onOptionsChange: (SessionOptions) -> Unit,
    engine: RecordingEngine,
    running: Boolean,
    onOpenGimbal: () -> Unit,
) {
    val context = LocalContext.current
    Section("Réglages fins") {
        OptionSwitch("Mode recadrage", "Filme large (×1), recadre ensuite sur le surfeur", options.reframe) { if (!running) onOptionsChange(options.copy(reframe = it)) }
        OptionSwitch("Forcer le téléobjectif", "Champ fixe ×5", options.tele) { if (!running) onOptionsChange(options.copy(tele = it)) }
        OptionSwitch("Verrouiller l'exposition", "Si l'image pompe quand un surfeur passe", options.lockExposure) { if (!running) onOptionsChange(options.copy(lockExposure = it)) }
        OptionSwitch("HDR 10 bits", "Lumières et couleurs plus riches, chauffe un peu plus", options.hdr) {
            if (!running) {
                if (it) context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit().putBoolean("hdr_broken", false).apply()
                onOptionsChange(options.copy(hdr = it))
            }
        }
        if (running) Text("Modifiable avant de démarrer.", color = SurfColors.Muted, fontSize = 11.sp)
    }
    if (!running) {
        Section("Tête motorisée") {
            OutlinedButton(onClick = onOpenGimbal) { Text("Essai et calibrage du gimbal") }
        }
    }
    GimbalLogSection()
    LearningSection(engine)
    if (state.phase == Phase.RUNNING) DiagnosticSection(state)
    if (state.phase == Phase.RUNNING) {
        Section("Test manuel") {
            if (state.clipActive) {
                Button(
                    onClick = engine::finishClip,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfColors.Alert, contentColor = Color.White),
                ) { Text("Fin de vague · +${CaptureConfig.POST_ROLL_SEC} s") }
            } else {
                Button(onClick = engine::startClip, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Text("Début de vague · -${CaptureConfig.PRE_ROLL_SEC} s")
                }
            }
        }
    }
    state.lastClip?.let { Section("Dernier clip") { Text(it, color = SurfColors.Foam, fontSize = 13.sp) } }
    details(state)?.let { Section("Technique") { Text(it, color = SurfColors.Muted, fontSize = 12.sp, lineHeight = 17.sp) } }
}

@Composable
private fun PresetCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(2.dp, if (selected) SurfColors.Aqua else Color(0xFF27414F), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = SurfColors.Foam, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = SurfColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun Help(title: String, text: String) {
    Column {
        Text(title, color = SurfColors.Foam, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = SurfColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** Journal des décisions du suivi : à copier ou partager pour comprendre pourquoi le gimbal a bougé. */
@Composable
private fun GimbalLogSection() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(com.surfcam.app.data.gimbal.GimbalLog.tail(40)) }
    Section("Journal du gimbal") {
        Text(
            if (text.isEmpty()) "Vide : le journal se remplit pendant une session avec le gimbal." else text,
            color = SurfColors.Muted, fontSize = 10.sp, lineHeight = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { text = com.surfcam.app.data.gimbal.GimbalLog.tail(40) }) { Text("Actualiser", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val full = com.surfcam.app.data.gimbal.GimbalLog.tail(800)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Journal du gimbal SurfCam")
                    putExtra(android.content.Intent.EXTRA_TEXT, full)
                }
                context.startActivity(android.content.Intent.createChooser(send, "Partager le journal").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Partager", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun DiagnosticSection(state: EngineState) {
    Section("Diagnostic de la détection") {
        Text(
            "Modèle : ${state.analysisStatus ?: "—"}\nImages reçues de la caméra : ${state.analysisReceived}",
            color = SurfColors.Foam,
            fontSize = 12.sp,
        )
        state.analysis?.let { snapshot ->
            snapshot.thumbnail?.let { bitmap ->
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        for (box in snapshot.boxes) {
                            drawRect(
                                color = if (box.marked) SurfColors.Ok else if (box.riding) SurfColors.Alert else SurfColors.Aqua,
                                topLeft = Offset(box.left * size.width, box.top * size.height),
                                size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
            Text(
                "${snapshot.frames} analysées · ${snapshot.personsInFrame} personne(s) · confiance max %.2f · ${snapshot.inferenceMs} ms · écart d'horloge ${snapshot.clockOffsetMs} ms"
                    .format(snapshot.bestConfidence),
                color = SurfColors.Muted,
                fontSize = 11.sp,
            )
        }
    }
}

/** Ce que l'app a appris de tes retours (boutons « Bonne vague / Fausse alerte » dans Mes vidéos). */
@Composable
private fun LearningSection(engine: RecordingEngine) {
    var records by remember { mutableStateOf(engine.learning.records()) }
    var report by remember { mutableStateOf(engine.learning.lastReport()) }
    val good = records.count { it.good == true }
    val bad = records.count { it.good == false }
    Section("Apprentissage") {
        Text(
            "${records.size} clip(s) automatique(s) suivis · $good bonne(s) vague(s) · $bad fausse(s) alerte(s) signalée(s).",
            color = SurfColors.Foam,
            fontSize = 12.sp,
        )
        Text(
            report ?: "Dans « Mes vidéos », indique pour chaque clip automatique s'il était bon ou faux : l'app règle ses seuils d'après tes retours.",
            color = SurfColors.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                engine.learning.recalibrate()
                report = engine.learning.lastReport()
                records = engine.learning.records()
            }) { Text("Adapter", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                engine.learning.reset()
                report = null
                records = engine.learning.records()
            }) { Text("Réinitialiser", fontSize = 12.sp, color = SurfColors.Alert) }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = SurfColors.Aqua, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
internal fun OptionSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = SurfColors.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun details(state: EngineState): String? {
    if (state.phase != Phase.RUNNING) return null
    val config = state.config ?: return null
    return buildString {
        append("${config.width}×${config.height} à ${config.fps} i/s")
        if (state.maxFpsAtSize in 1 until 59) append(" (max ${state.maxFpsAtSize} avec cet objectif)")
        append("\nTampon : %.1f s · %d Mo".format(state.bufferedSec, state.bufferedMb))
        if (state.keyFrameIntervalSec > 0f) append("\nImage clé toutes les %.1f s".format(state.keyFrameIntervalSec))
        append("\nZoom ${"%.1f".format(state.zoom)}×")
        state.lensInfo?.let { append("\nObjectif : $it") }
        state.captureInfo?.let { append("\nCapture : $it") }
        state.hdrText?.let { append("\nHDR : $it") }
        append("\nChaleur : ").append(thermalLabel(state.thermalStatus)).append(" · allègement ").append(state.thermalLevel)
        append(" · analyse toutes les ").append(CaptureConfig.ANALYSIS_INTERVAL_MS * when (state.thermalLevel) { 0 -> 1; 1 -> 2; else -> 4 }).append(" ms")
    }
}

// ---------------------------------------------------------------------------------------------
// Rapport de plantage.
// ---------------------------------------------------------------------------------------------

@Composable
internal fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("L'app s'est arrêtée la dernière fois") },
        text = {
            Text(
                report.take(3000),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

private fun thermalLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "normale"
    PowerManager.THERMAL_STATUS_LIGHT -> "légère"
    PowerManager.THERMAL_STATUS_MODERATE -> "modérée"
    PowerManager.THERMAL_STATUS_SEVERE -> "forte"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critique"
    else -> "extrême"
}

private fun thermalColor(status: Int): Color = when {
    status <= PowerManager.THERMAL_STATUS_LIGHT -> SurfColors.Ok
    status == PowerManager.THERMAL_STATUS_MODERATE -> SurfColors.Warn
    else -> SurfColors.Alert
}
