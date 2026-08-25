package com.printserver.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.printserver.core.backup.PhotoBackupService
import java.text.SimpleDateFormat
import java.util.Date

class BackupActivity : ServiceBoundActivity() {

    private lateinit var text: TextView
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        title = getString(R.string.title_backup)
        text = findViewById(R.id.textBackup)

        findViewById<Button>(R.id.buttonPickDrive).setOnClickListener {
            runCatching {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                    42
                )
            }.onFailure { Toast.makeText(this, "Storage picker unavailable", Toast.LENGTH_SHORT).show() }
        }
        findViewById<Button>(R.id.buttonClearDrive).setOnClickListener {
            com.printserver.core.common.PreferencesManager(this).setDriveTreeUri("")
            refresh()
        }
        findViewById<Button>(R.id.buttonRunNow).setOnClickListener {
            val b = backupSvc()
            if (b == null) Toast.makeText(this, "Enable Photo Backup first", Toast.LENGTH_SHORT).show()
            else { b.runNow(applicationContext); Toast.makeText(this, "Backup cycle started", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            if (com.printserver.core.files.DriveSaf.persist(this, uri)) {
                com.printserver.core.common.PreferencesManager(this).setDriveTreeUri(uri.toString())
                Toast.makeText(this, "Drive folder linked", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not persist access", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
    }

    private fun backupSvc(): PhotoBackupService? =
        server?.services()?.get(HomeServerService.ID_BACKUP) as? PhotoBackupService

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val b = backupSvc()
        val prefs = com.printserver.core.common.PreferencesManager(this)
        val drive = prefs.driveTreeUri.value
        text.text = buildString {
            append("Service: ${b?.state?.value?.name?.lowercase() ?: "off"} (toggle on dashboard)\n")
            append("Source: ${b?.sourceDir()?.absolutePath ?: "-"}\n")
            val root = b?.let { runCatching { it.backupRoot(this@BackupActivity) }.getOrNull() }
            append("Local dest: ${root?.let { java.io.File(it, "PhotoBackup").absolutePath } ?: "-"}\n")
            append("Google Drive: ${if (drive.isBlank()) "not linked" else "linked ✓ (uploads on new backup)"}\n")
            append("Interval: every ${prefs.backupIntervalMin.value} min\n")
            if (b != null) {
                append("Last run: ${if (b.lastRunMs == 0L) "never" else fmt.format(Date(b.lastRunMs))}\n")
                append("Last cycle: ${b.lastCopied} copied · total ${b.totalCopied}\n")
                if (b.lastError != null) append("Error: ${b.lastError}\n")
            }
            append("\nHow it works: new photos/videos from the camera folder are copied to the share (and Drive when linked). Nothing is deleted — it is a one-way archive.")
        }
    }
}
