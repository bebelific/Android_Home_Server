package com.printserver.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.printserver.core.camera.FrameBus
import com.printserver.core.common.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebcamActivity : ServiceBoundActivity() {

    private lateinit var preview: ImageView
    private lateinit var previewHint: TextView
    private lateinit var textStatus: TextView
    private lateinit var textUsage: TextView
    private lateinit var swTorch: Switch
    private lateinit var swFacing: Switch
    private lateinit var sbFps: SeekBar
    private lateinit var sbQuality: SeekBar
    private lateinit var tvFps: TextView
    private lateinit var tvQuality: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webcam)
        title = getString(R.string.title_webcam)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = findViewById(R.id.imagePreview)
        previewHint = findViewById(R.id.textPreviewHint)
        textStatus = findViewById(R.id.textWebcamStatus)
        textUsage = findViewById(R.id.textWebcamUsage)
        swTorch = findViewById(R.id.swTorch)
        swFacing = findViewById(R.id.swFacing)
        sbFps = findViewById(R.id.sbFps)
        sbQuality = findViewById(R.id.sbQuality)
        tvFps = findViewById(R.id.tvFps)
        tvQuality = findViewById(R.id.tvQuality)

        val prefs = PreferencesManager(this)

        sbFps.max = 25
        sbFps.progress = prefs.mjpegFps.value - 5
        tvFps.text = "FPS: ${prefs.mjpegFps.value}"
        sbFps.setOnSeekBarChangeListener(simple { v ->
            val fps = v + 5
            prefs.setMjpegFps(fps)
            camera()?.streamer?.let { it.fpsCap = fps }
            tvFps.text = "FPS: $fps"
        })

        sbQuality.max = 65
        sbQuality.progress = prefs.mjpegQuality.value - 30
        tvQuality.text = "JPEG quality: ${prefs.mjpegQuality.value}%"
        sbQuality.setOnSeekBarChangeListener(simple { v ->
            val q = v + 30
            prefs.setMjpegQuality(q)
            camera()?.streamer?.let { it.jpegQuality = q }
            tvQuality.text = "JPEG quality: $q%"
        })

        bindTorch()
        bindFacing()

        findViewById<Button>(R.id.buttonOpenStream).setOnClickListener {
            val ip = DiscoveryServiceIp() ?: return@setOnClickListener
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$ip:${prefs.mjpegPort.value}/view")))
            }.onFailure {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.buttonCopyUrls).setOnClickListener { copyUrls(prefs) }

        lifecycleScope.launch { previewLoop() }
        handler.post(statusTick)
    }

    private fun DiscoveryServiceIp() = com.printserver.core.discovery.DiscoveryService.localWifiIp(this)

    private fun simple(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    private fun bindTorch() {
        swTorch.setOnCheckedChangeListener(null)
        swTorch.isChecked = camera()?.streamer?.torchRequested ?: false
        swTorch.setOnCheckedChangeListener { _, c ->
            val ok = camera()?.setTorch(c) ?: false
            if (c && !ok) {
                Toast.makeText(this, "Torch unavailable (needs back camera with flash)", Toast.LENGTH_SHORT).show()
                swTorch.isChecked = false
            }
        }
    }

    private fun bindFacing() {
        val prefs = PreferencesManager(this)
        swFacing.setOnCheckedChangeListener(null)
        swFacing.isChecked = prefs.cameraFacingBack.value
        swFacing.setOnCheckedChangeListener { _, back ->
            camera()?.switchCamera(back)
            if (!back) { prefs.setCameraTorch(false); bindTorch() }
        }
    }

    private suspend fun previewLoop() {
        previewActive = true
        var bus: FrameBus? = null
        var sub: FrameBus.Subscription? = null
        try {
            while (previewActive) {
                val cam = camera()
                if (cam == null || !cam.state.value.toString().contains("RUNNING") || !cam.streamer.isRunning) {
                    withContext(Dispatchers.Main) { previewHint.text = "Waiting for camera..." }
                    kotlinx.coroutines.delay(1000)
                    continue
                }
                if (bus == null || sub == null) {
                    bus = cam.bus
                    sub = bus.subscribe()
                    if (sub == null) {
                        withContext(Dispatchers.Main) { previewHint.text = "Viewer limit reached (8)" }
                        kotlinx.coroutines.delay(1000)
                        continue
                    }
                }
                val frame = withContext(Dispatchers.IO) { sub.take(500) } ?: continue
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(frame, 0, frame.size) }
                if (bmp != null) withContext(Dispatchers.Main) {
                    preview.setImageBitmap(bmp)
                    previewHint.text = ""
                }
            }
        } finally {
            previewActive = false
            val b = bus
            val s = sub
            if (b != null && s != null) b.unsubscribe(s)
        }
    }

    @Volatile private var previewActive = false

    private fun refreshStatus() {
        val cam = camera()
        val prefs = PreferencesManager(this)
        if (cam == null) { textStatus.text = "Service: unavailable"; return }
        textStatus.text = buildString {
            append("Service: ${cam.state.value.name.lowercase()}\n")
            append("Camera: ${if (cam.streamer.isRunning) "streaming ${cam.streamer.resolution}" else "not attached (auto-retry)"}\n")
            append("Torch: ${if (cam.streamer.torchRequested) "on" else "off"}   Facing: ${if (prefs.cameraFacingBack.value) "back" else "front"}")
        }
        textUsage.text = buildString {
            append("Viewers: ${cam.bus.subscriberCount}/8 (this page counts as one)\n")
            append("Frames served: ${cam.bus.frameCount}\n")
            val ip = DiscoveryServiceIp() ?: "<phone-ip>"
            append("Viewer (any browser): http://$ip:${prefs.mjpegPort.value}/view\n")
            append("Stream (VLC etc.): http://$ip:${prefs.mjpegPort.value}/stream\n")
            append("Snapshot: /snapshot.jpg    Status: /status\n")
            append("Tip: lower FPS/quality on weak Wi-Fi. If the phone locks, the camera auto-recovers within ~20 s.")
        }
    }

    private fun copyUrls(prefs: PreferencesManager) {
        val ip = DiscoveryServiceIp() ?: return
        val text = "Viewer: http://$ip:${prefs.mjpegPort.value}/view\n" +
            "Stream: http://$ip:${prefs.mjpegPort.value}/stream\n" +
            "Snapshot: http://$ip:${prefs.mjpegPort.value}/snapshot.jpg\n" +
            "Status: http://$ip:${prefs.mjpegPort.value}/status"
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AndroidHomeServer webcam", text))
        Toast.makeText(this, "URLs copied", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() { previewActive = false; handler.removeCallbacks(statusTick); super.onPause() }
    override fun onResume() { super.onResume(); bindTorch(); bindFacing(); handler.post(statusTick) }
}
