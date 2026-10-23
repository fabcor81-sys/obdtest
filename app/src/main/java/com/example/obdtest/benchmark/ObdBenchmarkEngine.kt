package com.example.obdtest.benchmark

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.obdtest.ui.BenchmarkMetrics
import com.example.obdtest.ui.BenchmarkMode
import com.example.obdtest.ui.BenchmarkStatus
import com.example.obdtest.ui.CommandTimingSample
import com.example.obdtest.ui.ObdProfile
import com.example.obdtest.ui.SampleStatus
import com.example.obdtest.ui.SingleTestSummary
import com.example.obdtest.ui.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

data class BenchmarkTestDefinition(
    val name: String,
    val command: String,
    val repetitions: Int,
    val isSingleExecution: Boolean = false,
    val requiresAuthorization: Boolean = false
)

class ObdBenchmarkEngine {

    companion object {
        private const val TAG = "OBD_DEBUG"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEFAULT_TIMEOUT_MS = 2500L
        private const val READ_BUFFER_SIZE = 1024

        private val NORDIC_UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NORDIC_UART_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NORDIC_UART_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        private val FFF0_SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        private val FFF1_TX_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        private val FFF2_RX_UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")

        private val FFE0_SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val FFE1_TX_RX_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

        private val CCCD_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var isBleMode = false

    @Volatile
    private var isGattConnected = false

    @Volatile
    private var isGattReady = false

    @Volatile
    private var activeSessionId: Long = 0L

    private val bleResponseBuffer = StringBuilder()

    @Volatile
    private var bleFirstByteTimestampNanos = 0L

    @Volatile
    private var blePayloadTimestampNanos = 0L

    @Volatile
    private var blePromptTimestampNanos = 0L

    @Volatile
    private var bleCurrentCommand = ""

    private val bleLock = Any()

    @Volatile
    private var isRunning = false

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private fun log(message: String, isError: Boolean = false) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formattedLog = "[$timestamp] $message"
        _logs.update { (it + formattedLog).takeLast(200) }
        if (isError) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun stop() {
        isRunning = false
        closeConnection()
    }

    @SuppressLint("MissingPermission")
    suspend fun runBenchmark(
        context: Context,
        device: BluetoothDevice,
        transportMode: TransportMode = TransportMode.AUTO,
        obdProfile: ObdProfile = ObdProfile.AT_ONLY,
        targetRequestCount: Int = 100,
        warmupCount: Int = 3,
        isAbsPumpOffAuthorized: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onStatusUpdate: (BenchmarkStatus, String) -> Unit,
        onMetricsUpdate: (BenchmarkMetrics) -> Unit
    ): BenchmarkMetrics = withContext(Dispatchers.IO) {
        isRunning = true
        activeSessionId = System.currentTimeMillis()
        val currentSessionId = activeSessionId

        val isExplicitBle = transportMode == TransportMode.BLE_GATT ||
                (transportMode == TransportMode.AUTO && (
                        device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                        device.type == BluetoothDevice.DEVICE_TYPE_DUAL
                ))

        isBleMode = isExplicitBle
        val effectiveTransport = if (isBleMode) TransportMode.BLE_GATT else TransportMode.CLASSIC_SPP

        var metrics = BenchmarkMetrics(
            deviceName = device.name ?: device.address,
            deviceAddress = device.address,
            effectiveTransport = effectiveTransport,
            obdProfile = obdProfile,
            targetRequestCount = targetRequestCount,
            warmupRequestCount = warmupCount,
            isAbsPumpOffAuthorized = isAbsPumpOffAuthorized,
            timeoutMs = timeoutMs
        )

        val allSamples = mutableListOf<CommandTimingSample>()
        val testSummaries = mutableListOf<SingleTestSummary>()
        val connectionStartMs = SystemClock.elapsedRealtime()

        val testSequence = listOf(
            BenchmarkTestDefinition("1. Identificazione ELM", "ATI\r", targetRequestCount),
            BenchmarkTestDefinition("2. Test diagnostico precedente", "0100\r", targetRequestCount),
            BenchmarkTestDefinition("3. ECU Identification", "1A80\r", targetRequestCount),
            BenchmarkTestDefinition("4. Battery Voltage", "2101\r", targetRequestCount),
            BenchmarkTestDefinition("5. ABS Status", "2102\r", targetRequestCount),
            BenchmarkTestDefinition(
                name = "6. ABS Pump OFF",
                command = "30020700\r",
                repetitions = 1,
                isSingleExecution = true,
                requiresAuthorization = true
            )
        )

        try {
            log("Connessione a ${device.name ?: device.address} via ${effectiveTransport.label}...")
            onStatusUpdate(BenchmarkStatus.Connecting, "Connessione in corso a ${device.name ?: device.address} (${effectiveTransport.label})...")

            if (isBleMode) {
                connectGattDevice(context.applicationContext, device)
            } else {
                try {
                    connectClassicSocket(device)
                } catch (e: Exception) {
                    if (transportMode == TransportMode.AUTO) {
                        log("Connessione Classic SPP fallita (${e.message}). Fallback su BLE GATT...")
                        isBleMode = true
                        connectGattDevice(context.applicationContext, device)
                    } else {
                        throw e
                    }
                }
            }

            coroutineContext.ensureActive()
            log("Connessione stabilita con successo via ${if (isBleMode) "BLE GATT" else "Classic SPP"}!")

            onStatusUpdate(BenchmarkStatus.Initializing, "Inizializzazione (${obdProfile.label})...")

            val initLogEntries = mutableListOf<String>()

            fun sendInitCmd(cmd: String, cmdTimeout: Long = timeoutMs): RawCommandResult {
                val res = executeRawCommand(cmd, cmdTimeout)
                val entry = "CMD: ${cmd.trim()} -> RESP: ${if (res.cleanResponse.isBlank()) "OK/NO_RESP" else res.cleanResponse}"
                initLogEntries.add(entry)
                log("[INIT] $entry")
                return res
            }

            fun verifyInitResp(cmdName: String, res: RawCommandResult): Boolean {
                val cleanUpper = res.cleanResponse.uppercase(Locale.US)
                val isOk = res.hasPrompt && !res.isTimeout && !cleanUpper.contains("ERROR") && !cleanUpper.contains("?")
                if (!isOk) {
                    log("ERRORE INIZIALIZZAZIONE [$cmdName]: risposta non valida -> '${res.cleanResponse}'", isError = true)
                }
                return isOk
            }

            val elmVersion: String
            val protocol: String

            when (obdProfile) {
                ObdProfile.AT_ONLY -> {
                    log("Configurazione modalità AT-Only (Latenza Chip ATI)...")
                    sendInitCmd("ATE0\r")
                    sendInitCmd("ATL0\r")
                    sendInitCmd("ATH0\r")

                    val atiRes = sendInitCmd("ATI\r")
                    elmVersion = if (atiRes.cleanResponse.isNotBlank()) atiRes.cleanResponse else "ELM327 / Chip AT"
                    protocol = "N/D (AT-Only)"
                }

                ObdProfile.GENERIC_AUTO -> {
                    log("Inizializzazione OBD Standard Auto Protocol (AT SP 0)...")
                    val atzRes = sendInitCmd("AT Z\r", 3000L)
                    kotlinx.coroutines.delay(600)
                    clearBuffer()

                    elmVersion = if (atzRes.cleanResponse.isNotBlank()) atzRes.cleanResponse else "ELM327 v1.5"

                    sendInitCmd("ATE0\r")
                    sendInitCmd("ATL0\r")
                    sendInitCmd("ATH0\r")
                    sendInitCmd("AT M0\r")
                    sendInitCmd("AT SP 0\r")

                    val atdpRes = sendInitCmd("AT DP\r")
                    protocol = if (atdpRes.cleanResponse.isNotBlank()) atdpRes.cleanResponse else "Auto OBD2"
                }

                ObdProfile.LOTUS_ABS -> {
                    log("Inizializzazione ECU ABS Lotus (Sequenza inderogabile: ATZ -> AT L1 -> AT SH 812BF1 -> AT WM 812BF13E -> AT SP 5)...")
                    val atzRes = sendInitCmd("AT Z\r", 3000L)
                    kotlinx.coroutines.delay(800)
                    clearBuffer()

                    elmVersion = if (atzRes.cleanResponse.isNotBlank()) atzRes.cleanResponse else "ELM327"

                    val resL1 = sendInitCmd("AT L1\r")
                    if (!verifyInitResp("AT L1", resL1)) throw Exception("Inizializzazione Lotus ABS fallita al comando AT L1")

                    val resSH = sendInitCmd("AT SH 812BF1\r")
                    if (!verifyInitResp("AT SH 812BF1", resSH)) throw Exception("Inizializzazione Lotus ABS fallita al comando AT SH 812BF1")

                    val resWM = sendInitCmd("AT WM 812BF13E\r")
                    if (!verifyInitResp("AT WM 812BF13E", resWM)) throw Exception("Inizializzazione Lotus ABS fallita al comando AT WM 812BF13E")

                    val resSP = sendInitCmd("AT SP 5\r")
                    if (!verifyInitResp("AT SP 5", resSP)) throw Exception("Inizializzazione Lotus ABS fallita al comando AT SP 5")

                    protocol = "ISO 14230-4 KWP2000 Fast Init (Header 812BF1)"
                }
            }

            metrics = metrics.copy(
                elmVersion = elmVersion,
                protocolName = protocol,
                initLog = initLogEntries
            )
            onMetricsUpdate(metrics)

            coroutineContext.ensureActive()

            // FASE DI RISCALDAMENTO PRELIMINARE
            log("=== INIZIO FASE DI RISCALDAMENTO ($warmupCount comandi preliminari) ===")
            onStatusUpdate(BenchmarkStatus.Warmup, "Riscaldamento in corso ($warmupCount comandi preliminari)...")

            var warmupFirstLatency = 0.0
            var warmupSuccesses = 0

            for (w in 1..warmupCount) {
                if (!isRunning) break
                coroutineContext.ensureActive()

                val warmupSample = executeCommandTimed(
                    sampleIndex = -w,
                    testName = "Warmup",
                    profile = obdProfile,
                    command = "ATI\r",
                    previousT0Nanos = 0L,
                    timeoutMs = timeoutMs
                )

                if (w == 1) warmupFirstLatency = warmupSample.totalRoundTripMs
                if (warmupSample.isSuccess) warmupSuccesses++
            }

            metrics = metrics.copy(
                warmupFirstLatencyMs = warmupFirstLatency,
                isWarmupSuccess = (warmupSuccesses > 0)
            )
            onMetricsUpdate(metrics)

            coroutineContext.ensureActive()

            // ESECUZIONE SEQUENZA AUTOMATICA DEI 6 TEST
            val testStartNanos = SystemClock.elapsedRealtimeNanos()
            val activeTestSequence = if (obdProfile == ObdProfile.AT_ONLY) {
                listOf(BenchmarkTestDefinition("1. Identificazione ELM", "ATI\r", targetRequestCount))
            } else {
                testSequence
            }

            for ((testIdx, testDef) in activeTestSequence.withIndex()) {
                if (!isRunning || currentSessionId != activeSessionId) break
                coroutineContext.ensureActive()

                if (testDef.requiresAuthorization && !isAbsPumpOffAuthorized) {
                    log("TEST SKIPPED: ${testDef.name} - Autorizzazione non concessa o non verificata.", isError = false)
                    testSummaries.add(
                        SingleTestSummary(
                            testName = testDef.name,
                            commandSent = testDef.command.trim(),
                            samplesRequested = 1,
                            samplesExecuted = 0,
                            positiveResponses = 0,
                            negativeResponses = 0,
                            unexpectedResponses = 0,
                            timeouts = 0,
                            adapterErrors = 0,
                            minLatencyMs = 0.0,
                            avgLatencyMs = 0.0,
                            maxLatencyMs = 0.0,
                            p50Ms = 0.0,
                            p95Ms = 0.0,
                            p99Ms = 0.0,
                            stdDevMs = 0.0,
                            jitterMs = 0.0,
                            avgInterRequestIntervalMs = 0.0,
                            commandsPerSecond = 0.0,
                            percentUnder30ms = 0.0,
                            percentUnder40ms = 0.0,
                            isSingleExecution = true,
                            singleExecutionResult = "NOT_VERIFIED / SKIPPED"
                        )
                    )
                    continue
                }

                log("=== AVVIO TEST ${testIdx + 1}/${activeTestSequence.size}: ${testDef.name} (${testDef.command.trim()}) ===")
                val testSamples = mutableListOf<CommandTimingSample>()
                var lastT0Nanos = 0L

                val targetReps = if (testDef.isSingleExecution) 1 else testDef.repetitions

                for (rep in 1..targetReps) {
                    if (!isRunning || currentSessionId != activeSessionId) break
                    coroutineContext.ensureActive()

                    val sample = executeCommandTimed(
                        sampleIndex = allSamples.size + 1,
                        testName = testDef.name,
                        profile = obdProfile,
                        command = testDef.command,
                        previousT0Nanos = lastT0Nanos,
                        timeoutMs = timeoutMs
                    )

                    lastT0Nanos = sample.t0Nanos
                    testSamples.add(sample)
                    allSamples.add(sample)

                    val elapsedSec = ((SystemClock.elapsedRealtimeNanos() - testStartNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                    val currentPidsPerSec = allSamples.count { it.isSuccess } / elapsedSec

                    onStatusUpdate(
                        BenchmarkStatus.RunningTestSequence(
                            currentTestIndex = testIdx + 1,
                            totalTests = activeTestSequence.size,
                            testName = testDef.name,
                            completedRequests = rep,
                            targetRequests = targetReps,
                            currentPidsPerSec = currentPidsPerSec,
                            lastLatencyMs = sample.totalRoundTripMs
                        ),
                        "Test ${testIdx + 1}/${activeTestSequence.size}: ${testDef.name} [$rep/$targetReps] - Status: ${sample.status.name} (${String.format(Locale.US, "%.1f", sample.totalRoundTripMs)} ms)"
                    )
                }

                val summary = calculateSingleTestSummary(testDef, testSamples)
                testSummaries.add(summary)

                val currentDurationMs = (SystemClock.elapsedRealtimeNanos() - testStartNanos) / 1_000_000L
                metrics = calculateAggregateMetrics(metrics, allSamples, testSummaries, currentDurationMs, isPartial = false)
                onMetricsUpdate(metrics)
            }

            val finalMeasuredDurationMs = (SystemClock.elapsedRealtimeNanos() - testStartNanos) / 1_000_000L
            metrics = calculateAggregateMetrics(metrics, allSamples, testSummaries, finalMeasuredDurationMs, isPartial = false)

            log("=== BENCHMARK COMPLETATO ===")
            onMetricsUpdate(metrics)
            onStatusUpdate(BenchmarkStatus.Completed, "Benchmark metrologico completato!")

            metrics
        } catch (e: CancellationException) {
            log("Benchmark annullato dall'utente.", isError = false)
            val currentDurationMs = SystemClock.elapsedRealtime() - connectionStartMs
            val partialMetrics = calculateAggregateMetrics(metrics, allSamples, testSummaries, currentDurationMs, isPartial = true)
            onMetricsUpdate(partialMetrics)
            onStatusUpdate(BenchmarkStatus.Cancelled, "Benchmark interrotto dall'utente.")
            throw e
        } catch (e: Exception) {
            val errLog = "ERRORE APPLICATIVO CRITICO: ${e.javaClass.simpleName} - ${e.message}\n${Log.getStackTraceString(e)}"
            log(errLog, isError = true)

            val currentDurationMs = SystemClock.elapsedRealtime() - connectionStartMs
            val partialMetrics = calculateAggregateMetrics(metrics, allSamples, testSummaries, currentDurationMs, isPartial = true)
            onMetricsUpdate(partialMetrics)
            onStatusUpdate(BenchmarkStatus.Error(e.message ?: "Errore di comunicazione"), "Errore: ${e.message}")
            throw e
        } finally {
            closeConnection()
        }
    }

    private fun executeCommandTimed(
        sampleIndex: Int,
        testName: String,
        profile: ObdProfile,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        return if (isBleMode) {
            executeCommandTimedBle(sampleIndex, testName, profile, command, previousT0Nanos, timeoutMs)
        } else {
            executeCommandTimedClassic(sampleIndex, testName, profile, command, previousT0Nanos, timeoutMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeCommandTimedBle(
        sampleIndex: Int,
        testName: String,
        profile: ObdProfile,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        val gatt = bluetoothGatt ?: return createFailedSample(sampleIndex, testName, command, previousT0Nanos)
        val rxChar = rxCharacteristic ?: return createFailedSample(sampleIndex, testName, command, previousT0Nanos)

        synchronized(bleLock) {
            bleResponseBuffer.clear()
            bleFirstByteTimestampNanos = 0L
            blePayloadTimestampNanos = 0L
            blePromptTimestampNanos = 0L
            bleCurrentCommand = command
        }

        val commandBytes = command.toByteArray(Charsets.US_ASCII)
        val t0Nanos = SystemClock.elapsedRealtimeNanos()

        val writeType = if ((rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, commandBytes, writeType)
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = commandBytes
            @Suppress("DEPRECATION")
            rxChar.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }

        val t1Nanos = SystemClock.elapsedRealtimeNanos()

        val timeoutNanos = timeoutMs * 1_000_000L
        var t2Nanos = 0L
        var tPayloadNanos = 0L
        var t3Nanos = 0L
        var rawResponse = ""

        val startWaitNanos = SystemClock.elapsedRealtimeNanos()
        while (isRunning && (SystemClock.elapsedRealtimeNanos() - startWaitNanos) < timeoutNanos) {
            synchronized(bleLock) {
                if (bleFirstByteTimestampNanos > 0L && t2Nanos == 0L) {
                    t2Nanos = bleFirstByteTimestampNanos
                }
                if (blePayloadTimestampNanos > 0L && tPayloadNanos == 0L) {
                    tPayloadNanos = blePayloadTimestampNanos
                }
                if (bleResponseBuffer.contains(">")) {
                    t3Nanos = if (blePromptTimestampNanos > 0L) blePromptTimestampNanos else SystemClock.elapsedRealtimeNanos()
                    if (tPayloadNanos == 0L) {
                        tPayloadNanos = if (blePayloadTimestampNanos > 0L) blePayloadTimestampNanos else t3Nanos
                    }
                    rawResponse = bleResponseBuffer.toString()
                }
            }

            if (t3Nanos > 0L) break
            Thread.sleep(2)
        }

        val hasPrompt = t3Nanos > 0L
        if (!hasPrompt) {
            t3Nanos = SystemClock.elapsedRealtimeNanos()
            synchronized(bleLock) {
                rawResponse = bleResponseBuffer.toString()
            }
        }

        val parseResult = ObdResponseParser.parseResponse(
            profile = profile,
            commandSent = command,
            rawResponse = rawResponse,
            hasPrompt = hasPrompt,
            bytesReceived = rawResponse.length
        )

        val writeDurationMs = (t1Nanos - t0Nanos) / 1_000_000.0
        val timeToFirstByteMs = if (t2Nanos > 0) (t2Nanos - t1Nanos) / 1_000_000.0 else 0.0
        val payloadRxDurationMs = if (t2Nanos > 0 && tPayloadNanos > 0) (tPayloadNanos - t2Nanos) / 1_000_000.0 else 0.0
        val promptDelayMs = if (tPayloadNanos > 0 && t3Nanos > 0) (t3Nanos - tPayloadNanos) / 1_000_000.0 else 0.0
        val rxDurationMs = if (t2Nanos > 0) (t3Nanos - t2Nanos) / 1_000_000.0 else 0.0
        val totalRoundTripMs = (t3Nanos - t0Nanos) / 1_000_000.0
        val interRequestIntervalMs = if (previousT0Nanos > 0) (t0Nanos - previousT0Nanos) / 1_000_000.0 else 0.0

        val isSuccess = parseResult.status == SampleStatus.POSITIVE_RESPONSE || parseResult.status == SampleStatus.SUCCESS

        return CommandTimingSample(
            sampleIndex = sampleIndex,
            testName = testName,
            t0Nanos = t0Nanos,
            t1Nanos = t1Nanos,
            t2Nanos = if (t2Nanos > 0) t2Nanos else t3Nanos,
            tPayloadNanos = tPayloadNanos,
            t3Nanos = t3Nanos,
            writeDurationMs = writeDurationMs,
            timeToFirstByteMs = timeToFirstByteMs,
            payloadRxDurationMs = payloadRxDurationMs,
            promptDelayMs = promptDelayMs,
            rxDurationMs = rxDurationMs,
            totalRoundTripMs = totalRoundTripMs,
            interRequestIntervalMs = interRequestIntervalMs,
            status = parseResult.status,
            isSuccess = isSuccess,
            isTimeout = parseResult.status == SampleStatus.TIMEOUT,
            commandSent = command.trim(),
            rawResponse = parseResult.cleanedResponse,
            parsedResponse = parseResult.extractedPayload,
            serviceId = parseResult.serviceId,
            nrcCode = parseResult.nrcCode
        )
    }

    private fun executeCommandTimedClassic(
        sampleIndex: Int,
        testName: String,
        profile: ObdProfile,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        val out = outputStream ?: return createFailedSample(sampleIndex, testName, command, previousT0Nanos)
        val input = inputStream ?: return createFailedSample(sampleIndex, testName, command, previousT0Nanos)

        clearBuffer()
        val commandBytes = command.toByteArray(Charsets.US_ASCII)

        val t0Nanos = SystemClock.elapsedRealtimeNanos()
        out.write(commandBytes)
        out.flush()
        val t1Nanos = SystemClock.elapsedRealtimeNanos()

        var t2Nanos = 0L
        var tPayloadNanos = 0L
        var t3Nanos = 0L
        var totalBytesRead = 0

        val responseBuilder = StringBuilder()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val timeoutNanos = timeoutMs * 1_000_000L

        while (isRunning && (SystemClock.elapsedRealtimeNanos() - t0Nanos) < timeoutNanos) {
            try {
                if (input.available() > 0) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val nowNanos = SystemClock.elapsedRealtimeNanos()

                        if (t2Nanos == 0L) {
                            t2Nanos = nowNanos
                        }
                        totalBytesRead += bytesRead

                        val text = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                        responseBuilder.append(text)

                        val currentResponse = responseBuilder.toString()
                        if (tPayloadNanos == 0L && ObdResponseParser.isDiagnosticPayloadComplete(command, currentResponse)) {
                            tPayloadNanos = nowNanos
                        }

                        if (currentResponse.contains(">")) {
                            t3Nanos = nowNanos
                            if (tPayloadNanos == 0L) tPayloadNanos = nowNanos
                            break
                        }
                    }
                } else {
                    Thread.sleep(1)
                }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                log("Errore di lettura stream Classic: ${e.message}")
                break
            }
        }

        val hasPrompt = t3Nanos > 0L
        if (!hasPrompt) {
            t3Nanos = SystemClock.elapsedRealtimeNanos()
        }

        val rawResponse = responseBuilder.toString()
        val parseResult = ObdResponseParser.parseResponse(
            profile = profile,
            commandSent = command,
            rawResponse = rawResponse,
            hasPrompt = hasPrompt,
            bytesReceived = totalBytesRead
        )

        val writeDurationMs = (t1Nanos - t0Nanos) / 1_000_000.0
        val timeToFirstByteMs = if (t2Nanos > 0) (t2Nanos - t1Nanos) / 1_000_000.0 else 0.0
        val payloadRxDurationMs = if (t2Nanos > 0 && tPayloadNanos > 0) (tPayloadNanos - t2Nanos) / 1_000_000.0 else 0.0
        val promptDelayMs = if (tPayloadNanos > 0 && t3Nanos > 0) (t3Nanos - tPayloadNanos) / 1_000_000.0 else 0.0
        val rxDurationMs = if (t2Nanos > 0) (t3Nanos - t2Nanos) / 1_000_000.0 else 0.0
        val totalRoundTripMs = (t3Nanos - t0Nanos) / 1_000_000.0
        val interRequestIntervalMs = if (previousT0Nanos > 0) (t0Nanos - previousT0Nanos) / 1_000_000.0 else 0.0

        val isSuccess = parseResult.status == SampleStatus.POSITIVE_RESPONSE || parseResult.status == SampleStatus.SUCCESS

        return CommandTimingSample(
            sampleIndex = sampleIndex,
            testName = testName,
            t0Nanos = t0Nanos,
            t1Nanos = t1Nanos,
            t2Nanos = if (t2Nanos > 0) t2Nanos else t3Nanos,
            tPayloadNanos = tPayloadNanos,
            t3Nanos = t3Nanos,
            writeDurationMs = writeDurationMs,
            timeToFirstByteMs = timeToFirstByteMs,
            payloadRxDurationMs = payloadRxDurationMs,
            promptDelayMs = promptDelayMs,
            rxDurationMs = rxDurationMs,
            totalRoundTripMs = totalRoundTripMs,
            interRequestIntervalMs = interRequestIntervalMs,
            status = parseResult.status,
            isSuccess = isSuccess,
            isTimeout = parseResult.status == SampleStatus.TIMEOUT,
            commandSent = command.trim(),
            rawResponse = parseResult.cleanedResponse,
            parsedResponse = parseResult.extractedPayload,
            serviceId = parseResult.serviceId,
            nrcCode = parseResult.nrcCode
        )
    }

    private fun createFailedSample(
        sampleIndex: Int,
        testName: String,
        command: String,
        previousT0Nanos: Long
    ): CommandTimingSample {
        val now = SystemClock.elapsedRealtimeNanos()
        val interval = if (previousT0Nanos > 0) (now - previousT0Nanos) / 1_000_000.0 else 0.0
        return CommandTimingSample(
            sampleIndex = sampleIndex,
            testName = testName,
            t0Nanos = now,
            t1Nanos = now,
            t2Nanos = now,
            tPayloadNanos = 0L,
            t3Nanos = now,
            writeDurationMs = 0.0,
            timeToFirstByteMs = 0.0,
            payloadRxDurationMs = 0.0,
            promptDelayMs = 0.0,
            rxDurationMs = 0.0,
            totalRoundTripMs = 0.0,
            interRequestIntervalMs = interval,
            status = SampleStatus.TIMEOUT,
            isSuccess = false,
            isTimeout = true,
            commandSent = command.trim(),
            rawResponse = "ERRORE / ADATTATORE NON DISPONIBILE"
        )
    }

    private fun calculateSingleTestSummary(
        testDef: BenchmarkTestDefinition,
        samples: List<CommandTimingSample>
    ): SingleTestSummary {
        if (samples.isEmpty()) {
            return SingleTestSummary(
                testName = testDef.name,
                commandSent = testDef.command.trim(),
                samplesRequested = testDef.repetitions,
                samplesExecuted = 0,
                positiveResponses = 0,
                negativeResponses = 0,
                unexpectedResponses = 0,
                timeouts = 0,
                adapterErrors = 0,
                minLatencyMs = 0.0,
                avgLatencyMs = 0.0,
                maxLatencyMs = 0.0,
                p50Ms = 0.0,
                p95Ms = 0.0,
                p99Ms = 0.0,
                stdDevMs = 0.0,
                jitterMs = 0.0,
                avgInterRequestIntervalMs = 0.0,
                commandsPerSecond = 0.0,
                percentUnder30ms = 0.0,
                percentUnder40ms = 0.0,
                isSingleExecution = testDef.isSingleExecution,
                singleExecutionResult = "NON ESEGUITO"
            )
        }

        val positiveCount = samples.count { it.status == SampleStatus.POSITIVE_RESPONSE || it.status == SampleStatus.SUCCESS }
        val negativeCount = samples.count { it.status == SampleStatus.NEGATIVE_RESPONSE }
        val unexpectedCount = samples.count { it.status == SampleStatus.UNEXPECTED_RESPONSE }
        val timeoutCount = samples.count { it.status == SampleStatus.TIMEOUT }
        val adapterCount = samples.count { it.status == SampleStatus.ADAPTER_ERROR || it.status == SampleStatus.TRANSPORT_ERROR }

        // Mantiene l'ordine cronologico originale d'esecuzione dei campioni per il calcolo delle medie e del Jitter
        val completedChronologicalSamples = samples.filter { it.status != SampleStatus.TIMEOUT && it.status != SampleStatus.TRANSPORT_ERROR }
        val chronologicalLatencies = completedChronologicalSamples.map { it.totalRoundTripMs }

        val minLatency = chronologicalLatencies.minOrNull() ?: 0.0
        val maxLatency = chronologicalLatencies.maxOrNull() ?: 0.0
        val avgLatency = if (chronologicalLatencies.isNotEmpty()) chronologicalLatencies.average() else 0.0

        // Percentili calcolati ESCLUSIVAMENTE sulla lista ordinata
        val sortedLatencies = chronologicalLatencies.sorted()
        val p50 = getPercentile(sortedLatencies, 50.0)
        val p95 = getPercentile(sortedLatencies, 95.0)
        val p99 = getPercentile(sortedLatencies, 99.0)

        val stdDev = if (chronologicalLatencies.size > 1) {
            val mean = avgLatency
            sqrt(chronologicalLatencies.sumOf { (it - mean) * (it - mean) } / chronologicalLatencies.size)
        } else 0.0

        // Jitter calcolato sui campioni consecutivi completati nell'ordine CRONOLOGICO ORIGINALE: media(|RTT[i] - RTT[i-1]|)
        val jitter = if (chronologicalLatencies.size > 1) {
            var diffSum = 0.0
            for (i in 0 until chronologicalLatencies.size - 1) {
                diffSum += kotlin.math.abs(chronologicalLatencies[i + 1] - chronologicalLatencies[i])
            }
            diffSum / (chronologicalLatencies.size - 1)
        } else 0.0

        val intervals = samples.drop(1).map { it.interRequestIntervalMs }.filter { it > 0 }
        val avgInterval = if (intervals.isNotEmpty()) intervals.average() else 0.0

        val countUnder30 = intervals.count { it <= 30.0 }
        val countUnder40 = intervals.count { it <= 40.0 }
        val percentUnder30 = if (intervals.isNotEmpty()) (countUnder30.toDouble() / intervals.size) * 100.0 else 0.0
        val percentUnder40 = if (intervals.isNotEmpty()) (countUnder40.toDouble() / intervals.size) * 100.0 else 0.0

        // Throughput = transazioni completate / durata effettiva del test in secondi
        val firstT0 = samples.first().t0Nanos
        val lastT3 = samples.last().t3Nanos
        val testDurationSec = ((lastT3 - firstT0) / 1_000_000_000.0).coerceAtLeast(0.001)
        val commandsPerSec = samples.size / testDurationSec

        val singleResText = if (testDef.isSingleExecution) {
            val first = samples.first()
            "Status: ${first.status.name} | RTT: ${String.format(Locale.US, "%.1f", first.totalRoundTripMs)} ms | Resp: ${first.rawResponse}"
        } else ""

        return SingleTestSummary(
            testName = testDef.name,
            commandSent = testDef.command.trim(),
            samplesRequested = testDef.repetitions,
            samplesExecuted = samples.size,
            positiveResponses = positiveCount,
            negativeResponses = negativeCount,
            unexpectedResponses = unexpectedCount,
            timeouts = timeoutCount,
            adapterErrors = adapterCount,
            minLatencyMs = minLatency,
            avgLatencyMs = avgLatency,
            maxLatencyMs = maxLatency,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            stdDevMs = stdDev,
            jitterMs = jitter,
            avgInterRequestIntervalMs = avgInterval,
            commandsPerSecond = commandsPerSec,
            percentUnder30ms = percentUnder30,
            percentUnder40ms = percentUnder40,
            isSingleExecution = testDef.isSingleExecution,
            singleExecutionResult = singleResText
        )
    }

    private fun calculateAggregateMetrics(
        baseMetrics: BenchmarkMetrics,
        allSamples: List<CommandTimingSample>,
        testSummaries: List<SingleTestSummary>,
        totalDurationMs: Long,
        isPartial: Boolean
    ): BenchmarkMetrics {
        if (allSamples.isEmpty()) return baseMetrics.copy(isPartialSession = isPartial)

        val positiveCount = allSamples.count { it.status == SampleStatus.POSITIVE_RESPONSE || it.status == SampleStatus.SUCCESS }
        val negativeCount = allSamples.count { it.status == SampleStatus.NEGATIVE_RESPONSE }
        val adapterCount = allSamples.count { it.status == SampleStatus.ADAPTER_ERROR || it.status == SampleStatus.TRANSPORT_ERROR }
        val timeoutCount = allSamples.count { it.status == SampleStatus.TIMEOUT }
        val incompleteCount = allSamples.count { it.status == SampleStatus.INCOMPLETE_RESPONSE }

        // Mantiene l'ordine cronologico originale d'esecuzione
        val completedChronological = allSamples.filter { it.status != SampleStatus.TIMEOUT && it.status != SampleStatus.TRANSPORT_ERROR }
        val chronologicalLatencies = completedChronological.map { it.totalRoundTripMs }

        val minLatency = chronologicalLatencies.minOrNull() ?: 0.0
        val maxLatency = chronologicalLatencies.maxOrNull() ?: 0.0
        val avgLatency = if (chronologicalLatencies.isNotEmpty()) chronologicalLatencies.average() else 0.0

        val sorted = chronologicalLatencies.sorted()
        val p50 = getPercentile(sorted, 50.0)
        val p95 = getPercentile(sorted, 95.0)
        val p99 = getPercentile(sorted, 99.0)

        val stdDev = if (chronologicalLatencies.size > 1) {
            val mean = avgLatency
            sqrt(chronologicalLatencies.sumOf { (it - mean) * (it - mean) } / chronologicalLatencies.size)
        } else 0.0

        // Jitter calcolato nell'ordine cronologico originale
        val jitter = if (chronologicalLatencies.size > 1) {
            var diffSum = 0.0
            for (i in 0 until chronologicalLatencies.size - 1) {
                diffSum += kotlin.math.abs(chronologicalLatencies[i + 1] - chronologicalLatencies[i])
            }
            diffSum / (chronologicalLatencies.size - 1)
        } else 0.0

        val totalSec = (totalDurationMs / 1000.0).coerceAtLeast(0.001)
        val commandsPerSec = allSamples.size / totalSec

        return baseMetrics.copy(
            totalCommandsSent = allSamples.size,
            successCount = positiveCount,
            ecuErrorCount = negativeCount,
            adapterErrorCount = adapterCount,
            timeoutCount = timeoutCount,
            incompleteCount = incompleteCount,
            minLatencyMs = minLatency,
            avgLatencyMs = avgLatency,
            maxLatencyMs = maxLatency,
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            stdDevMs = stdDev,
            jitterMs = jitter,
            commandsPerSecond = commandsPerSec,
            testDurationMs = totalDurationMs,
            isPartialSession = isPartial,
            testSummaries = testSummaries,
            sessionLogs = _logs.value,
            rawSamples = allSamples.toList()
        )
    }

    private fun getPercentile(sortedValues: List<Double>, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0]

        val index = (percentile / 100.0) * (sortedValues.size - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sortedValues.size - 1)
        val weight = index - lower

        return sortedValues[lower] * (1.0 - weight) + sortedValues[upper] * weight
    }

    private fun executeRawCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RawCommandResult {
        return if (isBleMode) {
            executeRawCommandBle(command, timeoutMs)
        } else {
            executeRawCommandClassic(command, timeoutMs)
        }
    }

    private fun executeRawCommandClassic(command: String, timeoutMs: Long): RawCommandResult {
        val out = outputStream ?: return RawCommandResult("", "", hasPrompt = false, isTimeout = true)
        val input = inputStream ?: return RawCommandResult("", "", hasPrompt = false, isTimeout = true)

        try {
            clearBuffer()
            out.write(command.toByteArray(Charsets.US_ASCII))
            out.flush()

            val responseBuilder = StringBuilder()
            val startMs = SystemClock.elapsedRealtime()
            val buffer = ByteArray(READ_BUFFER_SIZE)

            while (SystemClock.elapsedRealtime() - startMs < timeoutMs && isRunning) {
                if (input.available() > 0) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val text = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                        responseBuilder.append(text)
                        if (responseBuilder.contains(">")) {
                            break
                        }
                    }
                } else {
                    Thread.sleep(1)
                }
            }

            val raw = responseBuilder.toString()
            val hasPrompt = raw.contains(">")
            val clean = ObdResponseParser.cleanRawResponse(raw, command)

            return RawCommandResult(
                rawResponse = raw,
                cleanResponse = clean,
                hasPrompt = hasPrompt,
                isTimeout = !hasPrompt
            )
        } catch (e: Exception) {
            log("Errore comando raw Classic '$command': ${e.message}")
            return RawCommandResult("", "", hasPrompt = false, isTimeout = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeRawCommandBle(command: String, timeoutMs: Long): RawCommandResult {
        val gatt = bluetoothGatt ?: return RawCommandResult("", "", hasPrompt = false, isTimeout = true)
        val rxChar = rxCharacteristic ?: return RawCommandResult("", "", hasPrompt = false, isTimeout = true)

        synchronized(bleLock) {
            bleResponseBuffer.clear()
        }

        val commandBytes = command.toByteArray(Charsets.US_ASCII)
        val writeType = if ((rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, commandBytes, writeType)
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = commandBytes
            @Suppress("DEPRECATION")
            rxChar.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }

        val startMs = SystemClock.elapsedRealtime()
        var response = ""

        while (SystemClock.elapsedRealtime() - startMs < timeoutMs && isRunning) {
            synchronized(bleLock) {
                if (bleResponseBuffer.contains(">")) {
                    response = bleResponseBuffer.toString()
                }
            }
            if (response.isNotEmpty()) break
            Thread.sleep(2)
        }

        if (response.isEmpty()) {
            synchronized(bleLock) {
                response = bleResponseBuffer.toString()
            }
        }

        val hasPrompt = response.contains(">")
        val clean = ObdResponseParser.cleanRawResponse(response, command)

        return RawCommandResult(
            rawResponse = response,
            cleanResponse = clean,
            hasPrompt = hasPrompt,
            isTimeout = !hasPrompt
        )
    }

    private fun clearBuffer() {
        if (isBleMode) {
            synchronized(bleLock) {
                bleResponseBuffer.clear()
            }
        } else {
            try {
                val input = inputStream ?: return
                while (input.available() > 0) {
                    input.read()
                }
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectClassicSocket(device: BluetoothDevice) {
        closeConnection()

        var success = false
        var lastException: Exception? = null

        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()
            success = true
        } catch (e: Exception) {
            lastException = e
            log("Connessione SPP standard fallita. Tentativo di fallback via reflection...")
            closeConnection()
        }

        if (!success) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = method.invoke(device, 1) as BluetoothSocket
                socket?.connect()
                success = true
                log("Connessione di fallback riuscita!")
            } catch (e: Exception) {
                lastException = e
                log("Anche la connessione di fallback è fallita: ${e.message}")
            }
        }

        if (!success || socket?.isConnected != true) {
            closeConnection()
            throw lastException ?: Exception("Impossibile connettersi al dispositivo Bluetooth Classic")
        }

        inputStream = socket?.inputStream
        outputStream = socket?.outputStream
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectGattDevice(context: Context, device: BluetoothDevice) {
        closeConnection()

        isGattConnected = false
        isGattReady = false

        val gattCallback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isGattConnected = true
                    log("Dispositivo BLE connesso GATT. Avvio scoperta servizi...")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isGattConnected = false
                    isGattReady = false
                    log("Dispositivo BLE disconnesso GATT")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    log("Servizi BLE scoperti. Ricerca caratteristiche seriali...")
                    setupBleSerialCharacteristics(gatt)
                } else {
                    log("Scoperta servizi BLE fallita con stato: $status", isError = true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                @Suppress("DEPRECATION")
                val data = characteristic?.value ?: return
                handleIncomingBleData(data)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)
                handleIncomingBleData(value)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("Notifiche BLE attivate correttamente su CCCD!")
                    isGattReady = true
                } else {
                    log("Attivazione notifiche CCCD fallita: status $status", isError = true)
                }
            }
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        var waitTime = 0
        while (!isGattReady && waitTime < 10000 && isRunning) {
            kotlinx.coroutines.delay(100)
            waitTime += 100
        }

        if (!isGattReady) {
            closeConnection()
            throw Exception("Impossibile inizializzare il servizio seriale BLE del dispositivo")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBleSerialCharacteristics(gatt: BluetoothGatt) {
        val services = gatt.services
        var foundRx: BluetoothGattCharacteristic? = null
        var foundTx: BluetoothGattCharacteristic? = null

        val nusService = gatt.getService(NORDIC_UART_SERVICE_UUID)
        if (nusService != null) {
            foundRx = nusService.getCharacteristic(NORDIC_UART_RX_UUID)
            foundTx = nusService.getCharacteristic(NORDIC_UART_TX_UUID)
            log("Rilevato servizio Nordic UART Service (Norauto/BLE standard)!")
        }

        if (foundRx == null) {
            val fff0Service = gatt.getService(FFF0_SERVICE_UUID)
            if (fff0Service != null) {
                foundTx = fff0Service.getCharacteristic(FFF1_TX_UUID)
                foundRx = fff0Service.getCharacteristic(FFF2_RX_UUID)
                log("Rilevato servizio FFF0 Transparent Serial BLE!")
            }
        }

        if (foundRx == null) {
            val ffe0Service = gatt.getService(FFE0_SERVICE_UUID)
            if (ffe0Service != null) {
                val ffe1Char = ffe0Service.getCharacteristic(FFE1_TX_RX_UUID)
                foundRx = ffe1Char
                foundTx = ffe1Char
                log("Rilevato servizio FFE0 Transparent Serial BLE!")
            }
        }

        if (foundRx == null) {
            for (service in services) {
                for (char in service.characteristics) {
                    val props = char.properties
                    if (foundTx == null && (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        foundTx = char
                    }
                    if (foundRx == null && (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        foundRx = char
                    }
                }
            }
        }

        if (foundRx != null && foundTx != null) {
            rxCharacteristic = foundRx
            txCharacteristic = foundTx
            log("Caratteristiche BLE configurate con successo! TX: ${foundTx.uuid}, RX: ${foundRx.uuid}")

            gatt.setCharacteristicNotification(foundTx, true)
            val cccdDescriptor = foundTx.getDescriptor(CCCD_DESCRIPTOR_UUID)
            if (cccdDescriptor != null) {
                val isIndicate = (foundTx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                val descriptorValue = if (isIndicate) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccdDescriptor, descriptorValue)
                } else {
                    @Suppress("DEPRECATION")
                    cccdDescriptor.value = descriptorValue
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccdDescriptor)
                }
            } else {
                isGattReady = true
            }
        } else {
            log("Impossibile identificare le caratteristiche seriali BLE", isError = true)
        }
    }

    private fun handleIncomingBleData(data: ByteArray) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val text = String(data, Charsets.US_ASCII)

        synchronized(bleLock) {
            if (bleFirstByteTimestampNanos == 0L) {
                bleFirstByteTimestampNanos = nowNanos
            }
            bleResponseBuffer.append(text)

            val currentResponse = bleResponseBuffer.toString()
            if (blePayloadTimestampNanos == 0L && ObdResponseParser.isDiagnosticPayloadComplete(bleCurrentCommand, currentResponse)) {
                blePayloadTimestampNanos = nowNanos
            }

            if (text.contains(">") || bleResponseBuffer.contains(">")) {
                blePromptTimestampNanos = nowNanos
                if (blePayloadTimestampNanos == 0L) blePayloadTimestampNanos = nowNanos
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeConnection() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }

        inputStream = null
        outputStream = null
        socket = null
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        isGattConnected = false
        isGattReady = false
    }

    /**
     * Esporta tutti i dati del benchmark in un file CSV formattato con escaping RFC 4180
     */
    fun exportMetricsToCsv(context: Context, metrics: BenchmarkMetrics): File {
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val sanitizeName = metrics.deviceName.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val statusPrefix = if (metrics.isPartialSession) "PARZIALE" else "COMPLETO"
        val fileName = "OBD_Benchmark_${sanitizeName}_${statusPrefix}_${fileTimestamp}.csv"

        val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
        val csvFile = File(exportDir, fileName)

        fun escapeCsvField(value: String): String {
            val clean = value.replace("\r", " ").replace("\n", " ")
            return if (clean.contains(",") || clean.contains("\"") || clean.contains(";")) {
                "\"" + clean.replace("\"", "\"\"") + "\""
            } else {
                clean
            }
        }

        csvFile.printWriter(Charsets.UTF_8).use { writer ->
            writer.println("# OBD2 SPEED BENCHMARK METROLOGICO REPORT - LOTUS EBC430 / KWP2000")
            writer.println("# Data Generazione,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            writer.println("# Stato Sessione,${if (metrics.isPartialSession) "INTERROTTA / PARZIALE" else "COMPLETATA"}")
            writer.println("# Dispositivo,${escapeCsvField(metrics.deviceName)}")
            writer.println("# Indirizzo MAC,${metrics.deviceAddress}")
            writer.println("# Trasporto Effettivo,${metrics.effectiveTransport.label}")
            writer.println("# Profilo OBD,${metrics.obdProfile.label}")
            writer.println("# Firmware ELM,${escapeCsvField(metrics.elmVersion)}")
            writer.println("# Protocollo Rilevato,${escapeCsvField(metrics.protocolName)}")
            writer.println("# Timeout Configurato (ms),${metrics.timeoutMs}")
            writer.println("# Campioni Target per Test,${metrics.targetRequestCount}")
            writer.println("# Autorizzazione ABS Pump OFF,${if (metrics.isAbsPumpOffAuthorized) "CONCESSA" else "NEGATA"}")
            writer.println("# Totale Transazioni Eseguite,${metrics.totalCommandsSent}")
            writer.println("# Risposte Positive,${metrics.successCount}")
            writer.println("# Risposte Negative (NRC),${metrics.ecuErrorCount}")
            writer.println("# Timeout,${metrics.timeoutCount}")
            writer.println("# Errori Adattatore/Trasporto,${metrics.adapterErrorCount}")
            writer.println("# Durata Complessiva Fase Misurata (ms),${metrics.testDurationMs}")
            writer.println("#")
            writer.println("# --- RIEPILOGO STATISTICO PER SINGOLO TEST ---")
            writer.println("Test_Name,Command,Requested,Executed,Positive_Responses,Negative_Responses,Unexpected_Responses,Timeouts,Adapter_Errors,Min_ms,Avg_ms,Max_ms,P50_ms,P95_ms,P99_ms,StdDev_ms,Jitter_ms,Avg_InterRequest_ms,Cmd_per_Sec,Percent_under_30ms,Percent_under_40ms,Single_Execution_Result")

            metrics.testSummaries.forEach { s ->
                writer.println(
                    "${escapeCsvField(s.testName)}," +
                            "${s.commandSent}," +
                            "${s.samplesRequested}," +
                            "${s.samplesExecuted}," +
                            "${s.positiveResponses}," +
                            "${s.negativeResponses}," +
                            "${s.unexpectedResponses}," +
                            "${s.timeouts}," +
                            "${s.adapterErrors}," +
                            "${String.format(Locale.US, "%.3f", s.minLatencyMs)}," +
                            "${String.format(Locale.US, "%.3f", s.avgLatencyMs)}," +
                            "${String.format(Locale.US, "%.3f", s.maxLatencyMs)}," +
                            "${String.format(Locale.US, "%.3f", s.p50Ms)}," +
                            "${String.format(Locale.US, "%.3f", s.p95Ms)}," +
                            "${String.format(Locale.US, "%.3f", s.p99Ms)}," +
                            "${String.format(Locale.US, "%.3f", s.stdDevMs)}," +
                            "${String.format(Locale.US, "%.3f", s.jitterMs)}," +
                            "${String.format(Locale.US, "%.3f", s.avgInterRequestIntervalMs)}," +
                            "${String.format(Locale.US, "%.2f", s.commandsPerSecond)}," +
                            "${String.format(Locale.US, "%.1f", s.percentUnder30ms)}," +
                            "${String.format(Locale.US, "%.1f", s.percentUnder40ms)}," +
                            escapeCsvField(s.singleExecutionResult)
                )
            }

            writer.println("#")
            writer.println("# --- INIZIALIZZAZIONE AUTOMATICA SCRIPT ---")
            if (metrics.initLog.isNotEmpty()) {
                metrics.initLog.forEachIndexed { idx, entry ->
                    writer.println("# InitStep_${String.format(Locale.US, "%02d", idx + 1)},${escapeCsvField(entry)}")
                }
            } else {
                writer.println("# InitStep_01,Nessun log di inizializzazione")
            }
            writer.println("#")
            writer.println("# --- CONSOLE LOGS DI SESSIONE ---")
            val currentLogs = if (metrics.sessionLogs.isNotEmpty()) metrics.sessionLogs else _logs.value
            if (currentLogs.isNotEmpty()) {
                currentLogs.forEachIndexed { idx, logLine ->
                    writer.println("# SessionLog_${String.format(Locale.US, "%03d", idx + 1)},${escapeCsvField(logLine)}")
                }
            } else {
                writer.println("# SessionLog_001,Nessun log applicativo")
            }
            writer.println("#")
            writer.println("Sample_Index,Test_Name,t0_ns,t1_ns,t2_ns,tPayload_ns,t3_ns,Write_ms,TTFB_ms,PayloadRx_ms,PromptDelay_ms,Total_RTT_ms,InterRequest_Interval_ms,Status,Command,RawResponse,ParsedPayload,ServiceID,NRC_Code")

            metrics.rawSamples.forEach { sample ->
                writer.println(
                    "${sample.sampleIndex}," +
                            "${escapeCsvField(sample.testName)}," +
                            "${sample.t0Nanos}," +
                            "${sample.t1Nanos}," +
                            "${sample.t2Nanos}," +
                            "${sample.tPayloadNanos}," +
                            "${sample.t3Nanos}," +
                            "${String.format(Locale.US, "%.3f", sample.writeDurationMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.timeToFirstByteMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.payloadRxDurationMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.promptDelayMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.totalRoundTripMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.interRequestIntervalMs)}," +
                            "${sample.status.name}," +
                            "${sample.commandSent}," +
                            "${escapeCsvField(sample.rawResponse)}," +
                            "${escapeCsvField(sample.parsedResponse)}," +
                            "${sample.serviceId}," +
                            sample.nrcCode
                )
            }
        }

        log("File CSV esportato con successo in: ${csvFile.absolutePath}")
        return csvFile
    }
}
