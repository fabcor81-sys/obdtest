package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import com.example.obdtest.ui.SampleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdValidationRegressionTest {
    private fun parse(raw: String, command: String = "0100\r") = ObdResponseParser.parseResponse(
        ObdProfile.LOTUS_ABS, command, raw, raw.contains('>'), raw.length
    )

    @Test fun absRejectsNegativeResponsesAndUnrelatedText() {
        val nrcReply = parse("7F 01 11\r>")
        assertEquals(SampleStatus.NEGATIVE_RESPONSE, nrcReply.status)
        assertEquals("11", nrcReply.nrcCode)
    }

    @Test fun absAcceptsOnlyCompleteMatchingPayload() {
        val reply = parse("0100\rSEARCHING...\r\n41 00 BE 3F A8 13\r\n>")
        assertEquals(SampleStatus.POSITIVE_RESPONSE, reply.status)
        assertEquals("4100BE3FA813", reply.extractedPayload)
    }

    @Test fun kwpServicesPositiveResponses() {
        val replyEcu = parse("1A 80\r5A 80 45 42 43 34 33 30\r>", "1A80\r")
        assertEquals(SampleStatus.POSITIVE_RESPONSE, replyEcu.status)

        val replyBattery = parse("21 01\r61 01 82\r>", "2101\r")
        assertEquals(SampleStatus.POSITIVE_RESPONSE, replyBattery.status)
    }
}
