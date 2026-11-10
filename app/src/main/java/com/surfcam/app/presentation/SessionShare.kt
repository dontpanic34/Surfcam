package com.surfcam.app.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.surfcam.app.data.engine.SessionLog
import com.surfcam.app.data.library.VideoLibrary

/** Partage d'un coup le rapport de la dernière session, les rapports de ses clips et ses vidéos. */
object SessionShare {

    /** Il y a quelque chose à partager. */
    fun available(context: Context): Boolean = SessionLog.lastExport(context) != null

    /**
     * Ouvre le menu de partage Android avec : le rapport de session, un petit rapport par clip, les clips de vagues et,
     * si [withSessionFiles], les fichiers de session (gros : environ 1,2 Go par partie de 2 minutes en 4K).
     * Renvoie un message si rien n'a pu être préparé.
     */
    /**
     * Mode test : TOUT ce que la dernière session a produit — vagues gardées, vagues refusées, fichiers de session complète,
     * rapport de session, rapports de chaque clip, journal de capteurs — pour l'envoyer d'un coup dans le Drive.
     */
    fun shareEverything(context: Context): String? {
        val uris = ArrayList<Uri>()
        SessionLog.lastExport(context)?.first?.let(uris::add)
        uris += SessionLog.clipReportUris(context)
        val prefs = context.getSharedPreferences("rapport", Context.MODE_PRIVATE)
        prefs.getString("journalUri", null)?.let { uris += Uri.parse(it) }
        val since = prefs.getLong("startMs", 0L) - 60_000L
        val names = SessionLog.sessionFiles(context).toSet()
        val lib = VideoLibrary(context)
        (lib.list() + lib.listRejected()).filter { it.modifiedMs >= since || it.name.removeSuffix(".mp4") in names }.forEach { uris += it.uri }
        if (uris.isEmpty()) return "Rien à partager : fais d'abord une session."
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "SurfCam : tout de la dernière session (vagues, refusées, session, rapports, journal)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Tout exporter").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    fun share(context: Context, withSessionFiles: Boolean): String? {
        val uris = ArrayList<Uri>()
        SessionLog.lastExport(context)?.first?.let(uris::add)
        uris += SessionLog.clipReportUris(context)
        val names = SessionLog.sessionFiles(context).toSet()
        val videos = VideoLibrary(context).list().filter { it.name.removeSuffix(".mp4") in names && (withSessionFiles || !it.isSession) }
        videos.forEach { uris += it.uri }
        if (uris.isEmpty()) return "Rien à partager : fais d'abord une session."
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "SurfCam : rapport et vagues de la dernière session")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Partager la session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}
