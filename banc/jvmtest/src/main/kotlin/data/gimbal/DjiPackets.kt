package com.surfcam.app.data.gimbal

/** Construction des messages du protocole des DJI Osmo Mobile (décodage public). Pur calcul, testable sans téléphone. */
object DjiPackets {
    private val CRC_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        c
    }

    /** CRC-16 par table (polynôme 0x1021 réfléchi), sans XOR final. */
    fun crc16(data: ByteArray, init: Int): Int {
        var crc = init
        for (b in data) crc = (crc ushr 8) xor CRC_TABLE[(b.toInt() xor crc) and 0xFF]
        return crc and 0xFFFF
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private const val MODE_RELATIVE = 0x04
    private const val MODE_SPEED = 0x80

    private fun rotation(yaw: Int, mode: Int, duration: Int, roll: Int = 0): ByteArray {
        val body = byteArrayOf(0x02, 0x04, 0x01, 0x00, 0x00, 0x04, if (mode == MODE_SPEED) 0x0c else 0x14)
        val payload = le16(yaw) + le16(roll) + le16(0) + byteArrayOf(mode.toByte(), duration.toByte())
        val crc = crc16(body + payload, 0xdf0c)
        return byteArrayOf(0x55, 0x15, 0x04, 0xa9.toByte()) + body + payload + le16(crc)
    }

    /**
     * Pivotement en mode vitesse (lacet). [yawSpeed] est signé (le sens se règle par le signe), 0 arrête ; [duration] est
     * la durée de validité de l'ordre en dixièmes de seconde (d'après l'exemple du dépôt de référence). Le message fait 21 octets.
     */
    fun rotate(yawSpeed: Int, duration: Int): ByteArray = rotation(yawSpeed, MODE_SPEED, duration)

    /** Pivotement d'un angle donné (lacet, dixièmes de degré, signé) à effectuer en [duration] dixièmes de seconde. */
    fun rotateBy(yawTenthsDeg: Int, duration: Int): ByteArray = rotation(yawTenthsDeg, MODE_RELATIVE, duration)

    /** Pivotement d'un angle donné autour de l'axe de roulis (dixièmes de degré, signé) : sert à redresser l'horizon. */
    fun rollBy(rollTenthsDeg: Int, duration: Int): ByteArray = rotation(0, MODE_RELATIVE, duration, roll = rollTenthsDeg)
}

/** Message DUML reçu du gimbal (en-tête 0x55, longueur sur 10 bits, ensemble de commandes et commande). */
class DumlMessage(val cmdSet: Int, val cmd: Int, val payload: ByteArray)

/** Reconstitue les messages DUML à partir des morceaux de notification Bluetooth (un message peut arriver en plusieurs). */
class DumlParser {
    private var buffer = ByteArray(0)

    fun feed(data: ByteArray): List<DumlMessage> {
        buffer += data
        val out = mutableListOf<DumlMessage>()
        while (buffer.isNotEmpty()) {
            if (buffer[0] != 0x55.toByte()) {
                val i = buffer.indexOf(0x55.toByte())
                buffer = if (i < 0) ByteArray(0) else buffer.copyOfRange(i, buffer.size)
                continue
            }
            if (buffer.size < 4) break
            val length = ((buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)) and 0x3FF
            if (length < 13) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < length) break
            val packet = buffer.copyOfRange(0, length)
            buffer = buffer.copyOfRange(length, buffer.size)
            out += DumlMessage(packet[9].toInt() and 0xFF, packet[10].toInt() and 0xFF, packet.copyOfRange(11, length - 2))
        }
        return out
    }

    companion object {
        /** Niveau de batterie (%) et charge en cours, si le message est un état de batterie (ensemble 0x05, commande 0x06). */
        fun battery(m: DumlMessage): Pair<Int, Boolean>? =
            if (m.cmdSet == 0x05 && m.cmd == 0x06 && m.payload.isNotEmpty()) {
                (m.payload[0].toInt() and 0xFF) to (m.payload.last().toInt() == 1)
            } else null
    }
}
