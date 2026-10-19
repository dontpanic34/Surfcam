package com.surfcam.app.presentation

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.learning.FeedbackRecord
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Les vidéos enregistrées par l'app : lecture, suppression, et état « en traitement » pour celles pas encore finies. */
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
    var learningReport by remember { mutableStateOf(learning.lastReport()) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

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
                library.delete(video)
                learning.forget(video.name)
                videos = videos.filter { it.key != video.key }
            },
            onBack = { playingKey = null },
        )
        return
    }

    SurfCamTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("‹ Retour") }
                    Text("Mes vidéos", color = SurfColors.Aqua, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${videos.size} fichier(s) · ${formatSize(videos.sumOf { it.sizeBytes })}",
                        color = SurfColors.Muted,
                        fontSize = 13.sp,
                    )
                }

                copyMessage?.let { Banner(it) }
                learningReport?.let { Banner(it) }

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

                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(videos, key = { it.key }) { video ->
                        VideoRow(
                            library = library,
                            video = video,
                            portrait = portrait,
                            onPlay = { playingKey = video.key },
                            copyProgress = copying[video.key],
                            feedback = feedback[video.name],
                            onJudge = { good ->
                                scope.launch {
                                    learningReport = withContext(Dispatchers.IO) {
                                        learning.setJudgement(video.name, good)
                                        learning.recalibrate()
                                        feedback = learning.records().associateBy { it.clipName }
                                        learning.lastReport()
                                    }
                                }
                            },
                            onDelete = { toDelete = video },
                            onCopy = {
                                copyMessage = null
                                copying[video.key] = 0f
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        library.copyToPhone(video) { copying[video.key] = it }
                                    }
                                    copying.remove(video.key)
                                    copyMessage = if (ok) "Copiée sur le téléphone : ${video.name}" else "La copie a échoué : ${video.name}"
                                }
                            },
                        )
                    }
                }
            }

            toDelete?.let { video ->
                AlertDialog(
                    onDismissRequest = { toDelete = null },
                    title = { Text("Supprimer cette vidéo ?") },
                    text = { Text("${video.name}\nElle sera définitivement supprimée du téléphone.") },
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
    portrait: Boolean,
    onPlay: () -> Unit,
    copyProgress: Float?,
    feedback: FeedbackRecord?,
    onJudge: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(null, video.id, video.pending) {
        value = if (video.pending) null else withContext(Dispatchers.IO) { library.thumbnail(video, 320, 180) }
    }

    val thumb: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable(enabled = !video.pending, onClick = onPlay)) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (video.pending) {
                Text("En traitement…", color = SurfColors.Warn, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
    val info: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                VideoNaming.displayTitle(video.name),
                color = SurfColors.Foam,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                "${formatDate(video.modifiedMs)} · ${formatDuration(video.durationMs)} · ${formatSize(video.sizeBytes)}",
                color = SurfColors.Muted,
                fontSize = 12.sp,
            )
            Text(
                (if (video.isExternal) "Sur : ${video.volumeLabel}" else "Sur le téléphone") + " · ${video.name}",
                color = if (video.isExternal) SurfColors.Info else SurfColors.Muted,
                fontSize = 11.sp,
            )
            if (copyProgress != null) {
                Text("Copie en cours : ${(copyProgress * 100).toInt()} %", color = SurfColors.Aqua, fontSize = 12.sp)
            }
            if (feedback != null && !video.pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (feedback.good == null && !portrait) Text("Cette détection est-elle juste ?", color = SurfColors.Muted, fontSize = 12.sp)
                    if (feedback.good != false) OutlinedButton(onClick = { onJudge(true) }) {
                        Text(if (feedback.good == true) "✓ Bonne vague" else "Bonne vague", fontSize = 12.sp)
                    }
                    if (feedback.good != true) OutlinedButton(onClick = { onJudge(false) }) {
                        Text(if (feedback.good == false) "✓ Fausse alerte" else "Fausse alerte", fontSize = 12.sp)
                    }
                }
            }
            if (video.pending) {
                Text("En cours de traitement : pas encore lisible", color = SurfColors.Warn, fontSize = 12.sp)
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Button(enabled = !video.pending, onClick = onPlay) { Text("Lire") }
        if (video.isExternal) {
            OutlinedButton(enabled = !video.pending && copyProgress == null, onClick = onCopy) { Text("Copier sur le téléphone", fontSize = 11.sp) }
        }
        TextButton(enabled = !video.pending, onClick = onDelete) { Text("Supprimer", color = SurfColors.Alert) }
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(10.dp)
    if (portrait) {
        // Portrait : grande vignette en haut, informations dessous, actions en ligne.
        Column(cardModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            thumb(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            info(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    } else {
        Row(cardModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            thumb(Modifier.width(160.dp).height(90.dp))
            info(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
        }
    }
}

private fun formatDate(ms: Long): String = SimpleDateFormat("d MMM · HH:mm", Locale.FRANCE).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f Go".format(bytes / 1e9) else "${bytes / 1_000_000} Mo"
