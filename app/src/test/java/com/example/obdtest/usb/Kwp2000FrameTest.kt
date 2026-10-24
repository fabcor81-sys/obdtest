package com.example.obdtest.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Kwp2000FrameTest {

    private val decoder = Kwp2000StreamDecoder(
        expectedTargetAddress = 0xF1.toByte(), // Tester address
        expectedSourceAddress = 0x2B.toByte(), // ECU Lotus EBC430 address
        isEchoFilterEnabled = true
    )

    // TEST 1: Echo completo + risposta ECU
    @Test
    fun test1_CompleteEchoAndEcuResponse() {
        val txFrame = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        )
        val txBytes = txFrame.rawBytes

        val ecuFrame = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte(), 0x12.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        )
        val ecuBytes = ecuFrame.rawBytes

        val stream = txBytes + ecuBytes

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.SUCCESS, res.status)
        assertTrue(res.isSuccess)
        assertTrue(res.isEchoMatched)
        assertArrayEquals(txBytes, res.echoBytes)
        assertArrayEquals(ecuBytes, res.ecuBytes)
        assertEquals(ecuFrame, res.parsedFrame)
        assertEquals(txBytes.size + ecuBytes.size, res.bytesConsumed)
    }

    // TEST 2: Echo frammentato
    @Test
    fun test2_FragmentedEcho() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val partialEcho = txBytes.copyOfRange(0, 3)

        val res = decoder.decodeStream(partialEcho, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.INCOMPLETE_RESPONSE, res.status)
        assertFalse(res.isSuccess)
        assertTrue(res.isEchoMatched) // Echo in progress
    }

    // TEST 3: Echo e risposta nello stesso blocco USB
    @Test
    fun test3_EchoAndResponseInSameUsbBlock() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x21.toByte(), 0x01.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x61.toByte(), 0x01.toByte(), 0x0C.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val usbBlock = txBytes + ecuBytes

        val res = decoder.decodeStream(usbBlock, txBytes, requestedServiceId = 0x21)

        assertEquals(KwpDecodeStatus.SUCCESS, res.status)
        assertTrue(res.isSuccess)
        assertTrue(res.isEchoMatched)
        assertArrayEquals(txBytes, res.echoBytes)
        assertArrayEquals(ecuBytes, res.ecuBytes)
    }

    // TEST 4: Assenza completa di echo
    @Test
    fun test4_CompleteAbsenceOfEcho() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        // Lo stream contiene direttamente la risposta ECU senza echo TX
        val res = decoder.decodeStream(ecuBytes, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.SUCCESS, res.status)
        assertTrue(res.isSuccess)
        assertFalse(res.isEchoMatched) // Echo assente ma tollerato
        assertEquals(0, res.echoBytes.size)
        assertArrayEquals(ecuBytes, res.ecuBytes)
    }

    // TEST 5: Echo incompleto
    @Test
    fun test5_IncompleteEcho() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        // Echo di soli 2 byte seguiti subito dai byte ECU
        val incompleteEchoStream = txBytes.copyOfRange(0, 2) + ecuBytes

        val res = decoder.decodeStream(incompleteEchoStream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.SUCCESS, res.status)
        assertTrue(res.isSuccess)
        assertFalse(res.isEchoMatched)
    }

    // TEST 6: Echo diverso dal frame TX
    @Test
    fun test6_MismatchedEcho() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val corruptEcho = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        val ecuBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val stream = corruptEcho + ecuBytes

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.SUCCESS, res.status)
        assertTrue(res.isSuccess)
        assertFalse(res.isEchoMatched)
    }

    // TEST 7: Risposta ECU frammentata
    @Test
    fun test7_FragmentedEcuResponse() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val partialStream = txBytes + ecuBytes.copyOfRange(0, 3)

        val res = decoder.decodeStream(partialStream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.INCOMPLETE_RESPONSE, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 8: Checksum errato
    @Test
    fun test8_BadChecksum() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytesBadCs = byteArrayOf(0x82.toByte(), 0xF1.toByte(), 0x2B.toByte(), 0x5A.toByte(), 0x80.toByte(), 0xEE.toByte()) // CS errato

        val stream = txBytes + ecuBytesBadCs

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.CHECKSUM_ERROR, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 9: Indirizzo sorgente errato
    @Test
    fun test9_WrongSourceAddress() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytesWrongSrc = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x99.toByte() // Wrong ECU source address
        ).rawBytes

        val stream = txBytes + ecuBytesWrongSrc

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.INVALID_SOURCE_ADDRESS, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 10: Indirizzo destinatario errato
    @Test
    fun test10_WrongTargetAddress() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytesWrongTgt = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0x88.toByte(), // Wrong tester target address
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val stream = txBytes + ecuBytesWrongTgt

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.INVALID_TARGET_ADDRESS, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 11: Servizio di risposta inatteso
    @Test
    fun test11_UnexpectedServiceResponse() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()), // Service 1A
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytesUnexpected = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x61.toByte(), 0x01.toByte()), // Positive response for service 21 (61) instead of 1A (5A)
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val stream = txBytes + ecuBytesUnexpected

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.UNEXPECTED_RESPONSE, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 12: Risposta negativa riferita ad un altro servizio
    @Test
    fun test12_NegativeResponseForDifferentService() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()), // Service 1A
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuNegativeOtherService = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x7F.toByte(), 0x21.toByte(), 0x11.toByte()), // NRC for service 21
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val stream = txBytes + ecuNegativeOtherService

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.UNEXPECTED_RESPONSE, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 13: Due frame consecutivi nello stesso stream
    @Test
    fun test13_TwoConsecutiveFramesInSameStream() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val frame1 = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val frame2 = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x61.toByte(), 0x01.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val fullStream = txBytes + frame1 + frame2

        val res1 = decoder.decodeStream(fullStream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.SUCCESS, res1.status)
        assertTrue(res1.isSuccess)
        assertEquals(0x5A.toByte(), res1.parsedFrame!!.payload[0])

        val consumed = res1.bytesConsumed
        val remaining = fullStream.copyOfRange(consumed, fullStream.size)

        val res2 = decoder.decodeStream(remaining, byteArrayOf(), requestedServiceId = 0x21)

        assertEquals(KwpDecodeStatus.SUCCESS, res2.status)
        assertTrue(res2.isSuccess)
        assertEquals(0x61.toByte(), res2.parsedFrame!!.payload[0])
    }

    // TEST 14: Timeout durante risposta incompleta
    @Test
    fun test14_TimeoutIncompleteResponse() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val partialEcu = byteArrayOf(0x82.toByte(), 0xF1.toByte())

        val stream = txBytes + partialEcu

        val res = decoder.decodeStream(stream, txBytes, requestedServiceId = 0x1A)

        assertEquals(KwpDecodeStatus.INCOMPLETE_RESPONSE, res.status)
        assertFalse(res.isSuccess)
    }

    // TEST 15: Safety Guard Whitelist (Servizio 0x30 bloccato)
    @Test
    fun test15_SafetyGuardWhitelistService30Blocked() {
        val payloadActuator = "30020700".hexToByteArray()
        val serviceId = payloadActuator[0].toInt() and 0xFF
        assertEquals(0x30, serviceId)
        assertFalse("Il servizio 0x30 deve essere rifiutato dalla whitelist", Kwp2000KLineTransport.ALLOWED_READ_COMMANDS_HEX.contains("30020700"))
        assertTrue("La whitelist USB deve accettare 1A80", Kwp2000KLineTransport.ALLOWED_READ_COMMANDS_HEX.contains("1A80"))
        assertTrue("La whitelist USB deve accettare 2101", Kwp2000KLineTransport.ALLOWED_READ_COMMANDS_HEX.contains("2101"))
    }
}
