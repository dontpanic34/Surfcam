package com.surfcam.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Envoie en arrière-plan, vers le support choisi (carte SD), les fichiers que l'enregistrement vient d'écrire sur le téléphone.
 * L'enregistrement ne dépend ainsi jamais de la carte (bus USB, hub, alimentation) : si elle disparaît ou se réinitialise, les
 * fichiers attendent sur le téléphone et la copie reprend dès qu'elle revient. Un fichier n'est effacé du téléphone qu'une fois
 * sa copie vérifiée à l'octet près. Les envois en attente survivent à un redémarrage de l'appli.
 */
object SdMover {
    private const val SEP = "\u0001"
    private data class Job(val uri: String, val name: String, val volume: String, val path: String) {
        fun encode() = listOf(uri, name, volume, path).joinToString(SEP)
    }

    private val queue = LinkedBlockingQueue<Job>()
    private var worker: Thread? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("sdmover", Context.MODE_PRIVATE)

    private fun persisted(c: Context): List<Job> = (prefs(c).getStringSet("jobs", emptySet()) ?: emptySet()).mapNotNull {
        val p = it.split(SEP)
        if (p.size == 4) Job(p[0], p[1], p[2], p[3]) else null
    }

    private fun save(c: Context, jobs: List<Job>) {
        prefs(c).edit().putStringSet("jobs", jobs.map { it.encode() }.toSet()).apply()
    }

    @Synchronized
    fun pendingCount(c: Context): Int = persisted(c).size

    /** Met un fichier (déjà sur le téléphone) en file d'attente pour la carte [volume], dans le dossier [relativePath]. */
    @Synchronized
    fun enqueue(c: Context, uri: Uri?, name: String, volume: String, relativePath: String) {
        if (uri == null || volume == StorageTarget.PHONE) return
        val job = Job(uri.toString(), name, volume, relativePath)
        save(c, persisted(c) + job)
        queue.add(job)
        ensureWorker(c.applicationContext)
        StorageDiag.log("ENVOI VERS LA CARTE : « $name » mis en file d'attente (${persisted(c).size} en attente)")
    }

    /** À appeler au démarrage de l'appli : reprend les envois restés en attente. */
    @Synchronized
    fun resume(c: Context) {
        val jobs = persisted(c)
        if (jobs.isEmpty()) return
        queue.clear()
        queue.addAll(jobs)
        ensureWorker(c.applicationContext)
        StorageDiag.log("ENVOI VERS LA CARTE : reprise de ${jobs.size} fichier(s) en attente")
    }

    @Synchronized
    private fun ensureWorker(app: Context) {
        if (worker?.isAlive == true) return
        worker = Thread({ loop(app) }, "sd-mover").also { it.start() }
    }

    private fun loop(app: Context) {
        while (true) {
            val job = queue.poll(20, TimeUnit.SECONDS) ?: return
            val done = try { process(app, job) } catch (e: Exception) {
                StorageDiag.log("ENVOI VERS LA CARTE : erreur sur « ${job.name} » : ${e.javaClass.simpleName} ${e.message}")
                false
            }
            if (!done) {
                // La carte n'est pas là ou la copie a échoué : on réessaie plus tard, sans bloquer les autres.
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { return }
                queue.add(job)
            }
        }
    }

    /** Renvoie vrai si le fichier est sur la carte et effacé du téléphone, ou s'il n'existe plus. */
    private fun process(app: Context, job: Job): Boolean {
        val resolver = app.contentResolver
        val source = Uri.parse(job.uri)
        val sourceSize = try { resolver.openFileDescriptor(source, "r")?.use { it.statSize } } catch (_: Exception) { null }
        if (sourceSize == null || sourceSize <= 0) {
            forget(app, job)
            StorageDiag.log("ENVOI VERS LA CARTE : « ${job.name} » n'existe plus sur le téléphone, abandon")
            return true
        }
        if (!MediaStore.getExternalVolumeNames(app).contains(job.volume)) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, job.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, job.path)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(StorageTarget.videoUri(job.volume), values) ?: return false
        try {
            resolver.openInputStream(source)!!.use { input -> resolver.openOutputStream(target, "w")!!.use { output -> input.copyTo(output) } }
            val copied = resolver.openFileDescriptor(target, "r")?.use { it.statSize } ?: -1L
            if (copied != sourceSize) {
                resolver.delete(target, null, null)
                StorageDiag.log("ENVOI VERS LA CARTE : copie de « ${job.name} » incomplète ($copied sur $sourceSize octets), nouvel essai")
                return false
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { resolver.delete(target, null, null) } catch (_: Exception) {}
            throw e
        }
        // Conserver la copie téléphone : la référence SurfCam interdit toute suppression automatique.
        forget(app, job)
        StorageDiag.log("ENVOI VERS LA CARTE : « ${job.name} » copié sur la carte (${sourceSize / (1024 * 1024)} Mo) ; copie téléphone conservée")
        return true
    }

    @Synchronized
    private fun forget(app: Context, job: Job) {
        save(app, persisted(app).filter { it.encode() != job.encode() })
    }
}
