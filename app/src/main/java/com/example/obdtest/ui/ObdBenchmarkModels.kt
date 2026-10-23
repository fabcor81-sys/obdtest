package com.example.obdtest.ui

enum class SampleStatus {
    POSITIVE_RESPONSE,   // Risposta KWP2000 o AT positiva con prompt
    NEGATIVE_RESPONSE,   // Risposta negativa KWP2000 (es. 7F 01 11 / 7F 30 22)
    TIMEOUT,             // Nessuna risposta o nessun prompt entro il timeout
    INCOMPLETE_RESPONSE, // Risposta parziale priva di prompt o troncata
    ADAPTER_ERROR,       // Errore dell'adattatore ELM (es. BUS INIT: ERROR, UNABLE TO CONNECT, ?)
    TRANSPORT_ERROR,     // Errore del socket/GATT Bluetooth
    UNSUPPORTED,         // Servizio o comando non supportato
    NOT_VERIFIED,        // Comando o test non ancora verificato su hardware
    SUCCESS              // Compatibilità con codice precedente
}

enum class TransportMode(val label: String) {
    AUTO("Automatico"),
    CLASSIC_SPP("Bluetooth Classic (SPP)"),
    BLE_GATT("Bluetooth Low Energy (GATT)")
}

enum class ObdProfile(
    val label: String,
    val defaultCommand: String,
    val description: String
) {
    AT_ONLY(
        label = "AT-Only (ATI)",
        defaultCommand = "ATI\r",
        description = "Latenza chip/Bluetooth dell'adattatore (senza ECU)"
    ),
    GENERIC_AUTO(
        label = "OBD Generico (Auto Protocol)",
        defaultCommand = "0100\r",
        description = "Inizializzazione standard OBD2 AT SP 0"
    ),
    LOTUS_ABS(
        label = "ECU ABS Lotus (EBC430 KWP Fast)",
        defaultCommand = "0100\r",
        description = "Sequenza specifica: ATZ -> AT L1 -> AT SH 812BF1 -> AT WM 812BF13E -> AT SP 5"
    )
}

enum class BenchmarkMode(val label: String, val command: String) {
    AT_ONLY("Adattatore AT-Only (ATI)", "ATI\r"),
    OBD_PID("ECU / OBD2 (PID 0100)", "0100\r")
}

data class BluetoothDeviceItem(
    val name: String,
    val address: String,
    val isBle: Boolean = false
)

sealed interface BenchmarkStatus {
    object Idle : BenchmarkStatus
    object Connecting : BenchmarkStatus
    object Initializing : BenchmarkStatus
    object Warmup : BenchmarkStatus
    data class RunningTestSequence(
        val currentTestIndex: Int,
        val totalTests: Int,
        val testName: String,
        val completedRequests: Int,
        val targetRequests: Int,
        val currentPidsPerSec: Double,
        val lastLatencyMs: Double
    ) : BenchmarkStatus
    object Completed : BenchmarkStatus
    object Cancelled : BenchmarkStatus
    data class Error(val message: String) : BenchmarkStatus
}

data class CommandTimingSample(
    val sampleIndex: Int,
    val testName: String = "",
    val t0Nanos: Long,
    val t1Nanos: Long,
    val t2Nanos: Long,
    val tPayloadNanos: Long = 0L,
    val t3Nanos: Long,
    val writeDurationMs: Double,
    val timeToFirstByteMs: Double,
    val payloadRxDurationMs: Double = 0.0,
    val promptDelayMs: Double = 0.0,
    val rxDurationMs: Double,
    val totalRoundTripMs: Double,
    val interRequestIntervalMs: Double,
    val status: SampleStatus,
    val isSuccess: Boolean,
    val isTimeout: Boolean,
    val commandSent: String,
    val rawResponse: String,
    val parsedResponse: String = "",
    val serviceId: String = "",
    val nrcCode: String = ""
)

data class SingleTestSummary(
    val testName: String,
    val commandSent: String,
    val samplesRequested: Int,
    val samplesExecuted: Int,
    val positiveResponses: Int,
    val negativeResponses: Int,
    val timeouts: Int,
    val adapterErrors: Int,
    val minLatencyMs: Double,
    val avgLatencyMs: Double,
    val maxLatencyMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val stdDevMs: Double,
    val jitterMs: Double,
    val avgInterRequestIntervalMs: Double,
    val commandsPerSecond: Double,
    val percentUnder30ms: Double,
    val percentUnder40ms: Double,
    val isSingleExecution: Boolean = false,
    val singleExecutionResult: String = ""
)

data class BenchmarkMetrics(
    val deviceName: String = "",
    val deviceAddress: String = "",
    val effectiveTransport: TransportMode = TransportMode.AUTO,
    val obdProfile: ObdProfile = ObdProfile.AT_ONLY,
    val measuredCommand: String = "ATI\r",
    val benchmarkMode: BenchmarkMode = BenchmarkMode.AT_ONLY,
    val elmVersion: String = "N/D",
    val protocolName: String = "N/D",
    val targetRequestCount: Int = 100,
    val warmupRequestCount: Int = 3,
    val warmupFirstLatencyMs: Double = 0.0,
    val isWarmupSuccess: Boolean = true,
    val isAbsPumpOffAuthorized: Boolean = false,
    val totalCommandsSent: Int = 0,
    val successCount: Int = 0,
    val ecuErrorCount: Int = 0,
    val adapterErrorCount: Int = 0,
    val timeoutCount: Int = 0,
    val incompleteCount: Int = 0,
    val minLatencyMs: Double = 0.0,
    val avgLatencyMs: Double = 0.0,
    val maxLatencyMs: Double = 0.0,
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0,
    val stdDevMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val avgInterRequestIntervalMs: Double = 0.0,
    val commandsPerSecond: Double = 0.0,
    val appBytesPerSecond: Double = 0.0,
    val rawBytesPerSecond: Double = 0.0,
    val testDurationMs: Long = 0L,
    val timeoutMs: Long = 2500L,
    val isPartialSession: Boolean = false,
    val testSummaries: List<SingleTestSummary> = emptyList(),
    val initLog: List<String> = emptyList(),
    val sessionLogs: List<String> = emptyList(),
    val rawSamples: List<CommandTimingSample> = emptyList()
) {
    val successfulResponses: Int get() = successCount
    val successRatePercent: Double
        get() = if (totalCommandsSent > 0) (successCount.toDouble() / totalCommandsSent.toDouble()) * 100.0 else 0.0
    val speedUnitLabel: String
        get() = if (obdProfile == ObdProfile.AT_ONLY) "Cmd/s" else "PID/s"
}

data class ObdUiState(
    val pairedDevices: List<BluetoothDeviceItem> = emptyList(),
    val selectedDevice: BluetoothDeviceItem? = null,
    val selectedTransportMode: TransportMode = TransportMode.AUTO,
    val selectedProfile: ObdProfile = ObdProfile.AT_ONLY,
    val customCommand: String = "0100\r",
    val selectedRequestCount: Int = 100,
    val isAbsPumpOffAuthorized: Boolean = false,
    val isScanning: Boolean = false,
    val isBluetoothEnabled: Boolean = true,
    val hasPermissions: Boolean = false,
    val status: BenchmarkStatus = BenchmarkStatus.Idle,
    val statusMessage: String = "Seleziona un dispositivo, modalità e avvia il benchmark metrologico",
    val currentMetrics: BenchmarkMetrics? = null,
    val savedComparisonMetrics: BenchmarkMetrics? = null,
    val logs: List<String> = emptyList(),
    val exportedCsvPath: String? = null
)
