package com.surfcam.app.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.learning.FeedbackRecord
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.data.library.VideoLibrary
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

/** Les vidéos d'une même journée, repliables. */
private data class DayGroup(val date: LocalDate, val videos: List<LibraryVideo>)

private fun dateOf(video: LibraryVideo): LocalDate =
    VideoNaming.parse(video.name)?.dateTime?.toLocalDate()
        ?: Instant.ofEpochMilli(video.modifiedMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun timeLabel(video: LibraryVideo): String {
    val t = VideoNaming.parse(video.name)?.dateTime
        ?: Instant.ofEpochMilli(video.modifiedMs).atZone(ZoneId.systemDefault())
    return "${t.hour} h ${"%02d".format(t.minute)}"
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
 * Les vidéos enregistrées par l'app, rangées par jour (le jour est repliable) : une ligne par vidéo avec sa miniature,
 * la durée dans le coin, un nom court et la taille. Un toucher ouvre le lecteur.
 */
@Composable
fun LibraryScreen(library: VideoLibrary, learning: LearningRepository, sessionRunning: Boolean, onBack: () -> Unit) {
    var videos by remember { mutableStateOf<List<LibraryVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<LibraryVideo?>(null) }
    val scope = rememberCoroutineScope()
    // Progression des copies en cours (0 à 1), par vidéo.
    val copying = remember { mutableStateMapOf<String, Float>() }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Map<String, FeedbackRecord>>(emptyMap()) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    // Jours dont l'état est l'inverse de l'état par défaut (aujourd'hui ouvert, les autres jours repliés).
    val toggledDays = remember { mutableStateListOf<LocalDate>() }

    // Rafraîchi toutes les 3 s : une vidéo « en traitement » devient lisible sans rien faire.
    LaunchedEffect(Unit) {
        while (true) {
            videos = withContext(Dispatchers.IO) { library.list() }
            feedback = withContext(Dispatchers.IO) { learning.records().associateBy { it.clipName } }
            loaded = true
            delay(3_000)
        }
    }

    val playable = videos.filter { !it.pending }
    val playingIndex = playable.indexOfFirst { it.key == playingKey }
    if (playingKey != null && playingIndex >= 0) {
        PlayerScreen(
            videos = playable,
            startIndex = playingIndex,
            learning = learning,
            onDelete = { video ->
                val at = playable.indexOfFirst { it.key == video.key }
                val neighbour = playable.getOrNull(at + 1) ?: playable.getOrNull(at - 1)
                library.delete(video)
                learning.forget(video.name)
                videos = videos.filter { it.key != video.key }
                playingKey = neighbour?.key
            },
            onBack = { playingKey = null },
        )
        return
    }

    val days = videos.groupBy { dateOf(it) }.toSortedMap(compareByDescending { it }).map { DayGroup(it.key, it.value) }
    val today = LocalDate.now()

    SurfCamTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack) { Text("‹ Retour") }
                    Text("Mes vidéos", color = SurfColors.Aqua, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${videos.size} fichier(s) · ${formatSize(videos.sumOf { it.sizeBytes })}",
                        color = SurfColors.Muted,
                        fontSize = 12.sp,
                    )
                }

                copyMessage?.let { Banner(it) }
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

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    days.forEach { day ->
                        val expanded = (day.date == today) != (day.date in toggledDays)
                        item(key = "day-${day.date}") {
                            DayHeader(day, expanded) {
                                if (day.date in toggledDays) toggledDays.remove(day.date) else toggledDays.add(day.date)
                            }
                        }
                        if (expanded) {
                            items(day.videos, key = { it.key }) { video ->
                                VideoRow(
                                    library = library,
                                    video = video,
                                    onPlay = { playingKey = video.key },
                                    copyProgress = copying[video.key],
                                    feedback = feedback[video.name],
                                    onDelete = { toDelete = video },
                                    onCopy = {
                                        copyMessage = null
                                        copying[video.key] = 0f
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                library.copyToPhone(video) { copying[video.key] = it }
                                            }
                                            copying.remove(video.key)
                                            copyMessage = if (ok) "Copiée sur le téléphone : ${VideoNaming.shortTitle(video.name)}" else "La copie a échoué : ${VideoNaming.shortTitle(video.name)}"
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            toDelete?.let { video ->
                AlertDialog(
                    onDismissRequest = { toDelete = null },
                    title = { Text("Supprimer cette vidéo ?") },
                    text = { Text("${VideoNaming.displayTitle(video.name)}\nElle sera définitivement supprimée du téléphone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            library.delete(video)
                            learning.forget(video.name)
                            videos = videos.filter { it.key != video.key }
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
private fun DayHeader(day: DayGroup, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (expanded) "▾" else "▸", color = SurfColors.Aqua, fontSize = 14.sp)
        Text(dayLabel(day.date), color = SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${day.videos.size} vidéo(s) · ${formatSize(day.videos.sumOf { it.sizeBytes })}",
            color = SurfColors.Muted,
            fontSize = 12.sp,
        )
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

@Composable
private fun VideoRow(
    library: VideoLibrary,
    video: LibraryVideo,
    onPlay: () -> Unit,
    copyProgress: Float?,
    feedback: FeedbackRecord?,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(null, video.id, video.pending) {
        value = if (video.pending) null else withContext(Dispatchers.IO) { library.thumbnail(video, 320, 180) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !video.pending, onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (video.pending) {
                Text("En traitement…", color = SurfColors.Warn, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    formatDuration(video.durationMs),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xA6000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(VideoNaming.shortTitle(video.name), color = SurfColors.Foam, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            Text(
                timeLabel(video) + " · " + formatSize(video.sizeBytes) + if (video.isExternal) " · ${video.volumeLabel}" else "",
                color = SurfColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
            if (copyProgress != null) {
                Text("Copie : ${(copyProgress * 100).toInt()} %", color = SurfColors.Aqua, fontSize = 12.sp)
            } else if (video.isExternal && !video.pending) {
                Text(
                    "Copier sur le téléphone",
                    color = SurfColors.Info, fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onCopy),
                )
            }
        }
        if (feedback?.good != null) {
            val good = feedback.good == true
            Text(
                if (good) "✓" else "✗",
                color = Color(0xFF06212A), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (good) SurfColors.Ok else SurfColors.Alert)
                    .wrapContentSize(Alignment.Center),
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !video.pending, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
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
    }
}


private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f Go".format(bytes / 1e9) else "${bytes / 1_000_000} Mo"
