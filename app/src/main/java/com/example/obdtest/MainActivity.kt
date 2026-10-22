package com.example.obdtest

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.example.obdtest.bluetooth.BluetoothObdManager
import com.example.obdtest.ui.ObdBenchmarkScreen
import com.example.obdtest.ui.ObdViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val anyGranted = permissions.values.any { it }
        if (anyGranted) {
            viewModel.refreshBluetoothState()
        } else {
            Toast.makeText(
                this,
                "Permessi Bluetooth o di Posizione negati. Impossibile scansionare o connettersi.",
                Toast.LENGTH_LONG
            ).show()
            viewModel.refreshBluetoothState()
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
                    val uiState by viewModel.uiState.collectAsState()

                    ObdBenchmarkScreen(
                        uiState = uiState,
                        onRequestPermissions = {
                            permissionLauncher.launch(BluetoothObdManager.getRequiredPermissions())
                        },
                        onRefreshDevices = {
                            viewModel.refreshBluetoothState()
                        },
                        onStartScanDevices = {
                            if (!uiState.hasPermissions) {
                                permissionLauncher.launch(BluetoothObdManager.getRequiredPermissions())
                            } else {
                                viewModel.startDeviceScan()
                            }
                        },
                        onStopScanDevices = {
                            viewModel.stopDeviceScan()
                        },
                        onSelectDevice = { device ->
                            viewModel.selectDevice(device)
                        },
                        onSelectTransportMode = { transportMode ->
                            viewModel.selectTransportMode(transportMode)
                        },
                        onSelectProfile = { profile ->
                            viewModel.selectObdProfile(profile)
                        },
                        onSelectRequestCount = { count ->
                            viewModel.selectRequestCount(count)
                        },
                        onStartTest = {
                            viewModel.startBenchmarkTest()
                        },
                        onStopTest = {
                            viewModel.stopBenchmarkTest()
                        },
                        onSaveComparison = {
                            viewModel.saveCurrentMetricsForComparison()
                            Toast.makeText(this, "Risultato salvato come riferimento per il confronto!", Toast.LENGTH_SHORT).show()
                        },
                        onClearComparison = {
                            viewModel.clearComparisonMetrics()
                        },
                        onExportCsv = {
                            val file = viewModel.exportCurrentMetricsCsv(this)
                            if (file != null) {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        this,
                                        "$packageName.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_SUBJECT, "OBD2 Benchmark - ${uiState.currentMetrics?.deviceName}")
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(shareIntent, "Condividi Report CSV Benchmark"))
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Errore condivisione CSV: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(this, "Nessun dato di benchmark da esportare", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBluetoothState()
    }
}
