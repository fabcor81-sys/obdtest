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

data class RawCommandResult(
    val rawResponse: String,
    val cleanResponse: String,
    val hasPrompt: Boolean,
    val isTimeout: Boolean
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

    // Classic Bluetooth
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // BLE GATT
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
    private var blePromptTimestampNanos = 0L

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
        customCommand: String = "0100\r",
        targetRequestCount: Int = 100,
        warmupCount: Int = 3,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onStatusUpdate: (BenchmarkStatus, String) -> Unit,
        onMetricsUpdate: (BenchmarkMetrics) -> Unit
    ): BenchmarkMetrics = withContext(Dispatchers.IO) {
        isRunning = true
        activeSessionId = System.currentTimeMillis()
        val currentSessionId = activeSessionId

        val deviceNameUpper = (device.name ?: "").uppercase(Locale.US)
        val isExplicitBle = transportMode == TransportMode.BLE_GATT ||
                (transportMode == TransportMode.AUTO && (
                        device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                        device.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                        deviceNameUpper.contains("NORAUTO") ||
                        deviceNameUpper.contains("BLE") ||
                        deviceNameUpper.contains("VGATE") ||
                        deviceNameUpper.contains("GATT")
                ))

        isBleMode = isExplicitBle
        val effectiveTransport = if (isBleMode) TransportMode.BLE_GATT else TransportMode.CLASSIC_SPP

        val benchmarkMode = if (obdProfile == ObdProfile.AT_ONLY) BenchmarkMode.AT_ONLY else BenchmarkMode.OBD_PID
        val targetCommand = if (obdProfile == ObdProfile.AT_ONLY) "ATI\r" else customCommand.trim().let { if (it.endsWith("\r")) it else "$it\r" }

        var metrics = BenchmarkMetrics(
            deviceName = device.name ?: device.address,
            deviceAddress = device.address,
            effectiveTransport = effectiveTransport,
            obdProfile = obdProfile,
            measuredCommand = targetCommand.trim(),
            benchmarkMode = benchmarkMode,
            targetRequestCount = targetRequestCount,
            warmupRequestCount = warmupCount,
            timeoutMs = timeoutMs
        )

        val samples = mutableListOf<CommandTimingSample>()
        val connectionStartMs = SystemClock.elapsedRealtime()
        var totalRawBytesReceived = 0L
        var totalAppBytesReceived = 0L

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
                    log("ERRORE INIZIALIZZAZIONE [$cmdName]: risposta non valida -> '${res.cleanResponse}' (hasPrompt=${res.hasPrompt}, isTimeout=${res.isTimeout})", isError = true)
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
                    profile = obdProfile,
                    command = targetCommand,
                    previousT0Nanos = 0L,
                    timeoutMs = timeoutMs
                )

                if (w == 1) {
                    warmupFirstLatency = warmupSample.totalRoundTripMs
                }

                if (warmupSample.isSuccess) {
                    warmupSuccesses++
                }

                log("[WARMUP $w/$warmupCount] Status: ${warmupSample.status.name} | Latenza: ${String.format(Locale.US, "%.1f", warmupSample.totalRoundTripMs)}ms")
            }

            val isWarmupSuccess = (warmupSuccesses > 0) || (obdProfile == ObdProfile.AT_ONLY)
            metrics = metrics.copy(
                warmupFirstLatencyMs = warmupFirstLatency,
                isWarmupSuccess = isWarmupSuccess
            )
            onMetricsUpdate(metrics)

            if (!isWarmupSuccess && obdProfile != ObdProfile.AT_ONLY) {
                log("ATTENZIONE: La fase di riscaldamento ha evidenziato mancata risposta dalla ECU ABS/OBD!", isError = true)
            }

            coroutineContext.ensureActive()

            // FASE MISURATA DEL BENCHMARK (ISOLATA DAI TEMPI PRECEDENTI)
            log("=== INIZIO FASE MISURATA [${obdProfile.label}] ($targetRequestCount richieste - Comando: $targetCommand) ===")
            onStatusUpdate(
                BenchmarkStatus.RunningTest(0, targetRequestCount, 0.0, 0.0),
                "Avvio misura ($targetRequestCount richieste)..."
            )

            val testStartNanos = SystemClock.elapsedRealtimeNanos()
            var lastT0Nanos = 0L
            var lastUiUpdateMs = SystemClock.elapsedRealtime()

            for (i in 1..targetRequestCount) {
                if (!isRunning || currentSessionId != activeSessionId) break
                coroutineContext.ensureActive()

                val sample = executeCommandTimed(
                    sampleIndex = i,
                    profile = obdProfile,
                    command = targetCommand,
                    previousT0Nanos = lastT0Nanos,
                    timeoutMs = timeoutMs
                )

                lastT0Nanos = sample.t0Nanos
                samples.add(sample)

                totalRawBytesReceived += sample.rawResponse.length
                if (sample.isSuccess) {
                    totalAppBytesReceived += sample.parsedResponse.length
                }

                val nowMs = SystemClock.elapsedRealtime()
                val elapsedSec = ((SystemClock.elapsedRealtimeNanos() - testStartNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val currentPidsPerSec = samples.count { it.isSuccess } / elapsedSec

                if (nowMs - lastUiUpdateMs > 250 || i == targetRequestCount) {
                    lastUiUpdateMs = nowMs
                    val currentDurationMs = (SystemClock.elapsedRealtimeNanos() - testStartNanos) / 1_000_000L
                    val updatedMetrics = calculateMetrics(
                        baseMetrics = metrics,
                        samples = samples,
                        totalDurationMs = currentDurationMs,
                        totalRawBytesReceived = totalRawBytesReceived,
                        totalAppBytesReceived = totalAppBytesReceived,
                        isPartial = false
                    )
                    metrics = updatedMetrics
                    onMetricsUpdate(metrics)

                    onStatusUpdate(
                        BenchmarkStatus.RunningTest(
                            completedRequests = i,
                            targetRequests = targetRequestCount,
                            currentPidsPerSec = currentPidsPerSec,
                            lastLatencyMs = sample.totalRoundTripMs
                        ),
                        "Progresso: $i/$targetRequestCount [SUCCESS: ${metrics.successCount}, ERR: ${metrics.ecuErrorCount + metrics.adapterErrorCount + metrics.timeoutCount}] (${String.format(Locale.US, "%.1f", currentPidsPerSec)} ${metrics.speedUnitLabel} - Latenza: ${String.format(Locale.US, "%.1f", sample.totalRoundTripMs)} ms)"
                    )
                }
            }

            val finalMeasuredDurationMs = (SystemClock.elapsedRealtimeNanos() - testStartNanos) / 1_000_000L
            metrics = calculateMetrics(
                baseMetrics = metrics,
                samples = samples,
                totalDurationMs = finalMeasuredDurationMs,
                totalRawBytesReceived = totalRawBytesReceived,
                totalAppBytesReceived = totalAppBytesReceived,
                isPartial = false
            )

            log("=== BENCHMARK COMPLETATO ===")
            log("Risultati per ${metrics.deviceName} (${obdProfile.label}):")
            log(" - Durata Fase Misurata: ${metrics.testDurationMs} ms")
            log(" - Riscaldamento: 1° Latenza: ${String.format(Locale.US, "%.1f", metrics.warmupFirstLatencyMs)} ms | Esito: ${if (metrics.isWarmupSuccess) "OK" else "FALLITO"}")
            log(" - Trasporto: ${metrics.effectiveTransport.label}")
            log(" - Firmware: ${metrics.elmVersion}")
            log(" - Campioni: SUCCESS: ${metrics.successCount}/${metrics.totalCommandsSent} (${String.format(Locale.US, "%.1f", metrics.successRatePercent)}%) | ECU_ERR: ${metrics.ecuErrorCount} | ADAPTER_ERR: ${metrics.adapterErrorCount} | TIMEOUT: ${metrics.timeoutCount} | INCOMPLETE: ${metrics.incompleteCount}")
            log(" - Latenza Min: ${String.format(Locale.US, "%.2f", metrics.minLatencyMs)} ms | Media: ${String.format(Locale.US, "%.2f", metrics.avgLatencyMs)} ms | Max: ${String.format(Locale.US, "%.2f", metrics.maxLatencyMs)} ms")
            log(" - Percentili (sui successi): P50: ${String.format(Locale.US, "%.2f", metrics.p50Ms)} ms | P95: ${String.format(Locale.US, "%.2f", metrics.p95Ms)} ms | P99: ${String.format(Locale.US, "%.2f", metrics.p99Ms)} ms")
            log(" - Stabilità: StdDev: ${String.format(Locale.US, "%.2f", metrics.stdDevMs)} ms | Jitter: ${String.format(Locale.US, "%.2f", metrics.jitterMs)} ms")
            log(" - Throughput: App: ${String.format(Locale.US, "%.1f", metrics.appBytesPerSecond)} B/s | Grezzo: ${String.format(Locale.US, "%.1f", metrics.rawBytesPerSecond)} B/s")

            onMetricsUpdate(metrics)
            onStatusUpdate(BenchmarkStatus.Completed, "Benchmark metrologico completato!")

            metrics
        } catch (e: CancellationException) {
            log("Benchmark annullato dall'utente.", isError = false)
            val currentDurationMs = SystemClock.elapsedRealtime() - connectionStartMs
            val partialMetrics = calculateMetrics(
                baseMetrics = metrics,
                samples = samples,
                totalDurationMs = currentDurationMs,
                totalRawBytesReceived = totalRawBytesReceived,
                totalAppBytesReceived = totalAppBytesReceived,
                isPartial = true
            )
            onMetricsUpdate(partialMetrics)
            onStatusUpdate(BenchmarkStatus.Cancelled, "Benchmark interrotto dall'utente.")
            throw e
        } catch (e: Exception) {
            val errLog = "ERRORE APPLICATIVO CRITICO: ${e.javaClass.simpleName} - ${e.message}\n${Log.getStackTraceString(e)}"
            log(errLog, isError = true)

            val currentDurationMs = SystemClock.elapsedRealtime() - connectionStartMs
            val partialMetrics = calculateMetrics(
                baseMetrics = metrics,
                samples = samples,
                totalDurationMs = currentDurationMs,
                totalRawBytesReceived = totalRawBytesReceived,
                totalAppBytesReceived = totalAppBytesReceived,
                isPartial = true
            )
            onMetricsUpdate(partialMetrics)
            onStatusUpdate(BenchmarkStatus.Error(e.message ?: "Errore di comunicazione"), "Errore: ${e.message}")
            throw e
        } finally {
            closeConnection()
        }
    }

    private fun executeCommandTimed(
        sampleIndex: Int,
        profile: ObdProfile,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        return if (isBleMode) {
            executeCommandTimedBle(sampleIndex, profile, command, previousT0Nanos, timeoutMs)
        } else {
            executeCommandTimedClassic(sampleIndex, profile, command, previousT0Nanos, timeoutMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeCommandTimedBle(
        sampleIndex: Int,
        profile: ObdProfile,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        val gatt = bluetoothGatt ?: return createFailedSample(sampleIndex, command, previousT0Nanos)
        val rxChar = rxCharacteristic ?: return createFailedSample(sampleIndex, command, previousT0Nanos)

        synchronized(bleLock) {
            bleResponseBuffer.clear()
            bleFirstByteTimestampNanos = 0L
            blePromptTimestampNanos = 0L
        }

        val commandBytes = command.toByteArray(Charsets.US_ASCII)

        val t0Nanos = SystemClock.elapsedRealtimeNanos()

        val writeType = if ((rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, commandBytes, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = commandBytes
            @Suppress("DEPRECATION")
            rxChar.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }

        val t1Nanos = SystemClock.elapsedRealtimeNanos()

        if (!writeSuccess) {
            log("Scrittura caratteristica BLE fallita per comando $command", isError = true)
        }

        val timeoutNanos = timeoutMs * 1_000_000L
        var t2Nanos = 0L
        var t3Nanos = 0L
        var rawResponse = ""

        val startWaitNanos = SystemClock.elapsedRealtimeNanos()
        while (isRunning && (SystemClock.elapsedRealtimeNanos() - startWaitNanos) < timeoutNanos) {
            synchronized(bleLock) {
                if (bleFirstByteTimestampNanos > 0L && t2Nanos == 0L) {
                    t2Nanos = bleFirstByteTimestampNanos
                }
                if (bleResponseBuffer.contains(">")) {
                    t3Nanos = if (blePromptTimestampNanos > 0L) blePromptTimestampNanos else SystemClock.elapsedRealtimeNanos()
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

        if (parseResult.status == SampleStatus.TIMEOUT || parseResult.status == SampleStatus.INCOMPLETE_RESPONSE || !hasPrompt) {
            resyncStreamBle()
        }

        val isSuccess = (parseResult.status == SampleStatus.SUCCESS)
        val isTimeout = (parseResult.status == SampleStatus.TIMEOUT)

        val writeDurationMs = (t1Nanos - t0Nanos) / 1_000_000.0
        val timeToFirstByteMs = if (t2Nanos > 0) (t2Nanos - t1Nanos) / 1_000_000.0 else 0.0
        val rxDurationMs = if (t2Nanos > 0) (t3Nanos - t2Nanos) / 1_000_000.0 else 0.0
        val totalRoundTripMs = (t3Nanos - t0Nanos) / 1_000_000.0
        val interRequestIntervalMs = if (previousT0Nanos > 0) (t0Nanos - previousT0Nanos) / 1_000_000.0 else 0.0

        return CommandTimingSample(
            sampleIndex = sampleIndex,
            t0Nanos = t0Nanos,
            t1Nanos = t1Nanos,
            t2Nanos = if (t2Nanos > 0) t2Nanos else t3Nanos,
            t3Nanos = t3Nanos,
            writeDurationMs = writeDurationMs,
            timeToFirstByteMs = timeToFirstByteMs,
            rxDurationMs = rxDurationMs,
            totalRoundTripMs = totalRoundTripMs,
            interRequestIntervalMs = interRequestIntervalMs,
            status = parseResult.status,
            isSuccess = isSuccess,
            isTimeout = isTimeout,
            commandSent = command.trim(),
            rawResponse = parseResult.cleanedResponse,
            parsedResponse = parseResult.extractedPayload
        )
    }

    private fun executeCommandTimedClassic(
        sampleIndex: Int,
        profile: ObdProfile,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        val out = outputStream ?: return createFailedSample(sampleIndex, command, previousT0Nanos)
        val input = inputStream ?: return createFailedSample(sampleIndex, command, previousT0Nanos)

        clearBuffer()

        val commandBytes = command.toByteArray(Charsets.US_ASCII)

        val t0Nanos = SystemClock.elapsedRealtimeNanos()

        out.write(commandBytes)
        out.flush()

        val t1Nanos = SystemClock.elapsedRealtimeNanos()

        var t2Nanos = 0L
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

                        if (responseBuilder.contains(">")) {
                            t3Nanos = nowNanos
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

        if (parseResult.status == SampleStatus.TIMEOUT || parseResult.status == SampleStatus.INCOMPLETE_RESPONSE || !hasPrompt) {
            resyncStreamClassic()
        }

        val isSuccess = (parseResult.status == SampleStatus.SUCCESS)
        val isTimeout = (parseResult.status == SampleStatus.TIMEOUT)

        val writeDurationMs = (t1Nanos - t0Nanos) / 1_000_000.0
        val timeToFirstByteMs = if (t2Nanos > 0) (t2Nanos - t1Nanos) / 1_000_000.0 else 0.0
        val rxDurationMs = if (t2Nanos > 0) (t3Nanos - t2Nanos) / 1_000_000.0 else 0.0
        val totalRoundTripMs = (t3Nanos - t0Nanos) / 1_000_000.0
        val interRequestIntervalMs = if (previousT0Nanos > 0) (t0Nanos - previousT0Nanos) / 1_000_000.0 else 0.0

        return CommandTimingSample(
            sampleIndex = sampleIndex,
            t0Nanos = t0Nanos,
            t1Nanos = t1Nanos,
            t2Nanos = if (t2Nanos > 0) t2Nanos else t3Nanos,
            t3Nanos = t3Nanos,
            writeDurationMs = writeDurationMs,
            timeToFirstByteMs = timeToFirstByteMs,
            rxDurationMs = rxDurationMs,
            totalRoundTripMs = totalRoundTripMs,
            interRequestIntervalMs = interRequestIntervalMs,
            status = parseResult.status,
            isSuccess = isSuccess,
            isTimeout = isTimeout,
            commandSent = command.trim(),
            rawResponse = parseResult.cleanedResponse,
            parsedResponse = parseResult.extractedPayload
        )
    }

    private fun resyncStreamClassic() {
        val out = outputStream ?: return
        val input = inputStream ?: return
        try {
            out.write("\r".toByteArray(Charsets.US_ASCII))
            out.flush()

            val startNanos = SystemClock.elapsedRealtimeNanos()
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (isRunning && (SystemClock.elapsedRealtimeNanos() - startNanos) < 150_000_000L) {
                if (input.available() > 0) {
                    input.read(buffer)
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun resyncStreamBle() {
        val gatt = bluetoothGatt ?: return
        val rxChar = rxCharacteristic ?: return
        try {
            val commandBytes = "\r".toByteArray(Charsets.US_ASCII)
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

            Thread.sleep(150)
            synchronized(bleLock) {
                bleResponseBuffer.clear()
                bleFirstByteTimestampNanos = 0L
                blePromptTimestampNanos = 0L
            }
        } catch (_: Exception) {
        }
    }

    private fun createFailedSample(
        sampleIndex: Int,
        command: String,
        previousT0Nanos: Long
    ): CommandTimingSample {
        val now = SystemClock.elapsedRealtimeNanos()
        val interval = if (previousT0Nanos > 0) (now - previousT0Nanos) / 1_000_000.0 else 0.0
        return CommandTimingSample(
            sampleIndex = sampleIndex,
            t0Nanos = now,
            t1Nanos = now,
            t2Nanos = now,
            t3Nanos = now,
            writeDurationMs = 0.0,
            timeToFirstByteMs = 0.0,
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

    private fun calculateMetrics(
        baseMetrics: BenchmarkMetrics,
        samples: List<CommandTimingSample>,
        totalDurationMs: Long,
        totalRawBytesReceived: Long,
        totalAppBytesReceived: Long,
        isPartial: Boolean
    ): BenchmarkMetrics {
        if (samples.isEmpty()) return baseMetrics.copy(isPartialSession = isPartial)

        val successCount = samples.count { it.status == SampleStatus.SUCCESS }
        val ecuErrorCount = samples.count { it.status == SampleStatus.ECU_ERROR }
        val adapterErrorCount = samples.count { it.status == SampleStatus.ADAPTER_ERROR }
        val timeoutCount = samples.count { it.status == SampleStatus.TIMEOUT }
        val incompleteCount = samples.count { it.status == SampleStatus.INCOMPLETE_RESPONSE }

        val successfulSamples = samples.filter { it.status == SampleStatus.SUCCESS }
        val latencies = successfulSamples.map { it.totalRoundTripMs }
        val minLatency = latencies.minOrNull() ?: 0.0
        val maxLatency = latencies.maxOrNull() ?: 0.0
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0

        val sortedLatencies = latencies.sorted()
        val p50 = getPercentile(sortedLatencies, 50.0)
        val p95 = getPercentile(sortedLatencies, 95.0)
        val p99 = getPercentile(sortedLatencies, 99.0)

        val stdDev = if (latencies.size > 1) {
            val mean = avgLatency
            val sumOfSquaredDiffs = latencies.sumOf { (it - mean) * (it - mean) }
            sqrt(sumOfSquaredDiffs / latencies.size)
        } else 0.0

        val jitter = if (latencies.size > 1) {
            var diffSum = 0.0
            for (i in 0 until latencies.size - 1) {
                diffSum += kotlin.math.abs(latencies[i + 1] - latencies[i])
            }
            diffSum / (latencies.size - 1)
        } else 0.0

        val intervals = samples.drop(1).map { it.interRequestIntervalMs }.filter { it > 0 }
        val avgInterval = if (intervals.isNotEmpty()) intervals.average() else 0.0

        val totalSec = (totalDurationMs / 1000.0).coerceAtLeast(0.001)
        val pidsPerSec = successCount / totalSec
        val appBytesPerSec = totalAppBytesReceived / totalSec
        val rawBytesPerSec = totalRawBytesReceived / totalSec

        return baseMetrics.copy(
            totalCommandsSent = samples.size,
            successCount = successCount,
            ecuErrorCount = ecuErrorCount,
            adapterErrorCount = adapterErrorCount,
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
            avgInterRequestIntervalMs = avgInterval,
            commandsPerSecond = pidsPerSec,
            appBytesPerSecond = appBytesPerSec,
            rawBytesPerSecond = rawBytesPerSec,
            testDurationMs = totalDurationMs,
            isPartialSession = isPartial,
            sessionLogs = _logs.value,
            rawSamples = samples.toList()
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
            if (text.contains(">") || bleResponseBuffer.contains(">")) {
                blePromptTimestampNanos = nowNanos
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
            writer.println("# OBD2 SPEED BENCHMARK METROLOGICO REPORT")
            writer.println("# Data Generazione,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            writer.println("# Stato Sessione,${if (metrics.isPartialSession) "INTERROTTA / PARZIALE" else "COMPLETATA"}")
            writer.println("# Dispositivo,${escapeCsvField(metrics.deviceName)}")
            writer.println("# Indirizzo MAC,${metrics.deviceAddress}")
            writer.println("# Trasporto Effettivo,${metrics.effectiveTransport.label}")
            writer.println("# Profilo OBD,${metrics.obdProfile.label}")
            writer.println("# Comando Misurato,${escapeCsvField(metrics.measuredCommand)}")
            writer.println("# Firmware ELM,${escapeCsvField(metrics.elmVersion)}")
            writer.println("# Protocollo Rilevato,${escapeCsvField(metrics.protocolName)}")
            writer.println("# Timeout Configurato (ms),${metrics.timeoutMs}")
            writer.println("# Riscaldamento Richieste,${metrics.warmupRequestCount}")
            writer.println("# Riscaldamento 1° Latenza (ms),${String.format(Locale.US, "%.3f", metrics.warmupFirstLatencyMs)}")
            writer.println("# Riscaldamento Esito,${if (metrics.isWarmupSuccess) "SUCCESS" else "FALLITO"}")
            writer.println("# Campioni Target,${metrics.targetRequestCount}")
            writer.println("# Campioni Effettivi,${metrics.totalCommandsSent}")
            writer.println("# Risposte SUCCESS,${metrics.successCount}")
            writer.println("# Errori ECU (ECU_ERROR),${metrics.ecuErrorCount}")
            writer.println("# Errori Adattatore (ADAPTER_ERROR),${metrics.adapterErrorCount}")
            writer.println("# Timeout (TIMEOUT),${metrics.timeoutCount}")
            writer.println("# Risposte Incomplete (INCOMPLETE_RESPONSE),${metrics.incompleteCount}")
            writer.println("# Tasso di Successo (%),${String.format(Locale.US, "%.1f", metrics.successRatePercent)}")
            writer.println("# Latenza Min (ms - sui successi),${String.format(Locale.US, "%.3f", metrics.minLatencyMs)}")
            writer.println("# Latenza Media (ms - sui successi),${String.format(Locale.US, "%.3f", metrics.avgLatencyMs)}")
            writer.println("# Latenza Max (ms - sui successi),${String.format(Locale.US, "%.3f", metrics.maxLatencyMs)}")
            writer.println("# Percentile P50 (ms),${String.format(Locale.US, "%.3f", metrics.p50Ms)}")
            writer.println("# Percentile P95 (ms),${String.format(Locale.US, "%.3f", metrics.p95Ms)}")
            writer.println("# Percentile P99 (ms),${String.format(Locale.US, "%.3f", metrics.p99Ms)}")
            writer.println("# Deviazione Standard (ms),${String.format(Locale.US, "%.3f", metrics.stdDevMs)}")
            writer.println("# Jitter (ms),${String.format(Locale.US, "%.3f", metrics.jitterMs)}")
            writer.println("# Intervallo Medio Inter-Richiesta (ms),${String.format(Locale.US, "%.3f", metrics.avgInterRequestIntervalMs)}")
            writer.println("# Velocità (${metrics.speedUnitLabel}),${String.format(Locale.US, "%.2f", metrics.commandsPerSecond)}")
            writer.println("# Throughput Applicativo (B/s),${String.format(Locale.US, "%.2f", metrics.appBytesPerSecond)}")
            writer.println("# Throughput Grezzo (B/s),${String.format(Locale.US, "%.2f", metrics.rawBytesPerSecond)}")
            writer.println("# Durata Fase Misurata (ms),${metrics.testDurationMs}")
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
            writer.println("Sample_Index,t0_elapsed_ns,t1_elapsed_ns,t2_elapsed_ns,t3_elapsed_ns,Write_ms,TTFB_ms,RX_ms,Total_RTT_ms,InterRequest_Interval_ms,Status,Command,RawResponse,ParsedPayload")

            metrics.rawSamples.forEach { sample ->
                writer.println(
                    "${sample.sampleIndex}," +
                            "${sample.t0Nanos}," +
                            "${sample.t1Nanos}," +
                            "${sample.t2Nanos}," +
                            "${sample.t3Nanos}," +
                            "${String.format(Locale.US, "%.3f", sample.writeDurationMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.timeToFirstByteMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.rxDurationMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.totalRoundTripMs)}," +
                            "${String.format(Locale.US, "%.3f", sample.interRequestIntervalMs)}," +
                            "${sample.status.name}," +
                            "${sample.commandSent}," +
                            "${escapeCsvField(sample.rawResponse)}," +
                            escapeCsvField(sample.parsedResponse)
                )
            }
        }

        log("File CSV esportato con successo in: ${csvFile.absolutePath}")
        return csvFile
    }
}
