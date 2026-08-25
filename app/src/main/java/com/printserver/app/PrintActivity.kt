package com.printserver.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.ServiceState

class PrintActivity : ServiceBoundActivity() {

    private lateinit var textStatus: TextView
    private lateinit var textPrinter: TextView
    private lateinit var textJobs: TextView
    private lateinit var buttonUsb: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print)
        title = getString(R.string.title_print)
        textStatus = findViewById(R.id.textPrintStatus)
        textPrinter = findViewById(R.id.textPrinterInfo)
        textJobs = findViewById(R.id.textPrintJobs)
        buttonUsb = findViewById(R.id.buttonUsbPerm)
        buttonUsb.setOnClickListener {
            print()?.requestUsbPermission { runOnUiThread { refresh() } }
        }
        findViewById<Button>(R.id.buttonReprint).setOnClickListener {
            val b = android.widget.Toast.makeText(this, "Sending...", android.widget.Toast.LENGTH_SHORT)
            b.show()
            lifecycleScope.launch {
                val r = print()?.reprintLast()
                android.widget.Toast.makeText(
                    this@PrintActivity,
                    r?.fold({ it }, { "Failed: ${it.message}" }) ?: "Service unavailable",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        }
    }

    override fun onServiceReady() { handler.post(tick) }
    override fun onResume() { super.onResume(); if (server != null) handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val ps = print() ?: run { textStatus.text = "Service: unavailable"; return }
        val prefs = PreferencesManager(this)
        textStatus.text = buildString {
            append("Service: ${ps.state.value.name.lowercase()}\n")
            append("Raw port: ${prefs.printPort.value} (JetDirect)\n")
            append("Mode: pass-through — your PC's driver renders, phone streams bytes")
        }
        val desc = ps.printerDescription()
        textPrinter.text = when {
            desc == null -> "USB printer: none detected (connect via OTG)"
            ps.needsUsbPermission() -> "USB printer: $desc — permission required"
            else -> "USB printer: $desc — ready"
        }
        buttonUsb.visibility = if (ps.needsUsbPermission()) View.VISIBLE else View.GONE

        val (active, recent) = ps.jobSnapshot()
        textJobs.text = buildString {
            append("Active: ${active?.let { "#${it.id} ${it.state} ↑${it.bytesReceived / 1024}KB ↓${it.bytesSent / 1024}KB" } ?: "none"}\n")
            if (recent.isEmpty()) {
                append("Recent: (no completed jobs yet)")
            } else {
                append("Recent:\n")
                append(recent.joinToString("\n") {
                    "  #${it.id} ${it.state} ↑${it.bytesReceived / 1024}KB ↓${it.bytesSent / 1024}KB${it.error?.let { e -> "  $e" } ?: ""}"
                })
            }
        }

        if (ps.state.value == ServiceState.ERROR) textStatus.append("\nCheck Logs — port may be in use.")
    }
}
