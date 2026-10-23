package com.surfcam.app.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.surfcam.app.SurfCamApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.surfcam.app.data.engine.Phase
import com.surfcam.app.data.engine.OptionsStore
import com.surfcam.app.data.engine.SessionOptions
import com.surfcam.app.data.library.VideoLibrary
import com.surfcam.app.service.RecordingService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = (application as SurfCamApp).engine
        // Si une session s'est mal terminée, on ne laisse pas le téléphone en silence total.
        if (engine.state.value.phase != Phase.RUNNING && engine.state.value.phase != Phase.STARTING) {
            com.surfcam.app.service.DoNotDisturb.restore(this)
        }

        setContent {
            val app = application as SurfCamApp
            var crash by remember { mutableStateOf(app.readLastCrash()) }
            val optionsStore = remember { OptionsStore(applicationContext) }
            var options by remember { mutableStateOf(optionsStore.load()) }
            var showLibrary by rememberSaveable { mutableStateOf(false) }
            // L'accueil (portrait) est l'écran de départ ; on va directement à la caméra si une session tourne déjà.
            var showCamera by rememberSaveable { mutableStateOf(engine.state.value.phase == Phase.RUNNING || engine.state.value.phase == Phase.STARTING) }
            var showGimbal by rememberSaveable { mutableStateOf(false) }
            val library = remember { VideoLibrary(applicationContext) }
            BackHandler(enabled = showLibrary) { showLibrary = false }
            val enginePhase by engine.state.collectAsStateWithLifecycle()
            BackHandler(enabled = !showLibrary && showCamera && enginePhase.phase != Phase.RUNNING && enginePhase.phase != Phase.STARTING) { showCamera = false }
            var hasCamera by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
            LaunchedEffect(Unit) {
                if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA)
            }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                if (granted[Manifest.permission.CAMERA] == true) {
                    hasCamera = true
                    startSession(options)
                }
            }
            // La bibliothèque et le lecteur suivent l'orientation du téléphone ; l'écran caméra reste en paysage (trépied).
            LaunchedEffect(showLibrary, showGimbal, showCamera) {
                requestedOrientation = if (showLibrary || showGimbal || !showCamera) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (showGimbal) {
                GimbalScreen(onBack = { showGimbal = false })
                return@setContent
            }
            if (showLibrary) {
                val phase by engine.state.collectAsStateWithLifecycle()
                LibraryScreen(
                    library = library,
                    learning = engine.learning,
                    sessionRunning = phase.phase == Phase.RUNNING || phase.phase == Phase.STARTING,
                    onBack = { showLibrary = false },
                )
                return@setContent
            }
            if (!showCamera) {
                HomeScreen(
                    engine = engine,
                    options = options,
                    onOptionsChange = {
                        options = it
                        optionsStore.save(it)
                        engine.setMarkerColor(it.markerColor)
                    },
                    crashReport = crash,
                    onDismissCrash = {
                        app.clearLastCrash()
                        crash = null
                    },
                    onOpenCamera = { showCamera = true },
                    onOpenLibrary = { showLibrary = true },
                    onOpenGimbal = { showGimbal = true },
                )
                return@setContent
            }
            RecordingScreen(
                engine = engine,
                onOpenHome = { showCamera = false },
                onOpenLibrary = { showLibrary = true },
                onOpenGimbal = { showGimbal = true },
                options = options,
                hasCamera = hasCamera,
                crashReport = crash,
                onDismissCrash = {
                    app.clearLastCrash()
                    crash = null
                },
                onOptionsChange = {
                    options = it
                    optionsStore.save(it)
                    engine.setMarkerColor(it.markerColor)
                },
                onStartSession = {
                    val wanted = buildList {
                        add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                },
                onStopSession = {
                    startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                },
            )
        }
    }

    private fun startSession(options: SessionOptions) {
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val intent = Intent(this, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_ROTATION_DEGREES, rotation)
            .putExtra(RecordingService.EXTRA_TELE, options.tele)
            .putExtra(RecordingService.EXTRA_FULL_SESSION, options.fullSession)
            .putExtra(RecordingService.EXTRA_HD, options.hd)
            .putExtra(RecordingService.EXTRA_STORAGE, options.storage)
            .putExtra(RecordingService.EXTRA_FPS60, options.fps60)
            .putExtra(RecordingService.EXTRA_DND, options.doNotDisturb)
            .putExtra(RecordingService.EXTRA_LOCK_EXPOSURE, options.lockExposure)
            .putExtra(RecordingService.EXTRA_REFRAME, options.reframe)
            .putExtra(RecordingService.EXTRA_FOLLOW_GIMBAL, options.followGimbal)
            .putExtra(RecordingService.EXTRA_HDR, options.hdr)
            .putExtra(RecordingService.EXTRA_LED, options.ledBlink)
            .putExtra(RecordingService.EXTRA_SUBJECT, options.subject.name)
            .putExtra(RecordingService.EXTRA_MARKER, options.markerColor?.name)
        ContextCompat.startForegroundService(this, intent)
    }
}
