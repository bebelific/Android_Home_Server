package com.printserver.app

import android.os.Bundle
import android.widget.TextView
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.ServiceState

class DiscoveryActivity : ServiceBoundActivity() {

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discovery)
        title = getString(R.string.title_discovery)
        text = findViewById(R.id.textDiscovery)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val prefs = PreferencesManager(this)
        val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this) ?: "<no Wi-Fi>"
        val svc = { id: String ->
            server?.services()?.get(id)?.state?.value == ServiceState.RUNNING
        }
        text.text = buildString {
            append("State: ${if (prefs.discoveryEnabled.value) "enabled" else "disabled"}\n")
            append("Advertising on: $ip (mDNS/Bonjour)\n\n")
            append("Advertised services:\n")
            append("  Files (WebDAV)  _http._tcp            :${prefs.webdavPort.value}  ${if (svc(HomeServerService.ID_FILES)) "▲ live" else "— off"}\n")
            append("  Files (FTP)     _ftp._tcp             :${prefs.ftpPort.value}  ${if (svc(HomeServerService.ID_FILES)) "▲ live" else "— off"}\n")
            append("  Print (raw)     _pdl-datastream._tcp  :${prefs.printPort.value}  ${if (svc(HomeServerService.ID_PRINT)) "▲ live" else "— off"}\n")
            append("  Webcam (MJPEG)  _androidhomeserver._tcp :${prefs.mjpegPort.value}  always\n")
            append("\nWhere it shows up:\n")
            append("  • macOS Finder / iOS Files: Bonjour sidebar\n")
            append("  • Linux (Avahi): 'browse _http._tcp'\n")
            append("  • Windows: map drive to http://$ip:${prefs.webdavPort.value}/\n")
            append("  • Printing: Windows 'Add printer' may auto-list it; else Standard TCP/IP port ${prefs.printPort.value}\n")
            append("\nLAN-only. Nothing is exposed to the internet.")
        }
    }
}
