package com.surfcam.app.data.gimbal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.surfcam.app.domain.gimbal.FollowBox
import com.surfcam.app.domain.gimbal.FollowController

/**
 * Fait pivoter un gimbal DJI Osmo Mobile pour garder le surfeur au centre de l'image. Utilise l'appareil et le
 * calibrage enregistrés par l'écran « Tête motorisée ». Par sécurité : arrêt si le surfeur est perdu plus de
 * [LOST_TIMEOUT_MS] ou si plus rien n'arrive de l'analyse, et à la fin de la session.
 */
class GimbalFollower(context: Context) {

    private val link = OsmoMobileLink(context)
    private val prefs = context.getSharedPreferences("gimbal", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var enabled = true
        set(value) {
            field = value
            if (!value) haltOnce()
        }

    private var lastSeenAtMs = 0L
    private var lastCommandAtMs = 0L
    private var nextStepAtMs = 0L
    private var lastX: Float? = null
    private var moving = false
    private var gain = 0f
    private var invert = false
    private var started = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            val now = SystemClock.elapsedRealtime()
            if (moving && now - lastCommandAtMs > STALL_TIMEOUT_MS) haltOnce()
            handler.postDelayed(this, 500)
        }
    }

    /** Se connecte au gimbal enregistré. Renvoie un message d'erreur, ou null si la connexion est lancée. */
    fun start(): String? {
        val address = prefs.getString("address", null) ?: return "Aucun gimbal enregistré : fais d'abord l'essai dans « Tête motorisée »"
        invert = prefs.getBoolean("invert", false)
        link.connect(address)
        started = true
        handler.postDelayed(watchdog, 500)
        return null
    }

    fun status(): String {
        val s = link.state.value
        return when {
            s.connected -> if (enabled) "Gimbal connecté · suivi actif" else "Gimbal connecté · suivi en pause"
            s.connecting -> "Gimbal : connexion…"
            else -> "Gimbal : ${s.status}"
        }
    }

    /**
     * À appeler à chaque image analysée : surfeurs vus, champ de vision horizontal de l'image en degrés.
     * Un pas de pivotement à la fois, puis une pause le temps que le gimbal bouge et que l'analyse rattrape son retard.
     */
    fun onFrame(boxes: List<FollowBox>, hfovDeg: Float) {
        if (!started || !enabled || !link.state.value.connected) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextStepAtMs) return
        val target = FollowController.chooseTarget(boxes, lastX) ?: return
        lastX = target.centerX
        val step = FollowController.stepDegrees((target.centerX - 0.5f) * hfovDeg)
        if (step == 0f) return
        val seconds = FollowController.moveSeconds(step)
        link.rotateBy(if (invert) -step else step, seconds)
        // Le mouvement dure [seconds] ; on attend ensuite l'analyse (retard d'environ une seconde) avant de re-mesurer.
        nextStepAtMs = now + ((seconds + SETTLE_SECONDS) * 1000).toLong()
    }

    private fun haltOnce() {
        if (moving) link.stop()
        moving = false
    }

    fun stop() {
        started = false
        handler.removeCallbacks(watchdog)
        link.disconnect()
    }

    private companion object {
        const val LOST_TIMEOUT_MS = 1_200L
        const val SETTLE_SECONDS = 1.0f
        const val STALL_TIMEOUT_MS = 1_500L
    }
}
