package com.printserver.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class AboutActivity : ServiceBoundActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        title = getString(R.string.title_about)

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")

        val info = findViewById<TextView>(R.id.textAbout)
        info.text = buildString {
            append("AndroidHomeServer v$version\n\n")
            append("An old phone, repurposed: print server, file server, webcam — one toggle each, built to run 24/7 on a charger.\n\n")
            append("Device: ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("App ID: $packageName\n\n")
            append("Design: pass-through appliance — the PC renders, the phone serves and streams. LAN-only by design.\n\n")
            append("Libraries: NanoHTTPD, Apache FtpServer, JmDNS, Kotlin Coroutines.\n\n")
            append("Remote access (VPN): install Tailscale — sign in on the phone and your laptop/phone; every service is then reachable via the phone's 100.x.y.z tailnet IP from anywhere. Twingate works too: run Connectors on a small cloud VM/NAS, add this phone's LAN IP as a Resource, and access it through the Twingate client. Both are zero-config at the router and keep everything off the public internet.\n")
        }

        findViewById<Button>(R.id.buttonProject).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bebelific/Android_Home_Server"))) }
                .onFailure { Toast.makeText(this, "No browser", Toast.LENGTH_SHORT).show() }
        }
        findViewById<Button>(R.id.buttonOpenLogs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }
}
