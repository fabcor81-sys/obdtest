package com.example.obdtest.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.example.obdtest.ui.BluetoothDeviceItem
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

class UsbKLineManager(private val context: Context) {

    companion object {
        private const val TAG = "OBD_USB_DEBUG"
        const val ACTION_USB_PERMISSION = "com.example.obdtest.USB_PERMISSION"
        const val FTDI_VID = 0x0403
        const val FT232R_PID = 0x6001
    }

    private val usbManager: UsbManager? by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    private var usbReceiver: BroadcastReceiver? = null
    private var onPermissionGrantedCallback: ((Boolean) -> Unit)? = null

    /**
     * Cerca ed elenca i dispositivi USB Seriali collegati.
     * Riconosce esplicitamente FTDI VID 0x0403 e PID 0x6001.
     */
    fun getConnectedUsbDevices(): List<BluetoothDeviceItem> {
        val manager = usbManager ?: return emptyList()
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val resultList = mutableListOf<BluetoothDeviceItem>()

        for (driver in availableDrivers) {
            val device = driver.device
            val vid = device.vendorId
            val pid = device.productId

            val isFtdiKkl = (vid == FTDI_VID && pid == FT232R_PID)
            val displayName = if (isFtdiKkl) {
                "KKL 409.1 (FT232R USB)"
            } else {
                "USB Seriale (${driver.javaClass.simpleName.replace("SerialDriver", "")} - VID: ${String.format("%04X", vid)})"
            }

            val address = "USB:${String.format("%04X", vid)}:${String.format("%04X", pid)}:${device.deviceId}"

            resultList.add(
                BluetoothDeviceItem(
                    name = displayName,
                    address = address,
                    isBle = false,
                    isUsb = true,
                    vendorId = vid,
                    productId = pid
                )
            )
        }
        return resultList
    }

    fun findUsbDriverForAddress(address: String): UsbSerialDriver? {
        val manager = usbManager ?: return null
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return availableDrivers.find { driver ->
            val dev = driver.device
            val devAddr = "USB:${String.format("%04X", dev.vendorId)}:${String.format("%04X", dev.productId)}:${dev.deviceId}"
            devAddr == address
        } ?: availableDrivers.firstOrNull()
    }

    fun hasPermission(driver: UsbSerialDriver): Boolean {
        val manager = usbManager ?: return false
        return manager.hasPermission(driver.device)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun requestPermission(
        driver: UsbSerialDriver,
        onResult: (Boolean) -> Unit
    ) {
        val manager = usbManager
        if (manager == null) {
            onResult(false)
            return
        }

        if (manager.hasPermission(driver.device)) {
            Log.d(TAG, "Permesso USB già concesso per ${driver.device.deviceName}")
            onResult(true)
            return
        }

        onPermissionGrantedCallback = onResult

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (ACTION_USB_PERMISSION == intent?.action) {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(TAG, "Permesso USB per ${device?.deviceName ?: "dispositivo"}: $granted")

                        unregisterReceiver()
                        onPermissionGrantedCallback?.invoke(granted)
                        onPermissionGrantedCallback = null
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        Log.d(TAG, "Richiesta popup permesso USB a UsbManager per ${driver.device.deviceName}...")
        manager.requestPermission(driver.device, permissionIntent)
    }

    fun unregisterReceiver() {
        try {
            usbReceiver?.let {
                context.unregisterReceiver(it)
                usbReceiver = null
            }
        } catch (_: Exception) {}
    }
}
