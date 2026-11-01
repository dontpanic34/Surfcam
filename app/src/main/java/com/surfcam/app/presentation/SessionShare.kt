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
