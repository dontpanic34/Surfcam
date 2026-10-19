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

    /**
     * Pivotement en mode vitesse (lacet). [yawSpeed] est signé (le sens se règle par le signe), 0 arrête ; [duration] est
     * la durée de validité de l'ordre, en unités encore à mesurer sur un vrai appareil. Le message fait 21 octets.
     */
    fun rotate(yawSpeed: Int, duration: Int): ByteArray {
        val body = byteArrayOf(0x02, 0x04, 0x01, 0x00, 0x00, 0x04, 0x0c)
        val payload = le16(yawSpeed) + le16(0) + le16(0) + byteArrayOf(0x80.toByte(), duration.toByte())
        val crc = crc16(body + payload, 0xdf0c)
        return byteArrayOf(0x55, 0x15, 0x04, 0xa9.toByte()) + body + payload + le16(crc)
    }
}
