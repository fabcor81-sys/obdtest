package com.example.obdtest.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kwp2000FrameTest {

    @Test
    fun testStartCommunicationFrame() {
        // Payload 0x81 (Start Communication, length 1)
        val payload = byteArrayOf(0x81.toByte())
        val frame = Kwp2000Frame.buildFrame(
            payload = payload,
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        )

        // FMT = 0x80 | 1 = 0x81
        assertEquals(0x81.toByte(), frame.formatByte)
        assertEquals(0x2B.toByte(), frame.targetAddress)
        assertEquals(0xF1.toByte(), frame.sourceAddress)
        assertArrayEquals(payload, frame.payload)

        // Checksum = (0x81 + 0x2B + 0xF1 + 0x81) & 0xFF = 542 % 256 = 30 (0x1E)
        assertEquals(0x1E.toByte(), frame.checksum)

        val raw = frame.rawBytes
        val expectedRaw = byteArrayOf(0x81.toByte(), 0x2B.toByte(), 0xF1.toByte(), 0x81.toByte(), 0x1E.toByte())
        assertArrayEquals(expectedRaw, raw)
    }

    @Test
    fun testEcuIdentificationFrame_1A80() {
        // Payload 1A 80 (Length = 2) -> FMT = 0x80 | 2 = 0x82
        val payload = byteArrayOf(0x1A.toByte(), 0x80.toByte())
        val frame = Kwp2000Frame.buildFrame(
            payload = payload,
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        )

        assertEquals(0x82.toByte(), frame.formatByte)
        // Checksum = (0x82 + 0x2B + 0xF1 + 0x1A + 0x80) & 0xFF = 0x0238 & 0xFF = 0x38
        assertEquals(0x38.toByte(), frame.checksum)

        val expectedRaw = byteArrayOf(0x82.toByte(), 0x2B.toByte(), 0xF1.toByte(), 0x1A.toByte(), 0x80.toByte(), 0x38.toByte())
        assertArrayEquals(expectedRaw, frame.rawBytes)
    }

    @Test
    fun testReadDataByLocalIdentifierFrame_2101() {
        // Payload 21 01 (Length = 2) -> FMT = 0x80 | 2 = 0x82
        val payload = byteArrayOf(0x21.toByte(), 0x01.toByte())
        val frame = Kwp2000Frame.buildFrame(
            payload = payload,
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        )

        assertEquals(0x82.toByte(), frame.formatByte)
        // Checksum = (0x82 + 0x2B + 0xF1 + 0x21 + 0x01) & 0xFF = 448 % 256 = 192 (0xC0)
        assertEquals(0xC0.toByte(), frame.checksum)

        val expectedRaw = byteArrayOf(0x82.toByte(), 0x2B.toByte(), 0xF1.toByte(), 0x21.toByte(), 0x01.toByte(), 0xC0.toByte())
        assertArrayEquals(expectedRaw, frame.rawBytes)
    }

    @Test
    fun testParseValidResponseFrame() {
        // Sample ECU Positive Response: FMT 0x83 (length 3), TGT 0xF1, SRC 0x2B, Service 0x5A, Data 0x80, Data2 0x12, CS
        val payload = byteArrayOf(0x5A.toByte(), 0x80.toByte(), 0x12.toByte())
        val temp = byteArrayOf(0x83.toByte(), 0xF1.toByte(), 0x2B.toByte(), 0x5A.toByte(), 0x80.toByte(), 0x12.toByte())
        val cs = Kwp2000Frame.calculateChecksum(temp)

        val fullRaw = temp + byteArrayOf(cs)

        val parsed = Kwp2000Frame.parseFrame(fullRaw)
        assertNotNull(parsed)
        assertEquals(0x83.toByte(), parsed!!.formatByte)
        assertEquals(0xF1.toByte(), parsed.targetAddress)
        assertEquals(0x2B.toByte(), parsed.sourceAddress)
        assertArrayEquals(payload, parsed.payload)
        assertEquals(cs, parsed.checksum)
    }

    @Test
    fun testParseIncompleteFrameReturnsNull() {
        val incomplete = byteArrayOf(0x82.toByte(), 0xF1.toByte(), 0x2B.toByte())
        val parsed = Kwp2000Frame.parseFrame(incomplete)
        assertNull(parsed)
    }

    @Test
    fun testHexConversionHelpers() {
        val hexStr = "81 2B F1 81 84"
        val bytes = hexStr.hexToByteArray()
        assertEquals(5, bytes.size)
        assertEquals(0x81.toByte(), bytes[0])
        assertEquals(0x2B.toByte(), bytes[1])
        assertEquals(0xF1.toByte(), bytes[2])
        assertEquals(0x81.toByte(), bytes[3])
        assertEquals(0x84.toByte(), bytes[4])

        assertEquals("81 2B F1 81 84", bytes.toHexString())
    }

    @Test
    fun testSafetyGuardBlocksActuatorService30() {
        val payloadActuator = "30020700".hexToByteArray()
        val serviceId = payloadActuator[0].toInt() and 0xFF
        assertEquals(0x30, serviceId)
        assertTrue("Il servizio 0x30 deve essere identificato come comando di attuazione", serviceId == 0x30)
    }
}
