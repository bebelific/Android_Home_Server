package com.printserver.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.printserver.core.adblock.AdBlockService

class AdBlockActivity : ServiceBoundActivity() {

    private lateinit var text: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adblock)
        title = getString(R.string.title_adblock)
        text = findViewById(R.id.textAdblock)
        findViewById<Button>(R.id.buttonUpdateList).setOnClickListener {
            val svc = adblockSvc()
            if (svc == null) Toast.makeText(this, "Enable Ad Block first", Toast.LENGTH_SHORT).show()
            else {
                Toast.makeText(this, "Downloading StevenBlack hosts (~2.5 MB)...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val r = svc.updateBlocklist(applicationContext)
                    Toast.makeText(
                        this@AdBlockActivity,
                        r.fold({ "Blocklist updated (${it} domains)" }, { "Failed: ${it.message}" }),
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            }
        }
    }

    private fun adblockSvc(): AdBlockService? =
        server?.services()?.get(HomeServerService.ID_ADBLOCK) as? AdBlockService

    override fun onResume() { super.onResume(); refresh(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val svc = adblockSvc()
        val prefs = com.printserver.core.common.PreferencesManager(this)
        val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this) ?: "<phone-ip>"
        val stats = svc?.stats()
        text.text = buildString {
            append("Service: ${svc?.state?.value?.name?.lowercase() ?: "off"}   Port: ${prefs.adblockPort.value}\n")
            append("Blocklist: ${svc?.blocklistSize() ?: 0} domains ${if (svc?.usingCustomList() == true) "(StevenBlack)" else "(built-in starter)"}\n")
            if (stats != null) {
                val (total, blocked, recent) = stats
                val pct = if (total > 0) " (%.1f%%)".format(blocked * 100.0 / total) else ""
                append("Queries: $total   Blocked: $blocked$pct\n")
                if (recent.isNotEmpty()) append("Recent blocks:\n" + recent.take(8).joinToString("\n") { "  ✕ $it" } + "\n")
            }
            append("\nUSE IT (Pi-hole style):\n")
            append("  1. Router: set DHCP DNS server to $ip\n")
            append("     (every device on your Wi-Fi is then protected)\n")
            append("  2. Or per-device: set DNS manually to $ip\n")
            append("  3. Test: nslookup doubleclick.net $ip  → 0.0.0.0\n")
            append("\nNotes:\n")
            append("  • LAN only. Devices must point at this phone — nothing is auto-hijacked.\n")
            append("  • If the phone sleeps, wake/wifi locks keep DNS alive while the toggle is on.\n")
            append("  • Update blocklist weekly for fresh ad/tracker domains.")
        }
    }
}
