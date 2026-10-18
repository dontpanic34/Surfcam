package com.surfcam.app.presentation

import android.os.PowerManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
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
import com.surfcam.app.data.camera.Lens
import com.surfcam.app.data.engine.EngineState
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.data.engine.RecordingEngine
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.presentation.theme.SurfColors
import com.surfcam.app.presentation.theme.SurfCamTheme

@Composable
fun RecordingScreen(
    engine: RecordingEngine,
    tele: Boolean,
    hasCamera: Boolean,
    crashReport: String?,
    onDismissCrash: () -> Unit,
    onTeleChange: (Boolean) -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val running = state.phase == Phase.RUNNING || state.phase == Phase.STARTING

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
    LaunchedEffect(state.phase, tele, resumed, hasCamera) {
        val idle = state.phase == Phase.IDLE || state.phase == Phase.PREVIEW
        if (resumed && hasCamera && idle) {
            engine.startPreview(if (tele) Lens.TELE else Lens.MAIN)
        } else if (!resumed) {
            engine.stopPreview()
        }
    }

    SurfCamTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Viewfinder(engine, state, hasCamera, Modifier.weight(1f).fillMaxHeight())
                ControlPanel(state, running, tele, onTeleChange, engine, onStartSession, onStopSession)
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

        val (label, color) = statusPill(state)
        Pill(label, color, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val config = state.config
            if (state.phase == Phase.RUNNING && config != null) {
                InfoChip(resolutionLabel(config), SurfColors.Foam)
                InfoChip("%.0f s en mémoire".format(state.bufferedSec), SurfColors.Foam)
                state.analysis?.let { InfoChip("Analyse ${it.inferenceMs} ms", SurfColors.Foam) }
                InfoChip("Chauffe ${thermalLabel(state.thermalStatus)}", thermalColor(state.thermalStatus))
            }
        }

        state.lensInfo?.let {
            InfoChip(it, SurfColors.Muted, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
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
        state.analysisStatus?.startsWith("Détection indisponible") == true -> "Mode manuel · détection indisponible" to SurfColors.Warn
        state.analysisStatus == "Chargement du modèle…" -> "Chargement de la détection…" to SurfColors.Warn
        else -> "En veille · détection active" to SurfColors.Ok
    }
}

private fun resolutionLabel(config: CaptureConfig): String {
    val name = if (config.width >= 3840) "4K" else "${config.height}p"
    return "$name · ${config.fps} i/s"
}

// ---------------------------------------------------------------------------------------------
// Panneau de commandes.
// ---------------------------------------------------------------------------------------------

@Composable
private fun ControlPanel(
    state: EngineState,
    running: Boolean,
    tele: Boolean,
    onTeleChange: (Boolean) -> Unit,
    engine: RecordingEngine,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    Column(
        modifier = Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SurfCam", color = SurfColors.Aqua, fontSize = 26.sp, fontWeight = FontWeight.Bold)

        if (!running) {
            Section("Objectif · à choisir avant la session") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LensChoice("Principal", "4K · 60 i/s", !tele, { onTeleChange(false) }, Modifier.weight(1f))
                    LensChoice("Télé 5×", "4K · 30 i/s", tele, { onTeleChange(true) }, Modifier.weight(1f))
                }
                Text(
                    if (tele) "Plus de détail, champ étroit : à utiliser quand tu connais ta zone."
                    else "Grand champ : tu es plus petit à l'image, mais tu ne sors pas du cadre.",
                    color = SurfColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }

        if (running) {
            OutlinedButton(
                onClick = onStopSession,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = BorderStroke(1.5.dp, SurfColors.Alert),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SurfColors.Alert),
            ) { Text("Arrêter la session", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        } else {
            Button(
                onClick = onStartSession,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Démarrer la session", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }

        if (state.phase == Phase.RUNNING) {
            Section("Image") {
                Text("Zoom numérique", color = SurfColors.Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 2f).filter { it >= state.minZoom && it <= state.maxZoom }.forEach { ratio ->
                        FilterChip(
                            selected = state.zoom == ratio,
                            onClick = { engine.setZoom(ratio) },
                            label = { Text("${ratio.toInt()}×") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfColors.Aqua,
                                selectedLabelColor = SurfColors.OnAqua,
                            ),
                        )
                    }
                }
                OutlinedButton(onClick = engine::relock, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.locked) "Exposition verrouillée · relancer" else "Exposition en réglage…",
                        color = if (state.locked) SurfColors.Ok else SurfColors.Warn,
                    )
                }
            }

            Section("Test manuel · la détection automatique est aussi active") {
                if (state.clipActive) {
                    Button(
                        onClick = engine::finishClip,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfColors.Alert, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("Fin de vague · +${CaptureConfig.POST_ROLL_SEC} s") }
                } else {
                    Button(
                        onClick = engine::startClip,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("Début de vague · -${CaptureConfig.PRE_ROLL_SEC} s") }
                }
            }
        }

        state.message?.let {
            Text(
                it,
                color = SurfColors.Alert,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33FF6B6B))
                    .padding(12.dp),
            )
        }

        state.lastClip?.let {
            Section("Dernier clip") {
                Text(it, color = SurfColors.Foam, fontSize = 13.sp)
                Text("Galerie · dossier SurfCam", color = SurfColors.Muted, fontSize = 12.sp)
            }
        }

        details(state)?.let {
            Text(it, color = SurfColors.Muted, fontSize = 11.sp, lineHeight = 16.sp)
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
private fun LensChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) SurfColors.Aqua else Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = if (selected) SurfColors.OnAqua else SurfColors.Foam,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        Text(subtitle, color = if (selected) SurfColors.OnAqua else SurfColors.Muted, fontSize = 11.sp)
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
        state.analysisStatus?.let { append("\n$it") }
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
