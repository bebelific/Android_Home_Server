package com.printserver.core.camera

import android.content.Context
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.Service
import com.printserver.core.common.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CameraService(
    private val prefs: PreferencesManager,
) : Service {
    override val id = "webcam"
    override val displayName = "Webcam Stream"
    override val defaultPort = 8081

    private val _state = MutableStateFlow(ServiceState.DISABLED)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()

    val bus = FrameBus()
    val streamer = CameraStreamer(bus)
    private var server: MjpegServer? = null
    private var monitorJob: Job? = null

    override suspend fun start(context: Context): Result<Unit> {
        _state.value = ServiceState.STARTING
        return try {
            applyPrefsToStreamer()
            server = MjpegServer({ prefs.mjpegPort.value }, bus, streamer)
            server?.start()
            startMonitor()
            _state.value = ServiceState.RUNNING
            PrinterLog.i(TAG, "Running on port ${prefs.mjpegPort.value}")
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { server?.stop() }; server = null
            runCatching { streamer.stop() }
            _state.value = ServiceState.ERROR
            PrinterLog.e(TAG, "Start failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun applyPrefsToStreamer() {
        streamer.facingBack = prefs.cameraFacingBack.value
        streamer.torchRequested = prefs.cameraTorch.value
        streamer.jpegQuality = prefs.mjpegQuality.value
        streamer.fpsCap = prefs.mjpegFps.value
    }

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            var lastFrames = -1L
            var stalls = 0
            while (isActive && _state.value == ServiceState.RUNNING) {
                delay(10_000)
                if (_state.value != ServiceState.RUNNING) break
                val frames = bus.frameCount
                if (!streamer.isRunning) {
                    stalls = 0
                    applyPrefsToStreamer()
                    try {
                        streamer.start()
                        PrinterLog.i(TAG, "Camera attached ${streamer.resolution}")
                    } catch (_: Exception) {}
                } else if (frames == lastFrames) {
                    stalls++
                    PrinterLog.w(TAG, "Frame stall ($stalls) at $frames frames")
                    if (stalls >= 2) {
                        stalls = 0
                        PrinterLog.i(TAG, "Restarting stalled camera")
                        streamer.stop()
                        applyPrefsToStreamer()
                        try { streamer.start() } catch (_: Exception) {}
                    }
                } else {
                    stalls = 0
                }
                lastFrames = frames
            }
        }
    }

    override suspend fun stop(): Result<Unit> {
        _state.value = ServiceState.STOPPING
        return try {
            retryJobCancel()
            server?.stop(); server = null
            streamer.stop()
            _state.value = ServiceState.DISABLED
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
    }

    override fun isHealthy(): Boolean =
        _state.value == ServiceState.RUNNING && server?.isRunning == true

    fun throttleCamera(multiplier: Float) {
        streamer.fpsCap = (prefs.mjpegFps.value * multiplier).toInt().coerceAtLeast(2)
        PrinterLog.i(TAG, "FPS throttled to ${streamer.fpsCap}")
    }

    fun pauseCamera() { streamer.pause() }
    fun resumeCamera() {
        streamer.fpsCap = prefs.mjpegFps.value
        streamer.resume()
    }

    fun setTorch(on: Boolean): Boolean {
        prefs.setCameraTorch(on)
        return streamer.setTorch(on)
    }

    fun switchCamera(backFacing: Boolean) {
        prefs.setCameraFacingBack(backFacing)
        streamer.switchCamera(backFacing)
    }

    private fun retryJobCancel() { monitorJob?.cancel(); monitorJob = null }

    companion object { private const val TAG = "Webcam" }
}
