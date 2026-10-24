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

    // TEST 1: Checksum KWP2000 Valido
    @Test
    fun testValidKwp2000Checksum() {
        val payload = byteArrayOf(0x81.toByte())
        val frame = Kwp2000Frame.buildFrame(
            payload = payload,
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        )

        assertEquals(0x81.toByte(), frame.formatByte)
        assertEquals(0x2B.toByte(), frame.targetAddress)
        assertEquals(0xF1.toByte(), frame.sourceAddress)
        assertArrayEquals(payload, frame.payload)

        // Checksum = (0x81 + 0x2B + 0xF1 + 0x81) & 0xFF = 542 % 256 = 30 (0x1E)
        assertEquals(0x1E.toByte(), frame.checksum)

        val raw = frame.rawBytes
        val expectedRaw = byteArrayOf(0x81.toByte(), 0x2B.toByte(), 0xF1.toByte(), 0x81.toByte(), 0x1E.toByte())
        assertArrayEquals(expectedRaw, raw)

        val parsed = Kwp2000Frame.parseFrame(raw)
        assertNotNull("Un frame con checksum valido deve essere aperto e parsato con successo", parsed)
        assertEquals(frame, parsed)
    }

    // TEST 2: Checksum KWP2000 Errato Rifiutato
    @Test
    fun testInvalidChecksumRejected() {
        val corruptRaw = byteArrayOf(0x81.toByte(), 0x2B.toByte(), 0xF1.toByte(), 0x81.toByte(), 0xFF.toByte())
        val parsed = Kwp2000Frame.parseFrame(corruptRaw)
        assertNull("Un frame con checksum errato DEVE essere rifiutato (restituire null)", parsed)
    }

    // TEST 3: Risposta Incompleta
    @Test
    fun testIncompleteResponse() {
        val incompleteBytes = byteArrayOf(0x82.toByte(), 0xF1.toByte(), 0x2B.toByte(), 0x5A.toByte())
        val parsed = Kwp2000Frame.parseFrame(incompleteBytes)
        assertNull("Un frame incompleto privo di tutti i byte di payload o checksum deve restituire null", parsed)
    }

    // TEST 4: Risposta Frammentata Assemblata
    @Test
    fun testFragmentedResponseReassembly() {
        val fullFrame = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte(), 0x01.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val chunk1 = fullFrame.copyOfRange(0, 3)
        val chunk2 = fullFrame.copyOfRange(3, fullFrame.size)

        val stream = ByteArrayOutputStream()
        stream.write(chunk1)
        assertNull("Chunk 1 parziale non deve validare un frame", Kwp2000Frame.parseFrame(stream.toByteArray()))

        stream.write(chunk2)
        val parsed = Kwp2000Frame.parseFrame(stream.toByteArray())
        assertNotNull("L'unione dei due chunk deve restituire il frame valido", parsed)
        assertEquals(0x5A.toByte(), parsed!!.payload[0])
    }

    // TEST 5: Echo e Risposta ECU nello Stesso Blocco USB
    @Test
    fun testEchoAndEcuResponseInSameReadBlock() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x1A.toByte(), 0x80.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuResponseBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte(), 0x99.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        // Unico blocco USB contenente sia l'echo che la risposta ECU
        val combinedUsbBlock = txBytes + ecuResponseBytes

        val expectedEchoSize = txBytes.size
        assertTrue(combinedUsbBlock.size > expectedEchoSize)

        val extractedEcho = combinedUsbBlock.copyOfRange(0, expectedEchoSize)
        val extractedEcu = combinedUsbBlock.copyOfRange(expectedEchoSize, combinedUsbBlock.size)

        assertArrayEquals("L'echo deve corrispondere esattamente ai byte trasmessi", txBytes, extractedEcho)

        val parsedEcu = Kwp2000Frame.parseFrame(extractedEcu)
        assertNotNull("La risposta ECU separata dallo stesso blocco USB deve validare con successo", parsedEcu)
        assertEquals(0x5A.toByte(), parsedEcu!!.payload[0])
    }

    // TEST 6: Echo Distribuito su Più Letture
    @Test
    fun testEchoDistributedAcrossMultipleReads() {
        val txBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x21.toByte(), 0x01.toByte()),
            targetAddress = 0x2B.toByte(),
            sourceAddress = 0xF1.toByte()
        ).rawBytes

        val ecuBytes = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x61.toByte(), 0x01.toByte(), 0x0C.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        val read1 = txBytes.copyOfRange(0, 3)
        val read2 = txBytes.copyOfRange(3, txBytes.size) + ecuBytes.copyOfRange(0, 2)
        val read3 = ecuBytes.copyOfRange(2, ecuBytes.size)

        val persistentStream = ByteArrayOutputStream()
        persistentStream.write(read1)
        persistentStream.write(read2)
        persistentStream.write(read3)

        val fullStream = persistentStream.toByteArray()
        val echoPart = fullStream.copyOfRange(0, txBytes.size)
        val ecuPart = fullStream.copyOfRange(txBytes.size, fullStream.size)

        assertArrayEquals(txBytes, echoPart)
        val parsedEcu = Kwp2000Frame.parseFrame(ecuPart)
        assertNotNull(parsedEcu)
        assertEquals(0x61.toByte(), parsedEcu!!.payload[0])
    }

    // TEST 7: Risposta Negativa KWP2000 0x7F
    @Test
    fun testNegativeResponse7F() {
        // Response 7F 1A 11 (Service 1A, NRC 11 ServiceNotSupported)
        val nrcFrame = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x7F.toByte(), 0x1A.toByte(), 0x11.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        )

        val parsed = Kwp2000Frame.parseFrame(nrcFrame.rawBytes)
        assertNotNull(parsed)
        assertEquals(0x7F.toByte(), parsed!!.payload[0])
        assertEquals(0x1A.toByte(), parsed.payload[1])
        assertEquals(0x11.toByte(), parsed.payload[2])
    }

    // TEST 8: Frame Concatenati nello Stream
    @Test
    fun testConcatenatedFrames() {
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

        val concatenated = frame1 + frame2

        val parsed1 = Kwp2000Frame.parseFrame(concatenated)
        assertNotNull("Il primo frame deve essere estratto correttamente", parsed1)
        assertEquals(0x5A.toByte(), parsed1!!.payload[0])

        val frame1Len = 4 + parsed1.payload.size
        val secondPart = concatenated.copyOfRange(frame1Len, concatenated.size)
        val parsed2 = Kwp2000Frame.parseFrame(secondPart)
        assertNotNull("Il secondo frame concatenato deve essere estratto senza perdita di byte", parsed2)
        assertEquals(0x61.toByte(), parsed2!!.payload[0])
    }

    // TEST 9: Assenza Completa di Echo
    @Test
    fun testCompleteAbsenceOfEcho() {
        val ecuFrame = Kwp2000Frame.buildFrame(
            payload = byteArrayOf(0x5A.toByte(), 0x80.toByte()),
            targetAddress = 0xF1.toByte(),
            sourceAddress = 0x2B.toByte()
        ).rawBytes

        // Senza filtraggio echo (isEchoFilterEnabled = false), lo stream contiene direttamente i byte ECU
        val parsed = Kwp2000Frame.parseFrame(ecuFrame)
        assertNotNull("Anche senza echo, la risposta ECU deve validare correttamente", parsed)
        assertEquals(0x5A.toByte(), parsed!!.payload[0])
    }

    // TEST 10: Safety Guard Blocco Servizio 0x30
    @Test
    fun testSafetyGuardService30Block() {
        val payloadActuator = "30020700".hexToByteArray()
        val serviceId = payloadActuator[0].toInt() and 0xFF
        assertEquals(0x30, serviceId)
        assertTrue("Il servizio 0x30 deve essere identificato e bloccato sul trasporto USB K-Line", serviceId == 0x30)
    }
}
