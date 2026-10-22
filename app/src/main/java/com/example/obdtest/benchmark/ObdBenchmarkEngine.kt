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
import com.example.obdtest.ui.SampleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class ObdBenchmarkEngine {

    companion object {
        private const val TAG = "OBD_DEBUG"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val DEFAULT_TIMEOUT_MS = 2500L
        private const val READ_BUFFER_SIZE = 1024

        // UUID Servizi GATT Seriale Frequenti (Norauto BLE, Nordic UART, Vgate BLE)
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
        _logs.value = (_logs.value + formattedLog).takeLast(150)
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
        isBle: Boolean = false,
        benchmarkMode: BenchmarkMode = BenchmarkMode.AT_ONLY,
        targetRequestCount: Int = 100,
        onStatusUpdate: (BenchmarkStatus, String) -> Unit,
        onMetricsUpdate: (BenchmarkMetrics) -> Unit
    ): BenchmarkMetrics = withContext(Dispatchers.IO) {
        isRunning = true

        val deviceNameUpper = (device.name ?: "").uppercase(Locale.US)
        val effectiveIsBle = isBle ||
                device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                device.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                deviceNameUpper.contains("NORAUTO") ||
                deviceNameUpper.contains("BLE") ||
                deviceNameUpper.contains("VGATE") ||
                deviceNameUpper.contains("GATT")

        isBleMode = effectiveIsBle

        var metrics = BenchmarkMetrics(
            deviceName = device.name ?: device.address,
            deviceAddress = device.address,
            benchmarkMode = benchmarkMode,
            targetRequestCount = targetRequestCount
        )

        try {
            val transportTypeStr = if (isBleMode) "BLE (GATT)" else "Classic (SPP)"
            log("Connessione a ${device.name ?: device.address} via $transportTypeStr...")
            onStatusUpdate(BenchmarkStatus.Connecting, "Connessione $transportTypeStr in corso a ${device.name ?: device.address}...")

            if (isBleMode) {
                connectGattDevice(context, device)
            } else {
                connectClassicSocket(device)
            }
            log("Connessione $transportTypeStr stabilita con successo!")

            onStatusUpdate(BenchmarkStatus.Initializing, "Inizializzazione automatica adattatore (${benchmarkMode.label})...")

            val initLogEntries = mutableListOf<String>()

            fun sendInitCmd(cmd: String, timeout: Long = DEFAULT_TIMEOUT_MS): String {
                val resp = executeRawCommand(cmd, timeout)
                val clean = cleanResponse(resp, cmd)
                val entry = "CMD: ${cmd.trim()} -> RESP: ${if (clean.isBlank()) "OK/NO_RESP" else clean}"
                initLogEntries.add(entry)
                log("[INIT] $entry")
                return resp
            }

            val elmVersion: String
            val protocol: String
            val targetCommand: String

            if (benchmarkMode == BenchmarkMode.AT_ONLY) {
                log("Configurazione automatica modalità AT-Only...")
                sendInitCmd("ATE0\r")
                sendInitCmd("ATL0\r")
                sendInitCmd("ATH0\r")

                val atiResp = sendInitCmd("ATI\r")
                elmVersion = if (atiResp.isNotBlank()) atiResp else "ELM327 / Adattatore AT"
                protocol = "N/D (AT-Only)"
                targetCommand = BenchmarkMode.AT_ONLY.command
                log("Versione Adattatore Rilevata: $elmVersion")
            } else {
                log("Inizio sequenza automatica di inizializzazione ECU / OBD2...")

                val atzResp = sendInitCmd("AT Z\r", 3000L)
                kotlinx.coroutines.delay(600)
                clearBuffer()

                elmVersion = if (atzResp.isNotBlank()) atzResp else "ELM327 v1.5"

                log("Tentativo 1: Aggancio automatico KWP2000 Fast Init (ISO 14230-4 / Header 812BF1 / Keep-Alive 812BF13E)...")
                sendInitCmd("AT L1\r")
                sendInitCmd("ATE0\r")
                sendInitCmd("ATH0\r")
                sendInitCmd("AT SP 5\r") // ISO 14230-4 KWP Fast Init
                sendInitCmd("AT SH 812BF1\r") // Header Custom FCA/Fiat/Bosch
                sendInitCmd("AT WM 812BF13E\r") // Keep-Alive KWP2000 TesterPresent

                val kwpProbeResp = sendInitCmd("0100\r", 3500L)
                val cleanKwpProbe = cleanResponse(kwpProbeResp, "0100")
                val kwpSuccess = cleanKwpProbe.contains("41") || cleanKwpProbe.contains("4100") || cleanKwpProbe.contains("41 00")

                if (kwpSuccess) {
                    protocol = "ISO 14230-4 KWP2000 Fast Init (Header 812BF1)"
                    log("Aggancio ECU KWP2000 completato con successo!")
                } else {
                    log("KWP2000 Custom non ha risposto o non presente. Tentativo 2: Fallback su Auto Protocol OBD2 (AT SP 0)...")
                    sendInitCmd("AT SP 0\r") // Auto Protocol
                    sendInitCmd("AT SH 7DF\r") // Reset Header Standard Broadcast
                    sendInitCmd("0100\r", 4000L)
                    val atdpResp = sendInitCmd("AT DP\r")
                    protocol = if (atdpResp.isNotBlank()) atdpResp else "Auto OBD2"
                    log("Protocollo OBD2 Rilevato: $protocol")
                }

                targetCommand = BenchmarkMode.OBD_PID.command
            }

            metrics = metrics.copy(
                elmVersion = elmVersion,
                protocolName = protocol,
                initLog = initLogEntries
            )
            onMetricsUpdate(metrics)

            if (!isRunning) throw InterruptedException("Test interrotto dall'utente")

            // =========================================================
            // BENCHMARK METROLOGICO AD ALTA PRECISIONE
            // =========================================================
            log("=== INIZIO BENCHMARK METROLOGICO [${benchmarkMode.label}] ($targetRequestCount richieste) ===")
            onStatusUpdate(
                BenchmarkStatus.RunningTest(0, targetRequestCount, 0.0, 0.0),
                "Avvio benchmark (${benchmarkMode.label})..."
            )

            val samples = mutableListOf<CommandTimingSample>()
            val testStartTimestampMs = System.currentTimeMillis()
            var lastT0Nanos = 0L
            var totalBytesReceived = 0L
            var lastUiUpdateMs = System.currentTimeMillis()

            for (i in 1..targetRequestCount) {
                if (!isRunning) break

                val sample = executeCommandTimed(
                    sampleIndex = i,
                    mode = benchmarkMode,
                    command = targetCommand,
                    previousT0Nanos = lastT0Nanos,
                    timeoutMs = DEFAULT_TIMEOUT_MS
                )

                lastT0Nanos = sample.t0Nanos
                samples.add(sample)

                if (sample.isSuccess) {
                    totalBytesReceived += sample.rawResponse.length
                }

                val nowMs = System.currentTimeMillis()
                val elapsedSec = (nowMs - testStartTimestampMs) / 1000.0
                val currentPidsPerSec = if (elapsedSec > 0) samples.count { it.isSuccess } / elapsedSec else 0.0

                if (nowMs - lastUiUpdateMs > 200 || i == targetRequestCount) {
                    lastUiUpdateMs = nowMs
                    val updatedMetrics = calculateMetrics(
                        baseMetrics = metrics,
                        samples = samples,
                        totalDurationMs = nowMs - testStartTimestampMs,
                        totalBytesReceived = totalBytesReceived
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
                        "Progresso: $i/$targetRequestCount [SUCCESS: ${metrics.successCount}, ERR: ${metrics.ecuErrorCount + metrics.adapterErrorCount + metrics.timeoutCount}] (${String.format(Locale.US, "%.1f", currentPidsPerSec)} cmd/s - Latenza: ${String.format(Locale.US, "%.1f", sample.totalRoundTripMs)} ms)"
                    )
                }
            }

            val finalDurationMs = System.currentTimeMillis() - testStartTimestampMs
            metrics = calculateMetrics(
                baseMetrics = metrics,
                samples = samples,
                totalDurationMs = finalDurationMs,
                totalBytesReceived = totalBytesReceived
            )

            log("=== BENCHMARK COMPLETATO ===")
            log("Risultati per ${metrics.deviceName} (${benchmarkMode.label}):")
            log(" - Tipo Connessione: $transportTypeStr")
            log(" - Firmware: ${metrics.elmVersion}")
            log(" - Campioni: Richiesti: $targetRequestCount | SUCCESS: ${metrics.successCount} | ECU_ERR: ${metrics.ecuErrorCount} | ADAPTER_ERR: ${metrics.adapterErrorCount} | TIMEOUT: ${metrics.timeoutCount} | INCOMPLETE: ${metrics.incompleteCount}")
            log(" - Latenza Min: ${String.format(Locale.US, "%.2f", metrics.minLatencyMs)} ms | Media: ${String.format(Locale.US, "%.2f", metrics.avgLatencyMs)} ms | Max: ${String.format(Locale.US, "%.2f", metrics.maxLatencyMs)} ms")
            log(" - Percentili: P50: ${String.format(Locale.US, "%.2f", metrics.p50Ms)} ms | P95: ${String.format(Locale.US, "%.2f", metrics.p95Ms)} ms | P99: ${String.format(Locale.US, "%.2f", metrics.p99Ms)} ms")
            log(" - Stabilità: StdDev: ${String.format(Locale.US, "%.2f", metrics.stdDevMs)} ms | Jitter: ${String.format(Locale.US, "%.2f", metrics.jitterMs)} ms")
            log(" - Velocità Comandi: ${String.format(Locale.US, "%.1f", metrics.commandsPerSecond)} Cmd/s")

            onMetricsUpdate(metrics)
            onStatusUpdate(BenchmarkStatus.Completed, "Benchmark metrologico completato!")

            metrics
        } catch (e: Exception) {
            val errLog = "ERRORE APPLICATIVO CRITICO: ${e.javaClass.simpleName} - ${e.message}\n${Log.getStackTraceString(e)}"
            log(errLog, isError = true)

            metrics = metrics.copy(sessionLogs = _logs.value)
            onMetricsUpdate(metrics)

            onStatusUpdate(BenchmarkStatus.Error(e.message ?: "Errore di comunicazione"), "Errore: ${e.message}")
            throw e
        } finally {
            closeConnection()
        }
    }

    private fun classifyResponse(
        mode: BenchmarkMode,
        commandSent: String,
        rawResponse: String,
        hasPrompt: Boolean,
        bytesReceived: Int
    ): SampleStatus {
        if (!hasPrompt) {
            return if (bytesReceived == 0 || rawResponse.isBlank()) {
                SampleStatus.TIMEOUT
            } else {
                SampleStatus.INCOMPLETE_RESPONSE
            }
        }

        val upper = rawResponse.uppercase(Locale.US).trim()

        // 1. Errore o stato di interruzione dell'adattatore
        if (upper.contains("STOPPED") ||
            upper.contains("SEARCHING...") ||
            upper.contains("BUFFER FULL") ||
            upper == "?" ||
            upper.contains("FB ERROR") ||
            upper.contains("ACT ERROR") ||
            upper.contains("DATA ERROR") ||
            upper.contains("ERR70")
        ) {
            return SampleStatus.ADAPTER_ERROR
        }

        // 2. Errore riscontrato sul bus ECU / veicolo
        if (upper.contains("BUS INIT: ERROR") ||
            upper.contains("BUS INIT ERROR") ||
            upper.contains("NO DATA") ||
            upper.contains("UNABLE TO CONNECT") ||
            upper.contains("BUS ERROR") ||
            upper.contains("BUS BUSY") ||
            upper.contains("CAN ERROR")
        ) {
            return SampleStatus.ECU_ERROR
        }

        // 3. Valutazione in base alla modalità di benchmark
        return when (mode) {
            BenchmarkMode.AT_ONLY -> {
                val cleaned = cleanResponse(rawResponse, commandSent)
                if (cleaned.isNotBlank()) {
                    SampleStatus.SUCCESS
                } else {
                    SampleStatus.ADAPTER_ERROR
                }
            }

            BenchmarkMode.OBD_PID -> {
                val cleaned = cleanResponse(rawResponse, commandSent).replace(" ", "")
                val cmdClean = commandSent.trim().replace("\r", "").replace(" ", "").uppercase(Locale.US)
                if (cmdClean.startsWith("01")) {
                    val expectedMode = "41" + cmdClean.substring(2) // es. 0100 -> 4100
                    if (cleaned.contains(expectedMode) || cleaned.startsWith("41")) {
                        SampleStatus.SUCCESS
                    } else {
                        SampleStatus.ECU_ERROR
                    }
                } else {
                    if (cleaned.isNotBlank()) {
                        SampleStatus.SUCCESS
                    } else {
                        SampleStatus.ECU_ERROR
                    }
                }
            }
        }
    }

    private fun executeCommandTimed(
        sampleIndex: Int,
        mode: BenchmarkMode,
        command: String,
        previousT0Nanos: Long,
        timeoutMs: Long
    ): CommandTimingSample {
        return if (isBleMode) {
            executeCommandTimedBle(sampleIndex, mode, command, previousT0Nanos, timeoutMs)
        } else {
            executeCommandTimedClassic(sampleIndex, mode, command, previousT0Nanos, timeoutMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeCommandTimedBle(
        sampleIndex: Int,
        mode: BenchmarkMode,
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

        // t0: Timestamp invio BLE
        val t0Nanos = SystemClock.elapsedRealtimeNanos()

        val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                rxChar,
                commandBytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = commandBytes
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }

        // t1: Timestamp dopo la scrittura del pacchetto BLE
        val t1Nanos = SystemClock.elapsedRealtimeNanos()

        if (!writeSuccess) {
            log("Scrittura caratteristica BLE fallita per comando $command", isError = true)
        }

        val timeoutNanos = timeoutMs * 1_000_000L
        var t2Nanos = 0L
        var t3Nanos = 0L
        var rawResponse = ""

        val startWaitMs = SystemClock.elapsedRealtimeNanos()
        while (isRunning && (SystemClock.elapsedRealtimeNanos() - startWaitMs) < timeoutNanos) {
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

        val status = classifyResponse(
            mode = mode,
            commandSent = command,
            rawResponse = rawResponse,
            hasPrompt = hasPrompt,
            bytesReceived = rawResponse.length
        )

        // Risincronizzazione dello stream in caso di timeout o mancato prompt per pulire il buffer
        if (status == SampleStatus.TIMEOUT || status == SampleStatus.INCOMPLETE_RESPONSE || !hasPrompt) {
            resyncStreamBle()
        }

        val isSuccess = (status == SampleStatus.SUCCESS)
        val isTimeout = (status == SampleStatus.TIMEOUT)

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
            status = status,
            isSuccess = isSuccess,
            isTimeout = isTimeout,
            commandSent = command.trim(),
            rawResponse = cleanResponse(rawResponse, command)
        )
    }

    private fun executeCommandTimedClassic(
        sampleIndex: Int,
        mode: BenchmarkMode,
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
        val status = classifyResponse(
            mode = mode,
            commandSent = command,
            rawResponse = rawResponse,
            hasPrompt = hasPrompt,
            bytesReceived = totalBytesRead
        )

        // Risincronizzazione dello stream in caso di timeout o mancato prompt per svuotare residui
        if (status == SampleStatus.TIMEOUT || status == SampleStatus.INCOMPLETE_RESPONSE || !hasPrompt) {
            resyncStreamClassic()
        }

        val isSuccess = (status == SampleStatus.SUCCESS)
        val isTimeout = (status == SampleStatus.TIMEOUT)

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
            status = status,
            isSuccess = isSuccess,
            isTimeout = isTimeout,
            commandSent = command.trim(),
            rawResponse = cleanResponse(rawResponse, command)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rxChar, commandBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                rxChar.value = commandBytes
                @Suppress("DEPRECATION")
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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
            rawResponse = "ERRORE / CONNETTORE NON DISPONIBILE"
        )
    }

    private fun calculateMetrics(
        baseMetrics: BenchmarkMetrics,
        samples: List<CommandTimingSample>,
        totalDurationMs: Long,
        totalBytesReceived: Long
    ): BenchmarkMetrics {
        if (samples.isEmpty()) return baseMetrics

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

        val totalSec = (totalDurationMs / 1000.0).coerceAtLeast(0.1)
        val pidsPerSec = successCount / totalSec
        val bytesPerSec = totalBytesReceived / totalSec

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
            bytesPerSecond = bytesPerSec,
            testDurationMs = totalDurationMs,
            sessionLogs = _logs.value,
            rawSamples = samples
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

    private fun executeRawCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        return if (isBleMode) {
            executeRawCommandBle(command, timeoutMs)
        } else {
            executeRawCommandClassic(command, timeoutMs)
        }
    }

    private fun executeRawCommandClassic(command: String, timeoutMs: Long): String {
        val out = outputStream ?: return ""
        val input = inputStream ?: return ""

        try {
            clearBuffer()
            out.write(command.toByteArray(Charsets.US_ASCII))
            out.flush()

            val responseBuilder = StringBuilder()
            val startMs = System.currentTimeMillis()
            val buffer = ByteArray(READ_BUFFER_SIZE)

            while (System.currentTimeMillis() - startMs < timeoutMs && isRunning) {
                if (input.available() > 0) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val text = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                        responseBuilder.append(text)
                        if (responseBuilder.contains(">")) {
                            break
                        }
                    }
                }
            }

            return cleanResponse(responseBuilder.toString(), command)
        } catch (e: Exception) {
            log("Errore comando raw Classic '$command': ${e.message}")
            return ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeRawCommandBle(command: String, timeoutMs: Long): String {
        val gatt = bluetoothGatt ?: return ""
        val rxChar = rxCharacteristic ?: return ""

        synchronized(bleLock) {
            bleResponseBuffer.clear()
        }

        val commandBytes = command.toByteArray(Charsets.US_ASCII)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, commandBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = commandBytes
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }

        val startMs = System.currentTimeMillis()
        var response = ""

        while (System.currentTimeMillis() - startMs < timeoutMs && isRunning) {
            synchronized(bleLock) {
                if (bleResponseBuffer.contains(">")) {
                    response = bleResponseBuffer.toString()
                }
            }
            if (response.isNotEmpty()) break
            Thread.sleep(5)
        }

        if (response.isEmpty()) {
            synchronized(bleLock) {
                response = bleResponseBuffer.toString()
            }
        }

        return cleanResponse(response, command)
    }

    private fun cleanResponse(rawResponse: String, sentCommand: String): String {
        var clean = rawResponse
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(sentCommand.trim(), "")
            .trim()

        while (clean.contains("  ")) {
            clean = clean.replace("  ", " ")
        }
        return clean
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

        // 1. Cerca Nordic UART Service (NUS)
        val nusService = gatt.getService(NORDIC_UART_SERVICE_UUID)
        if (nusService != null) {
            foundRx = nusService.getCharacteristic(NORDIC_UART_RX_UUID)
            foundTx = nusService.getCharacteristic(NORDIC_UART_TX_UUID)
            log("Rilevato servizio Nordic UART Service (Norauto/BLE standard)!")
        }

        // 2. Cerca FFF0 Service
        if (foundRx == null) {
            val fff0Service = gatt.getService(FFF0_SERVICE_UUID)
            if (fff0Service != null) {
                foundTx = fff0Service.getCharacteristic(FFF1_TX_UUID)
                foundRx = fff0Service.getCharacteristic(FFF2_RX_UUID)
                log("Rilevato servizio FFF0 Transparent Serial BLE!")
            }
        }

        // 3. Cerca FFE0 Service
        if (foundRx == null) {
            val ffe0Service = gatt.getService(FFE0_SERVICE_UUID)
            if (ffe0Service != null) {
                val ffe1Char = ffe0Service.getCharacteristic(FFE1_TX_RX_UUID)
                foundRx = ffe1Char
                foundTx = ffe1Char
                log("Rilevato servizio FFE0 Transparent Serial BLE!")
            }
        }

        // 4. Fallback euristico su tutti i servizi
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

            // Attiva notifiche su TX
            gatt.setCharacteristicNotification(foundTx, true)
            val cccdDescriptor = foundTx.getDescriptor(CCCD_DESCRIPTOR_UUID)
            if (cccdDescriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccdDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccdDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
     * Esporta tutti i dati del benchmark in un file CSV formattato
     */
    fun exportMetricsToCsv(context: Context, metrics: BenchmarkMetrics): File {
        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val sanitizeName = metrics.deviceName.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val fileName = "OBD_Benchmark_${sanitizeName}_${fileTimestamp}.csv"

        val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
        val csvFile = File(exportDir, fileName)

        csvFile.printWriter(Charsets.UTF_8).use { writer ->
            writer.println("# OBD2 SPEED BENCHMARK METROLOGICO REPORT")
            writer.println("# Data Generazione,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            writer.println("# Dispositivo,${metrics.deviceName}")
            writer.println("# Indirizzo MAC,${metrics.deviceAddress}")
            writer.println("# Modalità Benchmark,${metrics.benchmarkMode.label}")
            writer.println("# Firmware ELM,${metrics.elmVersion}")
            writer.println("# Protocollo OBD,${metrics.protocolName}")
            writer.println("# Campioni Target,${metrics.targetRequestCount}")
            writer.println("# Campioni Effettivi,${metrics.totalCommandsSent}")
            writer.println("# Risposte SUCCESS,${metrics.successCount}")
            writer.println("# Errori ECU (ECU_ERROR),${metrics.ecuErrorCount}")
            writer.println("# Errori Adattatore (ADAPTER_ERROR),${metrics.adapterErrorCount}")
            writer.println("# Timeout (TIMEOUT),${metrics.timeoutCount}")
            writer.println("# Risposte Incomplete (INCOMPLETE_RESPONSE),${metrics.incompleteCount}")
            writer.println("# Latenza Min (ms),${String.format(Locale.US, "%.3f", metrics.minLatencyMs)}")
            writer.println("# Latenza Media (ms),${String.format(Locale.US, "%.3f", metrics.avgLatencyMs)}")
            writer.println("# Latenza Max (ms),${String.format(Locale.US, "%.3f", metrics.maxLatencyMs)}")
            writer.println("# Percentile P50 (ms),${String.format(Locale.US, "%.3f", metrics.p50Ms)}")
            writer.println("# Percentile P95 (ms),${String.format(Locale.US, "%.3f", metrics.p95Ms)}")
            writer.println("# Percentile P99 (ms),${String.format(Locale.US, "%.3f", metrics.p99Ms)}")
            writer.println("# Deviazione Standard (ms),${String.format(Locale.US, "%.3f", metrics.stdDevMs)}")
            writer.println("# Jitter (ms),${String.format(Locale.US, "%.3f", metrics.jitterMs)}")
            writer.println("# Intervallo Medio Inter-Richiesta (ms),${String.format(Locale.US, "%.3f", metrics.avgInterRequestIntervalMs)}")
            writer.println("# Velocità (PID/s),${String.format(Locale.US, "%.2f", metrics.commandsPerSecond)}")
            writer.println("# Throughput (B/s),${String.format(Locale.US, "%.2f", metrics.bytesPerSecond)}")
            writer.println("# Durata Totale Test (ms),${metrics.testDurationMs}")
            writer.println("#")
            writer.println("# --- INIZIALIZZAZIONE AUTOMATICA SCRIPT ---")
            if (metrics.initLog.isNotEmpty()) {
                metrics.initLog.forEachIndexed { idx, entry ->
                    val cleanEntry = entry.replace(",", ";")
                    writer.println("# InitStep_${String.format(Locale.US, "%02d", idx + 1)},$cleanEntry")
                }
            } else {
                writer.println("# InitStep_01,Nessun log di inizializzazione registrato")
            }
            writer.println("#")
            writer.println("# --- LOG APPLICATIVI DI SESSIONE ---")
            val currentLogs = if (metrics.sessionLogs.isNotEmpty()) metrics.sessionLogs else _logs.value
            if (currentLogs.isNotEmpty()) {
                currentLogs.forEachIndexed { idx, logLine ->
                    val cleanLog = logLine.replace(",", ";").replace("\n", " ")
                    writer.println("# SessionLog_${String.format(Locale.US, "%03d", idx + 1)},$cleanLog")
                }
            } else {
                writer.println("# SessionLog_001,Nessun log applicativo registrato")
            }
            writer.println("#")
            writer.println("Sample_Index,t0_elapsed_ns,t1_elapsed_ns,t2_elapsed_ns,t3_elapsed_ns,Write_ms,TTFB_ms,RX_ms,Total_RTT_ms,InterRequest_Interval_ms,Status,Command,Response")

            metrics.rawSamples.forEach { sample ->
                val cleanResp = sample.rawResponse.replace(",", ";")
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
                            "\"$cleanResp\""
                )
            }
        }

        log("File CSV esportato con successo in: ${csvFile.absolutePath}")
        return csvFile
    }
}
