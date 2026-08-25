package com.printserver.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

abstract class ServiceBoundActivity : AppCompatActivity() {

    protected var server: HomeServerService? = null
        private set

    private var bound = false
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            server = (service as HomeServerService.LocalBinder).service()
            onServiceReady()
        }
        override fun onServiceDisconnected(name: ComponentName?) { server = null }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, HomeServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) runCatching { unbindService(connection) }
        bound = false
        server = null
        super.onStop()
    }

    protected open fun onServiceReady() {}

    protected fun camera(): com.printserver.core.camera.CameraService? =
        server?.services()?.get(HomeServerService.ID_WEBCAM) as? com.printserver.core.camera.CameraService

    protected fun files(): com.printserver.core.files.FileService? =
        server?.services()?.get(HomeServerService.ID_FILES) as? com.printserver.core.files.FileService

    protected fun print(): com.printserver.core.print.PrintService? =
        server?.services()?.get(HomeServerService.ID_PRINT) as? com.printserver.core.print.PrintService
}
