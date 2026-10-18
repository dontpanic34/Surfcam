package com.surfcam.app.service

import android.app.NotificationManager
import android.content.Context

/**
 * « Ne pas déranger » en silence total pendant la session : ni sonnerie ni bandeau. Le réglage d'avant est mémorisé
 * puis remis à l'arrêt. Demande une autorisation que l'utilisateur donne une fois dans les réglages Android.
 */
object DoNotDisturb {
    private const val PREFS = "dnd"
    private const val KEY_PREVIOUS = "previous"

    fun isAllowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Active le silence total ; sans autorisation, ne fait rien. */
    fun enable(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Déjà activé par nous : on garde le réglage d'origine mémorisé.
        if (!prefs.contains(KEY_PREVIOUS)) prefs.edit().putInt(KEY_PREVIOUS, manager.currentInterruptionFilter).apply()
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    }

    /** Remet le réglage d'avant, seulement si c'est nous qui l'avions changé. */
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREVIOUS)) return
        val previous = prefs.getInt(KEY_PREVIOUS, NotificationManager.INTERRUPTION_FILTER_ALL)
        prefs.edit().remove(KEY_PREVIOUS).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.isNotificationPolicyAccessGranted) {
            manager.setInterruptionFilter(
                if (previous == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else previous,
            )
        }
    }
}
