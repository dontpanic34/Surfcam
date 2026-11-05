package com.surfcam.app.data.storage

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.surfcam.app.data.engine.SessionLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal et tests pour comprendre pourquoi une carte SD (sur hub USB-C) disparaît : changements d'état des volumes, arrivées et
 * départs USB, branchements du chargeur, courant et tension de la batterie, et un test d'écriture soutenue qui note à la
 * seconde ce qui se passe au moment d'une perte. Tout est horodaté, écrit dans un fichier et dans le rapport de session.
 */
object StorageDiag {
    private const val MAX_LINES = 1500
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var started = false
    private var file: File? = null
    private var lastPlugged = -1
    private var lastStatus = -1

    @Synchronized
    fun log(text: String) {
        val line = "${clock.format(Date())}  $text"
        lines.addLast(line)
        if (lines.size > MAX_LINES) lines.removeFirst()
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
        SessionLog.ev("usb", text)
    }

    @Synchronized
    fun tail(n: Int): String = lines.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun fullText(): String = lines.joinToString("\n")

    /** À appeler une fois au démarrage de l'appli : s'abonne aux événements de stockage, USB et alimentation. */
    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        file = File(app.filesDir, "diag-stockage.txt").also { if (it.exists() && it.length() > 1_000_000) it.delete() }
        log("— diagnostic démarré (l'appli vient de s'ouvrir) —")
        val sm = app.getSystemService(StorageManager::class.java)
        try {
            sm.registerStorageVolumeCallback(ContextCompat.getMainExecutor(app), object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    log("VOLUME « ${volume.getDescription(app)} » (${volume.mediaStoreVolumeName}) → état ${volume.state}")
                }
            })
        } catch (e: Exception) {
            log("abonnement aux volumes impossible : ${e.message}")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> log("USB : appareil BRANCHÉ ${describe(i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java))}")
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> log("USB : appareil DÉBRANCHÉ ${describe(i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java))}")
                    Intent.ACTION_POWER_CONNECTED -> log("ALIMENTATION : chargeur connecté")
                    Intent.ACTION_POWER_DISCONNECTED -> log("ALIMENTATION : chargeur déconnecté")
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        if (plugged != lastPlugged || status != lastStatus) {
                            if (lastPlugged != -1) log("BATTERIE : branchement ${pluggedText(plugged)} · état ${statusText(status)} · ${i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV")
                            lastPlugged = plugged
                            lastStatus = status
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        log("état initial : ${snapshot(app).replace("\n", " | ")}")
    }

    private fun describe(d: UsbDevice?): String =
        if (d == null) "(inconnu)" else "« ${d.productName ?: d.deviceName} » fabricant ${d.manufacturerName ?: "?"} (vid %04x pid %04x, classe ${d.deviceClass})".format(d.vendorId, d.productId)

    private fun pluggedText(p: Int) = when {
        p == 0 -> "aucun (sur batterie)"
        p and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "secteur/AC"
        p and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
        p and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "sans fil"
        else -> "code $p"
    }

    private fun statusText(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "en charge"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "en décharge"
        BatteryManager.BATTERY_STATUS_FULL -> "pleine"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "ne charge pas"
        else -> "inconnu"
    }

    /** L'état présent de tout ce qui compte : supports, appareils USB, alimentation. */
    fun snapshot(context: Context): String {
        val app = context.applicationContext
        val sm = app.getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes.joinToString("\n") {
            val name = it.mediaStoreVolumeName
            "Support « ${it.getDescription(app)} » · nom ${name ?: "?"} · état ${it.state} · amovible ${it.isRemovable} · connu de MediaStore ${name != null && (name == StorageTarget.PHONE || MediaStore.getExternalVolumeNames(app).contains(name))}"
        }
        val usb = (app.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.let { devices ->
            if (devices.isEmpty()) "Aucun appareil USB vu par le téléphone" else devices.joinToString("\n") { "Appareil USB : ${describe(it)}" }
        }
        return volumes + "\n" + usb + "\n" + powerLine(app)
    }

    fun powerLine(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val volt = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        return "Alimentation : branché ${pluggedText(plugged)} · batterie $level % · $volt mV · courant ${if (current == Int.MIN_VALUE) "?" else (current / 1000).toString() + " mA"} · ${"%.1f".format(temp)} °C"
    }

    private fun stateOf(context: Context, volumeName: String): String {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volumeName }?.state ?: "ABSENT"
    }

    /** Surveille un support pendant [seconds] secondes, une ligne par seconde (utilisé au démarrage d'une session). */
    fun monitor(context: Context, volumeName: String, seconds: Int) {
        val app = context.applicationContext
        Thread({
            repeat(seconds) { i ->
                log("surveillance +${i}s · support $volumeName : ${stateOf(app, volumeName)} · ${powerLine(app)}")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "diag-sd").start()
    }

    /**
     * Écrit en continu (≈ [mbPerSec] Mo/s, comme un enregistrement 4K) sur [volumeName] pendant [seconds] secondes en notant chaque
     * seconde l'état du support, le courant et le branchement, et chaque lenteur ou erreur. Renvoie le verdict en clair.
     */
    fun sustainedTest(context: Context, volumeName: String, seconds: Int, mbPerSec: Int, onProgress: (String) -> Unit): String {
        val app = context.applicationContext
        val resolver = app.contentResolver
        log("=== TEST D'ÉCRITURE SOUTENUE : $seconds s à ≈ $mbPerSec Mo/s sur $volumeName ===")
        log("avant : ${snapshot(app).replace("\n", " | ")}")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "surfcam-diag.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        var uri: android.net.Uri? = null
        var firstLoss: String? = null
        var slow = 0
        var errors = 0
        var written = 0L
        val chunk = ByteArray(mbPerSec * 1024 * 1024 / 4)
        val start = SystemClock.elapsedRealtime()
        try {
            uri = resolver.insert(StorageTarget.videoUri(volumeName), values)
            if (uri == null) {
                log("ÉCHEC : impossible de créer un fichier sur $volumeName")
                return "Impossible de créer un fichier sur le support : le volume n'est pas utilisable (état ${stateOf(app, volumeName)})."
            }
            val out = resolver.openOutputStream(uri, "w") ?: return "Impossible d'ouvrir le fichier en écriture."
            var nextSecond = 1
            out.use { stream ->
                while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        stream.write(chunk)
                        written += chunk.size
                    } catch (e: Exception) {
                        errors++
                        log("ERREUR d'écriture à +${(t0 - start) / 1000f} s : ${e.javaClass.simpleName} ${e.message}")
                        if (firstLoss == null) firstLoss = "erreur d'écriture à +%.1f s (${e.javaClass.simpleName}: ${e.message})".format((t0 - start) / 1000f)
                        if (errors >= 3) break
                    }
                    val took = SystemClock.elapsedRealtime() - t0
                    if (took > 800) { slow++; log("écriture lente : $took ms pour ${chunk.size / 1024} Ko") }
                    val st = stateOf(app, volumeName)
                    if (st != Environment.MEDIA_MOUNTED && firstLoss == null) {
                        firstLoss = "le support est passé à l'état $st à +%.1f s".format((t0 - start) / 1000f)
                        log("PERTE DU SUPPORT : état $st à +${(t0 - start) / 1000f} s")
                    }
                    val elapsedS = (SystemClock.elapsedRealtime() - start) / 1000
                    if (elapsedS >= nextSecond) {
                        nextSecond = elapsedS.toInt() + 1
                        val msg = "+${elapsedS}s · état $st · ${written / (1024 * 1024)} Mo écrits · ${powerLine(app)}"
                        log(msg)
                        onProgress(msg)
                    }
                    val wait = 250 - (SystemClock.elapsedRealtime() - t0)
                    if (wait > 0) Thread.sleep(wait)
                }
            }
        } catch (e: Exception) {
            errors++
            log("EXCEPTION : ${e.javaClass.simpleName} ${e.message}")
            if (firstLoss == null) firstLoss = "exception ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            uri?.let { try { resolver.delete(it, null, null) } catch (_: Exception) {} }
        }
        val total = (SystemClock.elapsedRealtime() - start) / 1000f
        val speed = written / (1024f * 1024f) / total.coerceAtLeast(0.1f)
        log("après : ${snapshot(app).replace("\n", " | ")}")
        val verdict = buildString {
            append("TEST TERMINÉ : %.0f Mo écrits en %.0f s (%.1f Mo/s en moyenne), %d écriture(s) lente(s), %d erreur(s). ".format(written / (1024f * 1024f), total, speed, slow, errors))
            if (firstLoss == null) append("Aucune perte du support pendant le test : le matériel tient cette charge. Si la carte disparaît au démarrage d'une session, la cause est ailleurs (démarrage de la caméra, charge de l'appli).")
            else {
                append("PROBLÈME : $firstLoss. ")
                val around = lines.toList().takeLast(60).filter { Regex("ALIMENTATION|BATTERIE : branchement|USB : appareil|VOLUME").containsMatchIn(it) }
                if (around.isNotEmpty()) append("Événements juste avant : " + around.takeLast(4).joinToString(" ; ") { it.substringAfter("  ") })
            }
        }
        log("=== $verdict ===")
        return verdict
    }
}
