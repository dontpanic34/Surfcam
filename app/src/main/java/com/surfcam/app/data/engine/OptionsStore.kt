package com.surfcam.app.data.engine

import android.content.Context

/** Mémorise les choix de l'utilisateur (qualité, cadence, support, options) d'une session à l'autre. */
class OptionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("options", Context.MODE_PRIVATE)

    fun load(): SessionOptions {
        val d = SessionOptions()
        // Le HDR est devenu expérimental (il faisait planter le démarrage) : désactivé une fois pour tous.
        if (!prefs.getBoolean("hdrOffV2", false)) {
            prefs.edit().putBoolean("hdr", false).putBoolean("hdrOffV2", true).apply()
        }
        return SessionOptions(
            tele = prefs.getBoolean("tele", d.tele),
            fullSession = prefs.getBoolean("fullSession", d.fullSession),
            hd = prefs.getBoolean("hd", d.hd),
            fps60 = prefs.getBoolean("fps60", d.fps60),
            storage = prefs.getString("storage", d.storage) ?: d.storage,
            sleepScreen = prefs.getBoolean("sleepScreen", d.sleepScreen),
            doNotDisturb = prefs.getBoolean("doNotDisturb", d.doNotDisturb),
            lockExposure = prefs.getBoolean("lockExposure", d.lockExposure),
            reframe = prefs.getBoolean("reframe", d.reframe),
            followGimbal = prefs.getBoolean("followGimbal", d.followGimbal),
            hdr = prefs.getBoolean("hdr", d.hdr),
            ledBlink = prefs.getBoolean("ledBlink", d.ledBlink),
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
            .putBoolean("sleepScreen", o.sleepScreen)
            .putBoolean("doNotDisturb", o.doNotDisturb)
            .putBoolean("lockExposure", o.lockExposure)
            .putBoolean("reframe", o.reframe)
            .putBoolean("followGimbal", o.followGimbal)
            .putBoolean("hdr", o.hdr)
            .putBoolean("ledBlink", o.ledBlink)
            .putString("markerColor", o.markerColor?.name)
            .apply()
    }
}
