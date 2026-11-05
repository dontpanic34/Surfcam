package com.surfcam.core.clip

import com.surfcam.core.ride.RideEvent

sealed interface ClipAction {
    /** Démarrer un clip dont le contenu commence à [fromUs] (le take-off moins la marge avant). */
    data class Start(val fromUs: Long) : ClipAction

    /** Terminer le clip : plus aucune image après [atUs]. */
    data class Stop(val atUs: Long) : ClipAction

    /** Fin de la marge après la vague : on n'écrit plus, mais on garde le clip ouvert au cas où la vague reprendrait. */
    data class Hold(val atUs: Long) : ClipAction

    /** La vague a repris pendant l'attente : les images gardées en attente sont écrites, le clip continue. */
    data object Resume : ClipAction

    /** Jeter le clip en cours (rangé à part dans « Vagues rejetées ») : ce n'était pas une vague surfée. */
    data class Discard(val reason: String) : ClipAction
}

/**
 * Transforme les courses en instructions d'enregistrement : marge [preRollUs] avant le take-off, [postRollUs] après la fin.
 * Une vague qui repart pendant la marge après la précédente prolonge le même clip au lieu d'en créer un second.
 */
class ClipPlanner(
    val preRollUs: Long = 5_000_000,
    val postRollUs: Long = 3_000_000,
    private val holdGraceUs: Long = 5_000_000,
) {
    private var holding = false
    private var open = false
    private var rideActive = false
    private var stopAtUs: Long? = null
    private var stopAtBeforeExtension: Long? = null
    private var openedByCurrentRide = false

    val isRecording: Boolean get() = open

    fun onRide(event: RideEvent, nowUs: Long): List<ClipAction> = when (event) {
        is RideEvent.Started -> {
            rideActive = true
            if (open) {
                stopAtBeforeExtension = stopAtUs
                stopAtUs = null
                openedByCurrentRide = false
                if (holding) {
                    holding = false
                    listOf(ClipAction.Resume)
                } else {
                    emptyList()
                }
            } else {
                open = true
                openedByCurrentRide = true
                listOf(ClipAction.Start(event.takeoffUs - preRollUs))
            }
        }

        is RideEvent.Ended -> {
            rideActive = false
            if (event.kept) {
                stopAtUs = event.endUs + postRollUs
                onTick(nowUs)
            } else if (open && openedByCurrentRide) {
                open = false
                holding = false
                stopAtUs = null
                listOf(ClipAction.Discard(event.reason))
            } else {
                // Le prolongement n'était qu'un faux départ : on reprend la fin prévue pour le clip précédent.
                stopAtUs = stopAtBeforeExtension
                onTick(nowUs)
            }
        }
    }

    /** À appeler à chaque image : ferme le clip quand la marge après la vague est écoulée. */
    fun onTick(nowUs: Long): List<ClipAction> {
        val stopAt = stopAtUs ?: return emptyList()
        if (!open || rideActive || nowUs < stopAt) return emptyList()
        if (!holding) {
            holding = true
            return if (nowUs >= stopAt + holdGraceUs) close(stopAt, listOf(ClipAction.Hold(stopAt))) else listOf(ClipAction.Hold(stopAt))
        }
        if (nowUs < stopAt + holdGraceUs) return emptyList()
        return close(stopAt, emptyList())
    }

    /** Arrêt de la session : ferme un clip ouvert. */
    fun finish(nowUs: Long): List<ClipAction> {
        if (!open) return emptyList()
        val at = stopAtUs ?: nowUs
        open = false
        holding = false
        stopAtUs = null
        return listOf(ClipAction.Stop(minOf(at, nowUs)))
    }

    private fun close(stopAt: Long, before: List<ClipAction>): List<ClipAction> {
        open = false
        holding = false
        stopAtUs = null
        return before + ClipAction.Stop(stopAt)
    }
}
