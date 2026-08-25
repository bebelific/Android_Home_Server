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
        val port = svc?.actualPort?.takeIf { it > 0 } ?: prefs.adblockPort.value
        text.text = buildString {
            append("Service: ${svc?.state?.value?.name?.lowercase() ?: "off"}   Port: $port\n")
            if (svc?.degraded == true)
                append("⚠ Android blocks port 53 for apps (needs root). Running on $port instead.\n\n")
            append("Blocklist: ${svc?.blocklistSize() ?: 0} domains ${if (svc?.usingCustomList() == true) "(StevenBlack)" else "(built-in starter)"}\n")
            if (stats != null) {
                val (total, blocked, recent) = stats
                val pct = if (total > 0) " (%.1f%%)".format(blocked * 100.0 / total) else ""
                append("Queries: $total   Blocked: $blocked$pct\n")
                if (recent.isNotEmpty()) append("Recent blocks:\n" + recent.take(8).joinToString("\n") { "  ✕ $it" } + "\n")
            }
            append("\nUSE IT (Pi-hole style):\n")
            if (port == 53) {
                append("  1. Router: set DHCP DNS server to $ip\n")
                append("     (every device on your Wi-Fi is then protected)\n")
                append("  2. Or per-device: set DNS manually to $ip\n")
                append("  3. Test: nslookup doubleclick.net $ip  → 0.0.0.0\n")
            } else {
                append("  Standard DNS clients use port 53 only, so options are:\n")
                append("  • Rooted: the app already lifts the port limit when possible\n")
                append("  • Router with NAT rules: redirect LAN udp/tcp :53 → $ip:$port\n")
                append("  • Apps with custom-port DNS (RethinkDNS, Intra, some clients): use $ip:$port\n")
                append("  • Test: nslookup -port=$port doubleclick.net $ip\n")
            }
            append("\nLAN only. Devices must point at this phone — nothing is auto-hijacked.")
        }
    }
}
