package com.surfcam.app.data.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore

/** Un emplacement d'enregistrement : le téléphone, ou un support branché (carte SD, clé USB). */
data class StorageChoice(
    /** Nom du volume pour MediaStore ("external_primary" pour le téléphone). */
    val name: String,
    val label: String,
    val removable: Boolean,
    /** Espace libre en octets ; null si Android ne le communique pas pour ce support. */
    val freeBytes: Long?,
)

/**
 * Où écrire les vidéos. Le volume peut changer en cours de session : si le support externe disparaît (carte retirée,
 * câble débranché), on retombe sur le téléphone et [onFallback] prévient l'utilisateur.
 */
class StorageTarget(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(StorageManager::class.java)

    @Volatile
    var volume: String = PHONE
        private set

    @Volatile
    var onFallback: ((String) -> Unit)? = null

    val isExternal: Boolean get() = volume != PHONE

    fun select(name: String) {
        volume = name
    }

    /** Les supports actuellement montés et utilisables pour enregistrer (le téléphone est toujours présent). */
    fun available(): List<StorageChoice> = manager.storageVolumes.mapNotNull { v ->
        val name = v.mediaStoreVolumeName ?: return@mapNotNull null
        if (v.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
        StorageChoice(name, labelOf(v), v.isRemovable, freeOf(v))
    }

    fun labelOf(volumeName: String): String =
        manager.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volumeName }?.let(::labelOf) ?: "Support retiré"

    /** Espace libre du volume courant ; null si inconnu. */
    fun freeBytes(): Long? = freeOf(manager.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volume })

    /** Le volume courant est-il toujours monté ? Faux = carte retirée ou câble débranché pour de bon. */
    fun isVolumeMounted(): Boolean = manager.storageVolumes.any { it.mediaStoreVolumeName == volume && it.state == Environment.MEDIA_MOUNTED }

    /** Texte exact de la panne : « retiré » seulement si le support a vraiment disparu, sinon l'erreur d'écriture. */
    fun describeFailure(e: Exception?): String {
        val detail = e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "erreur inconnue"
        return if (isVolumeMounted()) "Écriture impossible sur le support externe, toujours branché ($detail)" else "Support externe retiré ($detail)"
    }

    /**
     * Vérifie qu'on peut vraiment créer et écrire un fichier vidéo sur [volumeName] (2 Mo, effacés ensuite), exactement comme le
     * fera l'enregistrement. Renvoie null si tout va bien, sinon le message de l'erreur.
     */
    fun testWrite(volumeName: String): String? {
        if (volumeName != PHONE && !awaitKnown(volumeName, 4_000L)) return "Le support n'est pas (encore) connu du système de médias : débranche et rebranche le lecteur, puis réessaie."
        val resolver = appContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "surfcam-test.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        var uri: Uri? = null
        return try {
            uri = resolver.insert(videoUri(volumeName), values) ?: return "Impossible de créer un fichier sur ce support."
            resolver.openOutputStream(uri, "w")?.use { it.write(ByteArray(2 * 1024 * 1024)) } ?: return "Impossible d'ouvrir le fichier en écriture."
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName} : ${e.message}"
        } finally {
            uri?.let { try { resolver.delete(it, null, null) } catch (_: Exception) {} }
        }
    }

    private fun awaitKnown(volumeName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isKnownToMediaStore(volumeName)) {
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(250) } catch (_: InterruptedException) { return false }
        }
        return true
    }

    /** MediaStore connaît-il ce volume pour l'instant ? (Un support peut être « monté » pour Android mais pas encore pour MediaStore.) */
    fun isKnownToMediaStore(volumeName: String = volume): Boolean =
        volumeName == PHONE || MediaStore.getExternalVolumeNames(appContext).contains(volumeName)

    /**
     * Attend (jusqu'à [timeoutMs]) que le volume courant soit prêt pour MediaStore. Le message « Volume … not found »
     * apparaît quand un lecteur USB vient de se (re)connecter ou de se réinitialiser (appel de courant de la caméra).
     */
    fun awaitVolumeReady(timeoutMs: Long = 8_000L): Boolean {
        if (!isExternal) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isKnownToMediaStore()) return true
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(250) } catch (_: InterruptedException) { return false }
        }
    }

    fun fallBackToPhone(reason: String) {
        if (!isExternal) return
        com.surfcam.app.data.engine.SessionLog.ev("stockage", "repli sur le téléphone : $reason")
        volume = PHONE
        onFallback?.invoke(reason)
    }

    /**
     * Mesure la vitesse d'écriture soutenue du support [volumeName] (Mo/s) en y écrivant [megabytes] Mo puis en les
     * effaçant ; null si on ne peut pas y écrire. L'enregistrement 4K demande environ 10 Mo/s par flux (clip et session
     * en même temps : 20 Mo/s), avec de la marge.
     */
    fun measureWriteSpeed(volumeName: String, megabytes: Int = 96): Double? {
        val root = manager.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volumeName }?.directory?.path ?: return null
        val dir = appContext.getExternalFilesDirs(null).filterNotNull().firstOrNull { it.path.startsWith(root) } ?: return null
        val file = java.io.File(dir, "speedtest.tmp")
        return try {
            val chunk = ByteArray(1024 * 1024) { (it * 31).toByte() }
            val start = System.nanoTime()
            java.io.FileOutputStream(file).use { out ->
                repeat(megabytes) { out.write(chunk) }
                out.fd.sync()
            }
            megabytes / ((System.nanoTime() - start) / 1e9)
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    private fun labelOf(v: StorageVolume): String =
        if (v.isPrimary) "Téléphone" else (v.getDescription(appContext) ?: "Support externe")

    private fun freeOf(v: StorageVolume?): Long? {
        val dir = v?.directory ?: return null
        val free = try {
            StatFs(dir.path).availableBytes
        } catch (_: Exception) {
            return null
        }
        // Certains supports répondent 0 faute d'accès : on le traite comme « inconnu » plutôt que « plein ».
        return if (free > 0) free else null
    }

    companion object {
        const val PHONE = MediaStore.VOLUME_EXTERNAL_PRIMARY

        fun videoUri(volumeName: String): Uri = MediaStore.Video.Media.getContentUri(volumeName)
    }
}
