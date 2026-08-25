package com.printserver.app

import android.app.Application
import com.printserver.core.common.PrinterLog

class PrintServerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrinterLog.clearSinks()
        PrinterLog.addSink(com.printserver.core.common.FileLogSink(filesDir))
        PrinterLog.addSink(com.printserver.core.common.RingBufferSink(200))
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                PrinterLog.e("Crash", "uncaught on ${t.name}: ${e.stackTraceToString().take(3500)}")
            }
            prev?.uncaughtException(t, e)
        }
        PrinterLog.i(TAG, "Application created")
    }

    companion object {
        private val TAG = "PrintServerApp"
    }
}