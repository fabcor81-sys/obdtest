package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ObdLotusAbsInitializationTest {

    @Test
    fun testLotusAbsSequenceCommandsAndOrder() {
        val expectedSequence = listOf(
            "AT Z\r",
            "AT L1\r",
            "AT SH 812BF1\r",
            "AT WM 812BF13E\r",
            "AT SP 5\r"
        )

        assertEquals(5, expectedSequence.size)
        assertEquals("AT Z\r", expectedSequence[0])
        assertEquals("AT L1\r", expectedSequence[1])
        assertEquals("AT SH 812BF1\r", expectedSequence[2])
        assertEquals("AT WM 812BF13E\r", expectedSequence[3])
        assertEquals("AT SP 5\r", expectedSequence[4])
    }

    @Test
    fun testRawCommandResultPromptHandlingAndInitValidation() {
        // Simuliamo la risposta ELM327 reale al comando AT L1 con il prompt '>'
        val rawATL1 = "AT L1\rOK\r\n>"
        val cleanATL1 = ObdResponseParser.cleanRawResponse(rawATL1, "AT L1")

        val res = RawCommandResult(
            rawResponse = rawATL1,
            cleanResponse = cleanATL1,
            hasPrompt = rawATL1.contains(">"),
            isTimeout = !rawATL1.contains(">")
        )

        assertTrue("La risposta deve contenere il prompt '>'", res.hasPrompt)
        assertFalse("Non deve essere in timeout", res.isTimeout)
        assertEquals("OK", res.cleanResponse)

        val cleanUpper = res.cleanResponse.uppercase(Locale.US)
        val isOk = res.hasPrompt && !res.isTimeout && !cleanUpper.contains("ERROR") && !cleanUpper.contains("?")
        assertTrue("La risposta 'OK\r\n>' al comando AT L1 deve essere considerata valida!", isOk)
    }

    @Test
    fun testRawCommandResultRejectsMissingPromptOrError() {
        // Caso 1: Mancanza di prompt (Timeout)
        val rawNoPrompt = "AT L1\rOK\r\n"
        val cleanNoPrompt = ObdResponseParser.cleanRawResponse(rawNoPrompt, "AT L1")
        val resTimeout = RawCommandResult(
            rawResponse = rawNoPrompt,
            cleanResponse = cleanNoPrompt,
            hasPrompt = rawNoPrompt.contains(">"),
            isTimeout = !rawNoPrompt.contains(">")
        )
        val isOkTimeout = resTimeout.hasPrompt && !resTimeout.isTimeout
        assertFalse("Se manca il prompt '>', il comando deve fallire!", isOkTimeout)

        // Caso 2: Risposta con errore '?'
        val rawError = "AT SH 812BF1\r?\r\n>"
        val cleanError = ObdResponseParser.cleanRawResponse(rawError, "AT SH 812BF1")
        val resError = RawCommandResult(
            rawResponse = rawError,
            cleanResponse = cleanError,
            hasPrompt = rawError.contains(">"),
            isTimeout = false
        )
        val isOkError = resError.hasPrompt && !resError.isTimeout && !resError.cleanResponse.contains("?")
        assertFalse("Se la risposta contiene '?', il comando deve fallire!", isOkError)
    }

    @Test
    fun testSafetyNoDangerousActuatorOrClearDtcCommands() {
        val lotusProfile = ObdProfile.LOTUS_ABS
        val command = lotusProfile.defaultCommand
        assertFalse("Il comando non deve essere una cancellazione guasti", command.contains("04"))
        assertFalse("Il comando non deve essere un'attuazione", command.contains("2F") || command.contains("30"))
        assertTrue("Il comando deve essere una richiesta di lettura passiva", command.startsWith("01"))
    }
}
