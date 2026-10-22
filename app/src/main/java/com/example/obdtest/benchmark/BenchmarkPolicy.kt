package com.example.obdtest.benchmark

import com.example.obdtest.ui.TransportMode
import java.util.Locale

data class RawCommandResult(
    val rawResponse: String,
    val cleanResponse: String,
    val hasPrompt: Boolean,
    val isTimeout: Boolean
)

/** Shared by the real initialization path and its unit tests. */
object BenchmarkPolicy {
    val lotusCommands = listOf("AT Z\r", "AT L1\r", "AT SH 812BF1\r", "AT WM 812BF13E\r", "AT SP 5\r")

    fun acceptsInitialization(command: String, result: RawCommandResult): Boolean {
        if (!result.hasPrompt || result.isTimeout) return false
        val response = result.cleanResponse.trim().uppercase(Locale.US)
        return when (command.filterNot { it.isWhitespace() }.uppercase(Locale.US)) {
            "ATZ", "ATI" -> response.isNotBlank() && response != "OK" &&
                !listOf("?", "ERROR", "STOPPED", "NO DATA", "UNABLE").any { response.contains(it) }
            "ATDP" -> response.isNotBlank() &&
                !listOf("?", "ERROR", "STOPPED", "NO DATA", "UNABLE").any { response.contains(it) }
            else -> response == "OK"
        }
    }

    fun warmupPassed(attempted: Int, successes: Int): Boolean = attempted > 0 && successes > 0

    fun effectiveTransport(isBle: Boolean): TransportMode =
        if (isBle) TransportMode.BLE_GATT else TransportMode.CLASSIC_SPP
}

/** The clock is injected so partial and completed sessions share the same time origin. */
class MeasurementClock(private val nowNanos: () -> Long) {
    private var startedAt: Long? = null
    fun start() { startedAt = nowNanos() }
    fun elapsedMs(): Long = startedAt?.let { ((nowNanos() - it) / 1_000_000L).coerceAtLeast(0L) } ?: 0L
}
