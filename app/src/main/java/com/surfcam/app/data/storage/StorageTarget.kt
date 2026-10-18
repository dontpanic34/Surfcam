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

    fun fallBackToPhone(reason: String) {
        if (!isExternal) return
        volume = PHONE
        onFallback?.invoke(reason)
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
