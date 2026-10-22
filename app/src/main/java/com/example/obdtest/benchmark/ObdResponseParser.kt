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

        // Only validate documented Mode 01 payload lengths. Unsupported commands
        // must not turn arbitrary text or a negative ECU response into a success.
        val dataBytes = when (cmdClean) {
            "0100", "0120", "0140", "0160", "0180", "01A0", "01C0" -> 4
            "010C", "0110" -> 2
            "0105", "010A", "010D", "010F", "0111" -> 1
            else -> return ParseResult(SampleStatus.ECU_ERROR, cleaned)
        }
        val expectedHeader = "41" + cmdClean.substring(2)
        // Keep response lines separate: truncated replies from different ECUs
        // must never be concatenated into a seemingly complete payload.
        val payload = rawResponse.substringBefore(">").split('\r', '\n')
            .map { it.uppercase(Locale.US).replace("SEARCHING...", "").filterNot { c -> c.isWhitespace() } }
            .firstOrNull { line ->
                line.length == expectedHeader.length + dataBytes * 2 &&
                    line.startsWith(expectedHeader) && line.all { it in "0123456789ABCDEF" }
            }
        return if (payload != null) {
            ParseResult(SampleStatus.SUCCESS, cleaned, extractedPayload = payload)
        } else {
            ParseResult(SampleStatus.ECU_ERROR, cleaned)
        }
    }
}