package com.printserver.core.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build

class UsbPrinterSession(
    private val connection: UsbDeviceConnection,
    val interfaceId: Int,
    private val outEndpoint: UsbEndpoint,
) {
    val description: String
        get() = "if#$interfaceId ep=0x%02X".format(outEndpoint.address)

    fun write(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int = TRANSFER_TIMEOUT_MS): Int {
        return if (Build.VERSION.SDK_INT >= 26 || offset == 0) {
            connection.bulkTransfer(outEndpoint, buffer, offset, length, timeoutMs)
        } else {
            val tmp = ByteArray(length)
            System.arraycopy(buffer, offset, tmp, 0, length)
            connection.bulkTransfer(outEndpoint, tmp, length, timeoutMs)
        }
    }

    fun close() {
        runCatching { connection.close() }
    }

    companion object { const val TRANSFER_TIMEOUT_MS = 5000 }
}
