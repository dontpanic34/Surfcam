package com.surfcam.app.presentation

import android.graphics.Bitmap
import com.surfcam.app.data.library.FavoritesStore
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.engine.SessionStats
import com.surfcam.app.data.engine.SessionStatsStore
import com.surfcam.app.data.learning.FeedbackRecord
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.data.library.SessionAssembler
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.data.reframe.PathStore
import com.surfcam.app.domain.library.MediaInfo
import com.surfcam.app.domain.library.SessionGroup
import com.surfcam.app.domain.library.SessionGrouper
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Une journée : le nombre de vidéos et leur taille, pour l'en-tête repliable. */
private data class DayGroup(val date: LocalDate, val count: Int, val sizeBytes: Long)

/** Ce que le lecteur doit jouer : une liste (morceaux d'une session, vagues…), par où commencer, et les chapitres du film. */
private class PlayRequest(
    val keys: List<String>,
    val startKey: String,
    val autoAdvance: Boolean = false,
    val chapters: List<Long> = emptyList(),
)

/** Le montage ouvert : les vagues proposées (dans l'ordre du temps) et son identité pour retenir les choix. */
private class MontageRequest(val id: String, val title: String, val keys: List<String>)

private fun dateOfMs(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun clock(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "${t.hour} h ${"%02d".format(t.minute)}"
}

private fun startOf(video: LibraryVideo): Long =
    VideoNaming.parse(video.name)?.dateTime?.toInstant()?.toEpochMilli() ?: (video.modifiedMs - video.durationMs)

private fun infoOf(video: LibraryVideo): MediaInfo {
    val parsed = VideoNaming.parse(video.name)
    val kind = when {
        parsed?.isMontage == true -> MediaInfo.Kind.MONTAGE
        parsed?.isFilm == true -> MediaInfo.Kind.FILM
        parsed?.isSession == true || video.isSession -> MediaInfo.Kind.PART
        else -> MediaInfo.Kind.WAVE
    }
    return MediaInfo(video.key, startOf(video), video.durationMs, kind, parsed?.number ?: 1)
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val month = date.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)
    return when (date) {
        today -> "Aujourd'hui · ${date.dayOfMonth} $month"
        today.minusDays(1) -> "Hier · ${date.dayOfMonth} $month"
        else -> "${date.dayOfMonth} $month ${date.year}"
    }
}

/**
 * Les vidéos enregistrées par l'app, rangées par jour. Une session est un film (ses morceaux de 2 minutes, assemblables
 * en un seul fichier) dont les vagues sont les chapitres, repérés par des traits sur une frise ; les vagues qui n'en font
 * pas partie sont listées seules.
 */
@Composable
fun LibraryScreen(library: VideoLibrary, learning: LearningRepository, sessionRunning: Boolean, onBack: () -> Unit) {
    var videos by remember { mutableStateOf<List<LibraryVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<List<LibraryVideo>?>(null) }
    var deleteText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Progression des copies et des assemblages en cours (0 à 1).
    val copying = remember { mutableStateMapOf<String, Float>() }
    val assembling = remember { mutableStateMapOf<Long, Float>() }
    val assemblers = remember { mutableMapOf<Long, SessionAssembler>() }
    var message by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Map<String, FeedbackRecord>>(emptyMap()) }
    var request by remember { mutableStateOf<PlayRequest?>(null) }
    var montage by remember { mutableStateOf<MontageRequest?>(null) }
    val paths = remember { PathStore(context) }
    val statsStore = remember { SessionStatsStore(context) }
    var sessionStats by remember { mutableStateOf<List<SessionStats>>(emptyList()) }
    // Jours et sessions dont l'état est l'inverse de l'état par défaut (aujourd'hui ouvert ; chapitres repliés).
    val favStore = remember { FavoritesStore(context) }
    var favs by remember { mutableStateOf(favStore.all()) }
    // Recherche, filtres et sélection.
    var tab by remember { mutableStateOf("all") }
    var dayFilter by remember { mutableStateOf<LocalDate?>(null) }
    var judge by remember { mutableStateOf("all") }
    var favOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val openChapters = remember { mutableStateListOf<Long>() }
    val picked = remember { mutableStateMapOf<Long, String>() }

    // Rafraîchi toutes les 3 s : une vidéo « en traitement » devient lisible sans rien faire.
    LaunchedEffect(Unit) {
        while (true) {
            videos = withContext(Dispatchers.IO) { library.list() }
            feedback = withContext(Dispatchers.IO) { learning.records().associateBy { it.clipName } }
            sessionStats = withContext(Dispatchers.IO) { statsStore.all() }
            loaded = true
            delay(3_000)
        }
    }

    fun deleteVideos(list: List<LibraryVideo>) {
        list.forEach {
            library.delete(it)
            learning.forget(it.name)
            paths.delete(it.name)
        }
        val keys = list.map { it.key }.toSet()
        videos = videos.filter { it.key !in keys }
    }

    val byKey = videos.associateBy { it.key }
    val playable = videos.filter { !it.pending }
    val grouping = SessionGrouper.group(playable.map(::infoOf))

    val current = request
    if (current != null) {
        val list = current.keys.mapNotNull { byKey[it] }
        val start = list.indexOfFirst { it.key == current.startKey }
        if (list.isNotEmpty() && start >= 0) {
            PlayerScreen(
                videos = list,
                startIndex = start,
                library = library,
                learning = learning,
                autoAdvance = current.autoAdvance,
                chapters = current.chapters,
                onDelete = { video ->
                    val at = list.indexOfFirst { it.key == video.key }
                    val neighbour = list.getOrNull(at + 1) ?: list.getOrNull(at - 1)
                    deleteVideos(listOf(video))
                    request = neighbour?.let { n -> PlayRequest(current.keys.filter { it != video.key }, n.key, current.autoAdvance, current.chapters) }
                },
                onBack = { request = null },
            )
            return
        }
        request = null
    }

    val activeMontage = montage
    if (activeMontage != null) {
        val waves = activeMontage.keys.mapNotNull { byKey[it] }
        MontageScreen(
            title = activeMontage.title,
            montageId = activeMontage.id,
            waves = waves,
            onPreview = { list -> request = PlayRequest(list.map { it.key }, list.first().key, autoAdvance = true) },
            onExported = { message = it; scope.launch { videos = withContext(Dispatchers.IO) { library.list() } } },
            onBack = { montage = null },
        )
        return
    }

    // Toutes les vagues (chapitres de session ou vagues seules), les films de vagues et les sessions.
    val sessionOfWave = HashMap<String, String>()
    grouping.sessions.forEach { g -> g.waves.forEach { sessionOfWave[it.key] = clock(g.startMs) } }
    val waveInfos = grouping.sessions.flatMap { it.waves } + grouping.looseWaves.filter { it.kind == MediaInfo.Kind.WAVE }
    val montageInfos = grouping.looseWaves.filter { it.kind == MediaInfo.Kind.MONTAGE }
    val allDates = (grouping.sessions.map { dateOfMs(it.startMs) } + waveInfos.map { dateOfMs(it.startMs) } + montageInfos.map { dateOfMs(it.startMs) })
        .distinct().sortedDescending()
    val q = query.trim().lowercase()
    val waveOnlyFilter = judge != "all" || favOnly

    val shownWaves = waveInfos.mapNotNull { info -> byKey[info.key]?.let { info to it } }
        .filter { (info, v) ->
            (dayFilter == null || dateOfMs(info.startMs) == dayFilter) &&
                (!favOnly || v.name in favs) &&
                (judge == "all" || when (judge) {
                    "good" -> feedback[v.name]?.good == true
                    "bad" -> feedback[v.name]?.good == false
                    else -> feedback[v.name]?.good == null
                }) &&
                (q.isEmpty() || "${VideoNaming.shortTitle(v.name)} ${clock(info.startMs)} ${v.name}".lowercase().contains(q))
        }
        .sortedByDescending { it.first.startMs }
    val shownMontages = if (waveOnlyFilter) emptyList() else montageInfos.mapNotNull { info -> byKey[info.key]?.let { info to it } }
        .filter { (info, v) ->
            (dayFilter == null || dateOfMs(info.startMs) == dayFilter) &&
                (q.isEmpty() || "${VideoNaming.shortTitle(v.name)} ${clock(info.startMs)}".lowercase().contains(q))
        }
        .sortedByDescending { it.first.startMs }
    val shownSessions = if (waveOnlyFilter) emptyList() else grouping.sessions.filter { g ->
        (dayFilter == null || dateOfMs(g.startMs) == dayFilter) &&
            (q.isEmpty() || "session ${clock(g.startMs)} ${clock(g.endMs)}".lowercase().contains(q))
    }
    val showFilms = (tab == "all" || tab == "film") && shownMontages.isNotEmpty()
    val showSessions = (tab == "all" || tab == "session") && shownSessions.isNotEmpty()
    val showWaves = (tab == "all" || tab == "wave") && shownWaves.isNotEmpty()
    val tipSession = grouping.sessions.firstOrNull { it.film != null && it.parts.isNotEmpty() }
    val freeBytes = remember(videos.size) { try { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { 0L } }

    @Composable
    fun SessionItem(group: SessionGroup) {
        val partVideos = group.parts.mapNotNull { byKey[it.key] }
        val filmVideo = group.film?.let { byKey[it.key] }
        val waveVideos = group.waves.mapNotNull { byKey[it.key] }
        SessionCard(
            library = library,
            group = group,
            parts = partVideos,
            film = filmVideo,
            waves = waveVideos,
            feedback = feedback,
            battery = statsStore.forSession(group.startMs, sessionStats),
            chaptersOpen = group.startMs in openChapters,
            pickedKey = picked[group.startMs],
            progress = assembling[group.startMs],
            onToggleChapters = { if (group.startMs in openChapters) openChapters.remove(group.startMs) else openChapters.add(group.startMs) },
            onPick = { key -> if (picked[group.startMs] == key) picked.remove(group.startMs) else picked[group.startMs] = key },
            onMontage = {
                montage = MontageRequest("session-${group.startMs}", "Session ${clock(group.startMs)} → ${clock(group.endMs)}", waveVideos.map { it.key })
            },
            onPlayWave = { w -> request = PlayRequest(waveVideos.map { it.key }, w.key) },
            onPlay = {
                val chapters = group.waves.map { group.offsetOf(it) }
                request = if (filmVideo != null) {
                    PlayRequest(listOf(filmVideo.key), filmVideo.key, chapters = chapters)
                } else {
                    PlayRequest(partVideos.map { it.key }, partVideos.first().key, autoAdvance = true)
                }
            },
            onAssemble = {
                val start = group.startMs
                val assembler = SessionAssembler(context)
                assemblers[start] = assembler
                assembling[start] = 0f
                message = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        assembler.assemble(partVideos, VideoNaming.filmName(start) + ".mp4") { assembling[start] = it }
                    }
                    assembling.remove(start)
                    assemblers.remove(start)
                    videos = withContext(Dispatchers.IO) { library.list() }
                    message = error ?: "Film assemblé : ${clock(group.startMs)} → ${clock(group.endMs)}. Tu peux supprimer les morceaux pour libérer la place."
                }
            },
            onCancelAssemble = { assemblers[group.startMs]?.cancelled = true },
            onDeleteParts = {
                deleteText = "Supprimer les ${partVideos.size} morceaux de 2 minutes ? Le film assemblé est conservé."
                toDelete = partVideos
            },
            onDeleteSession = {
                deleteText = "Supprimer la session (film et ${partVideos.size} morceaux) ? Les vagues sont conservées."
                toDelete = partVideos + listOfNotNull(filmVideo)
            },
        )
    }

    fun toggleChosen(key: String) {
        if (key in chosen) chosen.remove(key) else chosen.add(key)
    }

    SurfCamTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            if (selecting) { selecting = false; chosen.clear() } else onBack()
                        }) { Text(if (selecting) "Terminer" else "‹ Retour") }
                        Text("Mes vidéos", color = SurfColors.Aqua, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${shownWaves.size + shownSessions.size + shownMontages.size} sur ${videos.size}",
                            color = SurfColors.Muted, fontSize = 12.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Chercher : « vague 19 », « 16 h »…", fontSize = 13.sp) },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f),
                        )
                        FilterPill(if (selecting) "Terminer" else "Sélectionner", selecting) {
                            selecting = !selecting
                            if (!selecting) chosen.clear()
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "Tout", "wave" to "Vagues", "session" to "Sessions", "film" to "Films").forEach { (id, label) ->
                            FilterPill(label, tab == id) { tab = id }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("Tous les jours", dayFilter == null) { dayFilter = null }
                        allDates.forEach { d -> FilterPill(dayChipLabel(d), dayFilter == d) { dayFilter = if (dayFilter == d) null else d } }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "Toutes", "good" to "✓ Bonnes", "bad" to "✗ Fausses alertes", "none" to "Non jugées").forEach { (id, label) ->
                            FilterPill(label, judge == id) { judge = id }
                        }
                        FilterPill("★ Favoris", favOnly, highlight = true) { favOnly = !favOnly }
                    }

                    message?.let { Banner(it) }
                    val pendingCount = videos.count { it.pending }
                    if (pendingCount > 0) {
                        Banner(
                            if (sessionRunning) "$pendingCount vidéo(s) en traitement : le fichier en cours d'écriture sera lisible dès qu'il sera terminé."
                            else "$pendingCount vidéo(s) en traitement : elles seront lisibles dans quelques secondes.",
                        )
                    }
                    if (loaded && videos.isEmpty()) {
                        Text(
                            "Aucune vidéo pour l'instant. Elles apparaîtront ici dès que l'app aura enregistré un clip ou une session.",
                            color = SurfColors.Muted,
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    ) {
                        item(key = "storage") {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("${formatSize(videos.sumOf { it.sizeBytes })} de vidéos" + if (freeBytes > 0) " · ${formatSize(freeBytes)} libres sur le téléphone" else "", color = SurfColors.Muted, fontSize = 12.sp)
                                tipSession?.let {
                                    Text(
                                        "Astuce : le film de la session ${clock(it.startMs)} est assemblé, tu peux supprimer ses ${it.parts.size} morceaux (${formatSize(it.parts.mapNotNull { p -> byKey[p.key]?.sizeBytes }.sum())}).",
                                        color = SurfColors.Info, fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        if (loaded && !showFilms && !showSessions && !showWaves && videos.isNotEmpty()) {
                            item(key = "empty") {
                                Text("Aucune vidéo ne correspond. Retire un filtre ou change de jour.", color = SurfColors.Muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
                            }
                        }
                        if (showFilms) {
                            item(key = "h-films") { SectionTitle("Films · ${shownMontages.size}") }
                            items(shownMontages, key = { it.first.key }) { (info, video) ->
                                MediaRow(
                                    library = library, video = video, kicker = "FILM DES VAGUES", kickerColor = SurfColors.Aqua,
                                    title = "${VideoNaming.shortTitle(video.name)} · ${clock(info.startMs)}",
                                    sub = "${formatSize(video.sizeBytes)}", record = null, favorite = null,
                                    selecting = selecting, selected = video.key in chosen, copyProgress = copying[video.key],
                                    onToggleFavorite = {},
                                    onCopy = { },
                                    onClick = { if (selecting) toggleChosen(video.key) else request = PlayRequest(listOf(video.key), video.key) },
                                    onLongClick = { selecting = true; if (video.key !in chosen) chosen.add(video.key) },
                                )
                            }
                        }
                        if (showSessions) {
                            item(key = "h-sessions") { SectionTitle("Sessions · ${shownSessions.size}") }
                            items(shownSessions, key = { "session-${it.startMs}" }) { group -> SessionItem(group) }
                        }
                        if (showWaves) {
                            item(key = "h-waves") { SectionTitle("Vagues · ${shownWaves.size}") }
                            items(shownWaves, key = { it.first.key }) { (info, video) ->
                                val label = sessionOfWave[video.key]
                                MediaRow(
                                    library = library, video = video,
                                    kicker = if (label != null) "VAGUE · SESSION $label" else "VAGUE SEULE", kickerColor = Color(0xFFFFD166),
                                    title = "${VideoNaming.shortTitle(video.name)} · ${clock(info.startMs)}",
                                    sub = "${formatDuration(video.durationMs)} · ${formatSize(video.sizeBytes)}" + if (video.isExternal) " · ${video.volumeLabel}" else "",
                                    record = feedback[video.name], favorite = video.name in favs,
                                    selecting = selecting, selected = video.key in chosen, copyProgress = copying[video.key],
                                    onToggleFavorite = { favs = favStore.toggle(video.name) },
                                    onCopy = {
                                        message = null
                                        copying[video.key] = 0f
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) { library.copyToPhone(video) { copying[video.key] = it } }
                                            copying.remove(video.key)
                                            message = if (ok) "Copiée sur le téléphone : ${VideoNaming.shortTitle(video.name)}" else "La copie a échoué : ${VideoNaming.shortTitle(video.name)}"
                                        }
                                    },
                                    onClick = {
                                        if (selecting) toggleChosen(video.key)
                                        else request = PlayRequest(shownWaves.map { it.second.key }, video.key)
                                    },
                                    onLongClick = { selecting = true; if (video.key !in chosen) chosen.add(video.key) },
                                )
                            }
                        }
                    }
                }

                if (selecting) {
                    val chosenWaves = chosen.mapNotNull { byKey[it] }.filter { infoOf(it).kind == MediaInfo.Kind.WAVE }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${chosen.size} choisie(s)", color = SurfColors.Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = chosenWaves.size >= 2,
                                onClick = {
                                    val ordered = chosenWaves.sortedBy { startOf(it) }
                                    montage = MontageRequest("selection-${System.currentTimeMillis()}", "Sélection de ${ordered.size} vagues", ordered.map { it.key })
                                    selecting = false
                                    chosen.clear()
                                },
                            ) { Text("Créer un film", fontSize = 13.sp) }
                            OutlinedButton(
                                enabled = chosenWaves.isNotEmpty(),
                                onClick = { favs = favStore.addAll(chosenWaves.map { it.name }) },
                            ) { Text("★ Favoris", fontSize = 13.sp) }
                            OutlinedButton(
                                enabled = chosen.isNotEmpty(),
                                onClick = {
                                    val list = chosen.mapNotNull { byKey[it] }
                                    deleteText = "Supprimer ${list.size} vidéo(s) ? Elles seront définitivement supprimées du téléphone."
                                    toDelete = list
                                },
                            ) { Text("Supprimer", fontSize = 13.sp, color = SurfColors.Alert) }
                        }
                    }
                }
            }

            toDelete?.let { list ->
                AlertDialog(
                    onDismissRequest = { toDelete = null },
                    title = { Text("Supprimer ?") },
                    text = { Text(deleteText) },
                    confirmButton = {
                        TextButton(onClick = {
                            deleteVideos(list)
                            chosen.clear()
                            selecting = false
                            toDelete = null
                        }) { Text("Supprimer", color = SurfColors.Alert) }
                    },
                    dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
                )
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    val bg = if (selected) (if (highlight) Color(0xFFFFD166) else SurfColors.Aqua) else Color.Transparent
    Text(
        text,
        color = if (selected) SurfColors.OnAqua else SurfColors.Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (selected) bg else Color(0xFF2D4252), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = SurfColors.Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
}

private fun dayChipLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)
    return when (date) {
        today -> "Aujourd'hui · ${date.dayOfMonth} $month"
        today.minusDays(1) -> "Hier · ${date.dayOfMonth} $month"
        else -> "${date.dayOfMonth} $month ${date.year}"
    }
}

/** Une vidéo (vague ou film) en ligne : miniature avec durée, étiquette de type, titre avec l'heure, jugement et étoile. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    library: VideoLibrary,
    video: LibraryVideo,
    kicker: String,
    kickerColor: Color,
    title: String,
    sub: String,
    record: FeedbackRecord?,
    favorite: Boolean?,
    selecting: Boolean,
    selected: Boolean,
    copyProgress: Float?,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(null, video.id, video.pending) {
        value = if (video.pending) null else withContext(Dispatchers.IO) { library.thumbnail(video, 320, 180) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF1B2D3B) else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(1.dp, SurfColors.Aqua, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(enabled = !video.pending, onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            thumbnail?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (video.pending) {
                Text("En traitement…", color = SurfColors.Warn, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    formatDuration(video.durationMs),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xA6000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (selecting) {
                Text(
                    if (selected) "✓" else "",
                    color = SurfColors.OnAqua, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                        .background(if (selected) SurfColors.Aqua else Color(0x59000000))
                        .border(2.dp, if (selected) SurfColors.Aqua else Color.White, CircleShape)
                        .wrapContentSize(Alignment.Center),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(kicker, color = kickerColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
            Text(title, color = SurfColors.Foam, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            if (copyProgress != null) {
                Text("Copie : ${(copyProgress * 100).toInt()} %", color = SurfColors.Aqua, fontSize = 12.sp)
            } else {
                Text(sub, color = SurfColors.Muted, fontSize = 12.sp, maxLines = 1)
            }
            if (video.isExternal && !video.pending && copyProgress == null) {
                Text("Copier sur le téléphone", color = SurfColors.Info, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onCopy))
            }
        }
        if (record?.good != null) {
            val good = record.good == true
            Text(
                if (good) "✓" else "✗",
                color = Color(0xFF06212A), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.size(20.dp).clip(CircleShape).background(if (good) SurfColors.Ok else SurfColors.Alert).wrapContentSize(Alignment.Center),
            )
        }
        if (favorite != null) {
            Text(
                if (favorite) "★" else "☆",
                color = if (favorite) Color(0xFFFFD166) else SurfColors.Muted, fontSize = 24.sp,
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onToggleFavorite).wrapContentSize(Alignment.Center),
            )
        }
    }
}

/**
 * Carte d'une session : le film (miniature, heures, taille), sa frise où chaque trait jaune est une vague, les boutons
 * Lire / Assembler / Supprimer, et la liste dépliable des vagues (les chapitres).
 */
@Composable
private fun SessionCard(
    library: VideoLibrary,
    group: SessionGroup,
    parts: List<LibraryVideo>,
    film: LibraryVideo?,
    waves: List<LibraryVideo>,
    feedback: Map<String, FeedbackRecord>,
    battery: SessionStats?,
    chaptersOpen: Boolean,
    pickedKey: String?,
    progress: Float?,
    onToggleChapters: () -> Unit,
    onPick: (String) -> Unit,
    onMontage: () -> Unit,
    onPlayWave: (LibraryVideo) -> Unit,
    onPlay: () -> Unit,
    onAssemble: () -> Unit,
    onCancelAssemble: () -> Unit,
    onDeleteParts: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    val cover = film ?: parts.firstOrNull()
    val thumbnail by produceState<Bitmap?>(null, cover?.key) {
        value = cover?.let { withContext(Dispatchers.IO) { library.thumbnail(it, 320, 180) } }
    }
    val totalMs = (group.endMs - group.startMs).coerceAtLeast(1L)
    val sizeBytes = parts.sumOf { it.sizeBytes } + (film?.sizeBytes ?: 0L)
    val waveKeys = group.waves.map { it.key }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable(onClick = onPlay)) {
                thumbnail?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Text(
                    formatDuration(maxOf(totalMs, parts.sumOf { it.durationMs }, film?.durationMs ?: 0L)),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xA6000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("FILM · SESSION", color = SurfColors.Aqua, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("${clock(group.startMs)} → ${clock(group.endMs)}", color = SurfColors.Foam, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        if (film != null) append("Film assemblé") else append("${parts.size} morceaux")
                        append(" · ${formatSize(sizeBytes)} · ${waves.size} vague(s)")
                    },
                    color = SurfColors.Muted, fontSize = 12.sp,
                )
                battery?.let {
                    Text(
                        if (it.charged) "Batterie : branché" else "Batterie : −${it.percentUsed} pts" + (it.percentPerHour?.let { p -> " · ${"%.0f".format(p)} %/h" } ?: ""),
                        color = SurfColors.Info, fontSize = 12.sp,
                    )
                }
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDeleteSession), contentAlignment = Alignment.Center) {
                TrashIcon()
            }
        }

        if (waves.isNotEmpty()) {
            // Frise : un trait jaune par vague, à sa place dans le film. Toucher un trait le sélectionne.
            Box(
                Modifier.fillMaxWidth().height(40.dp).pointerInput(waveKeys) {
                    detectTapGestures { p ->
                        val nearest = group.waves.indices.minByOrNull { i -> kotlin.math.abs(group.offsetOf(group.waves[i]).toFloat() / totalMs * size.width - p.x) }
                        if (nearest != null) onPick(waveKeys[nearest])
                    }
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(Color(0xFF2D4252), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                    group.waves.forEachIndexed { i, w ->
                        val x = (group.offsetOf(w).toFloat() / totalMs * size.width).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
                        val selected = waveKeys[i] == pickedKey
                        drawLine(
                            Color(0xFFFFD166).copy(alpha = if (selected) 1f else 0.7f),
                            Offset(x, size.height * 0.15f), Offset(x, size.height * 0.85f),
                            strokeWidth = (if (selected) 5 else 3).dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                }
            }
            val pickedWave = waves.firstOrNull { it.key == pickedKey }
            Text(
                pickedWave?.let {
                    val offsetMin = group.offsetOf(group.waves[waves.indexOf(it)]) / 60_000
                    "${VideoNaming.shortTitle(it.name)} · ${clock(startOf(it))} · à ${offsetMin} min du début du film · ${formatDuration(it.durationMs)}"
                } ?: "Touche un trait jaune pour voir où se trouve la vague dans le film.",
                color = SurfColors.Foam, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x33000000)).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = SurfColors.Aqua)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Assemblage : ${(progress * 100).toInt()} % (garde l'écran allumé)", color = SurfColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelAssemble) { Text("Annuler") }
            }
        } else {
            // Les boutons passent à la ligne quand l'écran est étroit : sinon le dernier sortait de l'écran.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = onPlay, enabled = cover != null) { Text("Lire") }
                if (film == null && parts.size >= 2) {
                    OutlinedButton(onClick = onAssemble) { Text("Assembler en un film", fontSize = 13.sp) }
                }
                if (waves.size >= 2) {
                    OutlinedButton(onClick = onMontage) { Text("Film des vagues", fontSize = 13.sp) }
                }
                if (film != null && parts.isNotEmpty()) {
                    OutlinedButton(onClick = onDeleteParts) { Text("Supprimer les ${parts.size} morceaux", fontSize = 12.sp, color = SurfColors.Alert) }
                }
            }
        }

        if (waves.isNotEmpty()) {
            Text(
                (if (chaptersOpen) "▾" else "▸") + " ${waves.size} chapitres (vagues de cette session)",
                color = SurfColors.Muted, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleChapters).padding(vertical = 8.dp),
            )
            if (chaptersOpen) {
                waves.forEach { w ->
                    ChapterRow(library, w, feedback[w.name], selected = w.key == pickedKey) { onPlayWave(w) }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(library: VideoLibrary, video: LibraryVideo, record: FeedbackRecord?, selected: Boolean, onPlay: () -> Unit) {
    val thumbnail by produceState<Bitmap?>(null, video.id) { value = withContext(Dispatchers.IO) { library.thumbnail(video, 240, 135) } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.border(1.dp, Color(0xFFFFD166), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onPlay)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            thumbnail?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Text(
                formatDuration(video.durationMs),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xA6000000)).padding(horizontal = 4.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(VideoNaming.shortTitle(video.name), color = SurfColors.Foam, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${clock(startOf(video))} · ${formatSize(video.sizeBytes)}", color = SurfColors.Muted, fontSize = 12.sp, maxLines = 1)
        }
        if (record?.good != null) {
            val good = record.good == true
            Text(
                if (good) "✓" else "✗",
                color = Color(0xFF06212A), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.size(20.dp).clip(CircleShape).background(if (good) SurfColors.Ok else SurfColors.Alert).wrapContentSize(Alignment.Center),
            )
        }
    }
}

@Composable
private fun TrashIcon() {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val c = SurfColors.Alert
        drawLine(c, Offset(w * 0.1f, h * 0.22f), Offset(w * 0.9f, h * 0.22f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.38f, h * 0.1f), Offset(w * 0.62f, h * 0.1f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        val body = Path().apply {
            moveTo(w * 0.2f, h * 0.22f); lineTo(w * 0.28f, h * 0.92f); lineTo(w * 0.72f, h * 0.92f); lineTo(w * 0.8f, h * 0.22f)
        }
        drawPath(body, c, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = SurfColors.Warn,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33FFC857))
            .padding(12.dp),
    )
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f Go".format(bytes / 1e9) else "${bytes / 1_000_000} Mo"
