package com.example.obdtest.ui

import org.junit.Assert.*
import org.junit.Test

class BenchmarkUiPolicyTest {
    private val usb = BluetoothDeviceItem("KKL 409.1", "usb:0403:6001", isUsb = true)
    private val bluetooth = BluetoothDeviceItem("Norauto ELM327", "00:11:22:33:44:55")

    @Test fun usbDoesNotRequireBluetoothPermissionsOrRadio() {
        assertTrue(BenchmarkUiPolicy.canRun(usb, false, false))
        assertTrue(BenchmarkUiPolicy.canRun(usb, true, false))
        assertFalse(BenchmarkUiPolicy.canRun(bluetooth, false, false))
        assertFalse(BenchmarkUiPolicy.canRun(bluetooth, true, false))
        assertTrue(BenchmarkUiPolicy.canRun(bluetooth, true, true))
    }

    @Test fun profileCorrectionMatchesActualAdapter() {
        assertEquals(ObdProfile.USB_KLINE_DIAG, BenchmarkUiPolicy.correctedProfile(usb, ObdProfile.AT_ONLY))
        assertEquals(ObdProfile.AT_ONLY, BenchmarkUiPolicy.correctedProfile(bluetooth, ObdProfile.USB_KLINE_DIAG))
        assertTrue(BenchmarkUiPolicy.supportsProfile(usb, ObdProfile.LOTUS_ABS))
        assertFalse(BenchmarkUiPolicy.supportsProfile(bluetooth, ObdProfile.USB_KLINE_DIAG))
        assertFalse(BenchmarkUiPolicy.supportsProfile(usb, ObdProfile.GENERIC_AUTO))
    }

    @Test fun busyGuardsPreventNavigationDuringConnectionAndTesting() {
        assertTrue(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Connecting))
        assertTrue(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Warmup))
        assertTrue(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Initializing))
        assertFalse(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Completed))
        assertFalse(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Cancelled))
        assertFalse(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Idle))
        assertFalse(BenchmarkUiPolicy.isBusy(BenchmarkStatus.Error("closed")))
    }

    @Test fun comparisonRejectsDifferentParametersOrPartialSessions() {
        val baseline = BenchmarkMetrics(
            deviceName = "A", obdProfile = ObdProfile.LOTUS_ABS,
            effectiveTransport = TransportMode.CLASSIC_SPP,
            targetRequestCount = 100, successCount = 90, totalCommandsSent = 100,
            measuredCommand = "1A80"
        )
        assertTrue(BenchmarkUiPolicy.comparable(baseline, baseline.copy(deviceName = "B")))
        assertFalse(BenchmarkUiPolicy.comparable(baseline, baseline.copy(obdProfile = ObdProfile.GENERIC_AUTO)))
        assertFalse(BenchmarkUiPolicy.comparable(baseline, baseline.copy(effectiveTransport = TransportMode.BLE_GATT)))
        assertFalse(BenchmarkUiPolicy.comparable(baseline, baseline.copy(targetRequestCount = 250)))
        assertFalse(BenchmarkUiPolicy.comparable(baseline, baseline.copy(isPartialSession = true)))
        assertFalse(BenchmarkUiPolicy.comparable(baseline, baseline.copy(successCount = 0)))
    }
}
