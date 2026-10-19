package com.surfcam.app.presentation

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.surfcam.app.data.library.SessionAssembler
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Montage : les vagues d'une session sont proposées dans l'ordre du temps, toutes cochées au départ (sauf les versions
 * recadrées). On retire ou remet une vague d'un toucher, on regarde le film tel qu'il sera, puis on le crée : les vagues
 * sont mises bout à bout sans réencodage. Le montage n'est qu'une liste : les choix sont retenus et on peut le rouvrir.
 */
@Composable
fun MontageScreen(
    title: String,
    montageId: String,
    waves: List<LibraryVideo>,
    onPreview: (List<LibraryVideo>) -> Unit,
    onExported: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { VideoLibrary(context) }
    val prefs = remember { context.getSharedPreferences("montage", Context.MODE_PRIVATE) }
    val ordered = remember(waves.map { it.key }) { waves.sortedBy { VideoNaming.parse(it.name)?.dateTime?.toInstant()?.toEpochMilli() ?: it.modifiedMs } }
    // Noms des vagues retenues : par défaut toutes les vagues d'origine, sans les versions recadrées.
    val included = remember(montageId) {
        mutableStateListOf<String>().also { list ->
            val saved = prefs.getStringSet(montageId, null)
            list.addAll(saved ?: ordered.filter { VideoNaming.parse(it.name)?.reframed != true }.map { it.name })
        }
    }
    fun persist() {
        prefs.edit().putStringSet(montageId, included.toSet()).apply()
    }
    var progress by remember { mutableStateOf<Float?>(null) }
    var assembler by remember { mutableStateOf<SessionAssembler?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    val chosen = ordered.filter { it.name in included }
    val totalMs = chosen.sumOf { it.durationMs }
    val totalBytes = chosen.sumOf { it.sizeBytes }

    SurfCamTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack) { Text("‹ Retour") }
                    Column {
                        Text("Film des vagues", color = SurfColors.Aqua, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(title, color = SurfColors.Muted, fontSize = 12.sp)
                    }
                }
                Text(
                    "${chosen.size} vague(s) · ${formatMontageDuration(totalMs)} · ${formatMontageSize(totalBytes)}",
                    color = SurfColors.Foam, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                error?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }

                if (progress != null) {
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth(), color = SurfColors.Aqua)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Création du film : ${((progress ?: 0f) * 100).toInt()} % (garde l'écran allumé)", color = SurfColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { assembler?.cancelled = true }) { Text("Annuler") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onPreview(chosen) }, enabled = chosen.isNotEmpty()) { Text("Lire le film") }
                        OutlinedButton(
                            enabled = chosen.size >= 2,
                            onClick = {
                                val a = SessionAssembler(context)
                                assembler = a
                                progress = 0f
                                error = null
                                val first = VideoNaming.parse(chosen.first().name)?.dateTime?.toInstant()?.toEpochMilli() ?: chosen.first().modifiedMs
                                scope.launch {
                                    val problem = withContext(Dispatchers.IO) {
                                        a.assemble(chosen, VideoNaming.montageName(first) + ".mp4") { progress = it }
                                    }
                                    progress = null
                                    assembler = null
                                    if (problem != null) error = problem else onExported("Film des vagues créé : ${chosen.size} vagues, ${formatMontageDuration(totalMs)}.")
                                }
                            },
                        ) { Text("Créer le film") }
                        OutlinedButton(onClick = {
                            val all = ordered.map { it.name }
                            if (included.size == all.size) included.clear() else { included.clear(); included.addAll(all) }
                            persist()
                        }) { Text(if (included.size == ordered.size) "Tout décocher" else "Tout cocher", fontSize = 12.sp) }
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(ordered, key = { it.key }) { video ->
                        val on = video.name in included
                        val thumbnail by produceState<android.graphics.Bitmap?>(null, video.id) {
                            value = withContext(Dispatchers.IO) { library.thumbnail(video, 240, 135) }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (on) included.remove(video.name) else included.add(video.name)
                                    persist()
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Switch(
                                checked = on,
                                onCheckedChange = {
                                    if (it) included.add(video.name) else included.remove(video.name)
                                    persist()
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = SurfColors.Aqua, checkedThumbColor = SurfColors.OnAqua),
                            )
                            Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                                thumbnail?.let {
                                    androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    VideoNaming.shortTitle(video.name),
                                    color = if (on) SurfColors.Foam else SurfColors.Muted,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                )
                                Text(
                                    VideoNaming.parse(video.name)?.dateTime?.let { "${it.hour} h ${"%02d".format(it.minute)}" }.orEmpty() +
                                        " · ${formatMontageDuration(video.durationMs)} · ${formatMontageSize(video.sizeBytes)}",
                                    color = SurfColors.Muted, fontSize = 12.sp, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMontageDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d h %02d".format(s / 3600, s % 3600 / 60) else "%d min %02d s".format(s / 60, s % 60)
}

private fun formatMontageSize(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f Go".format(bytes / 1e9) else "${bytes / 1_000_000} Mo"
