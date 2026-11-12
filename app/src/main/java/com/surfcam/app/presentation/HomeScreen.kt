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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
    onOpenRejects: () -> Unit = {},
    onOpenMine: () -> Unit = {},
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
    // Vérification réelle de l'écriture sur le support choisi (une carte « branchée » peut refuser d'écrire).
    val external = options.storage != com.surfcam.app.data.storage.StorageTarget.PHONE
    var storageProblem by remember { mutableStateOf<String?>(null) }
    var storageChecking by remember { mutableStateOf(false) }
    var recheck by remember { mutableStateOf(0) }
    var showSdBlock by remember { mutableStateOf(false) }
    LaunchedEffect(options.storage, recheck, storages.any { it.name == options.storage }, running) {
        if (running || !external) { storageProblem = null; return@LaunchedEffect }
        storageChecking = true
        storageProblem = if (storages.none { it.name == options.storage }) "la carte n'est pas branchée ou pas montée"
        else withContext(Dispatchers.IO) { engine.storage.testWrite(options.storage) }
        storageChecking = false
    }
    val exporter = remember { com.surfcam.app.data.library.Exporter(context) }
    var showExportAll by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    val waitingForCard by produceState(0) { while (true) { value = com.surfcam.app.data.storage.SdMover.pendingCount(context); delay(3_000) } }
    var sdDeclined by remember { mutableStateOf(setOf<String>()) }
    val sdOffer = if (running) null else storages.firstOrNull { it.removable && it.name != options.storage && it.name !in sdDeclined }
    val rejectCount by produceState(0, running) { value = withContext(Dispatchers.IO) { VideoLibrary(context).listRejected().size } }
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
        val needLevel = angle != null && abs(angle) > 0.3f
        if (gimbalState.connected && (needLandscape || needLevel)) {
            leveling = true
            notice = null
            scope.launch {
                if (needLandscape && !leveler.toLandscape { upright }) {
                    gp.edit().putBoolean("landscapeFailed", true).apply()
                    notice = "Le gimbal n'a pas tourné en paysage : fais un double appui sur son bouton M."
                }
                if (horizon?.let { abs(it) > 0.3f } == true) {
                    val lines = ArrayList<String>()
                    notice = leveler.run({ horizon }, log = { lines += it })
                    gp.edit().putString("lastLeveling", lines.joinToString("\n")).apply()
                }
                leveling = false
                onOpenCamera()
            }
        } else {
            onOpenCamera()
        }
    }

    val qualityName = "1080p 30 i/s · ×5 optique"
    val gimbalName = if (gimbalState.connected) "gimbal connecté" else if (GimbalHub.hasSavedGimbal(context)) "gimbal éteint" else "sans gimbal"

    SurfCamTheme {
        if (crashReport != null) CrashDialog(crashReport, onDismissCrash)
        if (showSdBlock) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSdBlock = false },
                title = { Text(if (storageChecking) "Vérification de la carte…" else "La carte n'est pas prête") },
                text = { Text(if (storageChecking) "Patiente un instant, puis réessaie." else "Impossible d'écrire sur le support choisi : ${storageProblem ?: "inconnu"}.\n\nSi tu filmes quand même, les vidéos iront sur la mémoire du téléphone.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showSdBlock = false
                        openCamera()
                    }) { Text("Filmer quand même") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showSdBlock = false; recheck++ }) { Text("Réessayer") }
                },
            )
        }
        if (showDiag) StorageDiagDialog(volumeName = storages.firstOrNull { it.name == options.storage && external }?.name ?: storages.firstOrNull { it.removable }?.name, onClose = { showDiag = false })
        if (sdOffer != null) {
            val free = sdOffer.freeBytes?.let { " (${it / 1_000_000_000L} Go libres)" } ?: ""
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { sdDeclined = sdDeclined + sdOffer.name },
                title = { Text("Carte SD détectée") },
                text = { Text("Enregistrer les vidéos sur « ${sdOffer.label} »$free au lieu de la mémoire du téléphone ?") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val chosen = sdOffer
                        sdDeclined = sdDeclined + chosen.name
                        onOptionsChange(options.copy(storage = chosen.name))
                        notice = "Test de la vitesse d'écriture de « ${chosen.label} »…"
                        scope.launch {
                            val speed = withContext(Dispatchers.IO) { engine.storage.measureWriteSpeed(chosen.name) }
                            notice = when {
                                speed == null -> "Impossible de mesurer la vitesse de « ${chosen.label} »."
                                speed < 25 -> "« ${chosen.label} » écrit à %.0f Mo/s : trop lent pour la 4K (il faut au moins 25 Mo/s avec la session complète). Les vidéos risquent de basculer sur le téléphone.".format(speed)
                                else -> "« ${chosen.label} » écrit à %.0f Mo/s : suffisant.".format(speed)
                            }
                        }
                    }) { Text("Oui, sur la carte") }
                },
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

                if (!running && external) {
                    val sdLabel = storages.firstOrNull { it.name == options.storage }?.label ?: "Carte SD"
                    val ok = !storageChecking && storageProblem == null
                    Text(
                        when {
                            storageChecking -> "Vérification de l'écriture sur « $sdLabel »…"
                            ok -> "✓ « $sdLabel » : écriture vérifiée"
                            else -> "⚠ « $sdLabel » : $storageProblem · toucher pour réessayer"
                        },
                        color = if (ok) SurfColors.Ok else SurfColors.Warn, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { recheck++ },
                    )
                }

                if (waitingForCard > 0) {
                    Text("$waitingForCard fichier(s) en cours d'envoi vers la carte (ils restent sur le téléphone tant que la copie n'est pas vérifiée)", color = SurfColors.Warn, fontSize = 12.sp)
                }
                if (!running && (external || storages.any { it.removable })) {
                    Text(
                        "Diagnostic de la carte SD (état, test d'écriture, journal)",
                        color = SurfColors.Aqua, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { showDiag = true }.padding(vertical = 4.dp),
                    )
                }

                // Le geste principal.
                if (running) {
                    BigButton("Retourner à la caméra", "Session en cours", enabled = true, onClick = onOpenCamera)
                } else {
                    BigButton(if (leveling) "Redressement du gimbal…" else "Filmer", "$qualityName · tout le monde · $gimbalName", enabled = !leveling, onClick = { if (external && (storageChecking || storageProblem != null)) showSdBlock = true else openCamera() })
                    notice?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
                }

                Heading("Après la session")
                ListCard {
                    if (!running) ListRow("Trier les vagues", "Dire qui surfe chaque vague", "›", onClick = onOpenMine)
                    ListRow("Mes vidéos", if (videoCount == 0) "Rien d'enregistré pour l'instant" else "Vagues, sessions", if (videoCount > 0) "$videoCount ›" else "›", onClick = onOpenLibrary)
                    if (!running && rejectCount > 0) ListRow("Vagues rejetées", "Récupérer les bonnes", "$rejectCount ›", onClick = onOpenRejects)
                    if (!running) ListRow("Exporter une journée", "Vers le dossier choisi (Drive)", "›", onClick = { showExportAll = true })
                }
                if (showExportAll) ExportDayDialog(exporter) { showExportAll = false }

                Heading("Pendant la session")
                ListCard {
                    SwitchRow("Garder toute la session", "≈ ${options.gigabytesPerHour()} Go par heure", options.fullSession, enabled = !running) { onOptionsChange(options.copy(fullSession = it)) }
                    SwitchRow("Écran noir après 1 min", "Économise la batterie", options.sleepScreen) { onOptionsChange(options.copy(sleepScreen = it)) }
                    SwitchRow("Flash clignotant", "Pour voir de l'eau que ça filme", options.ledBlink) { onOptionsChange(options.copy(ledBlink = it)) }
                    SwitchRow("Témoin large (à l'essai)", "Filme tout le pic en même temps, pour compter les vagues · ≈ 3,6 Go/h", options.wideWitness, enabled = !running) {
                        onOptionsChange(options.copy(wideWitness = it))
                    }
                    SwitchRow("Image HDR 10 bits (à l'essai)", "Comme l'appli Google : moins de bruit, plus de nuances", options.hdr, enabled = !running) {
                        // Un refus passé de la caméra est oublié : on lui redonne sa chance à chaque fois qu'on rallume l'option.
                        if (it) context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit().putBoolean("hdr_broken", false).apply()
                        onOptionsChange(options.copy(hdr = it))
                    }
                }
                Text("1080p · 30 images/s · téléobjectif ×5 optique, sans zoom numérique", color = SurfColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

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
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, SurfColors.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.55f)) { draw(SurfColors.Foam) } }
}

/** Une carte qui regroupe des lignes, séparées par un filet. */
@Composable
private fun ListCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}

/** Une ligne de liste : titre, précision, et à droite un compteur ou un chevron. */
@Composable
private fun ListRow(title: String, detail: String, trailing: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SurfColors.Foam, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = SurfColors.Muted, fontSize = 12.sp)
        }
        Text(trailing, color = SurfColors.Muted, fontSize = 15.sp)
    }
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(SurfColors.Line.copy(alpha = 0.6f)))
}

/** Une ligne de liste avec un interrupteur. */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) SurfColors.Foam else SurfColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = SurfColors.Muted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(SurfColors.Line.copy(alpha = 0.6f)))
}

/** Un voyant de l'état « prêt » : icône, valeur, légende ; bord orange s'il y a un souci, estompé s'il est éteint. */
@Composable
private fun Light(modifier: Modifier, health: Health, value: String, caption: String, onClick: (() -> Unit)? = null, icon: DrawScope.(Color) -> Unit) {
    val border = if (health == Health.WARN) SurfColors.Warn else Color.Transparent
    val tint = when (health) { Health.WARN -> SurfColors.Warn; Health.OFF -> SurfColors.Muted; Health.OK -> SurfColors.Foam }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, border, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(20.dp)) { icon(tint) }
        Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(caption, color = SurfColors.Muted, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Le geste principal : un grand bouton clair, avec le rond rouge d'enregistrement à droite. */
@Composable
private fun BigButton(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val ground = if (enabled) SurfColors.Foam else SurfColors.Card
    val ink = if (enabled) SurfColors.Night else SurfColors.Muted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ground)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = ink.copy(alpha = 0.72f), fontSize = 13.sp)
        }
        Box(Modifier.size(48.dp).border(3.dp, ink, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (enabled) SurfColors.Alert else SurfColors.Muted))
        }
    }
}

/** Choix exclusif sur une ligne : le choisi est plein. */
@Composable
private fun Segmented(items: List<Triple<String, String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, SurfColors.Line, RoundedCornerShape(16.dp)).padding(4.dp),
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

/**
 * Exporter une journée : on choisit le dossier (une fois), puis un jour dans la liste. Tout ce que l'appli a enregistré ce
 * jour-là part dans le dossier : rapports, journaux, vagues, rejets, sessions. Rien n'est supprimé du téléphone ; un fichier
 * déjà présent et complet dans le dossier n'est pas renvoyé.
 */
@Composable
private fun ExportDayDialog(exporter: com.surfcam.app.data.library.Exporter, onClose: () -> Unit) {
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(exporter.folderLabel()) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var days by remember { mutableStateOf<List<com.surfcam.app.data.library.Exporter.DaySummary>?>(null) }
    LaunchedEffect(Unit) { days = withContext(Dispatchers.IO) { try { exporter.days() } catch (_: Exception) { emptyList() } } }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { exporter.setFolder(uri); folder = exporter.folderLabel() }
    }
    val dayFormat = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.FRENCH) }
    fun gigabytes(bytes: Long) = if (bytes >= 1_000_000_000L) "%.1f Go".format(bytes / 1e9).replace('.', ',') else "${bytes / 1_000_000L} Mo"
    // Suppression d'un jour : jour choisi, contenu par groupe, groupes cochés, et deuxième appui pour confirmer.
    var deleteDay by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var deleteContent by remember { mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap()) }
    var deleteGroups by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        message = if (r.resultCode == android.app.Activity.RESULT_OK) "Suppression confirmée." else "Suppression des vidéos restantes annulée."
        scope.launch { days = withContext(Dispatchers.IO) { try { exporter.days() } catch (_: Exception) { emptyList() } } }
    }
    fun openDelete(day: java.time.LocalDate) {
        deleteDay = day
        confirmDelete = false
        message = null
        scope.launch {
            deleteContent = withContext(Dispatchers.IO) { exporter.dayContent(day) }
            deleteGroups = emptySet()
        }
    }
    fun runDelete() {
        val day = deleteDay ?: return
        if (working || deleteGroups.isEmpty()) return
        working = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { exporter.deleteDay(day, deleteGroups) }
            message = "${day.format(dayFormat)} : ${r.deleted} fichier(s) supprimé(s) du téléphone, ${gigabytes(r.freedBytes)} libérés" +
                (if (r.failed > 0) ", ${r.failed} échec(s)" else "") + ". Le Drive n'a pas été touché."
            if (r.needConsent.isNotEmpty()) {
                // Vidéos créées par une installation précédente de l'appli : Android demande l'accord une fois pour toutes.
                try {
                    val sender = android.provider.MediaStore.createDeleteRequest(appContext.contentResolver, r.needConsent).intentSender
                    consent.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                } catch (_: Exception) {
                    message += " ${r.needConsent.size} vidéo(s) n'ont pas pu être supprimées."
                }
            }
            days = withContext(Dispatchers.IO) { try { exporter.days() } catch (_: Exception) { emptyList() } }
            deleteDay = null
            confirmDelete = false
            working = false
        }
    }
    fun run(day: java.time.LocalDate) {
        if (working) return
        working = true
        message = null
        scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    val items = exporter.everything(day)
                    if (items.isEmpty()) "Rien à exporter pour ce jour."
                    else {
                        val r = exporter.exportAll(items) { i, n, name -> progress = "${i + 1} sur $n · $name" }
                        buildString {
                            append("${day.format(dayFormat)} : ${r.sent} fichier(s) copié(s), ${r.skipped} déjà présent(s)")
                            if (r.failed.isNotEmpty()) {
                                append(", ${r.failed.size} échec(s) : ${r.failed.take(2).joinToString(" ; ")}")
                                if (r.failed.any { it.contains("ENOSPC") }) append(". Le téléphone est plein (l'appli Drive garde une copie en attente d'envoi) : laisse-la finir d'envoyer, puis relance, les fichiers déjà partis ne seront pas renvoyés")
                            }
                            append(". Rien n'a été supprimé du téléphone.")
                        }
                    }
                } catch (e: Exception) {
                    "Export impossible : ${e.javaClass.simpleName} ${e.message ?: ""}"
                }
            }
            working = false
            progress = ""
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text("Exporter une journée") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dossier d'export : ${folder ?: "pas encore choisi"}", fontSize = 13.sp)
                OutlinedButton(onClick = { pick.launch(null) }, enabled = !working) { Text(if (folder == null) "Choisir le dossier" else "Changer de dossier") }
                Text("Touche un jour pour le copier en entier dans le dossier. « Supprimer » efface des fichiers de ce jour du téléphone, jamais du Drive.", fontSize = 12.sp, color = SurfColors.Muted)
                val list = days
                when {
                    list == null -> Text("Recherche des journées…", fontSize = 12.sp, color = SurfColors.Muted)
                    list.isEmpty() -> Text("Rien n'a encore été enregistré.", fontSize = 12.sp, color = SurfColors.Muted)
                    deleteDay != null -> {
                        val day = deleteDay!!
                        Text("Supprimer du téléphone : ${day.format(dayFormat)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SurfColors.Foam)
                        Text("Coche ce que tu veux effacer. Le Drive et le dossier d'export ne sont pas touchés : vérifie d'abord que ce jour est bien exporté.", fontSize = 12.sp, color = SurfColors.Muted)
                        val groups = listOf(com.surfcam.app.data.library.Exporter.GROUP_SESSIONS, com.surfcam.app.data.library.Exporter.GROUP_REJECTS,
                            com.surfcam.app.data.library.Exporter.GROUP_WAVES, com.surfcam.app.data.library.Exporter.GROUP_REPORTS)
                        for (g in groups) {
                            val (n, bytes) = deleteContent[g] ?: (0 to 0L)
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = n > 0 && !working) {
                                    deleteGroups = if (g in deleteGroups) deleteGroups - g else deleteGroups + g
                                    confirmDelete = false
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(checked = g in deleteGroups, onCheckedChange = null, enabled = n > 0 && !working)
                                Text("$g · $n fichier(s) · ${gigabytes(bytes)}", fontSize = 13.sp, color = if (n > 0) SurfColors.Foam else SurfColors.Muted)
                            }
                        }
                        val total = deleteGroups.sumOf { deleteContent[it]?.second ?: 0L }
                        Button(
                            onClick = { if (confirmDelete) runDelete() else confirmDelete = true },
                            enabled = !working && deleteGroups.isNotEmpty(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SurfColors.Alert, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (confirmDelete) "Confirmer : effacer ${gigabytes(total)}" else "Supprimer la sélection", fontSize = 13.sp) }
                        OutlinedButton(onClick = { deleteDay = null; confirmDelete = false }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
                    }
                    else -> for (d in list) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { run(d.day) }, enabled = !working && folder != null, modifier = Modifier.weight(1f)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(d.day.format(dayFormat).replaceFirstChar { it.uppercase() }, fontSize = 14.sp)
                                    Text(
                                        "${d.waves} vague(s) · ${d.rejects} rejet(s) · ${d.sessions} fichier(s) de session · ${d.reports} rapport(s) · ${gigabytes(d.bytes)}",
                                        fontSize = 11.sp, color = SurfColors.Muted,
                                    )
                                }
                            }
                            androidx.compose.material3.TextButton(onClick = { openDelete(d.day) }, enabled = !working) {
                                Text("Supprimer", color = SurfColors.Alert, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (progress.isNotEmpty()) Text(progress, fontSize = 12.sp, color = SurfColors.Foam)
                message?.let { Text(it, fontSize = 12.sp, color = SurfColors.Warn) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose, enabled = !working) { Text("Fermer") } },
    )
}
