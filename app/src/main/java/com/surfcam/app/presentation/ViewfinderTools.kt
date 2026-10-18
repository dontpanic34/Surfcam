package com.surfcam.app.presentation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Inclinaison du téléphone par rapport à l'horizontale, en degrés, dans le sens des aiguilles d'une montre :
 * c'est l'angle sous lequel l'horizon apparaîtrait dans l'image. Mesuré par la gravité (capteur du téléphone),
 * lissé pour ne pas trembler. Null si le téléphone n'a pas de capteur utilisable.
 */
@Composable
fun rememberHorizonAngle(): State<Float?> {
    val context = LocalContext.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val angle = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(rotation) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var gx = 0f
        var gy = 0f
        var started = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!started) {
                    gx = e.values[0]; gy = e.values[1]; started = true
                } else {
                    gx += SMOOTHING * (e.values[0] - gx)
                    gy += SMOOTHING * (e.values[1] - gy)
                }
                // Haut de l'écran exprimé dans les axes du téléphone, selon l'orientation de l'écran.
                val theta = when (rotation) {
                    Surface.ROTATION_90 -> atan2(-gy, gx)
                    Surface.ROTATION_270 -> atan2(gy, -gx)
                    Surface.ROTATION_180 -> atan2(-gx, -gy)
                    else -> atan2(gx, gy)
                }
                angle.value = Math.toDegrees(theta.toDouble()).toFloat()
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return angle
}

private const val SMOOTHING = 0.12f

/** Niveau à bulle : un repère horizontal fixe, la ligne d'horizon réelle qui s'incline, et l'angle en degrés. */
@Composable
fun HorizonLevel(angleDeg: Float, modifier: Modifier = Modifier) {
    val off = abs(angleDeg)
    val color = when {
        off <= 0.5f -> SurfColors.Ok
        off <= 2f -> SurfColors.Warn
        else -> SurfColors.Alert
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val half = 34.dp.toPx()
            val c = center
            drawLine(Color(0x99FFFFFF), Offset(c.x - half, c.y), Offset(c.x + half, c.y), strokeWidth = 1.dp.toPx())
            rotate(degrees = angleDeg, pivot = c) {
                drawLine(color, Offset(c.x - half, c.y + 4.dp.toPx()), Offset(c.x + half, c.y + 4.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** « 0,3° » : l'angle seul, comme l'appareil photo du Pixel ; « niveau » quand on est à moins de 0,5°. */
fun levelLabel(angleDeg: Float): String {
    val off = abs(angleDeg)
    return if (off <= 0.5f) "%.1f° ✓".format(off).replace('.', ',') else "%.1f°".format(off).replace('.', ',')
}

fun levelColor(angleDeg: Float): Color = when {
    abs(angleDeg) <= 0.5f -> SurfColors.Ok
    abs(angleDeg) <= 2f -> SurfColors.Warn
    else -> SurfColors.Alert
}

/** Batterie et température mesurées par le téléphone (la température est celle de la batterie, la seule exposée aux apps). */
data class DeviceStatus(val batteryPercent: Int, val charging: Boolean, val temperatureC: Float?)

@Composable
fun rememberDeviceStatus(): State<DeviceStatus?> {
    val context = LocalContext.current
    return produceState<DeviceStatus?>(null) {
        while (true) {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                value = DeviceStatus(
                    batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                    temperatureC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
                )
            }
            delay(5_000)
        }
    }
}

/** Couleur d'après la température de la batterie : au-delà de 40 °C le téléphone commence à se protéger. */
fun temperatureColor(c: Float): Color = when {
    c < 38f -> SurfColors.Ok
    c < 42f -> SurfColors.Warn
    else -> SurfColors.Alert
}

/** Écran noir de veille : presque aucune consommation sur un écran OLED, un toucher réveille. */
@Composable
fun SleepOverlay(status: DeviceStatus?, onWake: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInputWake(onWake),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            "Enregistrement en cours" + (status?.let { " · batterie ${it.batteryPercent} %" } ?: "") + " · touche l'écran pour réveiller",
            color = Color(0xFF3A4650),
            fontSize = 11.sp,
            modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

private fun Modifier.pointerInputWake(onWake: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                if (event.changes.any { it.pressed }) onWake()
            }
        }
    }
