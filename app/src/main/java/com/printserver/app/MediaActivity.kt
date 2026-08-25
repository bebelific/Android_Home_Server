package com.printserver.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.printserver.core.common.PreferencesManager
import com.printserver.core.files.StorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaActivity : ServiceBoundActivity() {

    private lateinit var textSummary: TextView
    private lateinit var container: LinearLayout
    private lateinit var prefs: PreferencesManager

    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "flv", "wmv", "3gp")
    private val audioExt = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        title = getString(R.string.title_media)
        prefs = PreferencesManager(this)
        textSummary = findViewById(R.id.textMediaSummary)
        container = findViewById(R.id.mediaContainer)

        findViewById<Button>(R.id.buttonRescan).setOnClickListener { scan() }
        if (server != null) scan() else {
            lifecycleScope.launch { while (server == null && isActiveCompat()) { kotlinx.coroutines.delay(300) } ; scan() }
        }
    }

    private fun isActiveCompat(): Boolean = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    private fun scan() {
        textSummary.text = "Scanning share..."
        container.removeAllViews()
        val root = files()?.currentRoot() ?: run {
            textSummary.text = "File Sharing service is not running — enable it on the dashboard first."
            return
        }
        lifecycleScope.launch {
            val (videos, audios, skipped) = withContext(Dispatchers.IO) { index(root) }
            val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this@MediaActivity) ?: "<phone-ip>"
            val port = prefs.webdavPort.value
            textSummary.text = buildString {
                append("${videos.size} video(s), ${audios.size} audio file(s)")
                if (skipped > 0) append(" (scan capped, $skipped skipped)")
                append("\nDirect-play URLs: http://$ip:$port/<path>  — seek works (HTTP Range)")
            }
            addSection("VIDEOS", videos, root, ip, port)
            addSection("MUSIC", audios, root, ip, port)
        }
    }

    private fun index(root: File): Triple<MutableList<File>, MutableList<File>, Int> {
        val videos = mutableListOf<File>()
        val audios = mutableListOf<File>()
        var count = 0
        root.walkTopDown()
            .onEnter { it.isDirectory && !it.name.startsWith(".") && count < MAX_FILES }
            .filter { it.isFile }
            .forEach { f ->
                if (count >= MAX_FILES) return@forEach
                val ext = f.extension.lowercase()
                when (ext) {
                    in videoExt -> { videos.add(f); count++ }
                    in audioExt -> { audios.add(f); count++ }
                }
            }
        videos.sortBy { it.name.lowercase() }
        audios.sortBy { it.name.lowercase() }
        val total = videos.size + audios.size
        return Triple(videos, audios, maxOf(0, total - count))
    }

    private fun addSection(title: String, items: List<File>, root: File, ip: String, port: Int) {
        if (items.isEmpty()) return
        val header = TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        container.addView(header)
        val shown = items.take(100)
        for (f in shown) {
            val rel = f.relativeTo(root).path
            val row = TextView(this).apply {
                text = "▶ ${f.name}  (${StorageProvider.humanSize(f.length())})"
                textSize = 13f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(0x0F888888)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4)
                layoutParams = lp
                setOnClickListener { play(f, rel, ip, port) }
            }
            container.addView(row)
        }
        if (items.size > shown.size) {
            container.addView(TextView(this).apply {
                text = "+ ${items.size - shown.size} more (rename/move to prioritize)"
                textSize = 12f; setPadding(dp(8), 0, 0, dp(8))
            })
        }
    }

    private fun play(f: File, rel: String, ip: String, port: Int) {
        val encoded = rel.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val url = "http://$ip:$port/$encoded"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(url), StorageProvider.guessMime(f.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, "No player app found. Install VLC/MX Player, or copy the URL:\n$url", Toast.LENGTH_LONG).show()
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("media", url))
            }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { private const val MAX_FILES = 2000 }
}
