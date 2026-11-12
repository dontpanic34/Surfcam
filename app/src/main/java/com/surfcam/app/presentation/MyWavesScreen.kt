package com.surfcam.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.surfcam.app.data.library.MyWaves
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * « Trier les vagues » : l'appli filme tout le monde ; ici, après la session, chaque vague est montrée par la vignette de la
 * silhouette du surfeur. On choisit un prénom en haut (on en ajoute à la volée), puis on touche les vagues de ce surfeur. Les
 * autres vagues se classent par ressemblance avec les siennes. Aucune vidéo n'est modifiée, déplacée ni supprimée.
 */
@Composable
fun MyWavesScreen(library: VideoLibrary, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { MyWaves(context) }
    val waves by produceState<List<LibraryVideo>?>(null) {
        value = withContext(Dispatchers.IO) {
            library.list().filter { v ->
                val p = VideoNaming.parse(v.name)
                !v.pending && p != null && !p.isSession && !p.isFilm && !p.isMontage
            }.groupBy { it.name }.values.map { copies -> copies.maxBy { it.sizeBytes } }.sortedBy { it.name }
        }
    }
    var names by remember { mutableStateOf(store.names()) }
    var who by remember { mutableStateOf(store.allSurfers()) }
    var current by remember { mutableStateOf(names.firstOrNull()) }
    var onlyMine by remember { mutableStateOf(false) }
    val days = remember(waves) { waves.orEmpty().map { it.name.take(10) }.distinct().sortedDescending() }
    var day by remember(days) { mutableStateOf(days.firstOrNull()) }
    SurfCamTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Trier les vagues", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onBack) { Text("Retour") }
                }
                val list = waves
                val dayAll = list.orEmpty().filter { it.name.take(10) == day }
                // Signature de couleur de chaque silhouette du jour, calculée une fois en arrière-plan.
                val signatures by produceState<Map<String, FloatArray>>(emptyMap(), day, waves) {
                    value = withContext(Dispatchers.IO) {
                        dayAll.mapNotNull { v -> store.signature(v.name)?.let { v.name.removeSuffix(".mp4") to it } }.toMap()
                    }
                }
                // Dès qu'une vague est désignée, les autres sont classées par ressemblance avec les siennes : les plus
                // probables remontent en tête, il n'y a plus qu'à descendre la liste jusqu'à ce que ce ne soit plus soi.
                fun isCurrent(v: LibraryVideo) = current != null && who[v.name.removeSuffix(".mp4")] == current
                val mineSigs = dayAll.filter { isCurrent(it) }.mapNotNull { signatures[it.name.removeSuffix(".mp4")] }
                val score: Map<String, Float> = if (mineSigs.isEmpty()) emptyMap() else dayAll.mapNotNull { v ->
                    val base = v.name.removeSuffix(".mp4")
                    val sig = signatures[base] ?: return@mapNotNull null
                    if (who[base] != null) null else base to mineSigs.maxOf { MyWaves.resemblance(sig, it) }
                }.toMap()
                val shown = dayAll.filter { !onlyMine || isCurrent(it) }.let { l ->
                    if (score.isEmpty()) l else l.sortedWith(compareByDescending<LibraryVideo> { isCurrent(it) }.thenBy { who[it.name.removeSuffix(".mp4")] != null }.thenByDescending { score[it.name.removeSuffix(".mp4")] ?: -1f }.thenBy { it.name })
                }
                when {
                    list == null -> Text("Recherche des vagues…", color = SurfColors.Muted)
                    list.isEmpty() -> Text("Aucune vague enregistrée pour l'instant.", color = SurfColors.Muted)
                    else -> {
                        SurferPicker(names, current, onSelect = { current = it; onlyMine = false }, onAdd = { n ->
                            if (store.addName(n)) { names = store.names(); current = n }
                        })
                        Text(
                            if (current == null) "Ajoute ou choisis un surfeur, puis touche ses vagues."
                            else "Touche les vagues de $current : les autres se classent par ressemblance avec les siennes. " +
                                "${dayAll.count { isCurrent(it) }} vague(s) de $current, ${dayAll.count { who[it.name.removeSuffix(".mp4")] == null }} sans nom ce jour-là.",
                            color = SurfColors.Muted, fontSize = 13.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (d in days.take(4)) FilterChip(selected = d == day, onClick = { day = d }, label = { Text(d.substring(8, 10) + "/" + d.substring(5, 7)) })
                            if (current != null) FilterChip(selected = onlyMine, onClick = { onlyMine = !onlyMine }, label = { Text("Seulement $current") })
                        }
                        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                            items(shown, key = { it.name }) { v ->
                                val base = v.name.removeSuffix(".mp4")
                                WaveCell(v, store, who[base], score[base]) {
                                    val sel = current
                                    if (sel != null) {
                                        store.setSurfer(v.name, if (who[base] == sel) null else sel)
                                        who = store.allSurfers()
                                    }
                                }
                            }
                            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Aucune vague à montrer.", color = SurfColors.Muted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveCell(video: LibraryVideo, store: MyWaves, surfer: String?, resemblance: Float?, onToggle: () -> Unit) {
    val context = LocalContext.current
    // La vignette de la silhouette si elle existe (vagues filmées depuis cette version), sinon une image tirée de la vidéo.
    val image by produceState<android.graphics.Bitmap?>(null, video.name) {
        value = withContext(Dispatchers.IO) {
            store.silhouette(video.name) ?: try {
                context.contentResolver.loadThumbnail(video.uri, android.util.Size(320, 320), null)
            } catch (_: Exception) {
                null
            }
        }
    }
    val parsed = VideoNaming.parse(video.name)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(3.dp, surfer?.let { surferColor(it) } ?: Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            val bmp = image
            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("pas d'image", color = SurfColors.Muted, fontSize = 11.sp)
            if (surfer != null) Text(surfer, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(50)).background(surferColor(surfer)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(parsed?.let { "%02dh%02d · vague %d".format(it.dateTime.hour, it.dateTime.minute, it.number) } ?: video.name.removeSuffix(".mp4"), fontSize = 12.sp)
                Text("%.0f s".format(video.durationMs / 1000f) + (resemblance?.let { " · ressemble à %.0f %%".format(it * 100) } ?: ""), fontSize = 11.sp, color = SurfColors.Muted)
            }
            OutlinedButton(onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(video.uri, "video/mp4")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {
                }
            }) { Text("Voir", fontSize = 11.sp) }
        }
    }
}
