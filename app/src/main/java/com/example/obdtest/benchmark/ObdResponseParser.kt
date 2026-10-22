package com.example.obdtest.benchmark

import com.example.obdtest.ui.ObdProfile
import com.example.obdtest.ui.SampleStatus
import java.util.Locale

data class ParseResult(
    val status: SampleStatus,
    val cleanedResponse: String,
    val extractedPayload: String = ""
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

    /**
     * Ripulisce la risposta da prompt '>', linefeed '\r', '\n', spazi multipli e dall'echo del comando.
     */
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
     * Valuta e classifica una risposta grezza in base al profilo di test selezionato.
     */
    fun parseResponse(
        profile: ObdProfile,
        commandSent: String,
        rawResponse: String,
        hasPrompt: Boolean,
        bytesReceived: Int
    ): ParseResult {
        val cleaned = cleanRawResponse(rawResponse, commandSent)

        // 1. Mancanza di prompt o risposta vuota
        if (!hasPrompt) {
            return if (bytesReceived == 0 || cleaned.isBlank()) {
                ParseResult(SampleStatus.TIMEOUT, cleaned)
            } else {
                ParseResult(SampleStatus.INCOMPLETE_RESPONSE, cleaned)
            }
        }

        val upperRaw = rawResponse.uppercase(Locale.US)
        val upperCleaned = cleaned.uppercase(Locale.US)

        // 2. Errore Adattatore (STOPPED, BUFFER FULL, ?)
        if (ADAPTER_ERROR_KEYWORDS.any { upperCleaned.contains(it) || upperRaw.contains(it) }) {
            return ParseResult(SampleStatus.ADAPTER_ERROR, cleaned)
        }

        // 3. Errore ECU / Bus (BUS INIT: ERROR, NO DATA, UNABLE TO CONNECT)
        if (ECU_ERROR_KEYWORDS.any { upperCleaned.contains(it) || upperRaw.contains(it) }) {
            return ParseResult(SampleStatus.ECU_ERROR, cleaned)
        }

        // 4. Modalità AT-Only (Comando ATI)
        if (profile == ObdProfile.AT_ONLY) {
            return if (cleaned.isNotBlank()) {
                ParseResult(SampleStatus.SUCCESS, cleaned, extractedPayload = cleaned)
            } else {
                ParseResult(SampleStatus.ADAPTER_ERROR, cleaned)
            }
        }

        // 5. Modalità ECU ABS Lotus vs OBD Generico
        val cmdClean = commandSent.trim().replace("\r", "").replace(" ", "").uppercase(Locale.US)

        if (profile == ObdProfile.LOTUS_ABS) {
            // Profilo ECU ABS Lotus: la risposta non è necessariamente la solita 41 00 di una ECU motore standard.
            // Se la ECU ABS risponde con 4100 o con byte esadecimali non-vuoti, consideriamo la risposta ricevuta.
            return if (cleaned.isNotBlank()) {
                ParseResult(SampleStatus.SUCCESS, cleaned, extractedPayload = cleaned)
            } else {
                ParseResult(SampleStatus.ECU_ERROR, cleaned)
            }
        }

        if (cmdClean == "0100") {
            // Per OBD Generico PID 0100 ci aspettiamo "41 00" seguito da 4 byte esadecimali dati (8 caratteri hex)
            val hexOnly = upperCleaned.replace("SEARCHING...", "").replace(" ", "")
            val expectedHeader = "4100"

            val matchIndex = hexOnly.indexOf(expectedHeader)
            if (matchIndex != -1) {
                val remainingHex = hexOnly.substring(matchIndex + expectedHeader.length)
                val validDataHex = remainingHex.takeWhile { it in '0'..'9' || it in 'A'..'F' }
                if (validDataHex.length >= 8) {
                    val payload = expectedHeader + validDataHex.take(8)
                    return ParseResult(SampleStatus.SUCCESS, cleaned, extractedPayload = payload)
                }
            }
            return ParseResult(SampleStatus.ECU_ERROR, cleaned)
        } else if (cmdClean.startsWith("01")) {
            val expectedHeader = "41" + cmdClean.substring(2)
            val hexOnly = upperCleaned.replace("SEARCHING...", "").replace(" ", "")
            if (hexOnly.contains(expectedHeader)) {
                return ParseResult(SampleStatus.SUCCESS, cleaned, extractedPayload = expectedHeader)
            }
            return ParseResult(SampleStatus.ECU_ERROR, cleaned)
        } else {
            return if (cleaned.isNotBlank()) {
                ParseResult(SampleStatus.SUCCESS, cleaned, extractedPayload = cleaned)
            } else {
                ParseResult(SampleStatus.ECU_ERROR, cleaned)
            }
        }
    }
}
