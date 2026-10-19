package com.surfcam.app.data.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiPacketsTest {
    @Test
    fun `le CRC reproduit le vecteur de controle du depot de reference`() {
        // « poly=0x1021 init=0x30fb refin=true refout=true xorout=0x0000 check=0x257f » ; 0xdf0c est 0x30fb en miroir (registre réfléchi).
        assertEquals(0x257f, DjiPackets.crc16("123456789".toByteArray(), 0xdf0c))
    }

    @Test
    fun `un message de rotation fait 21 octets avec l'en-tete attendu`() {
        val p = DjiPackets.rotate(1500, 10)
        assertEquals(21, p.size)
        assertEquals(listOf(0x55, 0x15, 0x04, 0xa9), p.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `la vitesse est un entier 16 bits signe petit-boutiste dans l'ordre lacet, roulis, tangage`() {
        val p = DjiPackets.rotate(-1500, 10) // -1500 = 0xFA24
        val payload = p.copyOfRange(11, 19)
        assertEquals(listOf(0x24, 0xFA, 0, 0, 0, 0, 0x80, 10), payload.map { it.toInt() and 0xFF })
    }

    @Test
    fun `le CRC final correspond aux octets du corps et de la charge`() {
        val p = DjiPackets.rotate(300, 5)
        val crc = DjiPackets.crc16(p.copyOfRange(4, 19), 0xdf0c)
        assertEquals(crc, (p[19].toInt() and 0xFF) or ((p[20].toInt() and 0xFF) shl 8))
        assertTrue(p.size == 21)
    }
}
