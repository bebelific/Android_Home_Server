package com.printserver.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.printserver.core.common.PreferencesManager
import com.printserver.core.files.StorageProvider
import java.io.File

class FilesActivity : ServiceBoundActivity() {

    private lateinit var textStatus: TextView
    private lateinit var textRoot: TextView
    private lateinit var textEntries: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        title = getString(R.string.title_files)
        textStatus = findViewById(R.id.textFilesStatus)
        textRoot = findViewById(R.id.textFilesRoot)
        textEntries = findViewById(R.id.textFilesEntries)

        findViewById<Button>(R.id.buttonOpenFiles).setOnClickListener {
            val prefs = PreferencesManager(this)
            val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this) ?: return@setOnClickListener
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$ip:${prefs.webdavPort.value}/")))
            }
        }
        findViewById<Button>(R.id.buttonShareFiles).setOnClickListener { shareUrls() }
        refresh()
    }

    private fun refresh() {
        val fs = files()
        val prefs = PreferencesManager(this)
        val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this) ?: "<phone-ip>"
        textStatus.text = buildString {
            append("Service: ${fs?.state?.value?.name?.lowercase() ?: "unavailable"}\n")
            append("WebDAV/HTTP: http://$ip:${prefs.webdavPort.value}/\n")
            append("FTP: ftp://$ip:${prefs.ftpPort.value}/\n")
            append("Auth: user '${prefs.username.value}', ${if (prefs.passwordHash.value.isBlank()) "no password (open)" else "password set"}")
        }
        val root: File? = fs?.currentRoot()
        if (root != null) {
            textRoot.text = "Share root: ${root.absolutePath}\n" +
                "Free ${StorageProvider.humanSize(root.usableSpace)} of ${StorageProvider.humanSize(root.totalSpace)}"
            val entries = root.listFiles()
            textEntries.text = if (entries.isNullOrEmpty()) "(empty — upload via browser or FTP)"
            else "Top level: ${entries.size} item(s)\n" + entries.sortedBy { it.name.lowercase() }
                .take(8).joinToString("\n") {
                    val icon = if (it.isDirectory) "[dir] " else "[file] "
                    "  $icon${it.name}${if (it.isDirectory) "" else "  (${StorageProvider.humanSize(it.length())})"}"
                }
        } else {
            textRoot.text = "Share root: unavailable (service stopped)"
            textEntries.text = ""
        }
    }

    private fun shareUrls() {
        val prefs = PreferencesManager(this)
        val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this) ?: return
        val text = "WebDAV: http://$ip:${prefs.webdavPort.value}/\nFTP: ftp://$ip:${prefs.ftpPort.value}/"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share server URLs")) }
            .onFailure { Toast.makeText(this, "No share target", Toast.LENGTH_SHORT).show() }
    }
}
