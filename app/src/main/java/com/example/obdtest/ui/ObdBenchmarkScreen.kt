package com.example.obdtest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class BenchmarkPage(val title: String) {
    DEVICE("1. Device"), TEST("2. Benchmark"), RESULTS("3. Results")
}

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
    onToggleAbsPumpOffAuthorization: (Boolean) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onSaveComparison: () -> Unit,
    onClearComparison: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = BenchmarkPage.entries[pageIndex.coerceIn(0, BenchmarkPage.entries.lastIndex)]
    val running = BenchmarkUiPolicy.isBusy(uiState.status)
    val usableDevice = BenchmarkUiPolicy.canRun(
        uiState.selectedDevice, uiState.hasPermissions, uiState.isBluetoothEnabled
    )
    val validProfile = BenchmarkUiPolicy.supportsProfile(uiState.selectedDevice, uiState.selectedProfile)

    BackHandler(enabled = pageIndex > 0) {
        // Never abandon the benchmark flow while a command is running.
        if (!running) pageIndex -= 1
    }

    Scaffold(
        containerColor = RacingBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "OBD TEST  •  BENCHMARK",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = RacingYellow
                        )
                        Text(
                            com.example.obdtest.AppVersion.BUILD_TAG,
                            fontSize = 11.sp,
                            color = RacingTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RacingSurface),
                actions = {
                    IconButton(onClick = onRefreshDevices, enabled = !running) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh devices", tint = RacingYellow)
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().background(RacingBackground)
                .padding(innerPadding).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                BenchmarkPage.entries.forEachIndexed { index, item ->
                    FilterChip(
                        selected = pageIndex == index,
                        onClick = { if (!running) pageIndex = index },
                        enabled = !running,
                        label = {
                            Text(
                                item.title,
                                fontSize = 12.sp,
                                fontWeight = if (pageIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RacingYellow,
                            selectedLabelColor = RacingBackground,
                            containerColor = RacingSurfaceSecondary,
                            labelColor = RacingText
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (running) {
                Surface(color = RacingSurfaceSecondary, shape = RoundedCornerShape(8.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("BENCHMARK RUNNING — ${uiState.statusMessage}",
                            color = RacingYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val phase = uiState.status
                        if (phase is BenchmarkStatus.RunningTestSequence && phase.targetRequests > 0) {
                            Text(
                                "Test ${phase.currentTestIndex}/${phase.totalTests}: ${phase.testName} — " +
                                    "${phase.completedRequests}/${phase.targetRequests}",
                                color = RacingText, fontSize = 13.sp
                            )
                            LinearProgressIndicator(
                                progress = { (phase.completedRequests.toFloat() / phase.targetRequests).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = RacingYellow
                            )
                        }
                        Button(
                            onClick = onStopTest,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RacingError, contentColor = RacingText)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("STOP BENCHMARK", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (uiState.status is BenchmarkStatus.Error) {
                Text(
                    "ERROR: ${uiState.statusMessage}",
                    color = RacingError, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (page) {
                    BenchmarkPage.DEVICE -> {
                        Text("Choose the adapter", color = RacingYellow,
                            fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "USB K-Line works independently of Bluetooth. Bluetooth scanning requires " +
                                "permissions and an enabled radio.",
                            color = RacingTextSecondary, fontSize = 13.sp
                        )
                        if (!uiState.hasPermissions && uiState.selectedDevice?.isUsb != true) {
                            PermissionRequiredCard(onRequestPermissions)
                        } else if (!uiState.isBluetoothEnabled && uiState.selectedDevice?.isUsb != true) {
                            BluetoothDisabledCard(onRefreshDevices)
                        }
                        DeviceSelectorCard(
                            devices = uiState.pairedDevices,
                            selectedDevice = uiState.selectedDevice,
                            isScanning = uiState.isScanning,
                            onSelectDevice = onSelectDevice,
                            onRefreshDevices = onRefreshDevices,
                            onStartScanDevices = onStartScanDevices,
                            onStopScanDevices = onStopScanDevices,
                            isTesting = running
                        )
                        Text(
                            if (usableDevice) "Selected device ready: ${uiState.selectedDevice?.name}"
                            else if (uiState.selectedDevice == null) "Select an adapter to continue."
                            else "Bluetooth is unavailable. Select USB or grant Bluetooth access.",
                            color = if (usableDevice) RacingSuccess else RacingTextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    BenchmarkPage.TEST -> {
                        Text("Configure benchmark", color = RacingYellow,
                            fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Selected: ${uiState.selectedDevice?.name ?: "No device"}" +
                                "  •  ${if (uiState.selectedDevice?.isUsb == true) "USB K-Line" else "Bluetooth"}",
                            color = RacingTextSecondary, fontSize = 13.sp
                        )
                        TestControlCard(
                            status = uiState.status,
                            statusMessage = uiState.statusMessage,
                            selectedTransportMode = uiState.selectedTransportMode,
                            selectedProfile = uiState.selectedProfile,
                            selectedRequestCount = uiState.selectedRequestCount,
                            isUsbDevice = uiState.selectedDevice?.isUsb == true,
                            isAbsPumpOffAuthorized = uiState.isAbsPumpOffAuthorized,
                            isDeviceSelected = usableDevice && validProfile,
                            onSelectTransportMode = onSelectTransportMode,
                            onSelectProfile = onSelectProfile,
                            onSelectRequestCount = onSelectRequestCount,
                            onToggleAbsPumpOffAuthorization = onToggleAbsPumpOffAuthorization,
                            onStartTest = {
                                if (usableDevice && validProfile && !running) {
                                    onStartTest()
                                    pageIndex = BenchmarkPage.RESULTS.ordinal
                                }
                            },
                            onStopTest = onStopTest
                        )
                        Surface(color = RacingSurfaceSecondary, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("READ-ONLY BENCHMARK", color = RacingYellow, fontWeight = FontWeight.Bold)
                                Text(
                                    "OBD Test measures adapter and ECU response latency. ABS actuator " +
                                        "commands, including Pump OFF (30 02 07 00), are excluded. " +
                                        "Use a dedicated, validated service procedure for actuator control.",
                                    color = RacingTextSecondary, fontSize = 12.sp
                                )
                            }
                        }
                    }

                    BenchmarkPage.RESULTS -> {
                        Text("Benchmark results", color = RacingYellow,
                            fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (uiState.currentMetrics == null) {
                            Text(
                                if (running) "Connecting and initializing; measurements will appear here."
                                else "No measurements yet. Go to Benchmark and run a test.",
                                color = RacingTextSecondary, fontSize = 14.sp
                            )
                        }
                        uiState.currentMetrics?.let { metrics ->
                            CurrentMetricsCard(
                                metrics = metrics,
                                status = uiState.status,
                                onSaveComparison = onSaveComparison,
                                onExportCsv = onExportCsv
                            )
                        }
                        uiState.savedComparisonMetrics?.let { baseline ->
                            uiState.currentMetrics?.let { current ->
                                ComparisonCard(
                                    baselineMetrics = baseline,
                                    currentMetrics = current,
                                    onClearComparison = onClearComparison
                                )
                            } ?: Text(
                                "Saved reference: ${baseline.deviceName}. Start a new benchmark to compare.",
                                color = RacingTextSecondary, fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            ConsoleLogsCard(logs = uiState.logs, modifier = Modifier.fillMaxWidth())

            if (!running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pageIndex > 0) {
                        OutlinedButton(
                            onClick = { pageIndex-- },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RacingYellow)
                        ) { Text("BACK") }
                    }
                    if (page == BenchmarkPage.DEVICE) {
                        Button(
                            onClick = { pageIndex = BenchmarkPage.TEST.ordinal },
                            enabled = usableDevice,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RacingYellow, contentColor = RacingBackground
                            )
                        ) { Text("NEXT: BENCHMARK") }
                    } else if (page == BenchmarkPage.TEST && uiState.currentMetrics != null) {
                        Button(
                            onClick = { pageIndex = BenchmarkPage.RESULTS.ordinal },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RacingYellow, contentColor = RacingBackground
                            )
                        ) { Text("VIEW RESULTS") }
                    }
                }
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
                text = "Bluetooth permissions required",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = RacingText
            )
            Text(
                text = "Allow Bluetooth access to scan and connect to wireless OBD adapters. USB K-Line does not require Bluetooth.",
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
                Text("GRANT PERMISSIONS", fontWeight = FontWeight.Bold)
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
                text = "Bluetooth is disabled",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = RacingText
            )
            Text(
                text = "Enable Bluetooth to use a wireless adapter. USB K-Line works without Bluetooth.",
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
                Text("CHECK AGAIN", fontWeight = FontWeight.Bold)
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
                    text = "ADAPTER SELECTION • BLUETOOTH / USB",
                    fontSize = 12.sp,
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
                        Text("Stop scan", fontSize = 10.sp, color = RacingError)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = RacingYellow
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scan", fontSize = 10.sp, color = RacingYellow)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = expanded && !isTesting,
                onExpandedChange = { if (!isTesting) expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedDevice?.let {
                        if (it.isUsb) "${it.name}\n${it.address}" else "${it.name}\nMAC: ${it.address}"
                    } ?: "No adapter selected",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isTesting,
                    singleLine = false,
                    maxLines = 2,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    leadingIcon = {
                        Icon(
                            if (selectedDevice?.isUsb == true) Icons.Default.Usb else Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = RacingYellow
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 17.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = RacingText,
                        unfocusedTextColor = RacingText,
                        focusedBorderColor = RacingYellow,
                        unfocusedBorderColor = RacingSurfaceSecondary,
                        focusedContainerColor = RacingSurfaceSecondary,
                        unfocusedContainerColor = RacingSurfaceSecondary
                    ),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .height(60.dp)
                )

                ExposedDropdownMenu(
                    expanded = expanded && !isTesting,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(RacingSurfaceSecondary)
                ) {
                    if (devices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No adapters found", color = RacingTextSecondary, fontSize = 11.sp) },
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
                                        if (device.isUsb) Icons.Default.Usb else Icons.Default.BluetoothConnected,
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
    selectedRequestCount: Int,
    isUsbDevice: Boolean,
    isAbsPumpOffAuthorized: Boolean,
    isDeviceSelected: Boolean,
    onSelectTransportMode: (TransportMode) -> Unit,
    onSelectProfile: (ObdProfile) -> Unit,
    onSelectRequestCount: (Int) -> Unit,
    onToggleAbsPumpOffAuthorization: (Boolean) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit
) {
    val running = BenchmarkUiPolicy.isBusy(status)
    var confirmPumpOff by remember { mutableStateOf(false) }

    if (confirmPumpOff) {
        AlertDialog(
            onDismissRequest = { confirmPumpOff = false },
            title = { Text("Optional ABS Pump OFF test") },
            text = {
                Text(
                    "For the Lotus ABS profile only, run one additional 30 02 07 00 (Pump OFF) " +
                        "command at the end of this benchmark? This addresses the ABS actuator system. " +
                        "Use only on a stationary vehicle in a controlled diagnostic setup. " +
                        "Authorization applies to this run only."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmPumpOff = false
                    onToggleAbsPumpOffAuthorization(true)
                }) { Text("ENABLE ONE TEST") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPumpOff = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RacingSurface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("TRANSPORT", fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold, color = RacingYellow)
            if (isUsbDevice) {
                Text("USB K-Line FTDI — direct serial connection",
                    color = RacingText, fontSize = 14.sp)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(TransportMode.AUTO, TransportMode.CLASSIC_SPP, TransportMode.BLE_GATT)
                        .forEach { mode ->
                            FilterChip(
                                selected = selectedTransportMode == mode,
                                onClick = { onSelectTransportMode(mode) },
                                enabled = !running,
                                label = { Text(mode.label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RacingYellow,
                                    selectedLabelColor = RacingBackground,
                                    containerColor = RacingSurfaceSecondary,
                                    labelColor = RacingText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                }
            }

            Text("BENCHMARK PROFILE", fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold, color = RacingYellow)
            val validProfiles = if (isUsbDevice) {
                listOf(ObdProfile.USB_KLINE_DIAG, ObdProfile.LOTUS_ABS)
            } else {
                listOf(ObdProfile.AT_ONLY, ObdProfile.GENERIC_AUTO, ObdProfile.LOTUS_ABS)
            }
            validProfiles.forEach { profile ->
                val shortName = when (profile) {
                    ObdProfile.AT_ONLY -> "ELM327 — AT only"
                    ObdProfile.GENERIC_AUTO -> "Generic OBD2"
                    ObdProfile.LOTUS_ABS -> "Lotus EBC430 ABS"
                    ObdProfile.USB_KLINE_DIAG -> "USB K-Line — ECU ID"
                }
                OutlinedButton(
                    onClick = { onSelectProfile(profile) },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (profile == selectedProfile) 2.dp else 1.dp,
                        if (profile == selectedProfile) RacingYellow else RacingTextSecondary
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (profile == selectedProfile) RacingSurfaceSecondary else RacingSurface,
                        contentColor = RacingText
                    )
                ) {
                    Text(shortName, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    if (profile == selectedProfile)
                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = RacingYellow)
                }
            }

            Text("SAMPLES PER TEST", fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold, color = RacingYellow)
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(25, 50, 100, 250).forEach { count ->
                    FilterChip(
                        selected = selectedRequestCount == count,
                        enabled = !running && selectedProfile != ObdProfile.USB_KLINE_DIAG,
                        onClick = { onSelectRequestCount(count) },
                        label = { Text("$count", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RacingYellow,
                            selectedLabelColor = RacingBackground,
                            containerColor = RacingSurfaceSecondary,
                            labelColor = RacingText
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (selectedProfile == ObdProfile.USB_KLINE_DIAG) {
                Text("USB K-Line diagnostic mode always performs one ECU identification request.",
                    color = RacingTextSecondary, fontSize = 12.sp)
            }

            if (selectedProfile == ObdProfile.LOTUS_ABS && !isUsbDevice) {
                Surface(shape = RoundedCornerShape(8.dp), color = RacingSurfaceSecondary) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("OPTIONAL TEST 6 — ABS PUMP OFF", color = RacingYellow,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("One 30 02 07 00 command; explicit one-run opt-in.",
                                color = RacingTextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = isAbsPumpOffAuthorized,
                            onCheckedChange = { enabled ->
                                if (enabled) confirmPumpOff = true
                                else onToggleAbsPumpOffAuthorization(false)
                            },
                            enabled = !running,
                            colors = SwitchDefaults.colors(checkedThumbColor = RacingYellow)
                        )
                    }
                }
            }

            HorizontalDivider(color = RacingSurfaceSecondary)
            Text(statusMessage, fontSize = 13.sp,
                color = if (status is BenchmarkStatus.Error) RacingError else RacingTextSecondary)
            if (!running) {
                Button(
                    onClick = onStartTest,
                    enabled = isDeviceSelected,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RacingYellow,
                        contentColor = RacingBackground,
                        disabledContainerColor = RacingSurfaceSecondary,
                        disabledContentColor = RacingTextSecondary
                    )
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("START BENCHMARK", fontWeight = FontWeight.Bold)
                }
            } else {
                Text("Benchmark in progress. Use STOP at the top of the screen.",
                    color = RacingYellow, fontSize = 12.sp)
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
    var showCommandDetails by remember { mutableStateOf(false) }
    val finished = status is BenchmarkStatus.Completed || status is BenchmarkStatus.Cancelled ||
        status is BenchmarkStatus.Error
    val validBaseline = status is BenchmarkStatus.Completed &&
        !metrics.isPartialSession && metrics.successCount > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RacingSurface)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RESULTS — ${metrics.deviceName}",
                color = RacingYellow, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "${metrics.effectiveTransport.label} • ${metrics.obdProfile.label}" +
                    if (metrics.isPartialSession) " • PARTIAL SESSION" else "",
                color = if (metrics.isPartialSession) RacingError else RacingTextSecondary,
                fontSize = 12.sp
            )
            Text("Adapter: ${metrics.elmVersion}", color = RacingTextSecondary, fontSize = 12.sp)
            if (metrics.isPartialSession) {
                Surface(color = RacingSurfaceSecondary, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "The test was interrupted. Metrics reflect collected samples only; " +
                            "do not use them as a comparison baseline.",
                        color = RacingError, fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            HorizontalDivider(color = RacingSurfaceSecondary)
            MetricItem(
                label = "MEAN LATENCY (SUCCESSFUL RESPONSES)",
                value = if (metrics.successCount > 0)
                    "${String.format(Locale.US, "%.1f", metrics.avgLatencyMs)} ms" else "N/A",
                subValue = if (metrics.successCount > 0)
                    "Min ${String.format(Locale.US, "%.1f", metrics.minLatencyMs)} ms • " +
                        "Max ${String.format(Locale.US, "%.1f", metrics.maxLatencyMs)} ms"
                else "No successful ECU/adapter responses"
            )
            MetricItem(
                label = "SUCCESS RATE",
                value = "${String.format(Locale.US, "%.1f", metrics.successRatePercent)}%",
                subValue = "${metrics.successCount} / ${metrics.totalCommandsSent} successful • " +
                    "${String.format(Locale.US, "%.1f", metrics.commandsPerSecond)} commands/s"
            )
            HorizontalDivider(color = RacingSurfaceSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricItemSmall(
                    "P50", if (metrics.successCount > 0)
                        "${String.format(Locale.US, "%.1f", metrics.p50Ms)} ms" else "N/A",
                    Modifier.weight(1f)
                )
                MetricItemSmall(
                    "P95", if (metrics.successCount > 0)
                        "${String.format(Locale.US, "%.1f", metrics.p95Ms)} ms" else "N/A",
                    Modifier.weight(1f)
                )
                MetricItemSmall(
                    "P99", if (metrics.successCount > 0)
                        "${String.format(Locale.US, "%.1f", metrics.p99Ms)} ms" else "N/A",
                    Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricItemSmall(
                    "STD. DEV.",
                    if (metrics.successCount > 1) "${String.format(Locale.US, "%.1f", metrics.stdDevMs)} ms" else "N/A",
                    Modifier.weight(1f)
                )
                MetricItemSmall(
                    "JITTER",
                    if (metrics.successCount > 1) "${String.format(Locale.US, "%.1f", metrics.jitterMs)} ms" else "N/A",
                    Modifier.weight(1f)
                )
            }

            if (metrics.testSummaries.isNotEmpty()) {
                HorizontalDivider(color = RacingSurfaceSecondary)
                TextButton(
                    onClick = { showCommandDetails = !showCommandDetails },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        if (showCommandDetails) "HIDE COMMAND DETAILS" else
                            "SHOW COMMAND DETAILS (${metrics.testSummaries.size})",
                        color = RacingYellow, fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                }
                if (showCommandDetails) {
                    metrics.testSummaries.forEach { summary ->
                        Surface(color = RacingSurfaceSecondary, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${summary.testName} • ${summary.commandSent}",
                                    color = RacingText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (summary.isSingleExecution) summary.singleExecutionResult
                                    else "P50 ${String.format(Locale.US, "%.1f", summary.p50Ms)} ms • " +
                                        "${summary.positiveResponses}/${summary.samplesExecuted} positive",
                                    fontSize = 12.sp,
                                    color = if (summary.positiveResponses > 0) RacingSuccess else RacingTextSecondary
                                )
                            }
                        }
                    }
                }
            }
            if (finished) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportCsv,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RacingYellow)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("EXPORT CSV", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onSaveComparison,
                        enabled = validBaseline,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RacingYellow,
                            contentColor = RacingBackground
                        )
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SAVE BASELINE", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, subValue: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RacingTextSecondary)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = RacingYellow)
        Text(subValue, fontSize = 12.sp, color = RacingText)
    }
}

@Composable
fun MetricItemSmall(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RacingTextSecondary)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RacingText)
    }
}

@Composable
fun ComparisonCard(
    baselineMetrics: BenchmarkMetrics,
    currentMetrics: BenchmarkMetrics,
    onClearComparison: () -> Unit
) {
    val comparable = BenchmarkUiPolicy.comparable(baselineMetrics, currentMetrics)
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RacingSurfaceSecondary)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ADAPTER COMPARISON", fontWeight = FontWeight.ExtraBold,
                    color = RacingYellow, fontSize = 15.sp)
                TextButton(onClick = onClearComparison) {
                    Text("Clear baseline", color = RacingTextSecondary, fontSize = 12.sp)
                }
            }
            if (!comparable) {
                Surface(color = RacingSurface, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "NOT DIRECTLY COMPARABLE: both runs must be complete and successful, " +
                            "with the same profile, transport, command and sample count. " +
                            "Only raw metrics are shown below.",
                        color = RacingError, fontSize = 13.sp, modifier = Modifier.padding(12.dp)
                    )
                }
            }
            listOf("BASELINE" to baselineMetrics, "CURRENT" to currentMetrics)
                .forEach { (title, metrics) ->
                    Surface(color = RacingSurface, shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$title • ${metrics.deviceName}", fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, color = RacingYellow)
                            Text(
                                "${metrics.effectiveTransport.label} • ${metrics.obdProfile.label} • " +
                                    "${metrics.targetRequestCount} samples/test",
                                color = RacingTextSecondary, fontSize = 12.sp
                            )
                            Text(
                                "P50 ${String.format(Locale.US, "%.1f", metrics.p50Ms)} ms • " +
                                    "${String.format(Locale.US, "%.1f", metrics.commandsPerSecond)} cmd/s • " +
                                    "${String.format(Locale.US, "%.1f", metrics.successRatePercent)}% success",
                                color = RacingText, fontSize = 13.sp
                            )
                        }
                    }
                }
            if (comparable) {
                val p50Delta = currentMetrics.p50Ms - baselineMetrics.p50Ms
                val throughputDelta = currentMetrics.commandsPerSecond - baselineMetrics.commandsPerSecond
                val p50Percent = if (baselineMetrics.p50Ms > 0)
                    p50Delta / baselineMetrics.p50Ms * 100.0 else 0.0
                val throughputPercent = if (baselineMetrics.commandsPerSecond > 0)
                    throughputDelta / baselineMetrics.commandsPerSecond * 100.0 else 0.0
                HorizontalDivider(color = RacingSurface)
                Text("CHANGE VS BASELINE", color = RacingYellow,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "P50 latency: ${String.format(Locale.US, "%+.1f", p50Delta)} ms " +
                        "(${String.format(Locale.US, "%+.1f", p50Percent)}%)",
                    color = if (p50Delta <= 0) RacingSuccess else RacingError,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text(
                    "Throughput: ${String.format(Locale.US, "%+.1f", throughputDelta)} cmd/s " +
                        "(${String.format(Locale.US, "%+.1f", throughputPercent)}%)",
                    color = if (throughputDelta >= 0) RacingSuccess else RacingError,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
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
                    .heightIn(min = 48.dp)
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RacingYellow
                    )
                }

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse console" else "Expand console",
                    modifier = Modifier.size(24.dp),
                    tint = RacingYellow
                )
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
