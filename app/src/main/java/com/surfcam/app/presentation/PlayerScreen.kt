package com.surfcam.app.presentation

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.surfcam.app.data.learning.FeedbackRecord
import com.surfcam.app.data.learning.LearningRepository
import com.surfcam.app.data.library.LibraryVideo
import com.surfcam.app.domain.naming.VideoNaming
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lecteur intégré : la vidéo, des commandes (lecture, barre, vitesse) et, pour un clip créé par la détection, les boutons
 * « Bonne vague » / « Fausse alerte » directement sur l'image. En portrait, la vidéo est en haut et les commandes dessous.
 */
@Composable
fun PlayerScreen(
    videos: List<LibraryVideo>,
    startIndex: Int,
    learning: LearningRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, videos.lastIndex)) }
    val video = videos[index]
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    BackHandler(onBack = onBack)

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var feedback by remember { mutableStateOf<FeedbackRecord?>(null) }
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(video.key) {
        player.setMediaItem(MediaItem.fromUri(video.uri))
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = true
        report = null
        feedback = withContext(Dispatchers.IO) { learning.records().firstOrNull { it.clipName == video.name } }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = maxOf(player.duration, 0L)
            delay(200)
        }
    }

    fun judge(good: Boolean) {
        val record = feedback ?: return
        // Un second toucher sur le même choix le retire.
        val newValue = if (record.good == good) null else good
        feedback = record.copy(good = newValue)
        report = null
        Thread {
            learning.setJudgement(video.name, newValue)
            learning.recalibrate()
            report = learning.lastReport()
        }.start()
    }

    val videoView: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
        )
    }
    val judgement: @Composable () -> Unit = {
        val record = feedback
        if (record != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JudgeButton("Bonne vague", record.good == true, SurfColors.Ok) { judge(true) }
                JudgeButton("Fausse alerte", record.good == false, SurfColors.Alert) { judge(false) }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerChip(if (playing) "Pause" else "Lecture", false) {
                    if (player.isPlaying) player.pause() else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { player.seekTo((it * durationMs).toLong()) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = SurfColors.Aqua, activeTrackColor = SurfColors.Aqua),
                )
                Text("${formatClock(positionMs)} / ${formatClock(durationMs)}", color = Color.White, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0.25f to "×0,25", 0.5f to "×0,5", 1f to "×1", 2f to "×2").forEach { (value, label) ->
                    PlayerChip(label, speed == value) { speed = value }
                }
            }
        }
    }

    SurfCamTheme {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            if (portrait) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerHeader(video, index, videos.size, onBack, { if (index > 0) index-- }, { if (index < videos.lastIndex) index++ })
                    videoView(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    controls()
                    judgement()
                    if (feedback == null) Text("Clip manuel ou session : pas de jugement à donner.", color = SurfColors.Muted, fontSize = 12.sp)
                    report?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    videoView(Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
                        Box(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(4.dp)) {
                            PlayerHeader(video, index, videos.size, onBack, { if (index > 0) index-- }, { if (index < videos.lastIndex) index++ })
                        }
                        Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val record = feedback
                            if (record != null) {
                                JudgeButton("Bonne vague", record.good == true, SurfColors.Ok) { judge(true) }
                                JudgeButton("Fausse alerte", record.good == false, SurfColors.Alert) { judge(false) }
                            }
                        }
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.8f).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            report?.let { Text(it, color = SurfColors.Warn, fontSize = 11.sp) }
                            controls()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    video: LibraryVideo,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerChip("‹ Retour", false, onBack)
        PlayerChip("◀", false, onPrevious)
        PlayerChip("▶", false, onNext)
        Text(
            VideoNaming.displayTitle(video.name) + "  (${index + 1}/$total)",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
private fun PlayerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) SurfColors.OnAqua else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) SurfColors.Aqua else Color(0x66000000))
            .border(1.dp, Color(0xFF3B5565), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun JudgeButton(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        (if (selected) "✓ " else "") + text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) color else Color(0xB3000000))
            .border(2.dp, color, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
