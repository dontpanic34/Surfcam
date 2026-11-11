package com.surfcam.app.data.gimbal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

/**
 * Inclinaison du téléphone (degrés, sens horaire) mesurée par la gravité, hors de l'interface : sert à la mise à niveau
 * automatique lancée par le moteur. Même calcul que l'horizon affiché à l'écran.
 */
class GravityRoll(context: Context, private val rotationDegrees: Int) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var started = false

    @Volatile
    var rollDeg: Float? = null
        private set

    /** Bascule avant/arrière du téléphone (degrés) : sert à repérer un ordre qui incline de haut en bas au lieu de tourner. */
    @Volatile
    var pitchDeg: Float? = null
        private set

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        if (!started) {
            gx = e.values[0]; gy = e.values[1]; gz = e.values[2]; started = true
        } else {
            gx += 0.2f * (e.values[0] - gx)
            gy += 0.2f * (e.values[1] - gy)
            gz += 0.2f * (e.values[2] - gz)
        }
        val theta = when (rotationDegrees) {
            90 -> atan2(-gy, gx)
            270 -> atan2(gy, -gx)
            180 -> atan2(-gx, -gy)
            else -> atan2(gx, gy)
        }
        rollDeg = Math.toDegrees(theta.toDouble()).toFloat()
        pitchDeg = Math.toDegrees(kotlin.math.atan2(gz.toDouble(), kotlin.math.hypot(gx.toDouble(), gy.toDouble()))).toFloat()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
}
