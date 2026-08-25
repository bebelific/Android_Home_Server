package com.printserver.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.printserver.core.common.PrinterLog

class UsbPrinterManager(private val context: Context) {

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val permissionCallbacks = mutableMapOf<Int, (Boolean) -> Unit>()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            PrinterLog.i(TAG, "USB permission granted=$granted device=${device?.let(::describe)}")
            device?.deviceId?.let { id -> permissionCallbacks.remove(id)?.invoke(granted) }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    fun findPrinter(): UsbDevice? = usbManager.deviceList.values.firstOrNull { looksLikePrinter(it) }

    private fun looksLikePrinter(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
        }
        return false
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun describe(d: UsbDevice): String =
        "${d.productName ?: "?"} VID=%04X PID=%04X".format(d.vendorId, d.productId)

    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        permissionCallbacks[device.deviceId] = callback
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags or PendingIntent.FLAG_ONE_SHOT
        )
        usbManager.requestPermission(device, pi)
    }

    fun openPrinter(device: UsbDevice): UsbPrinterSession? {
        if (!hasPermission(device)) {
            PrinterLog.w(TAG, "Cannot open ${describe(device)}: no permission")
            return null
        }
        val iface = pickInterface(device) ?: run {
            PrinterLog.e(TAG, "No bulk OUT interface on ${describe(device)}")
            return null
        }
        val conn = usbManager.openDevice(device) ?: run {
            PrinterLog.e(TAG, "openDevice returned null")
            return null
        }
        return try {
            conn.claimInterface(iface, true)
            val ep = bulkOut(iface) ?: throw IllegalStateException("no bulk OUT endpoint")
            val session = UsbPrinterSession(conn, iface.id, ep)
            PrinterLog.i(TAG, "Opened ${describe(device)} -> ${session.description}")
            session
        } catch (e: Exception) {
            runCatching { conn.close() }
            PrinterLog.e(TAG, "Claim failed: ${e.message}")
            null
        }
    }

    private fun pickInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_PRINTER && f.endpointCount > 0) return f
        }
        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            for (j in 0 until f.endpointCount) {
                val e = f.getEndpoint(j)
                if (e.direction == UsbConstants.USB_DIR_OUT && e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) return f
            }
        }
        return null
    }

    private fun bulkOut(iface: UsbInterface) =
        (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }

    companion object {
        private const val TAG = "UsbPrinter"
        const val ACTION_USB_PERMISSION = "com.printserver.USB_PERMISSION"
    }
}
