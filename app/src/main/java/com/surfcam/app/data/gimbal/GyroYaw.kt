package com.surfcam.app.data.gimbal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.surfcam.app.domain.gimbal.GyroMath

/**
 * Rotation réelle du téléphone autour de la verticale, mesurée au gyroscope (horodatage monotone du capteur) : sert
 * uniquement à vérifier ce que le gimbal a réellement fait. Positif vers la droite (sens horaire vu d'en haut).
 * N'agit sur rien : instrument de mesure.
 */
class GyroYaw(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val g = FloatArray(3)
    private var lastTs = 0L

    /** Rotation cumulée depuis le démarrage, en degrés (positif vers la droite). */
    @Volatile
    var cumulativeDeg = 0f
        private set

    /** Vitesse de rotation instantanée en degrés par seconde (positif vers la droite). */
    @Volatile
    var rateDegPerSec = 0f
        private set

    val available: Boolean get() = gyro != null && gravitySensor != null

    fun start() {
        if (!available) return
        lastTs = 0L
        manager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        manager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                // L'accéléromètre brut est lissé ; le capteur de gravité est déjà propre.
                val a = if (e.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
                for (i in 0..2) g[i] += a * (e.values[i] - g[i])
            }
            Sensor.TYPE_GYROSCOPE -> {
                val ts = e.timestamp
                if (lastTs != 0L) {
                    val dt = (ts - lastTs) / 1e9f
                    if (dt in 0f..0.5f) {
                        val up = GyroMath.rateAboutUp(e.values[0], e.values[1], e.values[2], g[0], g[1], g[2])
                        // Anti-horaire vu d'en haut = vers la gauche : on inverse pour compter vers la droite.
                        val panRight = -Math.toDegrees(up.toDouble()).toFloat()
                        rateDegPerSec = panRight
                        cumulativeDeg += panRight * dt
                    }
                }
                lastTs = ts
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
