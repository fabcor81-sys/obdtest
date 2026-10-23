package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import com.example.obdtest.ui.SampleStatus
import java.util.Locale

data class ParseResult(
    val status: SampleStatus,
    val cleanedResponse: String,
    val extractedPayload: String = "",
    val serviceId: String = "",
    val nrcCode: String = ""
)

object ObdResponseParser {

    private val ECU_ERROR_KEYWORDS = listOf(
        "BUS INIT: ERROR",
        "BUS INIT ERROR",
        "NO DATA",
        "UNABLE TO CONNECT",
        "BUS ERROR",
        "BUS BUSY",
        "CAN ERROR",
        "DATA ERROR",
        "ERR70",
        "ACT ERROR"
    )

    private val ADAPTER_ERROR_KEYWORDS = listOf(
        "STOPPED",
        "BUFFER FULL",
        "FB ERROR",
        "?"
    )

    fun cleanRawResponse(rawResponse: String, sentCommand: String): String {
        val cleanCmd = sentCommand.trim().replace("\r", "").replace("\n", "")
        var clean = rawResponse
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()

        if (cleanCmd.isNotEmpty() && clean.uppercase(Locale.US).startsWith(cleanCmd.uppercase(Locale.US))) {
            clean = clean.substring(cleanCmd.length).trim()
        }

        while (clean.contains("  ")) {
            clean = clean.replace("  ", " ")
        }
        return clean
    }

    /**
     * Identifica se il buffer grezzo contiene già la risposta diagnostica KWP2000 / AT completa,
     * consentendo di catturare il timestamp esatto tPayload prima dell'arrivo del prompt '>'.
     */
    fun isDiagnosticPayloadComplete(sentCommand: String, rawResponse: String): Boolean {
        val cmdClean = sentCommand.trim().replace("\r", "").replace(" ", "").uppercase(Locale.US)
        val upperRaw = rawResponse.uppercase(Locale.US).replace(" ", "")

        return when {
            // Comando AT (ATI, ATE0, etc.)
            cmdClean.startsWith("AT") -> upperRaw.contains("ELM") || upperRaw.contains("OK")
            // Risposta negativa KWP2000 (7F ...)
            upperRaw.contains("7F") -> Regex("7F[0-9A-F]{4}").containsMatchIn(upperRaw)
            // 0100 -> 4100 [4 Bytes Hex]
            cmdClean == "0100" -> upperRaw.contains("4100")
            // 1A80 -> 5A80
            cmdClean.startsWith("1A") -> upperRaw.contains("5A")
            // 2101 / 2102 -> 6101 / 6102
            cmdClean.startsWith("21") -> upperRaw.contains("61")
            // 30020700 -> 70020700
            cmdClean.startsWith("30") -> upperRaw.contains("70")
            else -> false
        }
    }

    fun parseResponse(
        profile: ObdProfile,
        commandSent: String,
        rawResponse: String,
        hasPrompt: Boolean,
        bytesReceived: Int
    ): ParseResult {
        val cleaned = cleanRawResponse(rawResponse, commandSent)

        if (!hasPrompt) {
            return if (bytesReceived == 0 || cleaned.isBlank()) {
                ParseResult(SampleStatus.TIMEOUT, cleaned)
            } else {
                ParseResult(SampleStatus.INCOMPLETE_RESPONSE, cleaned)
            }
        }

        val upperRaw = rawResponse.uppercase(Locale.US)
        val upperCleaned = cleaned.uppercase(Locale.US)

        if (ADAPTER_ERROR_KEYWORDS.any { upperCleaned.contains(it) || upperRaw.contains(it) }) {
            return ParseResult(SampleStatus.ADAPTER_ERROR, cleaned)
        }

        if (ECU_ERROR_KEYWORDS.any { upperCleaned.contains(it) || upperRaw.contains(it) }) {
            return ParseResult(SampleStatus.ADAPTER_ERROR, cleaned)
        }

        // Comandi AT (es. ATI)
        if (profile == ObdProfile.AT_ONLY || commandSent.trim().uppercase(Locale.US).startsWith("AT")) {
            return if (cleaned.isNotBlank()) {
                ParseResult(SampleStatus.POSITIVE_RESPONSE, cleaned, extractedPayload = cleaned)
            } else {
                ParseResult(SampleStatus.ADAPTER_ERROR, cleaned)
            }
        }

        val compactCleaned = upperCleaned.replace(" ", "")

        // 1. Controllo Risposte Negative KWP2000 / ISO 14230 (7F [Service ID] [NRC])
        val nrcMatch = Regex("7F([0-9A-F]{2})([0-9A-F]{2})").find(compactCleaned)
        if (nrcMatch != null) {
            val service = nrcMatch.groupValues[1]
            val nrc = nrcMatch.groupValues[2]
            return ParseResult(
                status = SampleStatus.NEGATIVE_RESPONSE,
                cleanedResponse = cleaned,
                extractedPayload = nrcMatch.value,
                serviceId = service,
                nrcCode = nrc
            )
        }

        val cmdClean = commandSent.trim().replace("\r", "").replace(" ", "").uppercase(Locale.US)

        // 2. Controllo Risposte Positive KWP2000 / OBD2 per comando specifico
        when {
            cmdClean == "0100" -> {
                val payloadIndex = compactCleaned.indexOf("4100")
                if (payloadIndex >= 0 && compactCleaned.length >= payloadIndex + 12) {
                    val fullPayload = compactCleaned.substring(payloadIndex, payloadIndex + 12)
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = fullPayload,
                        serviceId = "01"
                    )
                }
            }

            cmdClean.startsWith("1A") -> {
                val targetHeader = "5A" + if (cmdClean.length >= 4) cmdClean.substring(2, 4) else "80"
                if (compactCleaned.contains(targetHeader)) {
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = cleaned,
                        serviceId = "1A"
                    )
                }
            }

            cmdClean.startsWith("21") -> {
                val targetHeader = "61" + if (cmdClean.length >= 4) cmdClean.substring(2, 4) else "01"
                if (compactCleaned.contains(targetHeader)) {
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = cleaned,
                        serviceId = "21"
                    )
                }
            }

            cmdClean.startsWith("30") -> {
                if (compactCleaned.contains("70020700") || compactCleaned.startsWith("70")) {
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = cleaned,
                        serviceId = "30"
                    )
                }
            }
        }

        // 3. Rilevazione Risposta Inaspettata (UNEXPECTED_RESPONSE): payload diagnostico arrivato ma non corrispondente
        if (compactCleaned.startsWith("5A") || compactCleaned.startsWith("61") || compactCleaned.startsWith("70") || compactCleaned.startsWith("41")) {
            return ParseResult(
                status = SampleStatus.UNEXPECTED_RESPONSE,
                cleanedResponse = cleaned,
                extractedPayload = cleaned
            )
        }

        return ParseResult(SampleStatus.INCOMPLETE_RESPONSE, cleaned)
    }
}
