package com.printserver.app

import android.app.Application
import com.printserver.core.common.PrinterLog

class PrintServerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrinterLog.clearSinks()
        PrinterLog.addSink(com.printserver.core.common.FileLogSink(filesDir))
        PrinterLog.addSink(com.printserver.core.common.RingBufferSink(200))
        PrinterLog.i(TAG, "Application created")
    }

    companion object {
        private val TAG = "PrintServerApp"
    }
}