package com.example.obdtest

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.obdtest.benchmark.ObdBenchmarkEngine
import com.example.obdtest.bluetooth.BluetoothObdManager
import com.example.obdtest.ui.BenchmarkStatus
import com.example.obdtest.ui.ObdBenchmarkScreen
import com.example.obdtest.ui.ObdUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { BluetoothObdManager(this) }
    private val benchmarkEngine by lazy { ObdBenchmarkEngine() }

    private var benchmarkJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            refreshBluetoothDevices()
        } else {
            Toast.makeText(
                this,
                "Alcuni permessi Bluetooth o di Posizione sono stati negati",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var uiState by remember { mutableStateOf(ObdUiState()) }

                    // Monitoraggio log in tempo reale dall'engine
                    val logs by benchmarkEngine.logs.collectAsState()

                    LaunchedEffect(logs) {
                        uiState = uiState.copy(logs = logs)
                    }

                    LaunchedEffect(Unit) {
                        checkPermissionsAndLoadDevices { newState ->
                            uiState = newState
                        }
                    }

                    ObdBenchmarkScreen(
                        uiState = uiState,
                        onRequestPermissions = {
                            permissionLauncher.launch(BluetoothObdManager.getRequiredPermissions())
                        },
                        onRefreshDevices = {
                            bluetoothManager.stopDiscovery()
                            checkPermissionsAndLoadDevices { newState ->
                                uiState = newState
                            }
                        },
                        onStartScanDevices = {
                            if (!bluetoothManager.hasRequiredPermissions()) {
                                permissionLauncher.launch(BluetoothObdManager.getRequiredPermissions())
                                return@ObdBenchmarkScreen
                            }

                            uiState = uiState.copy(
                                isScanning = true,
                                statusMessage = "Scansione dispositivi Bluetooth nelle vicinanze in corso..."
                            )

                            bluetoothManager.startDiscovery(
                                onDeviceFound = { foundDevice ->
                                    val currentList = uiState.pairedDevices
                                    if (currentList.none { it.address == foundDevice.address }) {
                                        val updatedList = currentList + foundDevice
                                        uiState = uiState.copy(
                                            pairedDevices = updatedList,
                                            selectedDevice = uiState.selectedDevice ?: foundDevice
                                        )
                                    }
                                },
                                onDiscoveryFinished = {
                                    uiState = uiState.copy(
                                        isScanning = false,
                                        statusMessage = "Scansione completata. ${uiState.pairedDevices.size} dispositivi disponibili."
                                    )
                                }
                            )
                        },
                        onSelectDevice = { device ->
                            uiState = uiState.copy(selectedDevice = device)
                        },
                        onSelectRequestCount = { count ->
                            uiState = uiState.copy(selectedRequestCount = count)
                        },
                        onStartTest = {
                            bluetoothManager.stopDiscovery()
                            uiState = uiState.copy(isScanning = false)

                            val selectedDeviceItem = uiState.selectedDevice
                            if (selectedDeviceItem == null) {
                                Toast.makeText(this, "Seleziona un dispositivo", Toast.LENGTH_SHORT).show()
                                return@ObdBenchmarkScreen
                            }

                            val bluetoothDevice = bluetoothManager.getDeviceByAddress(selectedDeviceItem.address)
                            if (bluetoothDevice == null) {
                                Toast.makeText(this, "Impossibile trovare il dispositivo Bluetooth", Toast.LENGTH_SHORT).show()
                                return@ObdBenchmarkScreen
                            }

                            benchmarkEngine.clearLogs()
                            uiState = uiState.copy(
                                status = BenchmarkStatus.Connecting,
                                statusMessage = "Inizio benchmark metrologico (${uiState.selectedRequestCount} richieste)..."
                            )

                            benchmarkJob?.cancel()
                            benchmarkJob = lifecycleScope.launch {
                                try {
                                    val metrics = benchmarkEngine.runBenchmark(
                                        context = this@MainActivity,
                                        device = bluetoothDevice,
                                        isBle = selectedDeviceItem.isBle,
                                        targetRequestCount = uiState.selectedRequestCount,
                                        onStatusUpdate = { status, msg ->
                                            uiState = uiState.copy(
                                                status = status,
                                                statusMessage = msg
                                            )
                                        },
                                        onMetricsUpdate = { metricsUpdate ->
                                            uiState = uiState.copy(currentMetrics = metricsUpdate)
                                        }
                                    )

                                    // Salvataggio automatico del report CSV al termine del test
                                    try {
                                        val autoCsvFile = benchmarkEngine.exportMetricsToCsv(this@MainActivity, metrics)
                                        uiState = uiState.copy(
                                            currentMetrics = metrics,
                                            exportedCsvPath = autoCsvFile.absolutePath,
                                            status = BenchmarkStatus.Completed,
                                            statusMessage = "Benchmark completato! CSV salvato: ${autoCsvFile.name}"
                                        )
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Report CSV salvato automaticamente:\n${autoCsvFile.name}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (_: Exception) {
                                        uiState = uiState.copy(
                                            currentMetrics = metrics,
                                            status = BenchmarkStatus.Completed,
                                            statusMessage = "Benchmark completato con successo!"
                                        )
                                    }
                                } catch (e: Exception) {
                                    uiState = uiState.copy(
                                        status = BenchmarkStatus.Error(e.message ?: "Errore"),
                                        statusMessage = "Errore: ${e.message}"
                                    )
                                }
                            }
                        },
                        onStopTest = {
                            benchmarkEngine.stop()
                            benchmarkJob?.cancel()
                            uiState = uiState.copy(
                                status = BenchmarkStatus.Idle,
                                statusMessage = "Benchmark interrotto dall'utente"
                            )
                        },
                        onSaveComparison = {
                            uiState.currentMetrics?.let { metrics ->
                                uiState = uiState.copy(savedComparisonMetrics = metrics)
                                Toast.makeText(
                                    this,
                                    "Risultato salvato come riferimento per il confronto!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onClearComparison = {
                            uiState = uiState.copy(savedComparisonMetrics = null)
                        },
                        onExportCsv = {
                            val metrics = uiState.currentMetrics
                            if (metrics == null || metrics.rawSamples.isEmpty()) {
                                Toast.makeText(this, "Nessun dato di benchmark da esportare", Toast.LENGTH_SHORT).show()
                                return@ObdBenchmarkScreen
                            }

                            try {
                                val csvFile = benchmarkEngine.exportMetricsToCsv(this, metrics)
                                uiState = uiState.copy(exportedCsvPath = csvFile.absolutePath)

                                val uri = FileProvider.getUriForFile(
                                    this,
                                    "$packageName.fileprovider",
                                    csvFile
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_SUBJECT, "OBD2 Benchmark - ${metrics.deviceName}")
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                                startActivity(Intent.createChooser(shareIntent, "Condividi Report CSV Benchmark"))
                            } catch (e: Exception) {
                                Toast.makeText(this, "Errore durante esportazione CSV: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndLoadDevices(updateState: (ObdUiState) -> Unit) {
        val hasPerms = bluetoothManager.hasRequiredPermissions()
        if (!hasPerms) {
            updateState(
                ObdUiState(
                    hasPermissions = false,
                    statusMessage = "Richiesti permessi Bluetooth"
                )
            )
            return
        }

        val devices = bluetoothManager.getPairedDevices()
        updateState(
            ObdUiState(
                hasPermissions = true,
                pairedDevices = devices,
                selectedDevice = devices.firstOrNull(),
                statusMessage = if (devices.isEmpty()) {
                    "Nessun dispositivo OBD2 associato trovato. Usa 'Scansiona Dispositivi' per cercarne nelle vicinanze."
                } else {
                    "Seleziona un dispositivo e avvia il benchmark metrologico"
                }
            )
        )
    }

    private fun refreshBluetoothDevices() {
        checkPermissionsAndLoadDevices { _ -> }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothManager.stopDiscovery()
        benchmarkEngine.stop()
    }
}
