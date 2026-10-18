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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val running = state.phase == Phase.RUNNING || state.phase == Phase.STARTING

    MaterialTheme {
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
                Controls(state, running, engine, onStartSession, onStopSession)
            }
        }
    }
}

@Composable
private fun Controls(
    state: EngineState,
    running: Boolean,
    engine: RecordingEngine,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    Column(
        modifier = Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

            Text("Zoom", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f, 5f).filter { it >= state.minZoom && it <= state.maxZoom }.forEach { ratio ->
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
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.lastClip?.let { Text("Dernier clip : $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun statusText(state: EngineState): String = buildString {
    val config = state.config
    when (state.phase) {
        Phase.IDLE -> append("Session arrêtée")
        Phase.STARTING -> append("Démarrage…")
        Phase.ERROR -> append("Erreur")
        Phase.RUNNING -> {
            if (config != null) append("${config.width}×${config.height} à ${config.fps} i/s\n")
            append("Tampon : %.1f s (%d Mo)\n".format(state.bufferedSec, state.bufferedMb))
            append("Zoom ${"%.1f".format(state.zoom)}×")
        }
    }
    if (state.phase != Phase.IDLE) append("\nChauffe : ${thermalLabel(state.thermalStatus)}")
}

private fun thermalLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "normale"
    PowerManager.THERMAL_STATUS_LIGHT -> "légère"
    PowerManager.THERMAL_STATUS_MODERATE -> "modérée"
    PowerManager.THERMAL_STATUS_SEVERE -> "forte"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critique"
    else -> "extrême"
}
