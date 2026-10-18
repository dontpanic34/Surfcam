package com.surfcam.app.presentation

import android.Manifest
import android.content.Intent
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
            var tele by rememberSaveable { mutableStateOf(false) }
            var fullSession by rememberSaveable { mutableStateOf(true) }
            var hd by rememberSaveable { mutableStateOf(false) }
            var storageVolume by rememberSaveable { mutableStateOf("external_primary") }
            var fps60 by rememberSaveable { mutableStateOf(false) }
            var detection by rememberSaveable { mutableStateOf(true) }
            var sleepScreen by rememberSaveable { mutableStateOf(true) }
            var doNotDisturb by rememberSaveable { mutableStateOf(true) }
            val options = SessionOptions(tele = tele, fullSession = fullSession, hd = hd, fps60 = fps60, storage = storageVolume, detection = detection, sleepScreen = sleepScreen, doNotDisturb = doNotDisturb)
            var showLibrary by rememberSaveable { mutableStateOf(false) }
            val library = remember { VideoLibrary(applicationContext) }
            BackHandler(enabled = showLibrary) { showLibrary = false }
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
            RecordingScreen(
                engine = engine,
                onOpenLibrary = { showLibrary = true },
                options = options,
                hasCamera = hasCamera,
                crashReport = crash,
                onDismissCrash = {
                    app.clearLastCrash()
                    crash = null
                },
                onOptionsChange = {
                    tele = it.tele
                    fullSession = it.fullSession
                    hd = it.hd
                    fps60 = it.fps60
                    storageVolume = it.storage
                    detection = it.detection
                    sleepScreen = it.sleepScreen
                    doNotDisturb = it.doNotDisturb
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
            .putExtra(RecordingService.EXTRA_DETECTION, options.detection)
            .putExtra(RecordingService.EXTRA_DND, options.doNotDisturb)
        ContextCompat.startForegroundService(this, intent)
    }
}
