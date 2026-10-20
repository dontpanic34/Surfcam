package com.surfcam.app.data.engine

import android.content.Context

/** Mémorise les choix de l'utilisateur (qualité, cadence, support, options) d'une session à l'autre. */
class OptionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("options", Context.MODE_PRIVATE)

    fun load(): SessionOptions {
        val d = SessionOptions()
        // Une mise à jour avait laissé la détection décochée chez certains : on la remet en marche une fois pour toutes.
        if (!prefs.getBoolean("detectionResetV2", false)) {
            prefs.edit().putBoolean("detection", true).putBoolean("detectionResetV2", true).apply()
        }
        return SessionOptions(
            tele = prefs.getBoolean("tele", d.tele),
            fullSession = prefs.getBoolean("fullSession", d.fullSession),
            hd = prefs.getBoolean("hd", d.hd),
            fps60 = prefs.getBoolean("fps60", d.fps60),
            storage = prefs.getString("storage", d.storage) ?: d.storage,
            detection = prefs.getBoolean("detection", d.detection),
            sleepScreen = prefs.getBoolean("sleepScreen", d.sleepScreen),
            doNotDisturb = prefs.getBoolean("doNotDisturb", d.doNotDisturb),
            lockExposure = prefs.getBoolean("lockExposure", d.lockExposure),
            reframe = prefs.getBoolean("reframe", d.reframe),
            followGimbal = prefs.getBoolean("followGimbal", d.followGimbal),
            markerColor = com.surfcam.app.domain.detection.MarkerColor.fromName(prefs.getString("markerColor", null)),
        )
    }

    fun save(o: SessionOptions) {
        prefs.edit()
            .putBoolean("tele", o.tele)
            .putBoolean("fullSession", o.fullSession)
            .putBoolean("hd", o.hd)
            .putBoolean("fps60", o.fps60)
            .putString("storage", o.storage)
            .putBoolean("detection", o.detection)
            .putBoolean("sleepScreen", o.sleepScreen)
            .putBoolean("doNotDisturb", o.doNotDisturb)
            .putBoolean("lockExposure", o.lockExposure)
            .putBoolean("reframe", o.reframe)
            .putBoolean("followGimbal", o.followGimbal)
            .putString("markerColor", o.markerColor?.name)
            .apply()
    }
}
