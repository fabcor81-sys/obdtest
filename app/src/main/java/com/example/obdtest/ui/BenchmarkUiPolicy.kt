package com.example.obdtest.ui

/**
 * Pure rules shared by the UI and ViewModel.
 * A USB K-Line benchmark must never depend on Bluetooth permissions/state.
 * OBD Test measures read-only communication and does not activate ABS actuators.
 */
object BenchmarkUiPolicy {
    fun isBusy(status: BenchmarkStatus): Boolean = status !is BenchmarkStatus.Idle &&
        status !is BenchmarkStatus.Completed &&
        status !is BenchmarkStatus.Cancelled &&
        status !is BenchmarkStatus.Error

    fun canRun(
        device: BluetoothDeviceItem?,
        hasBluetoothPermissions: Boolean,
        bluetoothEnabled: Boolean
    ): Boolean = device != null && (device.isUsb || (hasBluetoothPermissions && bluetoothEnabled))

    fun supportsProfile(device: BluetoothDeviceItem?, profile: ObdProfile): Boolean = when {
        device == null -> false
        device.isUsb -> profile == ObdProfile.USB_KLINE_DIAG || profile == ObdProfile.LOTUS_ABS
        else -> profile != ObdProfile.USB_KLINE_DIAG
    }

    fun correctedProfile(device: BluetoothDeviceItem?, requested: ObdProfile): ObdProfile =
        if (supportsProfile(device, requested)) requested
        else if (device?.isUsb == true) ObdProfile.USB_KLINE_DIAG
        else ObdProfile.AT_ONLY

    fun comparable(baseline: BenchmarkMetrics, candidate: BenchmarkMetrics): Boolean =
        !baseline.isPartialSession && !candidate.isPartialSession &&
            baseline.successCount > 0 && candidate.successCount > 0 &&
            baseline.obdProfile == candidate.obdProfile &&
            baseline.effectiveTransport == candidate.effectiveTransport &&
            baseline.measuredCommand.trim() == candidate.measuredCommand.trim() &&
            baseline.targetRequestCount == candidate.targetRequestCount &&
            baseline.isAbsPumpOffAuthorized == candidate.isAbsPumpOffAuthorized &&
            baseline.testSummaries.map { it.commandSent.trim() } ==
                candidate.testSummaries.map { it.commandSent.trim() }
}
