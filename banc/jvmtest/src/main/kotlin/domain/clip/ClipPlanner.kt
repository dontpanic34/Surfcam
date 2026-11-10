package com.surfcam.app.domain.clip

import com.surfcam.app.domain.tracking.WaveEvent

sealed interface ClipAction {
    /** Démarrer un clip dont le contenu commence à [fromUs] (le décollage moins la marge avant). */
    data class Start(val fromUs: Long) : ClipAction

    /** Terminer le clip : plus aucune image après [atUs]. */
    data class Stop(val atUs: Long) : ClipAction

    /**
     * Fin de la marge après la vague : on cesse d'écrire dans le fichier (les images suivantes sont gardées en attente)
     * mais on ne ferme pas encore le clip, au cas où le surfeur réapparaîtrait (suivi perdu dans l'écume).
     */
    data class Hold(val atUs: Long) : ClipAction

    /** Le surfeur est reparti pendant l'attente : les images gardées en attente sont écrites, le clip continue. */
    data object Resume : ClipAction

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
    /** Temps d'attente, après la marge, avant de fermer vraiment le clip : une vague coupée en deux est recollée. */
    private val holdGraceUs: Long = 5_000_000,
) {
    private var holding = false
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
                if (holding) {
                    holding = false
                    listOf(ClipAction.Resume)
                } else {
                    emptyList()
                }
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
                holding = false
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
        if (!holding) {
            holding = true
            return if (nowUs >= stopAt + holdGraceUs) closeNow(stopAt, listOf(ClipAction.Hold(stopAt))) else listOf(ClipAction.Hold(stopAt))
        }
        if (nowUs < stopAt + holdGraceUs) return emptyList()
        return closeNow(stopAt, emptyList())
    }

    private fun closeNow(stopAt: Long, before: List<ClipAction>): List<ClipAction> {
        open = false
        holding = false
        stopAtUs = null
        return before + ClipAction.Stop(stopAt)
    }
}
