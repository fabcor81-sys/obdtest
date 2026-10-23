package com.example.obdtest.benchmark

import com.example.obdtest.ui.BenchmarkMetrics
import com.example.obdtest.ui.CommandTimingSample
import com.example.obdtest.ui.SampleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdMetricsCalculatorTest {

    @Test
    fun testMetricsCalculationWithSuccessSamples() {
        val samples = listOf(
            CommandTimingSample(
                sampleIndex = 1,
                t0Nanos = 0, t1Nanos = 1, t2Nanos = 10, t3Nanos = 100,
                writeDurationMs = 1.0, timeToFirstByteMs = 9.0, rxDurationMs = 90.0,
                totalRoundTripMs = 100.0, interRequestIntervalMs = 0.0,
                status = SampleStatus.POSITIVE_RESPONSE, isSuccess = true, isTimeout = false,
                commandSent = "0100", rawResponse = "41 00 BE 3F A8 13", parsedResponse = "4100BE3FA813"
            ),
            CommandTimingSample(
                sampleIndex = 2,
                t0Nanos = 100, t1Nanos = 101, t2Nanos = 110, t3Nanos = 300,
                writeDurationMs = 1.0, timeToFirstByteMs = 9.0, rxDurationMs = 190.0,
                totalRoundTripMs = 200.0, interRequestIntervalMs = 100.0,
                status = SampleStatus.POSITIVE_RESPONSE, isSuccess = true, isTimeout = false,
                commandSent = "0100", rawResponse = "41 00 BE 3F A8 13", parsedResponse = "4100BE3FA813"
            )
        )

        val baseMetrics = BenchmarkMetrics()
        val engine = ObdBenchmarkEngine()

        val calculateMethod = ObdBenchmarkEngine::class.java.getDeclaredMethod(
            "calculateAggregateMetrics",
            BenchmarkMetrics::class.java,
            List::class.java,
            List::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        calculateMethod.isAccessible = true

        val result = calculateMethod.invoke(
            engine,
            baseMetrics,
            samples,
            emptyList<Any>(),
            1000L, // 1 secondo totale
            false
        ) as BenchmarkMetrics

        assertEquals(2, result.totalCommandsSent)
        assertEquals(2, result.successCount)
        assertEquals(100.0, result.successRatePercent, 0.01)
        assertEquals(100.0, result.minLatencyMs, 0.01)
        assertEquals(200.0, result.maxLatencyMs, 0.01)
        assertEquals(150.0, result.avgLatencyMs, 0.01)
        assertEquals(100.0, result.jitterMs, 0.01) // |200 - 100| = 100
        assertEquals(2.0, result.commandsPerSecond, 0.01)
    }

    @Test
    fun testMetricsCalculationWithNoSuccesses() {
        val samples = listOf(
            CommandTimingSample(
                sampleIndex = 1,
                t0Nanos = 0, t1Nanos = 1, t2Nanos = 1, t3Nanos = 2500,
                writeDurationMs = 1.0, timeToFirstByteMs = 0.0, rxDurationMs = 0.0,
                totalRoundTripMs = 2500.0, interRequestIntervalMs = 0.0,
                status = SampleStatus.TIMEOUT, isSuccess = false, isTimeout = true,
                commandSent = "0100", rawResponse = "TIMEOUT"
            )
        )

        val baseMetrics = BenchmarkMetrics()
        val engine = ObdBenchmarkEngine()

        val calculateMethod = ObdBenchmarkEngine::class.java.getDeclaredMethod(
            "calculateAggregateMetrics",
            BenchmarkMetrics::class.java,
            List::class.java,
            List::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        calculateMethod.isAccessible = true

        val result = calculateMethod.invoke(
            engine,
            baseMetrics,
            samples,
            emptyList<Any>(),
            2500L,
            true
        ) as BenchmarkMetrics

        assertEquals(1, result.totalCommandsSent)
        assertEquals(0, result.successCount)
        assertEquals(1, result.timeoutCount)
        assertEquals(0.0, result.successRatePercent, 0.01)
        assertEquals(0.0, result.avgLatencyMs, 0.01)
        assertEquals(0.0, result.jitterMs, 0.01)
        assertEquals(true, result.isPartialSession)
    }
}
