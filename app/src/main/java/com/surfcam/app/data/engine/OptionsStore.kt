package com.surfcam.app.data.engine

import android.content.Context

/** Mémorise les choix de l'utilisateur (qualité, cadence, support, options) d'une session à l'autre. */
class OptionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("options", Context.MODE_PRIVATE)

    fun load(): SessionOptions {
        val d = SessionOptions()
        // Phase de test : on garde toute la session (le rush complet sert au débogage), une seule fois.
        if (!prefs.getBoolean("testModeV1", false)) {
            prefs.edit().putBoolean("fullSession", true).putBoolean("testModeV1", true).apply()
        }
        return SessionOptions(
            fullSession = prefs.getBoolean("fullSession", d.fullSession),
            storage = prefs.getString("storage", d.storage) ?: d.storage,
            sleepScreen = prefs.getBoolean("sleepScreen", d.sleepScreen),
            doNotDisturb = prefs.getBoolean("doNotDisturb", d.doNotDisturb),
            lockExposure = prefs.getBoolean("lockExposure", d.lockExposure),
            followGimbal = prefs.getBoolean("followGimbal", d.followGimbal),
            ledBlink = prefs.getBoolean("ledBlink", d.ledBlink),
            subject = if (prefs.getString("subject", null) == "ME") Subject.ME else Subject.EVERYONE,
            markerColor = com.surfcam.app.domain.detection.MarkerColor.fromName(prefs.getString("markerColor", null)),
        )
    }

    fun save(o: SessionOptions) {
        prefs.edit()
            .putBoolean("fullSession", o.fullSession)
            .putString("storage", o.storage)
            .putBoolean("sleepScreen", o.sleepScreen)
            .putBoolean("doNotDisturb", o.doNotDisturb)
            .putBoolean("lockExposure", o.lockExposure)
            .putBoolean("followGimbal", o.followGimbal)
            .putBoolean("ledBlink", o.ledBlink)
            .putString("subject", o.subject.name)
            .putString("markerColor", o.markerColor?.name)
            .apply()
    }
}
