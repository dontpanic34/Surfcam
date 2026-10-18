package com.surfcam.app.presentation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.mutableStateOf
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
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Les vidéos enregistrées par l'app : lecture, suppression, et état « en traitement » pour celles pas encore finies. */
@Composable
fun LibraryScreen(library: VideoLibrary, sessionRunning: Boolean, onBack: () -> Unit) {
    var videos by remember { mutableStateOf<List<LibraryVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<LibraryVideo?>(null) }

    // Rafraîchi toutes les 3 s : une vidéo « en traitement » devient lisible sans rien faire.
    LaunchedEffect(Unit) {
        while (true) {
            videos = withContext(Dispatchers.IO) { library.list() }
            loaded = true
            delay(3_000)
        }
    }

    SurfCamTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("‹ Retour") }
                    Text("Mes vidéos", color = SurfColors.Aqua, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${videos.size} fichier(s) · ${formatSize(videos.sumOf { it.sizeBytes })}",
                        color = SurfColors.Muted,
                        fontSize = 13.sp,
                    )
                }

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
                    items(videos, key = { it.id }) { video ->
                        VideoRow(library, video, onDelete = { toDelete = video })
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
                            videos = videos.filter { it.id != video.id }
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
private fun VideoRow(library: VideoLibrary, video: LibraryVideo, onDelete: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, video.id, video.pending) {
        value = if (video.pending) null else withContext(Dispatchers.IO) { library.thumbnail(video, 320, 180) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (video.pending) {
                Text("En traitement…", color = SurfColors.Warn, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (video.isSession) "Session complète" else "Clip de vague",
                color = SurfColors.Foam,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                "${formatDate(video.modifiedMs)} · ${formatDuration(video.durationMs)} · ${formatSize(video.sizeBytes)}",
                color = SurfColors.Muted,
                fontSize = 12.sp,
            )
            Text(video.name, color = SurfColors.Muted, fontSize = 10.sp)
            if (video.pending) {
                Text("En cours de traitement : pas encore lisible", color = SurfColors.Warn, fontSize = 12.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !video.pending,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(video.uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                    }
                },
            ) { Text("Lire") }
            TextButton(enabled = !video.pending, onClick = onDelete) { Text("Supprimer", color = SurfColors.Alert) }
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
