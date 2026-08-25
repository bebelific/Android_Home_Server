package com.printserver.core.camera

import android.graphics.ImageFormat
import android.graphics.Color
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import com.printserver.core.common.PrinterLog
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class CameraStreamer(private val bus: FrameBus) {
    @Volatile private var camera: Camera? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var lastFrameNs = 0L
    @Volatile private var paused = false
    val isPaused: Boolean get() = paused
    @Volatile var fpsCap: Int = 10
    @Volatile var jpegQuality: Int = 60
    @Volatile var facingBack: Boolean = true
    @Volatile var torchRequested: Boolean = false
    @Volatile var rotationDegrees: Int = 0
        private set
    @Volatile var motionEnabled: Boolean = false
    @Volatile var motionSave: Boolean = false
    var onMotionSnapshot: ((ByteArray) -> Unit)? = null
    @Volatile var motionCount: Long = 0
        private set
    @Volatile var lastMotionMs: Long = 0
        private set
    private var prevLuma: IntArray? = null
    private var lastMotionNs = 0L

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    val isRunning: Boolean get() = camera != null
    val resolution: String get() = if (width > 0) "${width}x${height}" else "-"

    fun start() {
        if (camera != null) return
        if (cameraThread == null || cameraThread!!.isAlive == false) {
            cameraThread = HandlerThread("CamStreamer").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var opened: Camera? = null
        var err: Exception? = null
        cameraHandler!!.post {
            try { opened = openCamera() } catch (e: Exception) { err = e }
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        err?.let { throw it }
        val cam = opened ?: throw IllegalStateException("No camera available")
        try {
            configure(cam)
            camera = cam
            PrinterLog.i(TAG, "Started ${if (facingBack) "back" else "front"} $width x $height rot=$rotationDegrees")
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
        val id = pickId()
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(id, info)
        rotationDegrees = when (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            true -> (info.orientation + 180) % 360
            false -> info.orientation
        }
        val p = cam.parameters
        val sizes = p.supportedPreviewSizes.orEmpty()
            .filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
            .sortedByDescending { it.width * it.height }
        val size = sizes.lastOrNull { it.width >= 320 } ?: sizes.firstOrNull() ?: p.previewSize
        width = size.width; height = size.height
        p.setPreviewSize(width, height)
        runCatching { p.setPreviewFormat(ImageFormat.NV21) }
        applyTorch(p)
        cam.parameters = p
        cam.setPreviewCallbackWithBuffer(null)

        val bufSize = width * height * 3 / 2 + 1024
        repeat(4) { cam.addCallbackBuffer(ByteArray(bufSize)) }
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
        val deg = ((rotationDegrees % 360) + 360) % 360
        val (frame, fw, fh) = if (deg == 0) Triple(data, width, height) else rotateNv21(data, width, height, deg)
        val yuv = YuvImage(frame, ImageFormat.NV21, fw, fh, null)
        val out = ByteArrayOutputStream(frame.size / 2)
        yuv.compressToJpeg(Rect(0, 0, fw, fh), jpegQuality.coerceIn(30, 95), out)
        val jpeg = out.toByteArray()
        if (motionEnabled) detectMotion(jpeg)
        bus.publish(jpeg)
    }

    private fun detectMotion(jpeg: ByteArray) {
        if (System.nanoTime() - lastMotionNs < 60_000_000_000L) return
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 24, true)
        val px = IntArray(32 * 24)
        small.getPixels(px, 0, 32, 0, 0, 32, 24)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        val luma = IntArray(px.size) { i ->
            val p = px[i]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
        val prev = prevLuma
        prevLuma = luma
        if (prev == null || prev.size != luma.size) return
        var diff = 0L
        for (i in luma.indices) diff += abs(luma[i] - prev[i])
        val mean = diff / luma.size
        if (mean > 18) {
            motionCount++
            lastMotionMs = System.currentTimeMillis()
            lastMotionNs = System.nanoTime()
            PrinterLog.i(TAG, "Motion detected")
            if (motionSave) onMotionSnapshot?.invoke(jpeg)
        }
    }

    private fun rotateNv21(src: ByteArray, w: Int, h: Int, deg: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(src.size)
        val ySize = w * h
        return when (deg) {
            90 -> {
                var i = 0
                for (dy in 0 until w) for (dx in 0 until h) out[i++] = src[(h - 1 - dx) * w + dy]
                for (dy in 0 until w / 2) for (dx in 0 until h / 2) {
                    val si = ySize + ((h / 2 - 1 - dx) * (w / 2) + dy) * 2
                    out[i++] = src[si]; out[i++] = src[si + 1]
                }
                Triple(out, h, w)
            }
            270 -> {
                var i = 0
                for (dy in 0 until w) for (dx in 0 until h) out[i++] = src[(w - 1 - dy) * w + dx]
                for (dy in 0 until w / 2) for (dx in 0 until h / 2) {
                    val si = ySize + ((w / 2 - 1 - dy) * (w / 2) + dx) * 2
                    out[i++] = src[si]; out[i++] = src[si + 1]
                }
                Triple(out, h, w)
            }
            180 -> {
                var i = 0
                for (dy in 0 until h) for (dx in 0 until w) out[i++] = src[(h - 1 - dy) * w + (w - 1 - dx)]
                for (dy in 0 until h / 2) for (dx in 0 until w / 2) {
                    val si = ySize + ((h / 2 - 1 - dy) * (w / 2) + (w / 2 - 1 - dx)) * 2
                    out[i++] = src[si]; out[i++] = src[si + 1]
                }
                Triple(out, w, h)
            }
            else -> Triple(src, w, h)
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

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
        cameraHandler?.post {
            camera?.let { c ->
                runCatching { c.stopPreview() }
                runCatching { c.setPreviewCallbackWithBuffer(null) }
                runCatching { c.release() }
            }
            camera = null
            width = 0; height = 0
        }
        PrinterLog.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "CamStreamer"
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 720
    }
}
