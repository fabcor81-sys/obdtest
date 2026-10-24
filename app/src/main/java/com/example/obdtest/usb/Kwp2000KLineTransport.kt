package com.example.obdtest.usb

import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.Locale

enum class KwpState {
    DISCONNECTED,
    PORT_OPEN,
    INITIALIZING,
    SESSION_ACTIVE,
    ERROR
}

data class Kwp2000Frame(
    val formatByte: Byte,
    val targetAddress: Byte,
    val sourceAddress: Byte,
    val payload: ByteArray,
    val checksum: Byte
) {
    val rawBytes: ByteArray get() {
        val bytes = ByteArray(4 + payload.size)
        bytes[0] = formatByte
        bytes[1] = targetAddress
        bytes[2] = sourceAddress
        System.arraycopy(payload, 0, bytes, 3, payload.size)
        bytes[bytes.size - 1] = checksum
        return bytes
    }

    val hexString: String get() = rawBytes.toHexString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Kwp2000Frame
        if (formatByte != other.formatByte) return false
        if (targetAddress != other.targetAddress) return false
        if (sourceAddress != other.sourceAddress) return false
        if (!payload.contentEquals(other.payload)) return false
        if (checksum != other.checksum) return false
        return true
    }

    override fun hashCode(): Int {
        var result = formatByte.toInt()
        result = 31 * result + targetAddress.toInt()
        result = 31 * result + sourceAddress.toInt()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + checksum.toInt()
        return result
    }

    companion object {
        fun calculateChecksum(bytes: ByteArray, length: Int = bytes.size): Byte {
            var sum = 0
            for (i in 0 until length) {
                sum += (bytes[i].toInt() and 0xFF)
            }
            return (sum and 0xFF).toByte()
        }

        /**
         * Costruisce un frame KWP2000 ISO 14230-2.
         * Formato Byte (FMT): Se payload.size <= 63, FMT = 0x80 | payload.size.
         * Per StartCommunication (1 byte): FMT = 0x81.
         * Per 1A80 o 2101 (2 byte): FMT = 0x82.
         */
        fun buildFrame(
            payload: ByteArray,
            targetAddress: Byte = 0x2B.toByte(),
            sourceAddress: Byte = 0xF1.toByte()
        ): Kwp2000Frame {
            val length = payload.size
            require(length in 1..63) { "Dimensione payload KWP2000 non supportata: $length" }

            val formatByte = (0x80 or length).toByte()

            val tempBuffer = ByteArray(3 + length)
            tempBuffer[0] = formatByte
            tempBuffer[1] = targetAddress
            tempBuffer[2] = sourceAddress
            System.arraycopy(payload, 0, tempBuffer, 3, length)

            val checksum = calculateChecksum(tempBuffer)

            return Kwp2000Frame(
                formatByte = formatByte,
                targetAddress = targetAddress,
                sourceAddress = sourceAddress,
                payload = payload,
                checksum = checksum
            )
        }

        fun parseFrame(bytes: ByteArray): Kwp2000Frame? {
            if (bytes.size < 5) return null
            val fmt = bytes[0]
            val tgt = bytes[1]
            val src = bytes[2]
            val length = (fmt.toInt() and 0x3F)

            if (bytes.size < 4 + length) return null

            val payload = ByteArray(length)
            System.arraycopy(bytes, 3, payload, 0, length)
            val expectedChecksum = calculateChecksum(bytes, 3 + length)
            val actualChecksum = bytes[3 + length]

            if (expectedChecksum != actualChecksum) {
                Log.w("KWP_DEBUG", "Checksum KWP2000 non valido: atteso ${expectedChecksum.toHexString()}, ricevuto ${actualChecksum.toHexString()}")
            }

            return Kwp2000Frame(fmt, tgt, src, payload, actualChecksum)
        }
    }
}

fun ByteArray.toHexString(): String {
    return joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}

fun Byte.toHexString(): String {
    return String.format("%02X", this.toInt() and 0xFF) }

fun String.hexToByteArray(): ByteArray {
    val clean = this.replace(" ", "").replace("\r", "").replace("\n", "").uppercase(Locale.US)
    if (clean.length % 2 != 0) return byteArrayOf()
    val bytes = ByteArray(clean.length / 2)
    for (i in bytes.indices) {
        val index = i * 2
        bytes[i] = clean.substring(index, index + 2).toInt(16).toByte()
    }
    return bytes
}

data class KwpExchangeResult(
    val isSuccess: Boolean,
    val statusText: String,
    val sentFrameHex: String,
    val echoFilteredHex: String,
    val rawRxHex: String,
    val payloadHex: String,
    val t0Nanos: Long,
    val t1Nanos: Long,
    val t2Nanos: Long,
    val tPayloadNanos: Long,
    val t3Nanos: Long,
    val kwpFrame: Kwp2000Frame? = null
)

class Kwp2000KLineTransport(
    private val port: UsbSerialPort,
    val targetAddress: Byte = 0x2B.toByte(),
    val sourceAddress: Byte = 0xF1.toByte(),
    var isEchoFilterEnabled: Boolean = true
) {
    companion object {
        private const val TAG = "KWP2000_KLINE"
        const val DEFAULT_READ_TIMEOUT_MS = 1500
    }

    var state: KwpState = KwpState.DISCONNECTED
        private set

    fun openPort(baudRate: Int = 10400) {
        try {
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            state = KwpState.PORT_OPEN
            Log.d(TAG, "Porta seriale FTDI aperta a $baudRate baud 8N1")
        } catch (e: Exception) {
            state = KwpState.ERROR
            throw IOException("Impossibile impostare i parametri della porta seriale: ${e.message}", e)
        }
    }

    /**
     * Esegue la procedura Fast Init KWP2000 (ISO 14230):
     * 1. Linea K a riposo HIGH (> 300 ms)
     * 2. Impulso LOW (~25 ms) via setBreak(true)
     * 3. Impulso HIGH (~25 ms) via setBreak(false)
     * 4. Transmissione messaggio StartCommunication: 81 [Target] [Source] 81 [CS]
     * 5. Filtraggio echo e verifica risposta ECU.
     */
    fun performFastInit(
        logCallback: ((String) -> Unit)? = null
    ): Boolean {
        if (state != KwpState.PORT_OPEN && state != KwpState.SESSION_ACTIVE) {
            logCallback?.invoke("[FAST INIT] Errore: stato porta non valido ($state)")
            return false
        }

        state = KwpState.INITIALIZING
        logCallback?.invoke("[FAST INIT] Avvio sequenza Fast Init (ISO 14230) per ECU Lotus EBC430...")

        try {
            // Bus Idle HIGH > 300ms
            Thread.sleep(320)

            val t0Nanos = SystemClock.elapsedRealtimeNanos()
            port.setBreak(true)
            Thread.sleep(25)
            val tBreakLowNanos = SystemClock.elapsedRealtimeNanos()
            port.setBreak(false)
            Thread.sleep(25)
            val tBreakHighNanos = SystemClock.elapsedRealtimeNanos()

            val lowDurationMs = (tBreakLowNanos - t0Nanos) / 1_000_000.0
            val highDurationMs = (tBreakHighNanos - tBreakLowNanos) / 1_000_000.0
            logCallback?.invoke("[FAST INIT] Impulsi misurati: LOW = ${String.format("%.2f", lowDurationMs)}ms, HIGH = ${String.format("%.2f", highDurationMs)}ms")

            // Start Communication Request (Payload 0x81, length 1 -> FMT = 0x81)
            val startCommFrame = Kwp2000Frame.buildFrame(
                payload = byteArrayOf(0x81.toByte()),
                targetAddress = targetAddress,
                sourceAddress = sourceAddress
            )

            logCallback?.invoke("[FAST INIT] TX StartCommunication: ${startCommFrame.hexString}")

            val result = sendFrameAndReceive(startCommFrame, timeoutMs = 2000)

            logCallback?.invoke("[FAST INIT] RX Echo filtrato: '${result.echoFilteredHex}'")
            logCallback?.invoke("[FAST INIT] RX Risposta ECU: '${result.rawRxHex}' (Durata t2: ${String.format("%.2f", (result.t2Nanos - result.t1Nanos) / 1_000_000.0)}ms)")

            if (result.isSuccess && result.kwpFrame != null) {
                val respPayload = result.kwpFrame.payload
                if (respPayload.isNotEmpty() && (respPayload[0].toInt() and 0xFF) == 0xC1) {
                    state = KwpState.SESSION_ACTIVE
                    logCallback?.invoke("[FAST INIT] Sessione KWP2000 ATTIVA! Risposta StartCommunication Positiva (0xC1)")
                    return true
                }
            }

            state = KwpState.ERROR
            logCallback?.invoke("[FAST INIT] FAILED: Risposta a StartCommunication non valida o assente. Risposta: '${result.rawRxHex}'")
            return false

        } catch (e: Exception) {
            state = KwpState.ERROR
            logCallback?.invoke("[FAST INIT] ERRORE durante l'inizializzazione: ${e.message}")
            return false
        }
    }

    /**
     * Esegue una richiesta diagnostica di SOLA LETTURA (es. 1A 80 o 21 01).
     * Sicurezza: Blocca qualsiasi tentativo di inviare comandi di attuazione (es. servizio 0x30).
     */
    fun sendDiagnosticReadCommand(
        commandCmdHex: String,
        timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        logCallback: ((String) -> Unit)? = null
    ): KwpExchangeResult {
        val payload = commandCmdHex.hexToByteArray()

        if (payload.isEmpty()) {
            return KwpExchangeResult(
                isSuccess = false,
                statusText = "Payload HEX non valido: '$commandCmdHex'",
                sentFrameHex = "",
                echoFilteredHex = "",
                rawRxHex = "",
                payloadHex = "",
                t0Nanos = SystemClock.elapsedRealtimeNanos(),
                t1Nanos = 0, t2Nanos = 0, tPayloadNanos = 0, t3Nanos = SystemClock.elapsedRealtimeNanos()
            )
        }

        // SICUREZZA: Escludi comandi di attuazione (es. Servizio 0x30) per USB FTDI
        val serviceId = payload[0].toInt() and 0xFF
        if (serviceId == 0x30) {
            val errMsg = "SICUREZZA USB: Il servizio 0x30 (Attuatori ABS) è disabilitato sul trasporto USB K-Line."
            logCallback?.invoke("[SAFETY GUARD] $errMsg")
            return KwpExchangeResult(
                isSuccess = false,
                statusText = errMsg,
                sentFrameHex = "",
                echoFilteredHex = "",
                rawRxHex = "",
                payloadHex = "",
                t0Nanos = SystemClock.elapsedRealtimeNanos(),
                t1Nanos = 0, t2Nanos = 0, tPayloadNanos = 0, t3Nanos = SystemClock.elapsedRealtimeNanos()
            )
        }

        val kwpFrame = Kwp2000Frame.buildFrame(
            payload = payload,
            targetAddress = targetAddress,
            sourceAddress = sourceAddress
        )

        return sendFrameAndReceive(kwpFrame, timeoutMs)
    }

    private fun sendFrameAndReceive(
        frame: Kwp2000Frame,
        timeoutMs: Int
    ): KwpExchangeResult {
        val txBytes = frame.rawBytes
        val buffer = ByteArray(256)

        val t0Nanos = SystemClock.elapsedRealtimeNanos()

        try {
            port.write(txBytes, timeoutMs)
        } catch (e: Exception) {
            val t1 = SystemClock.elapsedRealtimeNanos()
            return KwpExchangeResult(
                isSuccess = false,
                statusText = "Errore scrittura USB TX: ${e.message}",
                sentFrameHex = txBytes.toHexString(),
                echoFilteredHex = "", rawRxHex = "", payloadHex = "",
                t0Nanos = t0Nanos, t1Nanos = t1, t2Nanos = t1, tPayloadNanos = t1, t3Nanos = t1
            )
        }

        val t1Nanos = SystemClock.elapsedRealtimeNanos()

        // Filtraggio Echo K-Line
        var echoBytesRead = 0
        val echoBuffer = ByteArray(txBytes.size)

        if (isEchoFilterEnabled) {
            var totalRead = 0
            val echoDeadline = SystemClock.elapsedRealtime() + timeoutMs
            while (totalRead < txBytes.size && SystemClock.elapsedRealtime() < echoDeadline) {
                try {
                    val readNow = port.read(buffer, timeoutMs.coerceAtMost(200))
                    if (readNow > 0) {
                        val countToCopy = Math.min(readNow, txBytes.size - totalRead)
                        System.arraycopy(buffer, 0, echoBuffer, totalRead, countToCopy)
                        totalRead += countToCopy
                    }
                } catch (_: Exception) {
                    break
                }
            }
            echoBytesRead = totalRead
        }

        val echoFilteredHex = echoBuffer.copyOf(echoBytesRead).toHexString()

        // Lettura Risposta ECU
        val rxBuffer = ByteArray(256)
        var rxLength = 0
        var t2Nanos = 0L
        var tPayloadNanos = 0L

        val rxDeadline = SystemClock.elapsedRealtime() + timeoutMs

        while (SystemClock.elapsedRealtime() < rxDeadline) {
            try {
                val readNow = port.read(buffer, 100)
                if (readNow > 0) {
                    if (t2Nanos == 0L) {
                        t2Nanos = SystemClock.elapsedRealtimeNanos()
                    }
                    System.arraycopy(buffer, 0, rxBuffer, rxLength, readNow)
                    rxLength += readNow

                    // Verifica se il frame KWP2000 è completo
                    if (rxLength >= 5) {
                        val fmt = rxBuffer[0]
                        val payloadLen = (fmt.toInt() and 0x3F)
                        val expectedTotalLen = 4 + payloadLen
                        if (rxLength >= expectedTotalLen) {
                            tPayloadNanos = SystemClock.elapsedRealtimeNanos()
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                break
            }
        }

        val t3Nanos = SystemClock.elapsedRealtimeNanos()
        if (t2Nanos == 0L) t2Nanos = t3Nanos
        if (tPayloadNanos == 0L) tPayloadNanos = t3Nanos

        val rawRxBytes = rxBuffer.copyOf(rxLength)
        val rawRxHex = rawRxBytes.toHexString()

        val parsedFrame = Kwp2000Frame.parseFrame(rawRxBytes)

        if (parsedFrame == null) {
            val status = if (rxLength == 0) "TIMEOUT: Nessuna risposta dalla ECU" else "RISPOSTA TRONCATA O INVALIDA"
            return KwpExchangeResult(
                isSuccess = false,
                statusText = status,
                sentFrameHex = txBytes.toHexString(),
                echoFilteredHex = echoFilteredHex,
                rawRxHex = rawRxHex,
                payloadHex = "",
                t0Nanos = t0Nanos, t1Nanos = t1Nanos, t2Nanos = t2Nanos, tPayloadNanos = tPayloadNanos, t3Nanos = t3Nanos
            )
        }

        val payloadHex = parsedFrame.payload.toHexString()
        val isSuccess = parsedFrame.payload.isNotEmpty() && (parsedFrame.payload[0].toInt() and 0xFF) != 0x7F

        val statusText = if (isSuccess) {
            "SUCCESS"
        } else {
            val nrc = if (parsedFrame.payload.size >= 3) String.format("%02X", parsedFrame.payload[2].toInt() and 0xFF) else "N/D"
            "NEGATIVE RESPONSE (NRC: $nrc)"
        }

        return KwpExchangeResult(
            isSuccess = isSuccess,
            statusText = statusText,
            sentFrameHex = txBytes.toHexString(),
            echoFilteredHex = echoFilteredHex,
            rawRxHex = rawRxHex,
            payloadHex = payloadHex,
            t0Nanos = t0Nanos, t1Nanos = t1Nanos, t2Nanos = t2Nanos, tPayloadNanos = tPayloadNanos, t3Nanos = t3Nanos,
            kwpFrame = parsedFrame
        )
    }

    fun close() {
        state = KwpState.DISCONNECTED
        try {
            port.close()
        } catch (_: Exception) {}
    }
}
