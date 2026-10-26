package com.surfcam.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.surfcam.app.data.engine.EngineState
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.data.engine.RecordingEngine
import com.surfcam.app.data.engine.SessionOptions
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import com.surfcam.app.service.DoNotDisturb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Accueil, tenu en portrait comme on tient le téléphone : l'état « prêt à filmer », la qualité, ce que fait l'appli, et
 * l'accès aux vidéos. L'écran caméra, lui, reste en paysage sur le trépied.
 */
@Composable
fun HomeScreen(
    engine: RecordingEngine,
    options: SessionOptions,
    onOptionsChange: (SessionOptions) -> Unit,
    crashReport: String?,
    onDismissCrash: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenGimbal: () -> Unit,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val running = state.phase == Phase.RUNNING || state.phase == Phase.STARTING
    val context = LocalContext.current
    var advanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val horizon by rememberHorizonAngle()
    val gimbalState by com.surfcam.app.data.gimbal.GimbalHub.link(context).state.collectAsStateWithLifecycle()
    var leveling by remember { mutableStateOf(false) }
    // Redressement automatique avant de filmer : seulement si le gimbal est connecté, penché de plus de 0,8°, et si le roulis a déjà répondu.
    val openCamera: () -> Unit = {
        val gp = context.getSharedPreferences("gimbal", android.content.Context.MODE_PRIVATE)
        val angle = horizon
        if (gimbalState.connected && angle != null && abs(angle) > 0.8f && !gp.getBoolean("rollFailed", false)) {
            leveling = true
            scope.launch {
                val leveler = com.surfcam.app.data.gimbal.HorizonLeveler(com.surfcam.app.data.gimbal.GimbalHub.link(context), gp)
                leveler.run { horizon }
                if (abs(leveler.lastEndDeg ?: 0f) > 1.5f) gp.edit().putBoolean("rollFailed", true).apply()
                leveling = false
                onOpenCamera()
            }
        } else {
            onOpenCamera()
        }
    }
    // La caméra n'a pas à rester ouverte sur l'accueil.
    LaunchedEffect(running) { if (!running) engine.stopPreview() }
    val device by rememberDeviceStatus()
    val freeGb by produceState<Long?>(null, running) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val volumes = engine.storage.available()
                (volumes.firstOrNull { it.name == options.storage } ?: volumes.firstOrNull())?.freeBytes?.let { it / 1_000_000_000L }
            }
            delay(5_000)
        }
    }

    SurfCamTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("SurfCam", color = SurfColors.Aqua, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)

                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF16404A), Color(0xFF0F2A36)))).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (running) {
                        Text("Session en cours", color = SurfColors.Ok, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("L'appli filme et repère les vagues toute seule.", color = SurfColors.Foam, fontSize = 14.sp)
                        Button(onClick = onOpenCamera, modifier = Modifier.fillMaxWidth()) { Text("Retourner à la caméra", fontWeight = FontWeight.Bold) }
                    } else {
                        Text("Prêt à filmer ?", color = SurfColors.Foam, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        val battery = device?.batteryPercent ?: -1
                        ReadyRow(
                            if (battery in 0..19) SurfColors.Warn else SurfColors.Ok, "Batterie",
                            if (battery >= 0) "$battery %" + if (device?.charging == true) " · en charge" else "" else "—",
                        )
                        val gb = freeGb
                        val perHour = options.gigabytesPerHour().coerceAtLeast(1)
                        ReadyRow(
                            if (gb != null && gb < 3) SurfColors.Warn else SurfColors.Ok, "Place libre",
                            if (gb != null) "$gb Go · environ ${(gb * 60 / perHour).coerceAtLeast(1)} min en session complète" else "—",
                        )
                        val silent = options.doNotDisturb && DoNotDisturb.isAllowed(context)
                        ReadyRow(if (silent) SurfColors.Ok else SurfColors.Warn, "Mode silence", if (silent) "activé pendant la session" else "à autoriser dans l'appli caméra")
                        Button(
                            onClick = openCamera,
                            enabled = !leveling,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfColors.Aqua, contentColor = SurfColors.OnAqua),
                        ) { Text(if (leveling) "Redressement du gimbal…" else "Ouvrir la caméra", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 6.dp)) }
                        Text("Pose le téléphone sur le trépied en paysage, puis touche le bouton rond.", color = SurfColors.Muted, fontSize = 12.sp)
                    }
                }

                QualitySection(options, running, onOptionsChange)
                GimbalCard()
                BehaviourSection(options, running, onOptionsChange)

                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenLibrary).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Mes vidéos", color = SurfColors.Foam, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Vagues, sessions, films à monter et à partager", color = SurfColors.Muted, fontSize = 13.sp)
                }

                AdvancedHeader(advanced) { advanced = !advanced }
                if (advanced) AdvancedSections(state, options, onOptionsChange, engine, running, onOpenGimbal)
            }
        }
    }
}

@Composable
private fun ReadyRow(color: Color, title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("●", color = color, fontSize = 14.sp)
        Text(title, color = SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = SurfColors.Muted, fontSize = 12.sp)
    }
}
