package com.surfcam.app.data.gimbal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.surfcam.app.domain.gimbal.FollowBox
import com.surfcam.app.domain.gimbal.FollowController
import kotlin.math.abs

/**
 * Fait pivoter un gimbal DJI Osmo Mobile pour garder le surfeur au centre de l'image. Utilise l'appareil et le
 * calibrage enregistrés par l'écran « Tête motorisée ». Par sécurité : arrêt si le surfeur est perdu plus de
 * [LOST_TIMEOUT_MS] ou si plus rien n'arrive de l'analyse, et à la fin de la session.
 */
class GimbalFollower(context: Context) {

    private val link = GimbalHub.link(context)
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
    private var yawCmdDeg = 0f
    private var prevWorld: Float? = null
    private var prevWorldAtMs = 0L
    private var velocity = 0f
    private var style = FollowController.FollowStyle.NORMAL
    private var zoneDeg = 45f
    private var savedAddress: String? = null
    private var lastReconnectAtMs = 0L

    /** Cherche le surfeur en balayant la zone quand il est perdu (activé quand on le repère par son manchon). */
    @Volatile
    var searchEnabled = false
    private var searchPhase = 0
    private var sweeps = 0
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
            // Liaison perdue (gimbal éteint un instant, trop loin) : on retente toutes les 6 secondes.
            val s = link.state.value
            val address = savedAddress
            if (address != null && !s.connected && !s.connecting && now - lastReconnectAtMs > 6_000) {
                lastReconnectAtMs = now
                link.connect(address)
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Se connecte au gimbal enregistré. Renvoie un message d'erreur, ou null si la connexion est lancée. */
    fun start(): String? {
        val address = prefs.getString("address", null) ?: return "Aucun gimbal enregistré : fais d'abord l'essai dans « Tête motorisée »"
        invert = prefs.getBoolean("invert", false)
        savedAddress = address
        style = FollowController.FollowStyle.fromName(prefs.getString("followStyle", null))
        zoneDeg = prefs.getFloat("zoneDeg", 45f)
        yawCmdDeg = 0f
        prevWorld = null
        velocity = 0f
        nextStepAtMs = 0L
        searchPhase = 0
        sweeps = 0
        if (!link.state.value.connected && !link.state.value.connecting) link.connect(address)
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
     * Un pas de pivotement à la fois, avec anticipation du déplacement du surfeur, puis une courte pause le temps que le
     * gimbal bouge et que l'analyse rattrape son retard.
     */
    fun onFrame(boxes: List<FollowBox>, hfovDeg: Float) {
        if (!started || !enabled || !link.state.value.connected) return
        val now = SystemClock.elapsedRealtime()
        val target = FollowController.chooseTarget(boxes, lastX)
        if (target == null) {
            // Surfeur perdu un moment : on oublie sa vitesse, elle ne vaut plus rien.
            if (now - lastSeenAtMs > LOST_TIMEOUT_MS) {
                prevWorld = null
                velocity = 0f
            }
            // Plus de surfeur depuis un moment : retour à la direction de départ, face à la zone de surf.
            if (searchEnabled && lastSeenAtMs > 0 && now - lastSeenAtMs > SEARCH_AFTER_MS && sweeps < MAX_SWEEPS && now >= nextStepAtMs) {
                searchStep(now, hfovDeg)
            } else if (lastSeenAtMs > 0 && now - lastSeenAtMs > HOME_AFTER_MS && abs(yawCmdDeg) > 3f && now >= nextStepAtMs) {
                returnHome(now)
            }
            return
        }
        lastSeenAtMs = now
        searchPhase = 0
        sweeps = 0
        if (now < nextStepAtMs) return
        lastX = target.centerX
        val error = (target.centerX - 0.5f) * hfovDeg
        // Position du surfeur dans un repère fixe : là où le gimbal est tourné (somme des pas ordonnés) plus l'écart vu.
        val world = yawCmdDeg + error
        prevWorld?.let { pw ->
            val dt = (now - prevWorldAtMs) / 1000f
            if (dt in 0.15f..3f) {
                val v = (world - pw) / dt
                // Un « déplacement » irréaliste (gimbal bougé à la main, cible qui change) n'est pas une vitesse de surfeur.
                if (abs(v) > MAX_PLAUSIBLE_SPEED) velocity = 0f
                else velocity += VELOCITY_SMOOTHING * (v - velocity)
            }
        }
        prevWorld = world
        prevWorldAtMs = now
        // Le gimbal reste immobile tant que le surfeur est dans la partie centrale ; il ne bouge que près du bord.
        val wanted = FollowController.edgeStep(error, velocity, style.leadSeconds, hfovDeg, style.edgeFraction, style.step.maxStepDeg)
        // Le gimbal ne sort jamais de la zone de surf (de part et d'autre de sa direction de départ).
        val step = FollowController.clampToZone(yawCmdDeg, wanted, zoneDeg)
        if (abs(step) < 0.5f) return
        val seconds = ((0.8f + abs(step) / 30f) * style.moveScale).coerceIn(0.6f, 2f)
        link.rotateBy(if (invert) -step else step, seconds)
        yawCmdDeg += step
        nextStepAtMs = now + ((seconds + style.settleSeconds) * 1000).toLong()
    }

    /** Un pas de balayage : vers la droite jusqu'au bord de la zone, puis vers la gauche, puis retour au centre. */
    private fun searchStep(now: Long, hfovDeg: Float) {
        val width = (hfovDeg * 0.8f).coerceAtLeast(5f)
        var next = when (searchPhase) {
            0 -> yawCmdDeg + width
            1 -> yawCmdDeg - width
            else -> yawCmdDeg + width
        }
        when (searchPhase) {
            0 -> if (next >= zoneDeg) { next = zoneDeg; searchPhase = 1 }
            1 -> if (next <= -zoneDeg) { next = -zoneDeg; searchPhase = 2 }
            else -> if (next >= 0f) { next = 0f; searchPhase = 0; sweeps++ }
        }
        val step = next - yawCmdDeg
        if (abs(step) < 0.5f) {
            // Déjà au bord : on passe directement à la phase suivante au prochain tour.
            nextStepAtMs = now + 500
            return
        }
        val seconds = (abs(step) / 25f + 0.6f).coerceIn(0.8f, 2.5f)
        link.rotateBy(if (invert) -step else step, seconds)
        yawCmdDeg = next
        prevWorld = null
        velocity = 0f
        nextStepAtMs = now + ((seconds + SEARCH_DWELL_SECONDS) * 1000).toLong()
    }

    private fun returnHome(now: Long) {
        val back = -yawCmdDeg
        val seconds = (abs(back) / 30f + 0.8f).coerceIn(1f, 4f)
        link.rotateBy(if (invert) -back else back, seconds)
        yawCmdDeg = 0f
        prevWorld = null
        velocity = 0f
        nextStepAtMs = now + ((seconds + 0.5f) * 1000).toLong()
    }

    private fun haltOnce() {
        if (moving) link.stop()
        moving = false
    }

    fun stop() {
        started = false
        handler.removeCallbacks(watchdog)
        // La liaison reste ouverte pour l'accueil ; on arrête seulement le mouvement.
        link.stop()
    }

    private companion object {
        const val LOST_TIMEOUT_MS = 1_200L
        const val HOME_AFTER_MS = 8_000L
        const val SEARCH_AFTER_MS = 12_000L
        const val MAX_SWEEPS = 2
        const val SEARCH_DWELL_SECONDS = 2.5f
        const val VELOCITY_SMOOTHING = 0.5f
        const val MAX_PLAUSIBLE_SPEED = 25f
        const val STALL_TIMEOUT_MS = 1_500L
    }
}
