package com.surfcam.app.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import com.surfcam.app.data.engine.SessionOptions
import com.surfcam.app.service.RecordingService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = (application as SurfCamApp).engine

        setContent {
            val app = application as SurfCamApp
            var crash by remember { mutableStateOf(app.readLastCrash()) }
            var tele by rememberSaveable { mutableStateOf(false) }
            var fullSession by rememberSaveable { mutableStateOf(false) }
            var hd by rememberSaveable { mutableStateOf(false) }
            var detection by rememberSaveable { mutableStateOf(true) }
            val options = SessionOptions(tele, fullSession, hd, detection)
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
            RecordingScreen(
                engine = engine,
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
                    detection = it.detection
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
            .putExtra(RecordingService.EXTRA_DETECTION, options.detection)
        ContextCompat.startForegroundService(this, intent)
    }
}
