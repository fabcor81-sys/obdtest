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
            BluetoothDeviceItem(
                name = device.name ?: "Sconosciuto (${device.address})",
                address = device.address,
                isBle = false
            )
        } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(
        onDeviceFound: (BluetoothDeviceItem) -> Unit,
        onDiscoveryFinished: () -> Unit
    ) {
        if (!hasRequiredPermissions() || !isBluetoothEnabled()) {
            Log.d(TAG, "Scansione annullata: permessi insufficienti o Bluetooth disattivato")
            onDiscoveryFinished()
            return
        }

        stopDiscovery()
        Log.d(TAG, "Avvio ricerca attiva dispositivi Bluetooth Classic e BLE...")

        // 1. Scansione Classic (BroadcastReceiver)
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                        device?.let {
                            val deviceName = it.name ?: "Dispositivo Bluetooth (${it.address})"
                            Log.d(TAG, "Trovato dispositivo Classic: $deviceName [${it.address}]")
                            onDeviceFound(
                                BluetoothDeviceItem(
                                    name = deviceName,
                                    address = it.address,
                                    isBle = false
                                )
                            )
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Ricerca dispositivi Classic completata")
                    }
                }
            }
        }

        context.registerReceiver(receiver, filter)
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()

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
                                name = "$deviceName (BLE)",
                                address = device.address,
                                isBle = true
                            )
                        )
                    }

                    override fun onScanFailed(errorCode: Int) {
                        super.onScanFailed(errorCode)
                        Log.e(TAG, "Scansione BLE fallita con codice errore: $errorCode")
                    }
                }

                bleScanner.startScan(bleScanCallback)
            } else {
                Log.e(TAG, "BluetoothLeScanner non disponibile")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante avvio scansione BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (_: Exception) {
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
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun getDeviceByAddress(address: String): BluetoothDevice? {
        if (!hasRequiredPermissions() || !isBluetoothEnabled()) return null
        return bluetoothAdapter?.getRemoteDevice(address)
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
