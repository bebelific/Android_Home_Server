package com.printserver.core.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.view.SurfaceHolder
import com.printserver.core.common.PrinterLog
import java.io.ByteArrayOutputStream

class CameraStreamer(private val bus: FrameBus) {
    @Volatile private var camera: Camera? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var lastFrameNs = 0L
    @Volatile private var paused = false
    @Volatile var fpsCap: Int = 15
    @Volatile var jpegQuality: Int = 70
    @Volatile var facingBack: Boolean = true
    @Volatile var torchRequested: Boolean = false

    val isRunning: Boolean get() = camera != null
    val resolution: String get() = if (width > 0) "${width}x${height}" else "-"

    fun start() {
        if (camera != null) return
        val cam = openCamera() ?: throw IllegalStateException("No camera available")
        try {
            configure(cam)
            camera = cam
            PrinterLog.i(TAG, "Started ${if (facingBack) "back" else "front"} $width x $height")
        } catch (e: Exception) {
            runCatching { cam.release() }
            throw e
        }
    }

    private fun openCamera(): Camera? = try {
        Camera.open(pickId())
    } catch (e: Exception) {
        PrinterLog.e(TAG, "Camera.open failed: ${e.message}")
        null
    }

    private fun pickId(): Int {
        val info = Camera.CameraInfo()
        var backId = -1; var frontId = -1
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && backId < 0) backId = i
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && frontId < 0) frontId = i
        }
        return when {
            facingBack && backId >= 0 -> backId
            !facingBack && frontId >= 0 -> frontId
            backId >= 0 -> backId
            else -> 0
        }
    }

    private fun configure(cam: Camera) {
        val p = cam.parameters
        val sizes = p.supportedPreviewSizes.orEmpty()
            .filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
            .sortedByDescending { it.width * it.height }
        val size = sizes.firstOrNull() ?: p.previewSize
        width = size.width; height = size.height
        p.setPreviewSize(width, height)
        runCatching { p.setPreviewFormat(ImageFormat.NV21) }
        applyTorch(p)
        cam.parameters = p
        cam.setPreviewCallbackWithBuffer(null)

        runCatching {
            val tex = android.graphics.SurfaceTexture(0)
            tex.detachFromGLContext()
            cam.setPreviewTexture(tex)
        }

        val bufSize = width * height * 3 / 2 + 1024
        repeat(3) { cam.addCallbackBuffer(ByteArray(bufSize)) }
        cam.setPreviewCallbackWithBuffer { data, camRef ->
            try {
                if (!paused) maybePublish(data, camRef)
            } finally {
                camRef.addCallbackBuffer(data)
            }
        }
        cam.startPreview()
        paused = false
    }

    private fun applyTorch(p: Camera.Parameters) {
        val supported = p.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) == true
        p.flashMode = if (torchRequested && supported) Camera.Parameters.FLASH_MODE_TORCH
        else Camera.Parameters.FLASH_MODE_OFF
    }

    private fun maybePublish(data: ByteArray, cam: Camera) {
        val nowNs = System.nanoTime()
        val minIntervalNs = 1_000_000_000L / fpsCap.coerceIn(1, 30)
        if (nowNs - lastFrameNs < minIntervalNs) return
        lastFrameNs = nowNs
        val yuv = YuvImage(data, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(data.size / 2)
        yuv.compressToJpeg(Rect(0, 0, width, height), jpegQuality.coerceIn(30, 95), out)
        bus.publish(out.toByteArray())
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun setTorch(on: Boolean): Boolean {
        torchRequested = on
        val c = camera ?: return torchRequested
        return try {
            val p = c.parameters
            val supported = p.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) == true
            if (!supported) return false
            p.flashMode = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
            on
        } catch (_: Exception) { false }
    }

    fun switchCamera(backFacing: Boolean) {
        facingBack = backFacing
        stop()
        runCatching { start() }.onFailure { PrinterLog.e(TAG, "Switch failed: ${it.message}") }
    }

    fun stop() {
        camera?.let { c ->
            runCatching { c.stopPreview() }
            runCatching { c.setPreviewCallbackWithBuffer(null) }
            runCatching { c.release() }
        }
        camera = null
        width = 0; height = 0
        PrinterLog.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "CamStreamer"
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 720
    }
}
