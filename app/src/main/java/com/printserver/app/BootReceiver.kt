package com.printserver.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "BootReceiver: ${intent.action}, starting HomeServerService")
                val serviceIntent = Intent(context, HomeServerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}