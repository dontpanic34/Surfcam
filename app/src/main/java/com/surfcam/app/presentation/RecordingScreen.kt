package com.surfcam.app.presentation

import android.os.PowerManager
import androidx.compose.foundation.clickable
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
    onOpenLibrary: () -> Unit,
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

    SurfCamTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        if (showInfo) InfoDialog(state, options, onOptionsChange, engine, running) { showInfo = false }
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
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Viewfinder(engine, state, hasCamera, Modifier.weight(1f).fillMaxHeight())
                    ControlPanel(
                        state, running, options, onOptionsChange, engine,
                        onOpenLibrary, { showInfo = true }, onStartSession, onStopSession,
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
private fun Viewfinder(engine: RecordingEngine, state: EngineState, hasCamera: Boolean, modifier: Modifier) {
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
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) engine.setZoom(currentZoom * zoomChange)
                }
            },
        )

        // Cadres des surfeurs suivis par la détection, posés sur la même zone que l'image.
        state.analysis?.let { snapshot ->
            Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                for (box in snapshot.boxes) {
                    drawRect(
                        color = if (box.riding) SurfColors.Alert else SurfColors.Aqua,
                        topLeft = Offset(box.left * size.width, box.top * size.height),
                        size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                        style = Stroke(width = if (box.riding) 3.dp.toPx() else 1.5.dp.toPx()),
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

        val horizon by rememberHorizonAngle()
        if (hasCamera) horizon?.let { angle ->
            HorizonLevel(angle)
            Text(
                levelLabel(angle),
                color = levelColor(angle),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).offset(y = (-22).dp),
            )
        }

        val (label, color) = statusPill(state)
        Pill(label, color, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val config = state.config
            val device by rememberDeviceStatus()
            device?.let { d ->
                // La batterie est déjà dans la barre d'état : on ne la répète que si elle devient critique.
                if (d.batteryPercent in 0..15 && !d.charging) InfoChip("Batterie ${d.batteryPercent} %", SurfColors.Alert)
                d.temperatureC?.let { t ->
                    // Le degré est celui de la batterie ; l'état du système (normale, forte...) reste donné à côté.
                    val color = if (state.thermalStatus > PowerManager.THERMAL_STATUS_LIGHT) {
                        if (temperatureColor(t) == SurfColors.Ok) thermalColor(state.thermalStatus) else temperatureColor(t)
                    } else temperatureColor(t)
                    InfoChip("%.1f °C · %s".format(t, thermalLabel(state.thermalStatus)).replace('.', ','), color)
                }
            }
            if (state.phase == Phase.RUNNING && config != null) {
                if (state.fullSession) InfoChip("REC session · partie ${state.sessionPart}", SurfColors.Alert)
                InfoChip(resolutionLabel(config), SurfColors.Foam)
                if (state.actualFps > 0f) {
                    InfoChip(
                        "%.0f i/s réels".format(state.actualFps),
                        if (state.actualFps < config.fps * 0.85f) SurfColors.Warn else SurfColors.Foam,
                    )
                }
            }
        }

        banner(state)?.let { (text, color) ->
            Text(
                text,
                color = color,
                fontSize = 13.sp,
                maxLines = 3,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE60B1620))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
            .background(Color(0xCC0B1620))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    Phase.PREVIEW -> "Aperçu · rien n'est enregistré" to SurfColors.Info
    Phase.STARTING -> "Démarrage…" to SurfColors.Warn
    Phase.ERROR -> "Erreur" to SurfColors.Alert
    Phase.RUNNING -> when {
        state.clipActive -> "Vague en cours d'enregistrement" to SurfColors.Alert
        state.analysis?.riding == true -> "Vague détectée" to SurfColors.Alert
        !state.detectionEnabled ->
            (if (state.fullSession) "Session complète en cours d'enregistrement" else "Session en cours · détection désactivée") to SurfColors.Ok
        state.analysisStatus?.startsWith("Détection indisponible") == true -> "Mode manuel · détection indisponible" to SurfColors.Warn
        state.analysisStatus?.startsWith("Analyse arrêtée") == true -> "Détection arrêtée (voir le détail)" to SurfColors.Alert
        state.analysisStatus == "Chargement du modèle…" -> "Chargement de la détection…" to SurfColors.Warn
        state.analysisReceived == 0 -> "Détection : aucune image reçue de la caméra" to SurfColors.Warn
        state.analysis == null -> "Détection prête · en attente d'analyse" to SurfColors.Warn
        else -> "En veille · détection active" to SurfColors.Ok
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
// Panneau de commandes : tout tient sans défilement. Le détail technique est dans le panneau « Infos ».
// ---------------------------------------------------------------------------------------------

@Composable
private fun ControlPanel(
    state: EngineState,
    running: Boolean,
    options: SessionOptions,
    onOptionsChange: (SessionOptions) -> Unit,
    engine: RecordingEngine,
    onOpenLibrary: () -> Unit,
    onOpenInfo: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    Column(
        modifier = Modifier.width(300.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SurfCam", color = SurfColors.Aqua, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SmallButton("Vidéos", onOpenLibrary)
            SmallButton("Infos", onOpenInfo)
        }

        if (running) {
            OutlinedButton(onClick = engine::relock, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                Text(
                    if (state.locked) "Exposition verrouillée · relancer" else "Exposition en réglage…",
                    color = if (state.locked) SurfColors.Ok else SurfColors.Warn,
                    fontSize = 13.sp,
                )
            }
        } else {
            SetupGrid(options, onOptionsChange, engine)
        }

        Spacer(Modifier.weight(1f))

        if (running) {
            OutlinedButton(
                onClick = onStopSession,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                border = BorderStroke(1.5.dp, SurfColors.Alert),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SurfColors.Alert),
            ) { Text("Arrêter la session", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        } else {
            Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("Démarrer la session", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
private fun Toggle(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SurfColors.Aqua, selectedLabelColor = SurfColors.OnAqua),
    )
}

/** Réglages d'avant session, en quatre lignes courtes. Les explications sont dans « Infos ». */
@Composable
private fun SetupGrid(options: SessionOptions, onOptionsChange: (SessionOptions) -> Unit, engine: RecordingEngine) {
    var storages by remember { mutableStateOf(engine.storage.available()) }
    LaunchedEffect(Unit) {
        // Sondé toutes les 3 s : brancher ou retirer une carte SD / une clé USB se voit sans rien faire.
        while (true) {
            storages = withContext(Dispatchers.IO) { engine.storage.available() }
            delay(3_000)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Toggle("4K", !options.hd, Modifier.weight(1f)) { onOptionsChange(options.copy(hd = false)) }
        Toggle("1080p", options.hd, Modifier.weight(1f)) { onOptionsChange(options.copy(hd = true)) }
        Toggle("30 i/s", !options.fps60, Modifier.weight(1f)) { onOptionsChange(options.copy(fps60 = false)) }
        Toggle("60 i/s", options.fps60, Modifier.weight(1f)) { onOptionsChange(options.copy(fps60 = true)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Toggle("Session complète", options.fullSession, Modifier.weight(1f)) { onOptionsChange(options.copy(fullSession = !options.fullSession)) }
        Toggle("Détection", options.detection, Modifier.weight(1f)) { onOptionsChange(options.copy(detection = !options.detection)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Toggle("Écran noir après 1 min", options.sleepScreen, Modifier.weight(1f)) { onOptionsChange(options.copy(sleepScreen = !options.sleepScreen)) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        storages.forEach { choice ->
            Toggle(
                choice.label + (choice.freeBytes?.let { " · ${it / 1_000_000_000L} Go" } ?: ""),
                options.storage == choice.name,
            ) { onOptionsChange(options.copy(storage = choice.name)) }
        }
    }
    val chosen = storages.firstOrNull { it.name == options.storage }
    val freeGb = (chosen ?: storages.firstOrNull())?.freeBytes?.let { it / 1_000_000_000L }
    val line = when {
        chosen == null -> "Support absent : la session ira sur le téléphone"
        freeGb == null -> "Espace libre inconnu"
        options.fullSession && options.gigabytesPerHour() > 0 ->
            "$freeGb Go libres · environ ${freeGb / options.gigabytesPerHour()} h de session complète"
        else -> "$freeGb Go libres"
    }
    val warn = chosen == null || (options.fullSession && freeGb != null && freeGb < 2L * options.gigabytesPerHour())
    Text(line, color = if (warn) SurfColors.Warn else SurfColors.Muted, fontSize = 11.sp, maxLines = 1)
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
private fun InfoDialog(
    state: EngineState,
    options: SessionOptions,
    onOptionsChange: (SessionOptions) -> Unit,
    engine: RecordingEngine,
    running: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.92f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Infos et réglages avancés", color = SurfColors.Aqua, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("Fermer") }
                }
                Row(Modifier.weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Section("Aide") {
                            Help("Zoom", "Pince l'image à deux doigts. Vers ×5 le téléphone passe sur le téléobjectif (30 i/s ou moins).")
                            Help("4K / 1080p", "La 4K sert à recadrer ensuite sans perte de netteté. Sans recadrage, 1080p suffit et chauffe moins.")
                            Help("30 / 60 i/s", "60 i/s est plus fluide mais exclut le téléobjectif.")
                            Help("Session complète", "Enregistre tout, en fichiers de 2 minutes. Sans elle, seuls les clips de vagues sont gardés.")
                            Help("Détection", "Repère les vagues et crée un clip : 5 s avant le décollage, 3 s après la fin.")
                        }
                        Section("Réglages avancés") {
                            OptionSwitch("Forcer le téléobjectif", "Champ fixe ×5, sans zoom", options.tele) {
                                if (!running) onOptionsChange(options.copy(tele = it))
                            }
                            if (running) Text("Modifiable seulement hors session.", color = SurfColors.Muted, fontSize = 11.sp)
                        }
                        LearningSection(engine)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.detectionEnabled && state.phase == Phase.RUNNING) DiagnosticSection(state)
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
                        state.lastClip?.let {
                            Section("Dernier clip") { Text(it, color = SurfColors.Foam, fontSize = 13.sp) }
                        }
                        details(state)?.let {
                            Section("Technique") { Text(it, color = SurfColors.Muted, fontSize = 12.sp, lineHeight = 17.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Help(title: String, text: String) {
    Column {
        Text(title, color = SurfColors.Foam, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = SurfColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
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
                                color = if (box.riding) SurfColors.Alert else SurfColors.Aqua,
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
private fun Section(title: String, content: @Composable () -> Unit) {
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
private fun OptionSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = SurfColors.Muted, fontSize = 11.sp, lineHeight = 14.sp)
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
    }
}

// ---------------------------------------------------------------------------------------------
// Rapport de plantage.
// ---------------------------------------------------------------------------------------------

@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit) {
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
