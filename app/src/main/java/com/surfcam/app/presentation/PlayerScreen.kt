package com.surfcam.app.presentation

import android.content.Context
import android.content.res.Configuration
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ce que l'on sait du fichier filmé : définition, cadence, codec. */
private data class VideoInfo(val width: Int, val height: Int, val fps: Float?, val codec: String?)

private fun readVideoInfo(context: Context, uri: Uri): VideoInfo? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        (0 until extractor.trackCount).firstNotNullOfOrNull { i ->
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return@firstNotNullOfOrNull null
            if (!mime.startsWith("video/")) return@firstNotNullOfOrNull null
            val rotated = f.containsKey(MediaFormat.KEY_ROTATION) && f.getInteger(MediaFormat.KEY_ROTATION) % 180 != 0
            val w = f.getInteger(MediaFormat.KEY_WIDTH)
            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                } catch (_: ClassCastException) {
                    f.getFloat(MediaFormat.KEY_FRAME_RATE)
                }
            } else null
            VideoInfo(if (rotated) h else w, if (rotated) w else h, fps?.takeIf { it > 0f }, if (mime.contains("hevc")) "H.265" else if (mime.contains("avc")) "H.264" else mime.substringAfter('/'))
        }
    } catch (_: Exception) {
        null
    } finally {
        extractor.release()
    }
}

/**
 * Lecteur intégré : balayage du doigt sur la barre pour avancer image par image, boutons d'image précédente/suivante,
 * ralenti, glissement horizontal sur la vidéo pour changer de clip, suppression, et jugement « Bonne vague » /
 * « Fausse alerte » pour les clips créés par la détection.
 */
@Composable
fun PlayerScreen(
    videos: List<LibraryVideo>,
    startIndex: Int,
    learning: LearningRepository,
    onDelete: (LibraryVideo) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var currentKey by remember { mutableStateOf(videos[startIndex.coerceIn(0, videos.lastIndex)].key) }
    val index = videos.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    val video = videos[index]
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    BackHandler(onBack = onBack)

    val player = remember {
        ExoPlayer.Builder(context).build().also { it.setSeekParameters(SeekParameters.EXACT) }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var wasPlaying by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var stepPosition by remember { mutableStateOf(0.0) }
    var feedback by remember { mutableStateOf<FeedbackRecord?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }

    LaunchedEffect(video.key) {
        player.setMediaItem(MediaItem.fromUri(video.uri))
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = true
        report = null
        info = null
        feedback = withContext(Dispatchers.IO) { learning.records().firstOrNull { it.clipName == video.name } }
        info = withContext(Dispatchers.IO) { readVideoInfo(context, video.uri) }
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
            if (!scrubbing) positionMs = player.currentPosition
            durationMs = maxOf(player.duration, 0L)
            delay(100)
        }
    }
    // Les commandes se retirent d'elles-mêmes en paysage quand la vidéo joue ; un toucher les ramène.
    LaunchedEffect(interaction, playing, portrait) {
        controlsVisible = true
        if (!portrait && playing) {
            delay(3_500)
            controlsVisible = false
        }
    }

    val fps = info?.fps ?: player.videoFormat?.frameRate?.takeIf { it > 0f }
    fun touched() {
        interaction = System.nanoTime()
    }
    fun goTo(newIndex: Int) {
        if (newIndex in videos.indices) currentKey = videos[newIndex].key
    }
    fun step(direction: Int) {
        player.pause()
        val frameMs = 1000.0 / (fps ?: 30f)
        val base = if (abs(player.currentPosition - stepPosition) > frameMs * 2.5) player.currentPosition.toDouble() else stepPosition
        stepPosition = (base + direction * frameMs).coerceIn(0.0, durationMs.toDouble())
        positionMs = stepPosition.roundToInt().toLong()
        player.seekTo(positionMs)
        touched()
    }
    fun judge(good: Boolean) {
        val record = feedback ?: return
        // Un second toucher sur le même choix le retire.
        val newValue = if (record.good == good) null else good
        feedback = record.copy(good = newValue)
        report = null
        touched()
        Thread {
            learning.setJudgement(video.name, newValue)
            learning.recalibrate()
            report = learning.lastReport()
        }.start()
    }

    val qualityLabel = info?.let { i ->
        val definition = if (maxOf(i.width, i.height) >= 3800) "4K" else "${minOf(i.width, i.height)}p"
        buildString {
            append(definition)
            fps?.let { append(" · ${if (abs(it - it.roundToInt()) < 0.05f) it.roundToInt().toString() else "%.2f".format(it)} i/s") }
            i.codec?.let { append(" · $it") }
            if (video.durationMs > 0) append(" · ${"%.0f".format(video.sizeBytes * 8.0 / (video.durationMs / 1000.0) / 1_000_000.0)} Mb/s")
        }
    }

    val videoSurface: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .pointerInput(index, videos.size) {
                    // Glissement de droite à gauche : clip suivant ; de gauche à droite : clip précédent.
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -120f) goTo(index + 1) else if (total > 120f) goTo(index - 1)
                        },
                    ) { _, dx -> total += dx }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (portrait) {
                            if (player.isPlaying) player.pause() else player.play()
                        } else {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) interaction = System.nanoTime()
                        }
                    })
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
    }

    val scrubBar: @Composable (Modifier) -> Unit = { modifier ->
        Slider(
            value = if (scrubbing) scrubValue else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                if (!scrubbing) {
                    scrubbing = true
                    wasPlaying = player.isPlaying
                    player.pause()
                }
                scrubValue = it
                positionMs = (it * durationMs).toLong()
                stepPosition = positionMs.toDouble()
                player.seekTo(positionMs)
                touched()
            },
            onValueChangeFinished = {
                scrubbing = false
                if (wasPlaying) player.play()
            },
            modifier = modifier,
            colors = SliderDefaults.colors(
                thumbColor = SurfColors.Aqua,
                activeTrackColor = SurfColors.Aqua,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
    }

    val transport: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(PlayerIcon.FRAME_BACK, 44.dp) { step(-1) }
            RoundButton(if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY, 56.dp, filled = true) {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
                touched()
            }
            RoundButton(PlayerIcon.FRAME_NEXT, 44.dp) { step(1) }
            Spacer(Modifier.width(8.dp))
            listOf(0.25f to "×0,25", 0.5f to "×0,5", 1f to "×1", 2f to "×2").forEach { (value, label) ->
                SpeedChip(label, speed == value) {
                    speed = value
                    touched()
                }
            }
        }
    }

    val judgement: @Composable (Boolean) -> Unit = { horizontal ->
        val record = feedback
        if (record != null) {
            val buttons: @Composable () -> Unit = {
                JudgeButton("Bonne vague", record.good == true, SurfColors.Ok) { judge(true) }
                JudgeButton("Fausse alerte", record.good == false, SurfColors.Alert) { judge(false) }
            }
            if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    }

    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(PlayerIcon.BACK, 40.dp, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    VideoNaming.displayTitle(video.name),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1} / ${videos.size}", color = SurfColors.Muted, fontSize = 11.sp)
                    qualityLabel?.let {
                        Text(
                            it, color = SurfColors.Aqua, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            RoundButton(PlayerIcon.PREVIOUS, 40.dp) { goTo(index - 1); touched() }
            RoundButton(PlayerIcon.NEXT, 40.dp) { goTo(index + 1); touched() }
            RoundButton(PlayerIcon.TRASH, 40.dp, danger = true) { confirmDelete = true }
        }
    }

    val timeText = "${formatClock(positionMs)} / ${formatClock(durationMs)}"

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce clip ?") },
            text = { Text("${VideoNaming.displayTitle(video.name)}\nIl sera définitivement supprimé du téléphone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val neighbour = videos.getOrNull(index + 1) ?: videos.getOrNull(index - 1)
                    onDelete(video)
                    if (neighbour != null) currentKey = neighbour.key else onBack()
                }) { Text("Supprimer", color = SurfColors.Alert) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }

    SurfCamTheme {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            if (portrait) {
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    header()
                    videoSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        scrubBar(Modifier.fillMaxWidth())
                        Text(timeText, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { transport() }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { judgement(true) }
                    if (feedback == null) {
                        Text("Clip manuel ou session : pas de jugement à donner.", color = SurfColors.Muted, fontSize = 12.sp)
                    }
                    report?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
                    Text("Glisse la vidéo vers la gauche ou la droite pour changer de clip.", color = SurfColors.Muted, fontSize = 11.sp)
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    videoSurface(Modifier.fillMaxSize())
                    AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp),
                            ) { header() }
                            Column(
                                Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                                horizontalAlignment = Alignment.End,
                            ) { judgement(false) }
                            Column(
                                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                report?.let { Text(it, color = SurfColors.Warn, fontSize = 11.sp) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    scrubBar(Modifier.weight(1f))
                                    Text(timeText, color = Color.White, fontSize = 12.sp)
                                }
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { transport() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PlayerIcon { PLAY, PAUSE, FRAME_BACK, FRAME_NEXT, PREVIOUS, NEXT, BACK, TRASH }

@Composable
private fun RoundButton(icon: PlayerIcon, size: Dp, filled: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (filled) SurfColors.OnAqua else if (danger) SurfColors.Alert else Color.White
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) SurfColors.Aqua else Color(0x80000000))
            .border(1.dp, if (filled) Color.Transparent else Color(0xFF3B5565), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.5f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            fun triangle(left: Float, right: Float, pointRight: Boolean): Path = Path().apply {
                if (pointRight) {
                    moveTo(left, h * 0.1f); lineTo(left, h * 0.9f); lineTo(right, h * 0.5f)
                } else {
                    moveTo(right, h * 0.1f); lineTo(right, h * 0.9f); lineTo(left, h * 0.5f)
                }
                close()
            }
            when (icon) {
                PlayerIcon.PLAY -> drawPath(triangle(w * 0.2f, w * 0.9f, true), tint)
                PlayerIcon.PAUSE -> {
                    drawRect(tint, Offset(w * 0.2f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.8f))
                    drawRect(tint, Offset(w * 0.58f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.8f))
                }
                // Image précédente : barre + triangle vers la gauche ; image suivante : l'inverse.
                PlayerIcon.FRAME_BACK -> {
                    drawRect(tint, Offset(w * 0.1f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.8f))
                    drawPath(triangle(w * 0.35f, w * 0.95f, false), tint)
                }
                PlayerIcon.FRAME_NEXT -> {
                    drawRect(tint, Offset(w * 0.76f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.8f))
                    drawPath(triangle(w * 0.05f, w * 0.65f, true), tint)
                }
                PlayerIcon.PREVIOUS -> {
                    drawLine(tint, Offset(w * 0.65f, h * 0.15f), Offset(w * 0.3f, h * 0.5f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.65f, h * 0.85f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                PlayerIcon.NEXT -> {
                    drawLine(tint, Offset(w * 0.35f, h * 0.15f), Offset(w * 0.7f, h * 0.5f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.35f, h * 0.85f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                PlayerIcon.BACK -> {
                    drawLine(tint, Offset(w * 0.8f, h * 0.5f), Offset(w * 0.2f, h * 0.5f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.5f, h * 0.2f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.5f, h * 0.8f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                PlayerIcon.TRASH -> {
                    drawLine(tint, Offset(w * 0.1f, h * 0.22f), Offset(w * 0.9f, h * 0.22f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.38f, h * 0.1f), Offset(w * 0.62f, h * 0.1f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    val body = Path().apply {
                        moveTo(w * 0.2f, h * 0.22f); lineTo(w * 0.28f, h * 0.92f); lineTo(w * 0.72f, h * 0.92f); lineTo(w * 0.8f, h * 0.22f)
                    }
                    drawPath(body, tint, style = stroke)
                }
            }
        }
    }
}

@Composable
private fun SpeedChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) SurfColors.OnAqua else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) SurfColors.Aqua else Color(0x80000000))
            .border(1.dp, Color(0xFF3B5565), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
    val tenths = ms / 100
    return "%d:%02d,%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}
