package com.example.obdtest.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.obdtest.benchmark.ObdBenchmarkEngine
import com.example.obdtest.bluetooth.BluetoothObdManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothManager by lazy { BluetoothObdManager(application.applicationContext) }
    private val benchmarkEngine by lazy { ObdBenchmarkEngine() }

    private val _uiState = MutableStateFlow(ObdUiState())
    val uiState: StateFlow<ObdUiState> = _uiState.asStateFlow()

    private var benchmarkJob: Job? = null

    init {
        // Monitoraggio in tempo reale dei log dall'engine
        viewModelScope.launch {
            benchmarkEngine.logs.collect { newLogs ->
                _uiState.update { it.copy(logs = newLogs) }
            }
        }

        refreshBluetoothState()
    }

    fun refreshBluetoothState() {
        val hasPerms = bluetoothManager.hasRequiredPermissions()
        val btEnabled = bluetoothManager.isBluetoothEnabled()

        val paired = if (hasPerms && btEnabled) bluetoothManager.getPairedDevices() else emptyList()

        _uiState.update { currentState ->
            val currentSelected = currentState.selectedDevice
            val stillPresent = paired.find { it.address == currentSelected?.address }

            val statusMsg = when {
                !hasPerms -> "Richiesti permessi Bluetooth per proseguire"
                !btEnabled -> "Bluetooth disattivato sullo smartphone"
                paired.isEmpty() -> "Nessun dispositivo associato trovato. Usa 'Scansiona Dispositivi'."
                else -> "Seleziona un dispositivo, modalità e avvia il benchmark metrologico"
            }

            currentState.copy(
                hasPermissions = hasPerms,
                isBluetoothEnabled = btEnabled,
                pairedDevices = paired,
                selectedDevice = stillPresent ?: paired.firstOrNull(),
                statusMessage = statusMsg
            )
        }
    }

    fun startDeviceScan() {
        if (!_uiState.value.hasPermissions || !_uiState.value.isBluetoothEnabled) {
            refreshBluetoothState()
            return
        }

        _uiState.update {
            it.copy(
                isScanning = true,
                statusMessage = "Scansione dispositivi Bluetooth Classic e BLE in corso..."
            )
        }

        bluetoothManager.startDiscovery(
            onDeviceFound = { foundDevice ->
                _uiState.update { currentState ->
                    val currentList = currentState.pairedDevices
                    if (currentList.none { it.address == foundDevice.address }) {
                        val updatedList = currentList + foundDevice
                        currentState.copy(
                            pairedDevices = updatedList,
                            selectedDevice = currentState.selectedDevice ?: foundDevice
                        )
                    } else {
                        currentState
                    }
                }
            },
            onDiscoveryFinished = {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        statusMessage = "Scansione completata. ${it.pairedDevices.size} dispositivi trovati."
                    )
                }
            },
            onError = { errMsg ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        statusMessage = "Errore scansione: $errMsg"
                    )
                }
            }
        )
    }

    fun stopDeviceScan() {
        bluetoothManager.stopDiscovery()
        _uiState.update {
            it.copy(
                isScanning = false,
                statusMessage = "Scansione interrotta dall'utente."
            )
        }
    }

    fun selectDevice(device: BluetoothDeviceItem) {
        _uiState.update { it.copy(selectedDevice = device) }
    }

    fun selectTransportMode(transportMode: TransportMode) {
        _uiState.update { it.copy(selectedTransportMode = transportMode) }
    }

    fun selectObdProfile(profile: ObdProfile) {
        _uiState.update {
            it.copy(
                selectedProfile = profile,
                customCommand = profile.defaultCommand
            )
        }
    }

    fun setCustomCommand(cmd: String) {
        _uiState.update { it.copy(customCommand = cmd) }
    }

    fun selectRequestCount(count: Int) {
        _uiState.update { it.copy(selectedRequestCount = count) }
    }

    fun startBenchmarkTest() {
        bluetoothManager.stopDiscovery()

        val selectedDeviceItem = _uiState.value.selectedDevice ?: return
        val bluetoothDevice = bluetoothManager.getDeviceByAddress(selectedDeviceItem.address) ?: return

        // Resetta lo stato di sessione precedente prima di avviare un nuovo test
        benchmarkEngine.clearLogs()
        _uiState.update {
            it.copy(
                isScanning = false,
                currentMetrics = null,
                exportedCsvPath = null,
                status = BenchmarkStatus.Connecting,
                statusMessage = "Inizio benchmark metrologico [${it.selectedProfile.label}] (${it.selectedRequestCount} richieste)..."
            )
        }

        // Se un job precedente è in corso, attende la sua cancellazione prima di avviarne uno nuovo
        val previousJob = benchmarkJob
        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            previousJob?.cancel()
            previousJob?.join()

            try {
                val currentConfig = _uiState.value
                val metrics = benchmarkEngine.runBenchmark(
                    context = getApplication(),
                    device = bluetoothDevice,
                    transportMode = currentConfig.selectedTransportMode,
                    obdProfile = currentConfig.selectedProfile,
                    customCommand = currentConfig.customCommand,
                    targetRequestCount = currentConfig.selectedRequestCount,
                    timeoutMs = ObdBenchmarkEngine.DEFAULT_TIMEOUT_MS,
                    onStatusUpdate = { status, msg ->
                        _uiState.update {
                            it.copy(
                                status = status,
                                statusMessage = msg
                            )
                        }
                    },
                    onMetricsUpdate = { metricsUpdate ->
                        _uiState.update { it.copy(currentMetrics = metricsUpdate) }
                    }
                )

                // Salvataggio automatico del report CSV al termine del test
                try {
                    val autoCsvFile = benchmarkEngine.exportMetricsToCsv(getApplication(), metrics)
                    _uiState.update {
                        it.copy(
                            currentMetrics = metrics,
                            exportedCsvPath = autoCsvFile.absolutePath,
                            status = BenchmarkStatus.Completed,
                            statusMessage = "Benchmark completato! CSV salvato: ${autoCsvFile.name}"
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            currentMetrics = metrics,
                            status = BenchmarkStatus.Completed,
                            statusMessage = "Benchmark completato! (Errore salvataggio CSV: ${e.message})"
                        )
                    }
                }
            } catch (e: CancellationException) {
                _uiState.value.currentMetrics?.let { partialMetrics ->
                    try {
                        val autoCsvFile = benchmarkEngine.exportMetricsToCsv(getApplication(), partialMetrics)
                        _uiState.update { it.copy(exportedCsvPath = autoCsvFile.absolutePath) }
                    } catch (_: Exception) {}
                }
                _uiState.update {
                    it.copy(
                        status = BenchmarkStatus.Cancelled,
                        statusMessage = "Benchmark interrotto dall'utente"
                    )
                }
            } catch (e: Exception) {
                _uiState.value.currentMetrics?.let { partialMetrics ->
                    try {
                        val autoCsvFile = benchmarkEngine.exportMetricsToCsv(getApplication(), partialMetrics)
                        _uiState.update { it.copy(exportedCsvPath = autoCsvFile.absolutePath) }
                    } catch (_: Exception) {}
                }
                _uiState.update {
                    it.copy(
                        status = BenchmarkStatus.Error(e.message ?: "Errore di comunicazione"),
                        statusMessage = "Errore: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopBenchmarkTest() {
        benchmarkEngine.stop()
        benchmarkJob?.cancel()
        _uiState.update {
            it.copy(
                status = BenchmarkStatus.Cancelled,
                statusMessage = "Benchmark interrotto dall'utente"
            )
        }
    }

    fun saveCurrentMetricsForComparison() {
        _uiState.value.currentMetrics?.let { metrics ->
            _uiState.update { it.copy(savedComparisonMetrics = metrics) }
        }
    }

    fun clearComparisonMetrics() {
        _uiState.update { it.copy(savedComparisonMetrics = null) }
    }

    fun exportCurrentMetricsCsv(context: Context): File? {
        val metrics = _uiState.value.currentMetrics ?: return null
        return try {
            val csvFile = benchmarkEngine.exportMetricsToCsv(context, metrics)
            _uiState.update { it.copy(exportedCsvPath = csvFile.absolutePath) }
            csvFile
        } catch (e: Exception) {
            _uiState.update { it.copy(statusMessage = "Errore esportazione CSV: ${e.message}") }
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothManager.stopDiscovery()
        benchmarkEngine.stop()
    }
}
