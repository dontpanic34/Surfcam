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

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `les messages sont identiques a ceux du pilote Python de reference`() {
        // Vecteurs produits par gimbal.py du dépôt alkersan/om-research.
        assertEquals("551504a90204010000040cdc0500000000800a867e", DjiPackets.rotate(1500, 10).hex())
        assertEquals("551504a90204010000040c24fa000000008005f366", DjiPackets.rotate(-1500, 5).hex())
        assertEquals("551504a902040100000414c20100000000040a7711", DjiPackets.rotateBy(450, 10).hex())
        assertEquals("551504a902040100000414d4fe000000000403da44", DjiPackets.rotateBy(-300, 3).hex())
    }

    @Test
    fun `le roulis est place apres le lacet, comme dans le pilote de reference`() {
        assertEquals("551504a9020401000004140000ceff0000040a55e0", DjiPackets.rollBy(-50, 10).hex())
        assertEquals("551504a90204010000041400001e0000000408de73", DjiPackets.rollBy(30, 8).hex())
    }

    @Test
    fun `le lecteur de messages recolle un message coupe en deux et lit la batterie`() {
        // Message fabriqué : longueur 14 (13 + 1 octet de charge utile), ensemble 5, commande 6, batterie 87 %.
        val m = byteArrayOf(0x55, 0x0e, 0x04, 0x00, 0x02, 0x03, 0x01, 0x00, 0x00, 0x05, 0x06, 87, 0x00, 0x00)
        val parser = DumlParser()
        assertTrue(parser.feed(m.copyOfRange(0, 6)).isEmpty())
        val out = parser.feed(m.copyOfRange(6, m.size))
        assertEquals(1, out.size)
        assertEquals(87 to false, DumlParser.battery(out[0]))
    }
}
