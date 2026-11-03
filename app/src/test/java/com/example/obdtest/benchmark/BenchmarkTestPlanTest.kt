package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import org.junit.Assert.*
import org.junit.Test

class BenchmarkTestPlanTest {
    @Test fun genericObdNeverRunsLotusServices() {
        val plan = BenchmarkTestPlan.bluetooth(ObdProfile.GENERIC_AUTO, 100)
        assertEquals(listOf("ATI", "0100"), plan.map { it.command.trim() })
        assertFalse(BenchmarkTestPlan.containsActuatorCommand(plan))
    }

    @Test fun atOnlyUsesOnlyAdapterIdentification() {
        val plan = BenchmarkTestPlan.bluetooth(ObdProfile.AT_ONLY, 25)
        assertEquals(1, plan.size)
        assertEquals("ATI", plan.single().command.trim())
        assertEquals(25, plan.single().repetitions)
    }

    @Test fun lotusAbsPumpOffIsAbsentByDefault() {
        val plan = BenchmarkTestPlan.bluetooth(ObdProfile.LOTUS_ABS, 50)
        assertFalse(BenchmarkTestPlan.containsActuatorCommand(plan))
        assertEquals(listOf("ATI", "1A80", "2101", "2102"), plan.map { it.command.trim() })
    }

    @Test fun lotusOptionalPumpOffIsOneShotAndNeedsAuthorization() {
        val plan = BenchmarkTestPlan.bluetooth(ObdProfile.LOTUS_ABS, 50, allowPumpOff = true)
        val last = plan.last()
        assertEquals("30020700", last.command.trim())
        assertEquals(1, last.repetitions)
        assertTrue(last.requiresAuthorization)
        assertTrue(last.isSingleExecution)
        assertTrue(BenchmarkTestPlan.containsActuatorCommand(plan))
    }

    @Test fun usbNeverContainsActuatorCommands() {
        val diag = BenchmarkTestPlan.usb(ObdProfile.USB_KLINE_DIAG, 100)
        assertEquals(1, diag.size)
        assertEquals(1, diag.single().repetitions)
        assertFalse(BenchmarkTestPlan.containsActuatorCommand(diag))
        val lotus = BenchmarkTestPlan.usb(ObdProfile.LOTUS_ABS, 250)
        assertEquals(2, lotus.size)
        assertFalse(BenchmarkTestPlan.containsActuatorCommand(lotus))
    }
}
