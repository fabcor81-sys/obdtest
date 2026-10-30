package com.example.obdtest.benchmark

import com.example.obdtest.ui.TransportMode
import com.example.obdtest.ui.ObdProfile
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

/**
 * Read-only benchmark plans. A generic profile cannot run KWP Lotus-specific
 * services, and NO profile issues an ABS actuator command (30 xx).
 */
object BenchmarkReadOnlyPlan {
    fun bluetooth(profile: ObdProfile, samples: Int): List<BenchmarkTestDefinition> {
        require(samples in 1..1000)
        return when (profile) {
            ObdProfile.AT_ONLY -> listOf(BenchmarkTestDefinition("ELM identification", "ATI\\r", samples))
            ObdProfile.GENERIC_AUTO -> listOf(
                BenchmarkTestDefinition("ELM identification", "ATI\\r", samples),
                BenchmarkTestDefinition("Generic OBD PID 0100", "0100\\r", samples)
            )
            ObdProfile.LOTUS_ABS -> listOf(
                BenchmarkTestDefinition("ELM identification", "ATI\\r", samples),
                BenchmarkTestDefinition("ECU identification", "1A80\\r", samples),
                BenchmarkTestDefinition("Battery voltage", "2101\\r", samples),
                BenchmarkTestDefinition("ABS status", "2102\\r", samples)
            )
            ObdProfile.USB_KLINE_DIAG -> error("USB K-Line profile cannot be used over Bluetooth")
        }
    }

    fun usb(profile: ObdProfile, samples: Int): List<BenchmarkTestDefinition> {
        require(samples in 1..1000)
        return when (profile) {
            ObdProfile.USB_KLINE_DIAG ->
                listOf(BenchmarkTestDefinition("ECU identification", "1A80", 1, isSingleExecution = true))
            ObdProfile.LOTUS_ABS -> listOf(
                BenchmarkTestDefinition("ECU identification", "1A80", samples),
                BenchmarkTestDefinition("Read local data", "2101", samples)
            )
            else -> error("Unsupported USB K-Line profile: ${profile.name}")
        }
    }

    fun isReadOnly(plan: List<BenchmarkTestDefinition>): Boolean =
        plan.none { it.command.filterNot(Char::isWhitespace).startsWith("30") }
}
