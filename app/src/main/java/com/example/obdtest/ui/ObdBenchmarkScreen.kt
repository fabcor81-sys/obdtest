package com.example.obdtest.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObdBenchmarkScreen(
    uiState: ObdUiState,
    onRequestPermissions: () -> Unit,
    onRefreshDevices: () -> Unit,
    onStartScanDevices: () -> Unit,
    onStopScanDevices: () -> Unit,
    onSelectDevice: (BluetoothDeviceItem) -> Unit,
    onSelectTransportMode: (TransportMode) -> Unit,
    onSelectProfile: (ObdProfile) -> Unit,
    onSelectRequestCount: (Int) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onSaveComparison: () -> Unit,
    onClearComparison: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OBD2 Benchmark Metrologico",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Misura latenza, percentili (P50/P95/P99) e stabilità",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                actions = {
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aggiorna Dispositivi")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.hasPermissions) {
                PermissionRequiredCard(onRequestPermissions = onRequestPermissions)
            } else if (!uiState.isBluetoothEnabled) {
                BluetoothDisabledCard(onRefreshDevices = onRefreshDevices)
            } else {
                // 1. Selettore Dispositivo Bluetooth
                DeviceSelectorCard(
                    devices = uiState.pairedDevices,
                    selectedDevice = uiState.selectedDevice,
                    isScanning = uiState.isScanning,
                    onSelectDevice = onSelectDevice,
                    onRefreshDevices = onRefreshDevices,
                    onStartScanDevices = onStartScanDevices,
                    onStopScanDevices = onStopScanDevices,
                    isTesting = uiState.status !is BenchmarkStatus.Idle &&
                            uiState.status !is BenchmarkStatus.Completed &&
                            uiState.status !is BenchmarkStatus.Cancelled &&
                            uiState.status !is BenchmarkStatus.Error
                )

                // 2. Controlli Trasporto, Profilo e Numero Campioni
                TestControlCard(
                    status = uiState.status,
                    statusMessage = uiState.statusMessage,
                    selectedTransportMode = uiState.selectedTransportMode,
                    selectedProfile = uiState.selectedProfile,
                    selectedRequestCount = uiState.selectedRequestCount,
                    isDeviceSelected = uiState.selectedDevice != null,
                    onSelectTransportMode = onSelectTransportMode,
                    onSelectProfile = onSelectProfile,
                    onSelectRequestCount = onSelectRequestCount,
                    onStartTest = onStartTest,
                    onStopTest = onStopTest
                )

                // 3. Scheda Risultati
                uiState.currentMetrics?.let { metrics ->
                    CurrentMetricsCard(
                        metrics = metrics,
                        status = uiState.status,
                        onSaveComparison = onSaveComparison,
                        onExportCsv = onExportCsv
                    )
                }

                // 4. Sezione Confronto (es. Norauto vs vLinker)
                uiState.savedComparisonMetrics?.let { savedMetrics ->
                    uiState.currentMetrics?.let { currentMetrics ->
                        ComparisonCard(
                            baselineMetrics = savedMetrics,
                            currentMetrics = currentMetrics,
                            onClearComparison = onClearComparison
                        )
                    }
                }

                // 5. Console Terminal / Logs
                ConsoleLogsCard(
                    logs = uiState.logs,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun PermissionRequiredCard(onRequestPermissions: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Permessi Bluetooth e Posizione Richiesti",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "L'app richiede l'accesso al Bluetooth per scansionare e comunicare con gli adattatori OBD2.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Concedi Permessi")
            }
        }
    }
}

@Composable
fun BluetoothDisabledCard(onRefreshDevices: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Bluetooth Spento",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Attiva il Bluetooth nelle impostazioni del tuo smartphone per proseguire.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRefreshDevices) {
                Text("Ricontrolla")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectorCard(
    devices: List<BluetoothDeviceItem>,
    selectedDevice: BluetoothDeviceItem?,
    isScanning: Boolean,
    onSelectDevice: (BluetoothDeviceItem) -> Unit,
    onRefreshDevices: () -> Unit,
    onStartScanDevices: () -> Unit,
    onStopScanDevices: () -> Unit,
    isTesting: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISPOSITIVO OBD2 BLUETOOTH",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                TextButton(
                    onClick = {
                        if (isScanning) onStopScanDevices() else onStartScanDevices()
                    },
                    enabled = !isTesting
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ferma Scansione", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scansiona Dispositivi", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = expanded && !isTesting,
                onExpandedChange = { if (!isTesting) expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedDevice?.name ?: "Nessun dispositivo selezionato",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isTesting,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded && !isTesting,
                    onDismissRequest = { expanded = false }
                ) {
                    if (devices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Nessun dispositivo trovato") },
                            onClick = {
                                expanded = false
                                onRefreshDevices()
                            }
                        )
                    } else {
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(device.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            device.address,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectDevice(device)
                                    expanded = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TestControlCard(
    status: BenchmarkStatus,
    statusMessage: String,
    selectedTransportMode: TransportMode,
    selectedProfile: ObdProfile,
    selectedRequestCount: Int,
    isDeviceSelected: Boolean,
    onSelectTransportMode: (TransportMode) -> Unit,
    onSelectProfile: (ObdProfile) -> Unit,
    onSelectRequestCount: (Int) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit
) {
    val isTesting = status !is BenchmarkStatus.Idle &&
            status !is BenchmarkStatus.Completed &&
            status !is BenchmarkStatus.Cancelled &&
            status !is BenchmarkStatus.Error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "TIPO DI TRASPORTO BLUETOOTH",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TransportMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedTransportMode == mode,
                        onClick = { if (!isTesting) onSelectTransportMode(mode) },
                        label = { Text(mode.label, fontSize = 10.sp) },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = "PROFILO OBD / PROTOCOLLO",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ObdProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = selectedProfile == profile,
                        onClick = { if (!isTesting) onSelectProfile(profile) },
                        label = { Text(profile.label, fontSize = 11.sp) },
                        enabled = !isTesting,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text(
                text = "NUMERO DI CAMPIONI",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(100, 500, 1000).forEach { count ->
                    FilterChip(
                        selected = selectedRequestCount == count,
                        onClick = { if (!isTesting) onSelectRequestCount(count) },
                        label = { Text("$count campioni") },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            if (status is BenchmarkStatus.RunningTest) {
                val progress = status.completedRequests.toFloat() / status.targetRequests.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isTesting) {
                    Button(
                        onClick = onStopTest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("INTERROMP BENCHMARK")
                    }
                } else {
                    Button(
                        onClick = onStartTest,
                        enabled = isDeviceSelected,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AVVIA BENCHMARK ($selectedRequestCount RICHIESTE)")
                    }
                }
            }
        }
    }
}

@Composable
fun CurrentMetricsCard(
    metrics: BenchmarkMetrics,
    status: BenchmarkStatus,
    onSaveComparison: () -> Unit,
    onExportCsv: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RISULTATI: ${metrics.deviceName} ${if (metrics.isPartialSession) "[PARZIALE]" else ""}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Trasporto: ${metrics.effectiveTransport.label} | Profilo: ${metrics.obdProfile.label}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                Text(
                    text = metrics.elmVersion,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(
                    label = "LATENZA MEDIA (SUCCESSI)",
                    value = if (metrics.successCount > 0) "${String.format(Locale.US, "%.1f", metrics.avgLatencyMs)} ms" else "N/D",
                    subValue = if (metrics.successCount > 0) "Min: ${String.format(Locale.US, "%.1f", metrics.minLatencyMs)}ms | Max: ${String.format(Locale.US, "%.1f", metrics.maxLatencyMs)}ms" else "Nessun successo"
                )

                MetricItem(
                    label = "TASSO DI SUCCESSO",
                    value = "${String.format(Locale.US, "%.1f", metrics.successRatePercent)}%",
                    subValue = "SUCCESS: ${metrics.successCount}/${metrics.totalCommandsSent} (${String.format(Locale.US, "%.1f", metrics.commandsPerSecond)} PID/s)"
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItemSmall(
                    label = "P50 (MEDIANA)",
                    value = if (metrics.successCount > 0) "${String.format(Locale.US, "%.1f", metrics.p50Ms)} ms" else "N/D"
                )
                MetricItemSmall(
                    label = "P95",
                    value = if (metrics.successCount > 0) "${String.format(Locale.US, "%.1f", metrics.p95Ms)} ms" else "N/D"
                )
                MetricItemSmall(
                    label = "P99",
                    value = if (metrics.successCount > 0) "${String.format(Locale.US, "%.1f", metrics.p99Ms)} ms" else "N/D"
                )
                MetricItemSmall(
                    label = "DEV. STD (σ)",
                    value = if (metrics.successCount > 1) "${String.format(Locale.US, "%.1f", metrics.stdDevMs)} ms" else "N/D"
                )
                MetricItemSmall(
                    label = "JITTER",
                    value = if (metrics.successCount > 1) "${String.format(Locale.US, "%.1f", metrics.jitterMs)} ms" else "N/D"
                )
            }

            HorizontalDivider()

            Text(
                text = "CLASSIFICAZIONE RISPOSTE:",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "OK: ${metrics.successCount}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2E7D32)
                )
                Text(
                    text = "ECU ERR: ${metrics.ecuErrorCount}",
                    fontSize = 11.sp,
                    color = if (metrics.ecuErrorCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "ADAPTER ERR: ${metrics.adapterErrorCount}",
                    fontSize = 11.sp,
                    color = if (metrics.adapterErrorCount > 0) Color(0xFFE65100) else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "TIMEOUT: ${metrics.timeoutCount}",
                    fontSize = 11.sp,
                    color = if (metrics.timeoutCount > 0) Color(0xFFC62828) else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (status is BenchmarkStatus.Completed || status is BenchmarkStatus.Cancelled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExportCsv,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Esporta CSV", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onSaveComparison,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Salva Riferimento", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, subValue: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = subValue,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun MetricItemSmall(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
fun ComparisonCard(
    baselineMetrics: BenchmarkMetrics,
    currentMetrics: BenchmarkMetrics,
    onClearComparison: () -> Unit
) {
    val isDifferentConfig = baselineMetrics.obdProfile != currentMetrics.obdProfile ||
            baselineMetrics.effectiveTransport != currentMetrics.effectiveTransport

    val p50Diff = currentMetrics.p50Ms - baselineMetrics.p50Ms
    val p50Percent = if (baselineMetrics.p50Ms > 0) {
        ((p50Diff / baselineMetrics.p50Ms) * 100).toInt()
    } else 0

    val pidsDiff = currentMetrics.commandsPerSecond - baselineMetrics.commandsPerSecond
    val pidsPercent = if (baselineMetrics.commandsPerSecond > 0) {
        ((pidsDiff / baselineMetrics.commandsPerSecond) * 100).toInt()
    } else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONFRONTO PRESTAZIONI",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )

                TextButton(onClick = onClearComparison) {
                    Text("Cancella", fontSize = 12.sp)
                }
            }

            if (isDifferentConfig) {
                Text(
                    text = "⚠️ Avviso: I test usano configurazioni o trasporti differenti (${baselineMetrics.effectiveTransport.label}/${baselineMetrics.obdProfile.label} vs ${currentMetrics.effectiveTransport.label}/${currentMetrics.obdProfile.label})",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RIFERIMENTO (${baselineMetrics.deviceName})", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("P50: ${String.format(Locale.US, "%.1f", baselineMetrics.p50Ms)} ms", fontSize = 11.sp)
                    Text("Successo: ${String.format(Locale.US, "%.1f", baselineMetrics.successRatePercent)}%", fontSize = 10.sp)
                    Text("${String.format(Locale.US, "%.1f", baselineMetrics.commandsPerSecond)} PID/s", fontWeight = FontWeight.Bold)
                }

                Text("VS", fontWeight = FontWeight.Black, fontSize = 16.sp)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ATTUALE (${currentMetrics.deviceName})", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("P50: ${String.format(Locale.US, "%.1f", currentMetrics.p50Ms)} ms", fontSize = 11.sp)
                    Text("Successo: ${String.format(Locale.US, "%.1f", currentMetrics.successRatePercent)}%", fontSize = 10.sp)
                    Text("${String.format(Locale.US, "%.1f", currentMetrics.commandsPerSecond)} PID/s", fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                val p50Text = if (p50Diff <= 0) {
                    "${kotlin.math.abs(p50Percent)}% più veloce (-${String.format(Locale.US, "%.1f", kotlin.math.abs(p50Diff))}ms)"
                } else {
                    "${p50Percent}% più lento (+${String.format(Locale.US, "%.1f", p50Diff)}ms)"
                }

                val pidsText = if (pidsDiff >= 0) {
                    "+${pidsPercent}% PID/s (+${String.format(Locale.US, "%.1f", pidsDiff)})"
                } else {
                    "${pidsPercent}% PID/s (${String.format(Locale.US, "%.1f", pidsDiff)})"
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Diff. P50 Mediana", fontSize = 10.sp)
                    Text(
                        text = p50Text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (p50Diff <= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Diff. Velocità", fontSize = 10.sp)
                    Text(
                        text = pidsText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pidsDiff >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }
    }
}

@Composable
fun ConsoleLogsCard(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CONSOLE LOGS (${logs.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { logLine ->
                        val color = when {
                            logLine.contains("ERRORE", ignoreCase = true) || logLine.contains("ERR:", ignoreCase = true) -> Color(0xFFFF5252)
                            logLine.contains("SUCCESS", ignoreCase = true) || logLine.contains("riuscita", ignoreCase = true) -> Color(0xFF69F0AE)
                            logLine.contains("[INIT]", ignoreCase = true) -> Color(0xFFFFD740)
                            else -> Color(0xFFE0E0E0)
                        }

                        Text(
                            text = logLine,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}
