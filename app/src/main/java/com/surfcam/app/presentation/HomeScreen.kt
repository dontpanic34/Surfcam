package com.surfcam.app.presentation

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.data.engine.RecordingEngine
import com.surfcam.app.data.engine.SessionOptions
import com.surfcam.app.data.engine.Subject
import com.surfcam.app.data.gimbal.GimbalHub
import com.surfcam.app.data.gimbal.HorizonLeveler
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.domain.detection.MarkerColor
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import com.surfcam.app.service.DoNotDisturb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class Health { OK, WARN, OFF }

/**
 * Accueil : on l'ouvre pour filmer, pas pour régler. Quatre voyants disent si tout est prêt, un seul gros bouton lance la
 * caméra, la qualité et « qui filmer » se choisissent d'un toucher. Le gimbal et les réglages fins s'ouvrent à part.
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
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showGimbal by remember { mutableStateOf(false) }
    // La caméra n'a pas à rester ouverte sur l'accueil.
    LaunchedEffect(running) { if (!running) engine.stopPreview() }
    val device by rememberDeviceStatus()
    val horizon by rememberHorizonAngle()
    val upright by rememberPhoneUpright()
    val link = remember { GimbalHub.link(context) }
    val gimbalState by link.state.collectAsStateWithLifecycle()
    var storages by remember { mutableStateOf(engine.storage.available()) }
    val dndAllowed by produceState(DoNotDisturb.isAllowed(context)) {
        while (true) { value = DoNotDisturb.isAllowed(context); delay(2_000) }
    }
    LaunchedEffect(running) {
        while (!running) {
            storages = withContext(Dispatchers.IO) { engine.storage.available() }
            delay(3_000)
        }
    }
    var sdDeclined by remember { mutableStateOf(setOf<String>()) }
    val sdOffer = if (running) null else storages.firstOrNull { it.removable && it.name != options.storage && it.name !in sdDeclined }
    val videoCount by produceState(0, running) { value = withContext(Dispatchers.IO) { VideoLibrary(context).list().count { !it.pending } } }
    var leveling by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Avant de filmer, si le gimbal est connecté : paysage (comme un double appui sur M), puis horizon à 0°. Ce qui ne répond pas
    // est noté pour ne plus faire attendre, et l'utilisateur est prévenu.
    val openCamera: () -> Unit = {
        val gp = context.getSharedPreferences("gimbal", android.content.Context.MODE_PRIVATE)
        val leveler = HorizonLeveler(link, gp)
        val needLandscape = upright == true && !gp.getBoolean("landscapeFailed", false)
        val angle = horizon
        val needLevel = angle != null && abs(angle) > 0.8f && !gp.getBoolean("rollFailed", false)
        if (gimbalState.connected && (needLandscape || needLevel)) {
            leveling = true
            notice = null
            scope.launch {
                if (needLandscape && !leveler.toLandscape { upright }) {
                    gp.edit().putBoolean("landscapeFailed", true).apply()
                    notice = "Le gimbal n'a pas tourné en paysage : fais un double appui sur son bouton M."
                }
                if (horizon?.let { abs(it) > 0.8f } == true && !gp.getBoolean("rollFailed", false)) {
                    leveler.run { horizon }
                    if (abs(leveler.lastEndDeg ?: 0f) > 1.5f) gp.edit().putBoolean("rollFailed", true).apply()
                }
                leveling = false
                onOpenCamera()
            }
        } else {
            onOpenCamera()
        }
    }

    val preset = if (options.hd) "eco" else if (options.hdr) "max" else "eq"
    val who = when {
        options.subject == Subject.ME -> "me"
        options.markerColor != null -> "priority"
        else -> "all"
    }
    val firstColor = options.markerColor ?: MarkerColor.ORANGE
    val qualityName = when (preset) { "max" -> "4K HDR"; "eco" -> "1080p"; else -> "4K" }
    val whoName = when (who) { "me" -> "seulement moi"; "priority" -> "moi d'abord"; else -> "tout le monde" }
    val gimbalName = if (gimbalState.connected) "gimbal connecté" else if (GimbalHub.hasSavedGimbal(context)) "gimbal éteint" else "sans gimbal"

    SurfCamTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        if (sdOffer != null) {
            val free = sdOffer.freeBytes?.let { " (${it / 1_000_000_000L} Go libres)" } ?: ""
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { sdDeclined = sdDeclined + sdOffer.name },
                title = { Text("Carte SD détectée") },
                text = { Text("Enregistrer les vidéos sur « ${sdOffer.label} »$free au lieu de la mémoire du téléphone ?") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { sdDeclined = sdDeclined + sdOffer.name; onOptionsChange(options.copy(storage = sdOffer.name)) }) { Text("Oui, sur la carte") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { sdDeclined = sdDeclined + sdOffer.name }) { Text("Non") } },
            )
        }
        if (showSettings) InfoDialog(state, options, onOptionsChange, engine, running, onOpenGimbal = { showSettings = false; onOpenGimbal() }) { showSettings = false }
        if (showGimbal) {
            Dialog(onDismissRequest = { showGimbal = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.94f)) {
                    Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GimbalCard()
                        androidx.compose.material3.OutlinedButton(onClick = { showGimbal = false }, modifier = Modifier.fillMaxWidth()) { Text("Fermer") }
                    }
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // En-tête : le nom, et l'accès aux réglages.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(SurfColors.Aqua, Color(0xFF1B7F8A)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(16.dp)) {
                                val p = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(0f, size.height * 0.62f)
                                    cubicTo(size.width * 0.15f, size.height * 0.2f, size.width * 0.3f, size.height * 0.2f, size.width * 0.5f, size.height * 0.62f)
                                    cubicTo(size.width * 0.7f, size.height * 1.0f, size.width * 0.85f, size.height * 1.0f, size.width, size.height * 0.62f)
                                }
                                drawPath(p, SurfColors.OnAqua, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                        Text("SurfCam", color = SurfColors.Foam, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    RoundButton(40.dp, onClick = { showSettings = true }) { tint ->
                        drawCircle(tint, radius = 5.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                        for (k in 0 until 8) {
                            val a = k * (Math.PI / 4).toFloat()
                            drawLine(
                                tint,
                                Offset(center.x + 6.5.dp.toPx() * kotlin.math.cos(a), center.y + 6.5.dp.toPx() * kotlin.math.sin(a)),
                                Offset(center.x + 9.dp.toPx() * kotlin.math.cos(a), center.y + 9.dp.toPx() * kotlin.math.sin(a)),
                                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Butt,
                            )
                        }
                    }
                }

                // Quatre voyants : tout est-il prêt ?
                val battery = device?.batteryPercent ?: -1
                val freeGb = (storages.firstOrNull { it.name == options.storage } ?: storages.firstOrNull())?.freeBytes?.let { it / 1_000_000_000L }
                val perHour = options.gigabytesPerHour().coerceAtLeast(1)
                val silent = options.doNotDisturb && dndAllowed
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Light(
                        Modifier.weight(1f), if (battery in 0..19 && device?.charging != true) Health.WARN else Health.OK,
                        if (battery >= 0) "$battery %" else "—", "Batterie",
                    ) { tint ->
                        drawRoundRect(tint, Offset(size.width * 0.05f, size.height * 0.25f), Size(size.width * 0.8f, size.height * 0.5f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.8.dp.toPx()))
                        drawLine(tint, Offset(size.width * 0.93f, size.height * 0.42f), Offset(size.width * 0.93f, size.height * 0.58f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    Light(
                        Modifier.weight(1f), if (freeGb != null && freeGb < 3) Health.WARN else Health.OK,
                        freeGb?.let { "$it Go" } ?: "—", freeGb?.let { "≈ ${(it * 60 / perHour).coerceAtLeast(1)} min" } ?: "Place",
                        onClick = {
                            // Plusieurs supports (téléphone, carte SD, clé USB) : un toucher passe au suivant.
                            if (storages.size > 1) {
                                val next = storages[(storages.indexOfFirst { it.name == options.storage } + 1).mod(storages.size)]
                                onOptionsChange(options.copy(storage = next.name))
                            }
                        },
                    ) { tint ->
                        drawRoundRect(tint, Offset(size.width * 0.12f, size.height * 0.2f), Size(size.width * 0.76f, size.height * 0.6f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.8.dp.toPx()))
                        drawLine(tint, Offset(size.width * 0.3f, size.height * 0.2f), Offset(size.width * 0.3f, size.height * 0.42f), strokeWidth = 1.8.dp.toPx())
                    }
                    Light(
                        Modifier.weight(1f), if (gimbalState.connected) Health.OK else Health.OFF,
                        if (gimbalState.connected) (gimbalState.battery?.let { "$it %" } ?: "ok") else "—", if (gimbalState.connected) "Gimbal" else "Gimbal éteint",
                        onClick = { showGimbal = true },
                    ) { tint ->
                        drawCircle(tint, size.width * 0.18f, center, style = Stroke(1.8.dp.toPx()))
                        drawLine(tint, Offset(center.x, size.height * 0.05f), Offset(center.x, size.height * 0.25f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(tint, Offset(center.x, size.height * 0.75f), Offset(center.x, size.height * 0.95f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.05f, center.y), Offset(size.width * 0.25f, center.y), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(tint, Offset(size.width * 0.75f, center.y), Offset(size.width * 0.95f, center.y), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    }
                    Light(
                        Modifier.weight(1f), if (silent) Health.OK else Health.WARN,
                        "Silence", if (silent) "activé" else "à activer",
                        onClick = {
                            val turnOn = !options.doNotDisturb
                            onOptionsChange(options.copy(doNotDisturb = turnOn))
                            // Première fois : Android demande d'autoriser l'appli dans ses réglages.
                            if (turnOn && !DoNotDisturb.isAllowed(context)) {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                    ) { tint ->
                        drawCircle(tint, size.width * 0.34f, center, style = Stroke(1.8.dp.toPx()))
                        drawLine(tint, Offset(size.width * 0.3f, center.y), Offset(size.width * 0.7f, center.y), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    }
                }

                // Le geste principal.
                if (running) {
                    BigButton("Retourner à la caméra", "Session en cours", enabled = true, onClick = onOpenCamera)
                } else {
                    BigButton(if (leveling) "Redressement du gimbal…" else "Filmer", "$qualityName · $whoName · $gimbalName", enabled = !leveling, onClick = openCamera)
                    notice?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
                }

                // Qualité d'un toucher.
                Heading("Qualité")
                Segmented(
                    listOf(Triple("eq", "Équilibré", "4K"), Triple("max", "Maximale", "4K HDR"), Triple("eco", "Longue durée", "1080p")),
                    preset, enabled = !running,
                ) { id ->
                    val next = when (id) {
                        "max" -> options.copy(hd = false, fps60 = false, hdr = true)
                        "eco" -> options.copy(hd = true, fps60 = false, hdr = false)
                        else -> options.copy(hd = false, fps60 = false, hdr = false)
                    }
                    if (next.hdr) context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit().putBoolean("hdr_broken", false).apply()
                    onOptionsChange(next)
                }

                // Qui filmer.
                Heading("Qui filmer")
                Segmented(
                    listOf(Triple("all", "Tout le monde", ""), Triple("priority", "Moi d'abord", ""), Triple("me", "Seulement moi", "")),
                    who, enabled = !running,
                ) { id ->
                    onOptionsChange(
                        when (id) {
                            "me" -> options.copy(subject = Subject.ME, markerColor = firstColor)
                            "priority" -> options.copy(subject = Subject.EVERYONE, markerColor = firstColor)
                            else -> options.copy(subject = Subject.EVERYONE, markerColor = null)
                        },
                    )
                }
                if (who != "all") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Couleur de mon manchon", color = SurfColors.Muted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in MarkerColor.entries) {
                                val tone = when (c) { MarkerColor.YELLOW -> Color(0xFFE4FF3B); MarkerColor.GREEN -> Color(0xFFA9FF45); MarkerColor.ORANGE -> Color(0xFFFF7A2A) }
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(tone)
                                        .border(if (options.markerColor == c) 3.dp else 0.dp, SurfColors.Foam, CircleShape)
                                        .clickable { onOptionsChange(options.copy(markerColor = c)) },
                                )
                            }
                        }
                    }
                }

                // Vidéos.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenLibrary).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Mes vidéos", color = SurfColors.Foam, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(if (videoCount == 0) "Rien d'enregistré pour l'instant" else "$videoCount vidéo${if (videoCount > 1) "s" else ""} · vagues, sessions, films", color = SurfColors.Muted, fontSize = 13.sp)
                    }
                    Text("›", color = SurfColors.Aqua, fontSize = 26.sp)
                }

                Text(
                    "Réglages avancés",
                    color = SurfColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { showSettings = true }.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), color = SurfColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun RoundButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color(0xFF27414F), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.55f)) { draw(SurfColors.Foam) } }
}

/** Un voyant de l'état « prêt » : icône, valeur, légende ; bord orange si un souci, pointillé si éteint. */
@Composable
private fun Light(modifier: Modifier, health: Health, value: String, caption: String, onClick: (() -> Unit)? = null, icon: DrawScope.(Color) -> Unit) {
    val border = when (health) { Health.OK -> Color(0x733DDC97); Health.WARN -> SurfColors.Warn; Health.OFF -> Color(0xFF27414F) }
    val tint = if (health == Health.WARN) SurfColors.Warn else SurfColors.Foam
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, border, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(22.dp)) { icon(tint) }
        Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(caption, color = SurfColors.Muted, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BigButton(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(if (enabled) listOf(SurfColors.Aqua, Color(0xFF1FB7C7)) else listOf(Color(0xFF2B5B5A), Color(0xFF24474F))))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).border(3.dp, SurfColors.OnAqua, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(SurfColors.OnAqua))
        }
        Column {
            Text(title, color = SurfColors.OnAqua, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = SurfColors.OnAqua.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

/** Choix exclusif sur une ligne : le choisi est plein. */
@Composable
private fun Segmented(items: List<Triple<String, String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Color(0xFF27414F), RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((id, title, sub) in items) {
            val on = id == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) SurfColors.Aqua else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = if (on) SurfColors.OnAqua else SurfColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (sub.isNotEmpty()) Text(sub, color = if (on) SurfColors.OnAqua.copy(alpha = 0.8f) else SurfColors.Muted, fontSize = 11.sp)
            }
        }
    }
}
