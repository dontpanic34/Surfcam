package com.surfcam.app.domain.clip

import com.surfcam.app.domain.tracking.WaveEvent

sealed interface ClipAction {
    /** Démarrer un clip dont le contenu commence à [fromUs] (le décollage moins la marge avant). */
    data class Start(val fromUs: Long) : ClipAction

    /** Terminer le clip : plus aucune image après [atUs]. */
    data class Stop(val atUs: Long) : ClipAction

    /** Jeter le clip en cours : le mouvement détecté n'était pas une vague. */
    data object Discard : ClipAction
}

/**
 * Transforme les événements de vague en instructions d'enregistrement : marge de [preRollUs] avant le
 * décollage, marge de [postRollUs] après la fin. Si une nouvelle vague démarre avant la fin de la marge d'une
 * précédente (un surfeur qui ralentit puis repart, deux surfeurs d'affilée), le même clip se prolonge au lieu de
 * produire deux fichiers qui se chevauchent.
 */
class ClipPlanner(
    private val preRollUs: Long = 5_000_000,
    private val postRollUs: Long = 3_000_000,
) {
    private var open = false
    private var waveActive = false
    private var stopAtUs: Long? = null
    private var stopAtBeforeExtension: Long? = null
    private var openedByCurrentWave = false

    val isRecording: Boolean get() = open

    fun onWave(event: WaveEvent, nowUs: Long): List<ClipAction> = when (event) {
        is WaveEvent.Started -> {
            waveActive = true
            if (open) {
                stopAtBeforeExtension = stopAtUs
                stopAtUs = null
                openedByCurrentWave = false
                emptyList()
            } else {
                open = true
                openedByCurrentWave = true
                listOf(ClipAction.Start(event.takeoffUs - preRollUs))
            }
        }

        is WaveEvent.Ended -> {
            waveActive = false
            stopAtUs = event.endUs + postRollUs
            onTick(nowUs)
        }

        is WaveEvent.Cancelled -> {
            waveActive = false
            if (open && openedByCurrentWave) {
                open = false
                stopAtUs = null
                listOf(ClipAction.Discard)
            } else {
                // Le prolongement n'était qu'un faux départ : on reprend la fin prévue pour le clip précédent.
                stopAtUs = stopAtBeforeExtension
                onTick(nowUs)
            }
        }
    }

    /** À appeler à chaque image : termine le clip quand la marge après la vague est écoulée. */
    fun onTick(nowUs: Long): List<ClipAction> {
        val stopAt = stopAtUs ?: return emptyList()
        if (!open || waveActive || nowUs < stopAt) return emptyList()
        open = false
        stopAtUs = null
        return listOf(ClipAction.Stop(stopAt))
    }
}
