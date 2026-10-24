package com.example.obdtest.usb

import java.util.Locale

enum class KwpDecodeStatus {
    SUCCESS,
    NEGATIVE_RESPONSE,
    UNEXPECTED_RESPONSE,
    INVALID_TARGET_ADDRESS,
    INVALID_SOURCE_ADDRESS,
    CHECKSUM_ERROR,
    INCOMPLETE_RESPONSE,
    NO_DATA
}

data class KwpDecodeResult(
    val status: KwpDecodeStatus,
    val isSuccess: Boolean,
    val statusText: String,
    val echoBytes: ByteArray,
    val isEchoMatched: Boolean,
    val ecuBytes: ByteArray,
    val parsedFrame: Kwp2000Frame?,
    val bytesConsumed: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KwpDecodeResult
        if (status != other.status) return false
        if (isSuccess != other.isSuccess) return false
        if (statusText != other.statusText) return false
        if (!echoBytes.contentEquals(other.echoBytes)) return false
        if (isEchoMatched != other.isEchoMatched) return false
        if (!ecuBytes.contentEquals(other.ecuBytes)) return false
        if (parsedFrame != other.parsedFrame) return false
        if (bytesConsumed != other.bytesConsumed) return false
        return true
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + isSuccess.hashCode()
        result = 31 * result + statusText.hashCode()
        result = 31 * result + echoBytes.contentHashCode()
        result = 31 * result + isEchoMatched.hashCode()
        result = 31 * result + ecuBytes.contentHashCode()
        result = 31 * result + (parsedFrame?.hashCode() ?: 0)
        result = 31 * result + bytesConsumed
        return result
    }
}

class Kwp2000StreamDecoder(
    val expectedTargetAddress: Byte = 0xF1.toByte(), // Tester address in response header
    val expectedSourceAddress: Byte = 0x2B.toByte(), // ECU address in response header
    var isEchoFilterEnabled: Boolean = true
) {

    /**
     * Decodifica in modo deterministico e rigoroso uno stream di byte ricevuti da USB K-Line.
     * Separazione dinamica di echo ed ECU response, validazione del checksum, degli indirizzi e del servizio.
     */
    fun decodeStream(
        streamBytes: ByteArray,
        txBytes: ByteArray,
        requestedServiceId: Int
    ): KwpDecodeResult {
        if (streamBytes.isEmpty()) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.NO_DATA,
                isSuccess = false,
                statusText = "NO_DATA",
                echoBytes = byteArrayOf(),
                isEchoMatched = false,
                ecuBytes = byteArrayOf(),
                parsedFrame = null,
                bytesConsumed = 0
            )
        }

        var echoEndIndex = 0
        var isEchoMatched = false

        // 1. SEPARAZIONE DINAMICA ECHO VS ECU RESPONSE
        if (isEchoFilterEnabled && txBytes.isNotEmpty()) {
            val expectedEchoSize = txBytes.size

            if (streamBytes.size >= expectedEchoSize && streamBytes.copyOfRange(0, expectedEchoSize).contentEquals(txBytes)) {
                // Case A: Echo completo e perfettamente coincidente
                echoEndIndex = expectedEchoSize
                isEchoMatched = true
            } else {
                // Verifica se lo stream parte direttamente con un header KWP2000 per l'ECU (assenza di echo)
                val directHeaderValid = isKwpHeaderForTester(streamBytes, 0)

                if (directHeaderValid) {
                    // Case B: Assenza completa di echo hardware (il cavo o il transceiver non restituisce echo TX)
                    echoEndIndex = 0
                    isEchoMatched = false
                } else {
                    // Case C: Echo parziale, frammentato o non corrispondente
                    val matchLen = countMatchingPrefix(streamBytes, txBytes)
                    if (matchLen > 0 && streamBytes.size <= txBytes.size) {
                        // Echo in fase di ricezione (frammentato)
                        echoEndIndex = streamBytes.size
                        isEchoMatched = true
                    } else {
                        // Cerca l'inizio di un frame ECU valido nello stream
                        val ecuHeaderIndex = findEcuHeaderIndex(streamBytes)
                        if (ecuHeaderIndex >= 0) {
                            echoEndIndex = ecuHeaderIndex
                            isEchoMatched = false
                        } else {
                            // Non ancora identificabile
                            echoEndIndex = Math.min(streamBytes.size, expectedEchoSize)
                            isEchoMatched = false
                        }
                    }
                }
            }
        } else {
            // Echo filter disabilitato
            echoEndIndex = 0
            isEchoMatched = true
        }

        val echoBytes = if (echoEndIndex > 0) streamBytes.copyOfRange(0, echoEndIndex) else byteArrayOf()
        val ecuBytes = if (echoEndIndex < streamBytes.size) streamBytes.copyOfRange(echoEndIndex, streamBytes.size) else byteArrayOf()

        if (ecuBytes.isEmpty()) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.INCOMPLETE_RESPONSE,
                isSuccess = false,
                statusText = "INCOMPLETE_RESPONSE (Attesa byte risposta ECU)",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = byteArrayOf(),
                parsedFrame = null,
                bytesConsumed = echoEndIndex
            )
        }

        // 2. VALIDAZIONE RIGOROSA DEL FRAME KWP2000
        if (ecuBytes.size < 5) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.INCOMPLETE_RESPONSE,
                isSuccess = false,
                statusText = "INCOMPLETE_RESPONSE (Header KWP2000 incompleto)",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = ecuBytes,
                parsedFrame = null,
                bytesConsumed = echoEndIndex
            )
        }

        val fmt = ecuBytes[0]
        val tgt = ecuBytes[1]
        val src = ecuBytes[2]
        val length = (fmt.toInt() and 0x3F)

        if (length == 0) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.UNEXPECTED_RESPONSE,
                isSuccess = false,
                statusText = "FORMAT_BYTE_ERROR (Lunghezza 0)",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = ecuBytes,
                parsedFrame = null,
                bytesConsumed = echoEndIndex + 1
            )
        }

        val expectedTotalFrameLen = 4 + length
        if (ecuBytes.size < expectedTotalFrameLen) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.INCOMPLETE_RESPONSE,
                isSuccess = false,
                statusText = "INCOMPLETE_RESPONSE (Attesi $expectedTotalFrameLen byte, ricevuti ${ecuBytes.size})",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = ecuBytes,
                parsedFrame = null,
                bytesConsumed = echoEndIndex
            )
        }

        val frameBytes = ecuBytes.copyOfRange(0, expectedTotalFrameLen)
        val expectedCS = Kwp2000Frame.calculateChecksum(frameBytes, expectedTotalFrameLen - 1)
        val actualCS = frameBytes[expectedTotalFrameLen - 1]

        if (expectedCS != actualCS) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.CHECKSUM_ERROR,
                isSuccess = false,
                statusText = "CHECKSUM_ERROR (Calcolato: ${expectedCS.toHexString()}, Ricevuto: ${actualCS.toHexString()})",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = frameBytes,
                parsedFrame = null,
                bytesConsumed = echoEndIndex + expectedTotalFrameLen
            )
        }

        if (tgt != expectedTargetAddress) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.INVALID_TARGET_ADDRESS,
                isSuccess = false,
                statusText = "INVALID_TARGET_ADDRESS (Atteso: ${expectedTargetAddress.toHexString()}, Ricevuto: ${tgt.toHexString()})",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = frameBytes,
                parsedFrame = null,
                bytesConsumed = echoEndIndex + expectedTotalFrameLen
            )
        }

        if (src != expectedSourceAddress) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.INVALID_SOURCE_ADDRESS,
                isSuccess = false,
                statusText = "INVALID_SOURCE_ADDRESS (Atteso: ${expectedSourceAddress.toHexString()}, Ricevuto: ${src.toHexString()})",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = frameBytes,
                parsedFrame = null,
                bytesConsumed = echoEndIndex + expectedTotalFrameLen
            )
        }

        val payload = frameBytes.copyOfRange(3, 3 + length)
        val respServiceId = payload[0].toInt() and 0xFF
        val expectedPosService = (requestedServiceId + 0x40) and 0xFF

        val parsedFrame = Kwp2000Frame(fmt, tgt, src, payload, actualCS)

        if (respServiceId == expectedPosService) {
            return KwpDecodeResult(
                status = KwpDecodeStatus.SUCCESS,
                isSuccess = true,
                statusText = "SUCCESS",
                echoBytes = echoBytes,
                isEchoMatched = isEchoMatched,
                ecuBytes = frameBytes,
                parsedFrame = parsedFrame,
                bytesConsumed = echoEndIndex + expectedTotalFrameLen
            )
        }

        if (respServiceId == 0x7F) {
            val refService = if (payload.size >= 2) payload[1].toInt() and 0xFF else -1
            val nrcCode = if (payload.size >= 3) String.format("%02X", payload[2].toInt() and 0xFF) else "N/D"

            if (refService == requestedServiceId) {
                return KwpDecodeResult(
                    status = KwpDecodeStatus.NEGATIVE_RESPONSE,
                    isSuccess = false,
                    statusText = "NEGATIVE_RESPONSE (Servizio $requestedServiceId, NRC: $nrcCode)",
                    echoBytes = echoBytes,
                    isEchoMatched = isEchoMatched,
                    ecuBytes = frameBytes,
                    parsedFrame = parsedFrame,
                    bytesConsumed = echoEndIndex + expectedTotalFrameLen
                )
            } else {
                return KwpDecodeResult(
                    status = KwpDecodeStatus.UNEXPECTED_RESPONSE,
                    isSuccess = false,
                    statusText = "UNEXPECTED_NEGATIVE_RESPONSE (Servizio Riferito: $refService != Richiesto: $requestedServiceId, NRC: $nrcCode)",
                    echoBytes = echoBytes,
                    isEchoMatched = isEchoMatched,
                    ecuBytes = frameBytes,
                    parsedFrame = parsedFrame,
                    bytesConsumed = echoEndIndex + expectedTotalFrameLen
                )
            }
        }

        return KwpDecodeResult(
            status = KwpDecodeStatus.UNEXPECTED_RESPONSE,
            isSuccess = false,
            statusText = "UNEXPECTED_SERVICE_RESPONSE (Atteso $expectedPosService, Ricevuto $respServiceId)",
            echoBytes = echoBytes,
            isEchoMatched = isEchoMatched,
            ecuBytes = frameBytes,
            parsedFrame = parsedFrame,
            bytesConsumed = echoEndIndex + expectedTotalFrameLen
        )
    }

    private fun isKwpHeaderForTester(bytes: ByteArray, startIndex: Int): Boolean {
        if (bytes.size < startIndex + 3) return false
        val fmt = bytes[startIndex].toInt() and 0xFF
        val tgt = bytes[startIndex + 1]
        val src = bytes[startIndex + 2]

        val isFmtValid = (fmt and 0x80) != 0
        return isFmtValid && tgt == expectedTargetAddress && src == expectedSourceAddress
    }

    private fun findEcuHeaderIndex(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 2) {
            if (isKwpHeaderForTester(bytes, i)) return i
        }
        return -1
    }

    private fun countMatchingPrefix(bytes: ByteArray, txBytes: ByteArray): Int {
        var count = 0
        val maxLen = Math.min(bytes.size, txBytes.size)
        for (i in 0 until maxLen) {
            if (bytes[i] == txBytes[i]) count++ else break
        }
        return count
    }
}
