package com.example.obdtest.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.obdtest.ui.BluetoothDeviceItem

class BluetoothObdManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var receiver: BroadcastReceiver? = null
    private var bleScanCallback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private var isClassicDiscoveryFinished = false
    private var onScanFinishedCallback: (() -> Unit)? = null
    private var onScanErrorCallback: ((String) -> Unit)? = null

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceItem> {
        if (!hasRequiredPermissions() || !isBluetoothEnabled()) {
            return emptyList()
        }

        val bondedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        return bondedDevices?.map { device ->
            val rawName = device.name ?: "Sconosciuto (${device.address})"
            val isBleDevice = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                    rawName.contains("NORAUTO", ignoreCase = true) ||
                    rawName.contains("BLE", ignoreCase = true) ||
                    rawName.contains("VGATE", ignoreCase = true)

            BluetoothDeviceItem(
                name = if (isBleDevice && !rawName.contains("(BLE)", ignoreCase = true)) "$rawName (BLE)" else rawName,
                address = device.address,
                isBle = isBleDevice
            )
        } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(
        onDeviceFound: (BluetoothDeviceItem) -> Unit,
        onDiscoveryFinished: () -> Unit,
        onError: ((String) -> Unit)? = null,
        timeoutMs: Long = 12000L
    ) {
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "Scansione annullata: permessi insufficienti")
            onError?.invoke("Permessi Bluetooth o Posizione non concessi")
            onDiscoveryFinished()
            return
        }

        if (!isBluetoothEnabled()) {
            Log.d(TAG, "Scansione annullata: Bluetooth disattivato")
            onError?.invoke("Bluetooth disattivato")
            onDiscoveryFinished()
            return
        }

        stopDiscovery()
        Log.d(TAG, "Avvio ricerca attiva dispositivi Bluetooth Classic e BLE...")

        onScanFinishedCallback = onDiscoveryFinished
        onScanErrorCallback = onError
        isClassicDiscoveryFinished = false

        // 1. Scansione Classic (BroadcastReceiver)
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                        device?.let {
                            val rawName = it.name ?: "Dispositivo Bluetooth (${it.address})"
                            val isBleDevice = it.type == BluetoothDevice.DEVICE_TYPE_LE ||
                                    it.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                                    rawName.contains("NORAUTO", ignoreCase = true) ||
                                    rawName.contains("BLE", ignoreCase = true) ||
                                    rawName.contains("VGATE", ignoreCase = true)

                            Log.d(TAG, "Trovato dispositivo Classic: $rawName [${it.address}]")
                            onDeviceFound(
                                BluetoothDeviceItem(
                                    name = if (isBleDevice && !rawName.contains("(BLE)", ignoreCase = true)) "$rawName (BLE)" else rawName,
                                    address = it.address,
                                    isBle = isBleDevice
                                )
                            )
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Ricerca dispositivi Classic completata")
                        isClassicDiscoveryFinished = true
                    }
                }
            }
        }

        try {
            context.registerReceiver(receiver, filter)
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante avvio scansione Classic: ${e.message}")
        }

        // 2. Scansione BLE (BluetoothLeScanner)
        try {
            val bleScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bleScanner != null) {
                bleScanCallback = object : ScanCallback() {
                    @SuppressLint("MissingPermission")
                    override fun onScanResult(callbackType: Int, result: ScanResult?) {
                        super.onScanResult(callbackType, result)
                        val device = result?.device ?: return
                        val recordName = result.scanRecord?.deviceName
                        val deviceName = device.name ?: recordName ?: "Dispositivo BLE (${device.address})"

                        Log.d(TAG, "Trovato dispositivo BLE: $deviceName [${device.address}]")
                        onDeviceFound(
                            BluetoothDeviceItem(
                                name = if (!deviceName.contains("(BLE)", ignoreCase = true)) "$deviceName (BLE)" else deviceName,
                                address = device.address,
                                isBle = true
                            )
                        )
                    }

                    override fun onScanFailed(errorCode: Int) {
                        super.onScanFailed(errorCode)
                        val errMsg = "Scansione BLE fallita con codice errore $errorCode"
                        Log.e(TAG, errMsg)
                        onScanErrorCallback?.invoke(errMsg)
                    }
                }

                bleScanner.startScan(bleScanCallback)
            } else {
                Log.e(TAG, "BluetoothLeScanner non disponibile")
            }
        } catch (e: Exception) {
            val errMsg = "Errore durante avvio scansione BLE: ${e.message}"
            Log.e(TAG, errMsg)
            onError?.invoke(errMsg)
        }

        // Timeout di sicurezza per terminare la scansione
        scanTimeoutRunnable = Runnable {
            Log.d(TAG, "Timeout scansione raggiunto (${timeoutMs}ms). Arresto scansione...")
            stopDiscovery()
        }
        handler.postDelayed(scanTimeoutRunnable!!, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        scanTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            scanTimeoutRunnable = null
        }

        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante annullamento discovery Classic: ${e.message}")
        }

        try {
            receiver?.let {
                context.unregisterReceiver(it)
                receiver = null
            }
        } catch (_: Exception) {
        }

        try {
            bleScanCallback?.let { callback ->
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
                bleScanCallback = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante arresto scanner BLE: ${e.message}")
        }

        val callbackToInvoke = onScanFinishedCallback
        onScanFinishedCallback = null
        onScanErrorCallback = null
        callbackToInvoke?.invoke()
    }

    @SuppressLint("MissingPermission")
    fun getDeviceByAddress(address: String): BluetoothDevice? {
        if (!hasRequiredPermissions() || !isBluetoothEnabled()) return null
        return try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.e(TAG, "Indirizzo Bluetooth non valido o dispositivo non trovato: $address (${e.message})")
            null
        }
    }

    companion object {
        private const val TAG = "OBD_DEBUG"

        fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }
    }
}
