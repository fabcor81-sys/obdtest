package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import com.example.obdtest.ui.SampleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdResponseParserTest {

    @Test
    fun testValidObd0100Response() {
        val raw = "0100\r41 00 BE 3F A8 13\r\n>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.GENERIC_AUTO,
            commandSent = "0100\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.POSITIVE_RESPONSE, result.status)
        assertEquals("4100BE3FA813", result.extractedPayload)
    }

    @Test
    fun testKwpNegativeResponseNrc11() {
        val raw = "0100\r7F 01 11\r\n>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.LOTUS_ABS,
            commandSent = "0100\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.NEGATIVE_RESPONSE, result.status)
        assertEquals("01", result.serviceId)
        assertEquals("11", result.nrcCode)
    }

    @Test
    fun testKwpNegativeResponseNrc22() {
        val raw = "30020700\r7F 30 22\r\n>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.LOTUS_ABS,
            commandSent = "30020700\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.NEGATIVE_RESPONSE, result.status)
        assertEquals("30", result.serviceId)
        assertEquals("22", result.nrcCode)
    }

    @Test
    fun testKwpEcuIdentificationPositiveResponse() {
        val raw = "1A80\r5A 80 45 42 43 34 33 30\r\n>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.LOTUS_ABS,
            commandSent = "1A80\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.POSITIVE_RESPONSE, result.status)
        assertEquals("1A", result.serviceId)
    }

    @Test
    fun testKwpAbsPumpOffPositiveResponse() {
        val raw = "30020700\r70 02 07 00\r\n>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.LOTUS_ABS,
            commandSent = "30020700\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.POSITIVE_RESPONSE, result.status)
        assertEquals("30", result.serviceId)
    }

    @Test
    fun testBusInitErrorResponse() {
        val raw = "BUS INIT: ERROR\r\r>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.GENERIC_AUTO,
            commandSent = "0100\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.ADAPTER_ERROR, result.status)
    }

    @Test
    fun testStoppedAdapterErrorResponse() {
        val raw = "STOPPED\r>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.GENERIC_AUTO,
            commandSent = "0100\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.ADAPTER_ERROR, result.status)
    }

    @Test
    fun testAtOnlyAtiValidResponse() {
        val raw = "ATI\rELM327 v1.5\r\n>"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.AT_ONLY,
            commandSent = "ATI\r",
            rawResponse = raw,
            hasPrompt = true,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.POSITIVE_RESPONSE, result.status)
        assertEquals("ELM327 v1.5", result.extractedPayload)
    }

    @Test
    fun testTimeoutNoBytes() {
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.GENERIC_AUTO,
            commandSent = "0100\r",
            rawResponse = "",
            hasPrompt = false,
            bytesReceived = 0
        )

        assertEquals(SampleStatus.TIMEOUT, result.status)
    }

    @Test
    fun testIncompleteResponseWithBytes() {
        val raw = "41 00 BE 3F"
        val result = ObdResponseParser.parseResponse(
            profile = ObdProfile.GENERIC_AUTO,
            commandSent = "0100\r",
            rawResponse = raw,
            hasPrompt = false,
            bytesReceived = raw.length
        )

        assertEquals(SampleStatus.INCOMPLETE_RESPONSE, result.status)
    }
}
