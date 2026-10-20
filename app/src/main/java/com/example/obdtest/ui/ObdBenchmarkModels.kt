package com.example.obdtest.ui

data class BluetoothDeviceItem(
    val name: String,
    val address: String,
    val isBle: Boolean = false
)

sealed interface BenchmarkStatus {
    object Idle : BenchmarkStatus
    object Connecting : BenchmarkStatus
    object Initializing : BenchmarkStatus
    data class RunningTest(
        val completedRequests: Int,
        val targetRequests: Int,
        val currentPidsPerSec: Double,
        val lastLatencyMs: Double
    ) : BenchmarkStatus
    object Completed : BenchmarkStatus
    data class Error(val message: String) : BenchmarkStatus
}

data class CommandTimingSample(
    val sampleIndex: Int,
    val t0Nanos: Long,
    val t1Nanos: Long,
    val t2Nanos: Long,
    val t3Nanos: Long,
    val writeDurationMs: Double,
    val timeToFirstByteMs: Double,
    val rxDurationMs: Double,
    val totalRoundTripMs: Double,
    val interRequestIntervalMs: Double,
    val isSuccess: Boolean,
    val isTimeout: Boolean,
    val commandSent: String,
    val rawResponse: String
)

data class BenchmarkMetrics(
    val deviceName: String = "",
    val deviceAddress: String = "",
    val elmVersion: String = "N/D",
    val protocolName: String = "N/D",
    val targetRequestCount: Int = 100,
    val totalCommandsSent: Int = 0,
    val successfulResponses: Int = 0,
    val timeoutCount: Int = 0,
    val minLatencyMs: Double = 0.0,
    val avgLatencyMs: Double = 0.0,
    val maxLatencyMs: Double = 0.0,
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0,
    val stdDevMs: Double = 0.0,
    val avgInterRequestIntervalMs: Double = 0.0,
    val commandsPerSecond: Double = 0.0,
    val bytesPerSecond: Double = 0.0,
    val testDurationMs: Long = 0L,
    val rawSamples: List<CommandTimingSample> = emptyList()
)

data class ObdUiState(
    val pairedDevices: List<BluetoothDeviceItem> = emptyList(),
    val selectedDevice: BluetoothDeviceItem? = null,
    val selectedRequestCount: Int = 100,
    val isScanning: Boolean = false,
    val status: BenchmarkStatus = BenchmarkStatus.Idle,
    val statusMessage: String = "Seleziona un dispositivo e avvia il benchmark metrologico",
    val currentMetrics: BenchmarkMetrics? = null,
    val savedComparisonMetrics: BenchmarkMetrics? = null,
    val logs: List<String> = emptyList(),
    val hasPermissions: Boolean = false,
    val exportedCsvPath: String? = null
)
