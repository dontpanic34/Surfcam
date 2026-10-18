package com.surfcam.app.presentation

import android.os.PowerManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.surfcam.app.data.camera.Lens
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.surfcam.app.data.engine.EngineState
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.data.engine.RecordingEngine
import com.surfcam.app.domain.model.CaptureConfig

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

    MaterialTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
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
                }
                Controls(state, running, tele, onTeleChange, engine, onStartSession, onStopSession)
            }
        }
    }
}

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

@Composable
private fun Controls(
    state: EngineState,
    running: Boolean,
    tele: Boolean,
    onTeleChange: (Boolean) -> Unit,
    engine: RecordingEngine,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    Column(
        modifier = Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!running) {
            Text("Objectif (choisi avant la session)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !tele, onClick = { onTeleChange(false) }, label = { Text("Principal") })
                FilterChip(selected = tele, onClick = { onTeleChange(true) }, label = { Text("Télé 5× optique") })
            }
        }
        if (running) {
            OutlinedButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) { Text("Arrêter la session") }
        } else {
            Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) { Text("Démarrer la session") }
        }

        if (state.phase == Phase.RUNNING) {
            if (state.clipActive) {
                Button(onClick = engine::finishClip, modifier = Modifier.fillMaxWidth()) {
                    Text("Fin de vague (+${CaptureConfig.POST_ROLL_SEC} s)")
                }
            } else {
                Button(onClick = engine::startClip, modifier = Modifier.fillMaxWidth()) {
                    Text("Début de vague (-${CaptureConfig.PRE_ROLL_SEC} s)")
                }
            }

            Text("Zoom numérique", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f).filter { it >= state.minZoom && it <= state.maxZoom }.forEach { ratio ->
                    FilterChip(
                        selected = state.zoom == ratio,
                        onClick = { engine.setZoom(ratio) },
                        label = { Text("${ratio.toInt()}×") },
                    )
                }
            }
            OutlinedButton(onClick = engine::relock, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.locked) "Exposition verrouillée · relancer" else "Exposition en réglage…")
            }
        }

        Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
        state.lensInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.lastClip?.let { Text("Dernier clip : $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun statusText(state: EngineState): String = buildString {
    val config = state.config
    when (state.phase) {
        Phase.IDLE -> append("Session arrêtée")
        Phase.PREVIEW -> append("Aperçu : rien n'est enregistré")
        Phase.STARTING -> append("Démarrage…")
        Phase.ERROR -> append("Erreur")
        Phase.RUNNING -> {
            if (config != null) {
                append("${config.width}×${config.height} à ${config.fps} i/s")
                if (state.maxFpsAtSize in 1 until 59) append(" (max ${state.maxFpsAtSize} avec cet objectif)")
                append("\n")
            }
            append("Tampon : %.1f s (%d Mo)\n".format(state.bufferedSec, state.bufferedMb))
            append("Zoom ${"%.1f".format(state.zoom)}×")
        }
    }
    if (state.phase != Phase.IDLE && state.phase != Phase.PREVIEW) append("\nChauffe : ${thermalLabel(state.thermalStatus)}")
}

private fun thermalLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "normale"
    PowerManager.THERMAL_STATUS_LIGHT -> "légère"
    PowerManager.THERMAL_STATUS_MODERATE -> "modérée"
    PowerManager.THERMAL_STATUS_SEVERE -> "forte"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critique"
    else -> "extrême"
}
