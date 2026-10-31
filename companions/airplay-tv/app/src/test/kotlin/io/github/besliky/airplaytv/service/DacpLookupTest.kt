package io.github.besliky.airplaytv.service

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class DacpLookupTest {

    private val name = "iTunes_Ctrl_07111F783CBB0A38"

    /** A reply that echoes the question, then an unrelated TXT record, then the SRV record by pointer. */
    private fun reply(owner: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(DacpClient.query(owner)) // header and question; the records point back at its name
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, 16, 0, 1, 0, 0, 0, 120, 0, 1, 0)) // TXT, empty
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, 33, 0, 1, 0, 0, 0, 120, 0, 8))
        out.write(byteArrayOf(0, 0, 0, 0, 0xDB.toByte(), 0x8A.toByte(), 0xC0.toByte(), 12)) // port 56202
        val packet = out.toByteArray()
        packet[7] = 2 // two answers
        return packet
    }

    @Test
    fun findsThePortOfTheWantedService() {
        val packet = reply(name)
        assertEquals(56202, DacpClient.srvPort(packet, packet.size, name))
    }

    @Test
    fun ignoresAnotherSender() {
        val packet = reply("iTunes_Ctrl_0000000000000001")
        assertEquals(0, DacpClient.srvPort(packet, packet.size, name))
    }

    @Test
    fun survivesTruncatedAndLoopingPackets() {
        val packet = reply(name)
        assertEquals(0, DacpClient.srvPort(packet, packet.size - 5, name))
        val loop = packet.copyOf()
        loop[12] = 0xC0.toByte()
        loop[13] = 12 // the question's name points at itself
        assertEquals(0, DacpClient.srvPort(loop, loop.size, name))
    }
}
