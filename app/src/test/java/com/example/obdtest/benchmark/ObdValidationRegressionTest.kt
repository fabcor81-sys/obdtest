package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import com.example.obdtest.ui.SampleStatus
import org.junit.Assert.*
import org.junit.Test

class ObdValidationRegressionTest {
    private fun parse(raw: String, command: String = "0100\r") = ObdResponseParser.parseResponse(
        ObdProfile.LOTUS_ABS, command, raw, raw.contains('>'), raw.length
    )

    @Test fun absRejectsNegativeResponsesAndUnrelatedText() {
        for (raw in listOf("7F 01 11\r>", "7F 01 78\r>", "OK\r>", "SEARCHING...\r>", ">", "41 20 BE 3F A8 13\r>", "41 00 BE\r>")) {
            assertEquals(raw, SampleStatus.ECU_ERROR, parse(raw).status)
        }
    }

    @Test fun absAcceptsOnlyCompleteMatchingPayload() {
        val reply = parse("0100\rSEARCHING...\r\n41 00 BE 3F A8 13\r\n>")
        assertEquals(SampleStatus.SUCCESS, reply.status)
        assertEquals("4100BE3FA813", reply.extractedPayload)
    }

    @Test fun separateTruncatedEcuLinesCannotFormAValidPayload() {
        assertEquals(SampleStatus.ECU_ERROR, parse("41 00 BE 3F\r41 00 A8 13\r>").status)
    }

    @Test fun unknownCommandCannotClaimValidatedSuccess() {
        assertEquals(SampleStatus.ECU_ERROR, parse("61 30 12 34\r>", "2130\r").status)
    }

    @Test fun knownPidRequiresAllItsDataBytes() {
        assertEquals(SampleStatus.ECU_ERROR, parse("41 0C\r>", "010C\r").status)
        assertEquals(SampleStatus.SUCCESS, parse("41 0C 1A F8\r>", "010C\r").status)
    }
}
