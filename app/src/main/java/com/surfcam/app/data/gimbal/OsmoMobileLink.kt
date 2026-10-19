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

    fun stop() {
        repeat(2) { send(DjiPackets.rotate(0, 5), force = true) }
    }

    private fun send(packet: ByteArray, force: Boolean = false) {
        val g = gatt ?: return
        val c = writeChar ?: return
        // Pas d'empilement : une commande de vitesse en retard est inutile, la suivante arrive aussitôt.
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastWriteAt < MIN_INTERVAL_MS) return
        lastWriteAt = now
        val chunk = maxOf(20, mtu - 3)
        var offset = 0
        while (offset < packet.size) {
            val part = packet.copyOfRange(offset, minOf(packet.size, offset + chunk))
            @Suppress("DEPRECATION")
            run {
                c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = part
                g.writeCharacteristic(c)
            }
            offset += chunk
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

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            _state.update { it.copy(received = it.received + 1) }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BluetoothGattDescriptorEnable = byteArrayOf(0x01, 0x00)
        private const val MIN_INTERVAL_MS = 60L
    }
}
