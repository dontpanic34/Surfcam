package com.surfcam.app.data.gimbal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Un appareil Bluetooth vu pendant la recherche. */
data class FoundDevice(val name: String, val address: String, val rssi: Int, val likelyGimbal: Boolean)

data class GimbalState(
    val scanning: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "Non connecté",
    val received: Int = 0,
    val battery: Int? = null,
    val charging: Boolean = false,
    /** Derniers échanges, du plus récent au plus ancien : sert à diagnostiquer au premier essai sur le vrai gimbal. */
    val journal: List<String> = emptyList(),
)

/**
 * Liaison Bluetooth avec un DJI Osmo Mobile (OM2, OM3, OM4), d'après le décodage public du protocole
 * (github.com/alkersan/om-research, « à des fins d'interopérabilité ») : service FFF0, écriture sur FFF5, notifications
 * sur FFF4. Ni appairage ni échange préalable dans ce décodage. Seul le pivotement (lacet) est utilisé ici.
 * Non officiel : DJI peut le casser par une mise à jour.
 */
@SuppressLint("MissingPermission")
class OsmoMobileLink(private val context: Context) {

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var lastWriteAt = 0L
    private var lastNoteAt = 0L
    private val parser = DumlParser()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var batteryChar: BluetoothGattCharacteristic? = null

    /** Lit le niveau de batterie du gimbal (service standard 0x180F), sans attendre qu'il l'envoie de lui-même. */
    private fun readBattery() {
        val g = gatt ?: return
        val c = batteryChar ?: return
        try {
            g.readCharacteristic(c)
        } catch (_: Exception) {
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() {
            if (_state.value.connected) readBattery()
            handler.postDelayed(this, 60_000)
        }
    }

    private val _state = MutableStateFlow(GimbalState())
    val state: StateFlow<GimbalState> = _state

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val likely = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true ||
                name.contains("OM", ignoreCase = true) || name.contains("osmo", ignoreCase = true) || name.contains("dji", ignoreCase = true)
            _state.update { s ->
                val rest = s.devices.filter { it.address != result.device.address }
                s.copy(devices = (rest + FoundDevice(name, result.device.address, result.rssi, likely)).sortedWith(compareByDescending<FoundDevice> { it.likelyGimbal }.thenByDescending { it.rssi }))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(scanning = false, status = "Recherche impossible (code $errorCode)") }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            _state.update { it.copy(status = "Active le Bluetooth du téléphone") }
            return
        }
        _state.update { it.copy(scanning = true, devices = emptyList(), status = "Recherche en cours… allume le gimbal") }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    fun stopScan() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        _state.update { it.copy(scanning = false) }
    }

    fun connect(address: String) {
        stopScan()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return
        _state.update { it.copy(connecting = true, status = "Connexion…") }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        handler.removeCallbacks(batteryPoll)
        stop()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        _state.update { it.copy(connected = false, connecting = false, status = "Déconnecté") }
    }

    /** Vitesse de pivotement : positif et négatif font tourner dans un sens et dans l'autre, 0 = arrêt. */
    fun rotate(speed: Int, durationUnits: Int = 5) {
        val packet = DjiPackets.rotate(speed.coerceIn(-3000, 3000), durationUnits.coerceIn(0, 255))
        send(packet)
    }

    /** Pivote d'un angle donné (degrés, signé) en [seconds] secondes, sans passer par le calibrage de vitesse. */
    fun rotateBy(degrees: Float, seconds: Float) {
        val packet = DjiPackets.rotateBy((degrees * 10).toInt().coerceIn(-1800, 1800), (seconds * 10).toInt().coerceIn(1, 255))
        send(packet, force = true)
    }

    /** Fait pivoter le téléphone autour de l'axe de la caméra (degrés, signé) pour redresser l'horizon. */
    /** Champ de l'ordre qui porte le roulis (2 ou 3), appris par la mise à niveau et gardé dans les préférences du gimbal. */
    @Volatile
    var rollSlot = 3

    fun rollBy(degrees: Float, seconds: Float) {
        // Mesuré le 11/11/2026 : l'Osmo Mobile 4 ignore le roulis de cet ordre (le champ 2 ne fait rien, le champ 3 incline de
        // haut en bas). Plus aucun ordre de roulis n'est envoyé : il ferait basculer le téléphone au lieu de le redresser.
        if (!ROLL_COMMAND_WORKS) return
        val packet = DjiPackets.rollBy((degrees * 10).toInt().coerceIn(-1000, 1000), (seconds * 10).toInt().coerceIn(1, 255), rollSlot)
        send(packet, force = true)
    }

    private fun note(line: String) {
        _state.update { it.copy(journal = (listOf(line) + it.journal).take(14)) }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    fun stop() {
        repeat(2) { send(DjiPackets.rotate(0, 5), force = true) }
    }

    // Android n'accepte qu'une écriture Bluetooth à la fois : une écriture refusée n'est pas perdue, elle attend dans une seule
    // place. Une commande plus récente remplace celle qui attend (un mouvement périmé ne sert à rien), sauf un arrêt, prioritaire.
    private var waiting: ByteArray? = null
    private var waitingIsStop = false
    private var waitingTries = 0

    private fun retryWaiting() {
        val p = waiting ?: return
        val stop = waitingIsStop
        waiting = null
        if (!write(p)) {
            if (++waitingTries <= 20) {
                waiting = p; waitingIsStop = stop
                handler.postDelayed({ retryWaiting() }, 15)
            } else {
                note("commande abandonnée après 20 essais d'écriture")
            }
        }
    }

    /** Écrit un paquet ; faux si Android a refusé (une autre écriture en cours). */
    private fun write(packet: ByteArray): Boolean {
        val g = gatt ?: return true
        val c = writeChar ?: return true
        val chunk = maxOf(20, mtu - 3)
        var offset = 0
        var ok = true
        while (offset < packet.size) {
            val part = packet.copyOfRange(offset, minOf(packet.size, offset + chunk))
            @Suppress("DEPRECATION")
            run {
                c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = part
                ok = g.writeCharacteristic(c) && ok
            }
            offset += chunk
        }
        return ok
    }

    private fun send(packet: ByteArray, force: Boolean = false) {
        val g = gatt ?: return
        val c = writeChar ?: return
        // Pas d'empilement : une commande de vitesse en retard est inutile, la suivante arrive aussitôt.
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastWriteAt < MIN_INTERVAL_MS) return
        lastWriteAt = now
        if (force || now - lastNoteAt > 1000) {
            lastNoteAt = now
            note("→ " + hex(packet))
        }
        if (g !== gatt || c !== writeChar) return
        val isStop = packet.contentEquals(DjiPackets.rotate(0, 5))
        if (waiting != null && waitingIsStop && !isStop) return
        if (waiting != null || !write(packet)) {
            if (waiting == null || isStop || !waitingIsStop) { waiting = packet; waitingIsStop = isStop; waitingTries = 0 }
            handler.removeCallbacksAndMessages(RETRY_TOKEN)
            handler.postAtTime({ retryWaiting() }, RETRY_TOKEN, SystemClock.uptimeMillis() + 15)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.update { it.copy(status = "Connecté, lecture des services…") }
                if (!g.requestMtu(185)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                if (gatt === g) gatt = null
                writeChar = null
                _state.update { it.copy(connected = false, connecting = false, status = "Déconnecté (code $status)") }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@OsmoMobileLink.mtu = mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val write = service?.getCharacteristic(WRITE)
            val notify = service?.getCharacteristic(NOTIFY)
            if (service == null || write == null || notify == null) {
                _state.update { it.copy(connecting = false, status = "Service FFF0 absent : ce n'est pas un Osmo Mobile compatible") }
                g.disconnect()
                return
            }
            writeChar = write
            batteryChar = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
            note("Services trouvés, écriture ${write.uuid.toString().substring(4, 8)} · notif ${notify.uuid.toString().substring(4, 8)}")
            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(CCCD)?.let { d ->
                @Suppress("DEPRECATION")
                run {
                    d.value = BluetoothGattDescriptorEnable
                    g.writeDescriptor(d)
                }
            }
            _state.update { it.copy(connected = true, connecting = false, status = "Connecté : prêt à tourner") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            // Les notifications sont branchées : première lecture de la batterie, puis une par minute.
            if (descriptor.characteristic.uuid == NOTIFY) {
                readBattery()
                handler.removeCallbacks(batteryPoll)
                handler.postDelayed(batteryPoll, 60_000)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic.uuid != BATTERY_LEVEL) return
            @Suppress("DEPRECATION")
            val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: return
            if (level in 0..100) {
                _state.update { it.copy(battery = level) }
                note("Batterie du gimbal : $level %")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            _state.update { it.copy(received = it.received + 1) }
            for (m in parser.feed(data)) {
                val b = DumlParser.battery(m)
                if (b != null) _state.update { it.copy(battery = b.first, charging = b.second) }
                else if (m.cmdSet != 0x05) note("← ensemble ${m.cmdSet} cmd ${m.cmd} : " + hex(m.payload))
            }
        }
    }

    companion object {
        /** Le gimbal accepte-t-il qu'on le fasse tourner sur le côté (roulis) ? Non pour l'Osmo Mobile 4 (mesuré le 11/11/2026). */
        const val ROLL_COMMAND_WORKS = false

        /** Ce qu'on dit à l'utilisateur quand l'horizon penche. */
        const val ROLL_ADVICE = "Le gimbal tient l'horizon tout seul, même si le trépied penche : la pente vient du téléphone posé de travers " +
            "sur l'aimant. Tourne-le doucement sur l'aimant jusqu'à ce que la pastille Niveau passe au vert."

        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BluetoothGattDescriptorEnable = byteArrayOf(0x01, 0x00)
        private const val MIN_INTERVAL_MS = 60L
        private val RETRY_TOKEN = Any()
    }
}
