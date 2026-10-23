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

// Palette Racing Giallo e Nero
val RacingBackground = Color(0xFF0B0B0B)
val RacingSurface = Color(0xFF181818)
val RacingSurfaceSecondary = Color(0xFF242424)
val RacingYellow = Color(0xFFFFD600)
val RacingText = Color(0xFFF5F5F5)
val RacingTextSecondary = Color(0xFFBDBDBD)
val RacingError = Color(0xFFE53935)
val RacingSuccess = Color(0xFF00E676)

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
    onToggleAbsPumpOffAuthorization: (Boolean) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onSaveComparison: () -> Unit,
    onClearComparison: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        containerColor = RacingBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OBD2 Benchmark Metrologico",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = RacingYellow
                        )
                        Text(
                            text = "Motorsport Performance & Latenza Lotus EBC430",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = RacingTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RacingSurface
                ),
                actions = {
                    IconButton(onClick = onRefreshDevices) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Aggiorna Dispositivi",
                            tint = RacingYellow
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(RacingBackground)
                .padding(innerPadding)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!uiState.hasPermissions) {
                PermissionRequiredCard(onRequestPermissions = onRequestPermissions)
            } else if (!uiState.isBluetoothEnabled) {
                BluetoothDisabledCard(onRefreshDevices = onRefreshDevices)
            } else {
                // 1. Selettore Dispositivo Bluetooth Compattato e Perfettamente Leggibile
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

                // 2. Controlli Trasporto, Profilo, Flag Pompa ABS OFF e Pulsante Unico Avvio
                TestControlCard(
                    status = uiState.status,
                    statusMessage = uiState.statusMessage,
                    selectedTransportMode = uiState.selectedTransportMode,
                    selectedProfile = uiState.selectedProfile,
                    isAbsPumpOffAuthorized = uiState.isAbsPumpOffAuthorized,
                    isDeviceSelected = uiState.selectedDevice != null,
                    onSelectTransportMode = onSelectTransportMode,
                    onSelectProfile = onSelectProfile,
                    onToggleAbsPumpOffAuthorization = onToggleAbsPumpOffAuthorization,
                    onStartTest = onStartTest,
                    onStopTest = onStopTest
                )

                // 3. Scheda Risultati & Riepilogo Sequenza Test
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
            containerColor = RacingSurfaceSecondary
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = RacingError
            )
            Text(
                text = "Permessi Bluetooth e Posizione Richiesti",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = RacingText
            )
            Text(
                text = "L'app richiede l'accesso al Bluetooth per scansionare e comunicare con gli adattatori OBD2.",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = RacingTextSecondary
            )
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RacingYellow,
                    contentColor = RacingBackground
                )
            ) {
                Text("Concedi Permessi", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BluetoothDisabledCard(onRefreshDevices: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = RacingSurfaceSecondary
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = RacingYellow
            )
            Text(
                text = "Bluetooth Spento",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = RacingText
            )
            Text(
                text = "Attiva il Bluetooth nelle impostazioni del tuo smartphone per proseguire.",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = RacingTextSecondary
            )
            Button(
                onClick = onRefreshDevices,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RacingYellow,
                    contentColor = RacingBackground
                )
            ) {
                Text("Ricontrolla", fontWeight = FontWeight.Bold)
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
            containerColor = RacingSurface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISPOSITIVO OBD2 BLUETOOTH",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = RacingYellow
                )

                TextButton(
                    onClick = {
                        if (isScanning) onStopScanDevices() else onStartScanDevices()
                    },
                    enabled = !isTesting,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = RacingError
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ferma Scansione", fontSize = 10.sp, color = RacingError)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = RacingYellow
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scansiona", fontSize = 10.sp, color = RacingYellow)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = expanded && !isTesting,
                onExpandedChange = { if (!isTesting) expanded = !expanded }
            ) {
                Surface(
                    onClick = { if (!isTesting) expanded = !expanded },
                    shape = RoundedCornerShape(8.dp),
                    color = RacingSurfaceSecondary,
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = RacingYellow
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = selectedDevice?.name ?: "Nessun dispositivo selezionato",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = RacingText
                                )
                                if (selectedDevice != null) {
                                    Text(
                                        text = "MAC: ${selectedDevice.address}",
                                        fontSize = 10.sp,
                                        color = RacingTextSecondary
                                    )
                                }
                            }
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                }

                ExposedDropdownMenu(
                    expanded = expanded && !isTesting,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(RacingSurfaceSecondary)
                ) {
                    if (devices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Nessun dispositivo trovato", color = RacingTextSecondary, fontSize = 11.sp) },
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
                                        Text(device.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = RacingText)
                                        Text(
                                            device.address,
                                            fontSize = 10.sp,
                                            color = RacingTextSecondary
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectDevice(device)
                                    expanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.BluetoothConnected,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = RacingYellow
                                    )
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
    isAbsPumpOffAuthorized: Boolean,
    isDeviceSelected: Boolean,
    onSelectTransportMode: (TransportMode) -> Unit,
    onSelectProfile: (ObdProfile) -> Unit,
    onToggleAbsPumpOffAuthorization: (Boolean) -> Unit,
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
            containerColor = RacingSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Riga 1: Tipo Trasporto Bluetooth Compattato (AUTO, BT, BT BLE)
            Text(
                text = "TRASPORTO BLUETOOTH",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = RacingYellow
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TransportMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedTransportMode == mode,
                        onClick = { if (!isTesting) onSelectTransportMode(mode) },
                        label = { Text(mode.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (selectedTransportMode == mode) RacingBackground else RacingText) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RacingYellow,
                            containerColor = RacingSurfaceSecondary
                        ),
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Riga 2: Profilo OBD / Protocollo Affiancati Orizzontalmente
            Text(
                text = "PROFILO OBD / PROTOCOLLO",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = RacingYellow
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ObdProfile.entries.forEach { profile ->
                    val shortLabel = when (profile) {
                        ObdProfile.AT_ONLY -> "AT-Only (ATI)"
                        ObdProfile.GENERIC_AUTO -> "OBD Standard"
                        ObdProfile.LOTUS_ABS -> "Lotus ABS"
                    }
                    FilterChip(
                        selected = selectedProfile == profile,
                        onClick = { if (!isTesting) onSelectProfile(profile) },
                        label = { Text(shortLabel, fontSize = 9.sp, color = if (selectedProfile == profile) RacingBackground else RacingText) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RacingYellow,
                            containerColor = RacingSurfaceSecondary
                        ),
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Flag/Switch per abilitare il comando ABS Pompa OFF (30 02 07 00)
            if (selectedProfile == ObdProfile.LOTUS_ABS) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = RacingSurfaceSecondary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Abilita Test 6: Pompa ABS OFF (30 02 07 00)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAbsPumpOffAuthorized) RacingYellow else RacingTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isAbsPumpOffAuthorized,
                            onCheckedChange = { if (!isTesting) onToggleAbsPumpOffAuthorization(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = RacingYellow,
                                checkedTrackColor = RacingSurfaceSecondary
                            ),
                            enabled = !isTesting,
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = RacingSurfaceSecondary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = RacingText,
                    modifier = Modifier.weight(1f)
                )

                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = RacingYellow
                    )
                }
            }

            if (status is BenchmarkStatus.RunningTestSequence) {
                val progress = status.completedRequests.toFloat() / status.targetRequests.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    color = RacingYellow,
                    trackColor = RacingSurfaceSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // UNICO GRANDE PULSANTE D'AZIONE COMPATTO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isTesting) {
                    Button(
                        onClick = onStopTest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RacingError,
                            contentColor = RacingText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ARRESTA BENCHMARK (STOP)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onStartTest,
                        enabled = isDeviceSelected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RacingYellow,
                            contentColor = RacingBackground,
                            disabledContainerColor = RacingSurfaceSecondary,
                            disabledContentColor = RacingTextSecondary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AVVIA BENCHMARK METROLOGICO", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            containerColor = RacingSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RISULTATI SEQUENZA TEST: ${metrics.deviceName} ${if (metrics.isPartialSession) "[PARZIALE]" else ""}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RacingYellow
                    )
                    Text(
                        text = "Trasporto: ${metrics.effectiveTransport.label} | Profilo: ${metrics.obdProfile.label}",
                        fontSize = 9.sp,
                        color = RacingTextSecondary
                    )
                }

                Text(
                    text = metrics.elmVersion,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RacingYellow
                )
            }

            HorizontalDivider(color = RacingSurfaceSecondary)

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
                    subValue = "POS: ${metrics.successCount}/${metrics.totalCommandsSent} (${String.format(Locale.US, "%.1f", metrics.commandsPerSecond)} PID/s)"
                )
            }

            HorizontalDivider(color = RacingSurfaceSecondary)

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

            if (metrics.testSummaries.isNotEmpty()) {
                HorizontalDivider(color = RacingSurfaceSecondary)
                Text(
                    text = "DETTAGLIO RISULTATI PER COMANDO:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = RacingYellow
                )

                metrics.testSummaries.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${s.testName} (${s.commandSent})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RacingText,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (s.isSingleExecution) s.singleExecutionResult else "P50: ${String.format(Locale.US, "%.1f", s.p50Ms)}ms | POS: ${s.positiveResponses}/${s.samplesExecuted}",
                            fontSize = 10.sp,
                            color = if (s.positiveResponses > 0) RacingSuccess else RacingError
                        )
                    }
                }
            }

            if (status is BenchmarkStatus.Completed || status is BenchmarkStatus.Cancelled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onExportCsv,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = RacingYellow
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Esporta CSV", fontSize = 11.sp)
                    }

                    Button(
                        onClick = onSaveComparison,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RacingYellow,
                            contentColor = RacingBackground
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Salva Riferimento", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = RacingTextSecondary
        )
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = RacingYellow
        )
        Text(
            text = subValue,
            fontSize = 9.sp,
            color = RacingText
        )
    }
}

@Composable
fun MetricItemSmall(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = RacingTextSecondary
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = RacingText
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
            containerColor = RacingSurfaceSecondary
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONFRONTO PRESTAZIONI",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = RacingYellow
                )

                TextButton(onClick = onClearComparison) {
                    Text("Cancella", fontSize = 11.sp, color = RacingTextSecondary)
                }
            }

            if (isDifferentConfig) {
                Text(
                    text = "⚠️ Avviso: I test usano configurazioni o trasporti differenti (${baselineMetrics.effectiveTransport.label}/${baselineMetrics.obdProfile.label} vs ${currentMetrics.effectiveTransport.label}/${currentMetrics.obdProfile.label})",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RacingError
                )
            }

            HorizontalDivider(color = RacingSurface)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RIFERIMENTO (${baselineMetrics.deviceName})", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = RacingTextSecondary)
                    Text("P50: ${String.format(Locale.US, "%.1f", baselineMetrics.p50Ms)} ms", fontSize = 10.sp, color = RacingText)
                    Text("Successo: ${String.format(Locale.US, "%.1f", baselineMetrics.successRatePercent)}%", fontSize = 9.sp, color = RacingTextSecondary)
                    Text("${String.format(Locale.US, "%.1f", baselineMetrics.commandsPerSecond)} PID/s", fontWeight = FontWeight.Bold, color = RacingYellow, fontSize = 11.sp)
                }

                Text("VS", fontWeight = FontWeight.Black, fontSize = 14.sp, color = RacingYellow)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ATTUALE (${currentMetrics.deviceName})", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = RacingTextSecondary)
                    Text("P50: ${String.format(Locale.US, "%.1f", currentMetrics.p50Ms)} ms", fontSize = 10.sp, color = RacingText)
                    Text("Successo: ${String.format(Locale.US, "%.1f", currentMetrics.successRatePercent)}%", fontSize = 9.sp, color = RacingTextSecondary)
                    Text("${String.format(Locale.US, "%.1f", currentMetrics.commandsPerSecond)} PID/s", fontWeight = FontWeight.Bold, color = RacingYellow, fontSize = 11.sp)
                }
            }

            HorizontalDivider(color = RacingSurface)

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
                    Text("Diff. P50 Mediana", fontSize = 9.sp, color = RacingTextSecondary)
                    Text(
                        text = p50Text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (p50Diff <= 0) RacingSuccess else RacingError
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Diff. Velocità", fontSize = 9.sp, color = RacingTextSecondary)
                    Text(
                        text = pidsText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pidsDiff >= 0) RacingSuccess else RacingError
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
            containerColor = RacingSurface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
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
                        modifier = Modifier.size(14.dp),
                        tint = RacingYellow
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CONSOLE LOGS (${logs.size})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = RacingYellow
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = RacingYellow
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color(0xFF050505), shape = RoundedCornerShape(8.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { logLine ->
                        val color = when {
                            logLine.contains("ERRORE", ignoreCase = true) || logLine.contains("ERR:", ignoreCase = true) -> RacingError
                            logLine.contains("SUCCESS", ignoreCase = true) || logLine.contains("riuscita", ignoreCase = true) -> RacingSuccess
                            logLine.contains("[INIT]", ignoreCase = true) -> RacingYellow
                            else -> RacingText
                        }

                        Text(
                            text = logLine,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 11.sp
                        )
                    }
                }
            }
        }
    }
}
