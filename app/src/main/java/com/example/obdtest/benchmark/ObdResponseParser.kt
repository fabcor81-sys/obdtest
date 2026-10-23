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

        if (profile == ObdProfile.AT_ONLY) {
            return if (cleaned.isNotBlank()) {
                ParseResult(SampleStatus.POSITIVE_RESPONSE, cleaned, extractedPayload = cleaned)
            } else {
                ParseResult(SampleStatus.ADAPTER_ERROR, cleaned)
            }
        }

        // 1. Controllo Risposte Negative KWP2000 / ISO 14230 (7F [Service ID] [NRC])
        val compactCleaned = upperCleaned.replace(" ", "")
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

        // 2. Controllo Risposte Positive KWP2000 / OBD2
        when {
            // Comando 0100 (OBD2 Mode 01 PID 00) -> 4100 [4 Bytes]
            cmdClean == "0100" -> {
                val payload = compactCleaned.split("4100").drop(1).firstOrNull()
                if (payload != null && payload.length >= 8) {
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = "4100" + payload.substring(0, 8),
                        serviceId = "01"
                    )
                }
            }

            // Service 0x1A (ReadECUIdentification - es. 1A 80) -> Risposta 5A 80 ...
            cmdClean.startsWith("1A") -> {
                val sub = if (cmdClean.length >= 4) cmdClean.substring(2, 4) else ""
                val targetHeader = "5A" + sub
                if (compactCleaned.contains(targetHeader) || compactCleaned.startsWith("5A")) {
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = cleaned,
                        serviceId = "1A"
                    )
                }
            }

            // Service 0x21 (ReadDataByLocalIdentifier - es. 21 01, 21 02) -> Risposta 61 01 ...
            cmdClean.startsWith("21") -> {
                val sub = if (cmdClean.length >= 4) cmdClean.substring(2, 4) else ""
                val targetHeader = "61" + sub
                if (compactCleaned.contains(targetHeader) || compactCleaned.startsWith("61")) {
                    return ParseResult(
                        status = SampleStatus.POSITIVE_RESPONSE,
                        cleanedResponse = cleaned,
                        extractedPayload = cleaned,
                        serviceId = "21"
                    )
                }
            }

            // Service 0x30 (InputOutputControlByLocalIdentifier - es. 30 02 07 00) -> Risposta 70 02 07 00
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

        // Se è arrivate una risposta non riconosciuta come errore o positiva
        return if (cleaned.isNotBlank()) {
            ParseResult(
                status = SampleStatus.POSITIVE_RESPONSE,
                cleanedResponse = cleaned,
                extractedPayload = cleaned
            )
        } else {
            ParseResult(SampleStatus.INCOMPLETE_RESPONSE, cleaned)
        }
    }
}
