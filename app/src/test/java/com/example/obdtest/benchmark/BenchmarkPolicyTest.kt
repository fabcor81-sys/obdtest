package com.example.obdtest.benchmark

import com.example.obdtest.ui.TransportMode
import org.junit.Assert.*
import org.junit.Test

class BenchmarkPolicyTest {
    private fun result(raw: String, command: String = "AT L1") = RawCommandResult(
        raw, ObdResponseParser.cleanRawResponse(raw, command), raw.contains('>'), !raw.contains('>')
    )

    @Test fun realSequenceUsesRequiredCommandsInOrder() {
        assertEquals(listOf("AT Z\r", "AT L1\r", "AT SH 812BF1\r", "AT WM 812BF13E\r", "AT SP 5\r"), BenchmarkPolicy.lotusCommands)
    }

    @Test fun actualValidatorAcceptsOkWithEchoAndLinefeeds() {
        assertTrue(BenchmarkPolicy.acceptsInitialization("AT L1", result("AT L1\rOK\r\n>")))
    }

    @Test fun actualValidatorRejectsEmptyPromptErrorAndMissingPrompt() {
        for (raw in listOf(">", "AT L1\r>", "OK\r", "?\r>", "ERROR\r>", "STOPPED\r>", "arbitrary text\r>")) {
            assertFalse(raw, BenchmarkPolicy.acceptsInitialization("AT L1", result(raw)))
        }
    }

    @Test fun resetRequiresAnIdentityNotJustAnAcknowledgment() {
        assertTrue(BenchmarkPolicy.acceptsInitialization("AT Z", result("AT Z\rELM327 v1.5\r>", "AT Z")))
        assertFalse(BenchmarkPolicy.acceptsInitialization("AT Z", result("OK\r>")))
        assertFalse(BenchmarkPolicy.acceptsInitialization("AT Z", result(">")))
    }

    @Test fun warmupCannotSucceedWithoutAValidReply() {
        assertFalse(BenchmarkPolicy.warmupPassed(3, 0))
        assertFalse(BenchmarkPolicy.warmupPassed(0, 0))
        assertTrue(BenchmarkPolicy.warmupPassed(3, 1))
    }

    @Test fun transportFollowsActualConnectionIncludingFallback() {
        assertEquals(TransportMode.CLASSIC_SPP, BenchmarkPolicy.effectiveTransport(false))
        assertEquals(TransportMode.BLE_GATT, BenchmarkPolicy.effectiveTransport(true))
    }

    @Test fun measuredClockExcludesPreparationForCompleteAndPartialReports() {
        var now = 10_000_000_000L
        val clock = MeasurementClock { now }
        now += 5_000_000_000L
        assertEquals(0L, clock.elapsedMs())
        clock.start()
        now += 250_000_000L
        assertEquals(250L, clock.elapsedMs())
        now += 750_000_000L
        assertEquals(1000L, clock.elapsedMs())
    }
}
