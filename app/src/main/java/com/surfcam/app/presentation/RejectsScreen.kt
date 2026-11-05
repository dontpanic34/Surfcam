package com.surfcam.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Les clips que l'appli a pris pour des fausses alertes, gardés à part : on peut les regarder, récupérer ceux qui étaient
 * de vraies vagues (ils rejoignent Mes vidéos) ou les supprimer. L'appli ne garde que les plus récents (1,5 Go au plus).
 */
@Composable
fun RejectsScreen(library: VideoLibrary, learning: LearningRepository, onBack: () -> Unit) {
    var videos by remember { mutableStateOf<List<LibraryVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var playIndex by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun reload(then: () -> Unit = {}) {
        scope.launch {
            videos = withContext(Dispatchers.IO) { library.listRejected() }
            loaded = true
            then()
        }
    }
    LaunchedEffect(Unit) { reload() }
    BackHandler { if (playIndex != null) playIndex = null else onBack() }

    val current = playIndex
    if (current != null && videos.isNotEmpty()) {
        PlayerScreen(
            videos = videos,
            startIndex = current.coerceIn(0, videos.lastIndex),
            library = library,
            learning = learning,
            onKeepReject = { video ->
                scope.launch {
                    val name = withContext(Dispatchers.IO) {
                        library.promoteRejected(video)?.also {
                            learning.markRecovered(video.name, it)
                            com.surfcam.app.data.engine.SessionLog.recoveredReport(context, video.name, it, video.durationMs / 1000f)
                        }
                    }
                    message = if (name != null) "Récupérée : $name" else "Impossible de récupérer ce clip."
                    videos = withContext(Dispatchers.IO) { library.listRejected() }
                    playIndex = if (videos.isEmpty()) null else current.coerceIn(0, videos.lastIndex)
                }
            },
            onDelete = { video ->
                scope.launch {
                    // Rejeter = c'était bien une fausse alerte : on le retient (exemple négatif) avant de supprimer.
                    withContext(Dispatchers.IO) { learning.setJudgement(video.name.removeSuffix(".mp4"), false) }
                    withContext(Dispatchers.IO) { library.delete(video) }
                    videos = withContext(Dispatchers.IO) { library.listRejected() }
                    playIndex = if (videos.isEmpty()) null else current.coerceIn(0, videos.lastIndex)
                }
            },
            onBack = { playIndex = null },
        )
        return
    }

    SurfCamTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Vagues rejetées (${videos.size})", color = SurfColors.Foam, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (videos.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Tout supprimer", color = SurfColors.Alert) }
                        OutlinedButton(onClick = onBack) { Text("Retour") }
                    }
                }
                Text(
                    "Ce que l'appli a pris pour de fausses alertes. Si c'était une vraie vague, touche « Garder » : elle rejoint tes vidéos. L'appli ne conserve que les plus récentes.",
                    color = SurfColors.Muted, fontSize = 12.sp,
                )
                message?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
                if (loaded && videos.isEmpty()) Text("Aucun clip rejeté pour l'instant.", color = SurfColors.Muted, fontSize = 14.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(videos.size) { i ->
                        val v = videos[i]
                        val start = VideoNaming.rejectStart(v.name)
                        val title = (start?.let { "%02dh%02d".format(it.hour, it.minute) } ?: v.name.removeSuffix(".mp4")) +
                            " · %.1f s · %d Mo".format(v.durationMs / 1000f, v.sizeBytes / (1024 * 1024))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, v.key) {
                                value = withContext(Dispatchers.IO) { library.thumbnail(v, 240, 135)?.asImageBitmap() }
                            }
                            Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                                thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(title, color = SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                val st = learning.statsOf(v.name)
                                Text(
                                    if (st != null) "Rejeté : mouvement suivi %.1f s (minimum 3 s) · vitesse %.1f".format(st.durationUs / 1_000_000f, st.triggerSpeed) else "Rejeté (mesures non gardées)",
                                    color = SurfColors.Muted, fontSize = 11.sp,
                                )
                                Text(v.volumeLabel, color = SurfColors.Muted, fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { playIndex = i }) { Text("Voir", fontSize = 12.sp) }
                            Button(onClick = {
                                scope.launch {
                                    val name = withContext(Dispatchers.IO) { library.promoteRejected(v) }
                                    if (name != null) withContext(Dispatchers.IO) {
                                        learning.markRecovered(v.name, name)
                                        com.surfcam.app.data.engine.SessionLog.recoveredReport(context, v.name, name, v.durationMs / 1000f)
                                    }
                                    message = if (name != null) "Récupérée : $name (dans Mes vidéos). L'appli retient que c'était une vraie vague pour mieux détecter les suivantes." else "Impossible de récupérer ce clip."
                                    reload()
                                }
                            }) { Text("Garder", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        if (confirmAll) {
            AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text("Tout supprimer ?") },
                text = { Text("Les ${videos.size} clips rejetés seront supprimés définitivement.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmAll = false
                        scope.launch {
                            withContext(Dispatchers.IO) { videos.forEach { library.delete(it) } }
                            reload()
                        }
                    }) { Text("Supprimer", color = SurfColors.Alert) }
                },
                dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Annuler") } },
            )
        }
    }
}
