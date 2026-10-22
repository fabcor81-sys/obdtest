package com.example.obdtest.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdViewModelTest {

    @Test
    fun testTransportModeSelection() {
        var state = ObdUiState()
        state = state.copy(selectedTransportMode = TransportMode.BLE_GATT)

        assertEquals(TransportMode.BLE_GATT, state.selectedTransportMode)
    }

    @Test
    fun testObdProfileSelection() {
        var state = ObdUiState()
        state = state.copy(selectedProfile = ObdProfile.KWP_FAST_FCA)

        assertEquals(ObdProfile.KWP_FAST_FCA, state.selectedProfile)
    }

    @Test
    fun testComparisonMetricsSaveAndClear() {
        val dummyMetrics = BenchmarkMetrics(deviceName = "vLinker MC")
        var state = ObdUiState()

        state = state.copy(savedComparisonMetrics = dummyMetrics)
        assertEquals("vLinker MC", state.savedComparisonMetrics?.deviceName)

        state = state.copy(savedComparisonMetrics = null)
        assertNull(state.savedComparisonMetrics)
    }
}
