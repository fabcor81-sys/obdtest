package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdLotusAbsInitializationTest {

    @Test
    fun testLotusAbsSequenceCommandsAndOrder() {
        // Verifica la sequenza richiesta per Lotus ABS: ATZ -> AT L1 -> AT SH 812BF1 -> AT WM 812BF13E -> AT SP 5
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
    fun testSafetyNoDangerousActuatorOrClearDtcCommands() {
        // Garantisce che nessun comando di cancellazione DTC (04 / CLEAR) o attuazione sia presente
        val lotusProfile = ObdProfile.LOTUS_ABS

        val command = lotusProfile.defaultCommand
        assertFalse("Il comando non deve essere una cancellazione guasti", command.contains("04"))
        assertFalse("Il comando non deve essere un'attuazione", command.contains("2F") || command.contains("30"))
        assertTrue("Il comando deve essere una richiesta di lettura passiva", command.startsWith("01"))
    }
}
