package com.surfcam.app.presentation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.surfcam.app.SurfCamApp
import com.surfcam.app.service.RecordingService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = (application as SurfCamApp).engine

        setContent {
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                if (granted[Manifest.permission.CAMERA] == true) startSession()
            }
            RecordingScreen(
                engine = engine,
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

    private fun startSession() {
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val intent = Intent(this, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_ROTATION_DEGREES, rotation)
        ContextCompat.startForegroundService(this, intent)
    }
}
