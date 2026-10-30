package com.example.obdtest.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.obdtest.benchmark.ObdBenchmarkEngine
import com.example.obdtest.bluetooth.BluetoothObdManager
import com.example.obdtest.usb.UsbKLineManager
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
    private val usbKLineManager by lazy { UsbKLineManager(application.applicationContext) }
    private val benchmarkEngine by lazy { ObdBenchmarkEngine() }

    private val _uiState = MutableStateFlow(ObdUiState())
    val uiState: StateFlow<ObdUiState> = _uiState.asStateFlow()

    private var benchmarkJob: Job? = null

    init {
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

        val btPaired = if (hasPerms && btEnabled) bluetoothManager.getPairedDevices() else emptyList()
        val usbDevices = usbKLineManager.getConnectedUsbDevices()

        val allDevices = usbDevices + btPaired

        _uiState.update { currentState ->
            if (BenchmarkUiPolicy.isBusy(currentState.status)) {
                // A resume/USB hotplug refresh must not silently change the
                // target device or overwrite a running benchmark's status.
                return@update currentState.copy(
                    pairedDevices = allDevices,
                    hasPermissions = hasPerms,
                    isBluetoothEnabled = btEnabled
                )
            }
            val currentSelected = currentState.selectedDevice
            val stillPresent = allDevices.find { it.address == currentSelected?.address }
            val newlySelected = stillPresent ?: allDevices.firstOrNull()

            val selectedMode = if (newlySelected?.isUsb == true) {
                TransportMode.USB_KLINE
            } else if (currentState.selectedTransportMode == TransportMode.USB_KLINE) {
                TransportMode.AUTO
            } else {
                currentState.selectedTransportMode
            }

            val statusMsg = when {
                allDevices.isNotEmpty() -> "Dispositivo pronto. Seleziona e avvia il benchmark metrologico."
                !hasPerms -> "Richiesti permessi Bluetooth per scansionare adattatori wireless"
                !btEnabled -> "Bluetooth disattivato sullo smartphone. Collega cavo USB o attiva BT."
                else -> "Nessun dispositivo Bluetooth o USB trovato. Collega il cavo KKL USB OTG o usa 'Scansiona'."
            }

            currentState.copy(
                hasPermissions = hasPerms,
                isBluetoothEnabled = btEnabled,
                pairedDevices = allDevices,
                selectedDevice = newlySelected,
                selectedTransportMode = selectedMode,
                selectedProfile = BenchmarkUiPolicy.correctedProfile(newlySelected, currentState.selectedProfile),
                isAbsPumpOffAuthorized = false,
                statusMessage = statusMsg
            )
        }
    }

    fun startDeviceScan() {
        if (BenchmarkUiPolicy.isBusy(_uiState.value.status)) return
        val usbDevices = usbKLineManager.getConnectedUsbDevices()

        if (!_uiState.value.hasPermissions || !_uiState.value.isBluetoothEnabled) {
            _uiState.update { currentState ->
                val allDevs = (usbDevices + currentState.pairedDevices.filter { !it.isUsb }).distinctBy { it.address }
                currentState.copy(
                    pairedDevices = allDevs,
                    selectedDevice = currentState.selectedDevice ?: allDevs.firstOrNull(),
                    statusMessage = "Trovati ${usbDevices.size} dispositivi USB. Per Bluetooth abilita permessi e Bluetooth."
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isScanning = true,
                statusMessage = "Scansione dispositivi Bluetooth Classic, BLE e USB FTDI in corso..."
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
                _uiState.update { currentState ->
                    val combinedList = (usbDevices + currentState.pairedDevices).distinctBy { it.address }
                    currentState.copy(
                        isScanning = false,
                        pairedDevices = combinedList,
                        statusMessage = "Scansione completata. ${combinedList.size} dispositivi disponibili."
                    )
                }
            },
            onError = { errMsg ->
                _uiState.update { currentState ->
                    val combinedList = (usbDevices + currentState.pairedDevices).distinctBy { it.address }
                    currentState.copy(
                        isScanning = false,
                        pairedDevices = combinedList,
                        statusMessage = "Scansione completata con errori: $errMsg"
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
        if (BenchmarkUiPolicy.isBusy(_uiState.value.status)) return
        val targetTransport = if (device.isUsb) {
            TransportMode.USB_KLINE
        } else if (_uiState.value.selectedTransportMode == TransportMode.USB_KLINE) {
            TransportMode.AUTO
        } else {
            _uiState.value.selectedTransportMode
        }

        _uiState.update {
            it.copy(
                selectedDevice = device,
                selectedTransportMode = targetTransport,
                selectedProfile = BenchmarkUiPolicy.correctedProfile(device, it.selectedProfile),
                isAbsPumpOffAuthorized = false
            )
        }
    }

    fun selectTransportMode(transportMode: TransportMode) {
        _uiState.update {
            if (BenchmarkUiPolicy.isBusy(it.status)) it
            else if (it.selectedDevice?.isUsb == true) it.copy(selectedTransportMode = TransportMode.USB_KLINE)
            else if (transportMode == TransportMode.USB_KLINE) it
            else it.copy(selectedTransportMode = transportMode)
        }
    }

    fun selectObdProfile(profile: ObdProfile) {
        _uiState.update {
            if (BenchmarkUiPolicy.isBusy(it.status) ||
                !BenchmarkUiPolicy.supportsProfile(it.selectedDevice, profile)) it
            else it.copy(
                selectedProfile = profile,
                customCommand = profile.defaultCommand,
                isAbsPumpOffAuthorized = false
            )
        }
    }

    fun setCustomCommand(cmd: String) {
        _uiState.update { it.copy(customCommand = cmd) }
    }

    fun selectRequestCount(count: Int) {
        if (count !in listOf(25, 50, 100, 250)) return
        _uiState.update {
            if (BenchmarkUiPolicy.isBusy(it.status)) it
            else it.copy(selectedRequestCount = count)
        }
    }

    // Optional one-shot authorization for the Lotus-only Pump OFF test.
    fun toggleAbsPumpOffAuthorization(authorized: Boolean) {
        _uiState.update {
            if (BenchmarkUiPolicy.isBusy(it.status) ||
                it.selectedProfile != ObdProfile.LOTUS_ABS ||
                it.selectedDevice?.isUsb != false) it.copy(isAbsPumpOffAuthorized = false)
            else it.copy(isAbsPumpOffAuthorized = authorized)
        }
    }

    fun startBenchmarkTest() {
        val current = _uiState.value
        if (BenchmarkUiPolicy.isBusy(current.status)) return
        val selectedDeviceItem = current.selectedDevice ?: return
        if (!BenchmarkUiPolicy.canRun(selectedDeviceItem, current.hasPermissions, current.isBluetoothEnabled) ||
            !BenchmarkUiPolicy.supportsProfile(selectedDeviceItem, current.selectedProfile)
        ) {
            _uiState.update {
                it.copy(
                    status = BenchmarkStatus.Error("Selected device, profile or Bluetooth permissions are incompatible."),
                    statusMessage = "Check your device and benchmark profile before starting."
                )
            }
            return
        }
        bluetoothManager.stopDiscovery()
        benchmarkEngine.clearLogs()
        val authorizePumpOffThisRun = current.selectedProfile == ObdProfile.LOTUS_ABS &&
            !selectedDeviceItem.isUsb && current.isAbsPumpOffAuthorized
        _uiState.update { it.copy(isAbsPumpOffAuthorized = false) }

        if (selectedDeviceItem.isUsb) {
            val driver = usbKLineManager.findUsbDriverForAddress(selectedDeviceItem.address)
            if (driver == null) {
                _uiState.update {
                    it.copy(
                        status = BenchmarkStatus.Error("Dispositivo USB FTDI non trovato tra i dispositivi collegati."),
                        statusMessage = "Errore: Cavo USB FTDI non rilevato."
                    )
                }
                return
            }

            if (!usbKLineManager.hasPermission(driver)) {
                _uiState.update {
                    it.copy(
                        status = BenchmarkStatus.Connecting,
                        statusMessage = "Richiesta autorizzazione USB per ${selectedDeviceItem.name}... Accetta il popup sullo schermo del telefono."
                    )
                }

                usbKLineManager.requestPermission(driver) { granted ->
                    // A permission sheet can remain open after STOP was pressed.
                    // Never start a cancelled benchmark from that late callback.
                    if (_uiState.value.status !is BenchmarkStatus.Connecting ||
                        _uiState.value.selectedDevice?.address != selectedDeviceItem.address
                    ) {
                        // Stale permission result: ignore.
                    } else if (granted) {
                        launchUsbBenchmarkJob(selectedDeviceItem)
                    } else {
                        _uiState.update {
                            it.copy(
                                status = BenchmarkStatus.Error("USB permission denied."),
                                statusMessage = "USB permission was not granted for ${selectedDeviceItem.name}."
                            )
                        }
                    }
                }
                return
            }

            launchUsbBenchmarkJob(selectedDeviceItem)
        } else {
            launchBluetoothBenchmarkJob(selectedDeviceItem, authorizePumpOffThisRun)
        }
    }

    private fun launchUsbBenchmarkJob(selectedDeviceItem: BluetoothDeviceItem) {
        _uiState.update {
            it.copy(
                isScanning = false,
                currentMetrics = null,
                exportedCsvPath = null,
                status = BenchmarkStatus.Connecting,
                statusMessage = "Inizio benchmark USB K-Line [${selectedDeviceItem.name}] (${it.selectedRequestCount} campioni)..."
            )
        }

        val previousJob = benchmarkJob
        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            previousJob?.cancel()
            previousJob?.join()

            try {
                val currentConfig = _uiState.value

                val metrics = benchmarkEngine.runUsbKLineBenchmark(
                    context = getApplication(),
                    usbDeviceItem = selectedDeviceItem,
                    obdProfile = currentConfig.selectedProfile,
                    targetRequestCount = currentConfig.selectedRequestCount,
                    timeoutMs = ObdBenchmarkEngine.DEFAULT_TIMEOUT_MS,
                    onStatusUpdate = { status, msg ->
                        _uiState.update { it.copy(status = status, statusMessage = msg) }
                    },
                    onMetricsUpdate = { metricsUpdate ->
                        _uiState.update { it.copy(currentMetrics = metricsUpdate) }
                    }
                )

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
                        status = BenchmarkStatus.Error(e.message ?: "Errore di comunicazione USB"),
                        statusMessage = "Errore USB: ${e.message}"
                    )
                }
            }
        }
    }

    private fun launchBluetoothBenchmarkJob(
        selectedDeviceItem: BluetoothDeviceItem,
        allowPumpOffThisRun: Boolean
    ) {
        _uiState.update {
            it.copy(
                isScanning = false,
                currentMetrics = null,
                exportedCsvPath = null,
                status = BenchmarkStatus.Connecting,
                statusMessage = "Inizio benchmark Bluetooth [${selectedDeviceItem.name}] (${it.selectedRequestCount} campioni)..."
            )
        }

        val previousJob = benchmarkJob
        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            previousJob?.cancel()
            previousJob?.join()

            try {
                val currentConfig = _uiState.value
                val bluetoothDevice = bluetoothManager.getDeviceByAddress(selectedDeviceItem.address)
                    ?: throw IllegalStateException("Dispositivo Bluetooth ${selectedDeviceItem.name} non trovato.")

                val metrics = benchmarkEngine.runBenchmark(
                    context = getApplication(),
                    device = bluetoothDevice,
                    transportMode = currentConfig.selectedTransportMode,
                    obdProfile = currentConfig.selectedProfile,
                    targetRequestCount = currentConfig.selectedRequestCount,
                    isAbsPumpOffAuthorized = allowPumpOffThisRun,
                    timeoutMs = ObdBenchmarkEngine.DEFAULT_TIMEOUT_MS,
                    onStatusUpdate = { status, msg ->
                        _uiState.update { it.copy(status = status, statusMessage = msg) }
                    },
                    onMetricsUpdate = { metricsUpdate ->
                        _uiState.update { it.copy(currentMetrics = metricsUpdate) }
                    }
                )

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
        if (!BenchmarkUiPolicy.isBusy(_uiState.value.status)) return
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
        if (BenchmarkUiPolicy.isBusy(_uiState.value.status)) return
        _uiState.value.currentMetrics?.let { metrics ->
            if (metrics.isPartialSession || metrics.successCount == 0) {
                _uiState.update {
                    it.copy(statusMessage = "Only completed benchmarks with verified responses can be saved as a baseline.")
                }
            } else {
                _uiState.update {
                    it.copy(savedComparisonMetrics = metrics, statusMessage = "Baseline saved: ${metrics.deviceName}")
                }
            }
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
        usbKLineManager.unregisterReceiver()
        benchmarkEngine.stop()
    }
}
