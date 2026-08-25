package com.printserver.app

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class LogActivity : AppCompatActivity() {

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        text = findViewById(R.id.textLog)
        findViewById<Button>(R.id.buttonRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.buttonClear).setOnClickListener {
            logFile().delete()
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            load()
        }
        load()
    }

    private fun logFile(): File = File(filesDir, "print_server.log")

    private fun load() {
        val f = logFile()
        val body = if (!f.exists()) "(no log yet)"
        else runCatching {
            val bytes = f.length().coerceAtMost(200_000L).toInt()
            f.inputStream().use { ins ->
                if (f.length() > bytes) ins.skip(f.length() - bytes)
                ins.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrDefault("(unreadable)")
        text.text = body.ifBlank { "(empty)" }
        (text.parent as? ScrollView)?.post { (text.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
