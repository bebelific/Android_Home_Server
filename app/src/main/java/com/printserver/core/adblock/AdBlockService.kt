package com.printserver.core.adblock

import android.content.Context
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.Service
import com.printserver.core.common.ServiceState
import com.printserver.core.network.DnsFilterServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AdBlockService(
    private val prefs: PreferencesManager,
) : Service {
    override val id = "adblock"
    override val displayName = "Ad Block DNS"
    override val defaultPort = 53

    private val _state = MutableStateFlow(ServiceState.DISABLED)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()

    @Volatile private var blockset: Set<String> = defaultBlocklist
    @Volatile private var server: DnsFilterServer? = null
    private lateinit var listFile: File
    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var appContext: Context? = null

    @Volatile var actualPort: Int = 0
        private set
    @Volatile var degraded: Boolean = false
        private set

    fun blocklistSize(): Int = blockset.size
    fun stats() = server?.let { Triple(it.total.get(), it.blocked.get(), it.recentBlocked()) }
    fun usingCustomList(): Boolean = listFile.exists() && listFile.length() > 1024

    override suspend fun start(context: Context): Result<Unit> {
        _state.value = ServiceState.STARTING
        return try {
            appContext = context.applicationContext
            listFile = File(context.filesDir, "adblock_hosts.txt")
            loadList()
            val want = prefs.adblockPort.value
            var attempt = want
            degraded = false
            try {
                bindServer(attempt)
            } catch (first: Exception) {
                if (attempt == 53 && liftPortRestriction()) {
                    PrinterLog.i(TAG, "Port restriction lifted via root; retrying 53")
                    try {
                        bindServer(53)
                        attempt = 53
                    } catch (_: Exception) {
                        attempt = 5353
                        degraded = true
                        bindServer(attempt)
                    }
                } else {
                    attempt = 5353
                    degraded = true
                    bindServer(attempt)
                }
            }
            actualPort = attempt
            _state.value = ServiceState.RUNNING
            startAutoRefresh()
            PrinterLog.i(TAG, "Running on 0.0.0.0:$actualPort (degraded=$degraded, blocklist=${blockset.size}, upstream=${prefs.dnsUpstream.value})")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            PrinterLog.e(TAG, "Start failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun bindServer(port: Int) {
        server?.stop()
        server = DnsFilterServer(
            { port },
            { blockset },
            upstream = prefs.dnsUpstream.value,
            extraCheck = { client, domain ->
                com.printserver.core.adblock.ParentalControl.evaluate(prefs, client, domain).blocked
            }
        )
        server?.start()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        val ctx = appContext ?: return
        autoRefreshJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive && _state.value == ServiceState.RUNNING) {
                delay(60 * 60 * 1000L)
                if (!prefs.adblockAutoRefresh.value) continue
                val age = System.currentTimeMillis() - prefs.adblockLastList.value
                if (age > 7L * 24 * 60 * 60 * 1000 && prefs.adblockLastList.value > 0) {
                    PrinterLog.i(TAG, "Auto-refresh: blocklist older than 7 days")
                    updateBlocklist(ctx)
                }
            }
        }
    }

    fun restartFilter() {
        val port = actualPort.takeIf { it > 0 } ?: prefs.adblockPort.value
        runCatching { server?.stop() }
        runCatching { bindServer(port) }
        loadList()
    }

    fun pcBlockedCount(): Long = server?.pcBlocked?.get() ?: 0
    fun clientStats(): Map<String, LongArray> = server?.perClient?.toMap() ?: emptyMap()

    private fun liftPortRestriction(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "echo 0 > /proc/sys/net/ipv4/ip_unprivileged_port_start")
        p.start().waitFor() == 0
    }.getOrDefault(false)

    override suspend fun stop(): Result<Unit> {
        _state.value = ServiceState.STOPPING
        return try {
            server?.stop(); server = null
            _state.value = ServiceState.DISABLED
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
    }

    override fun isHealthy(): Boolean =
        _state.value == ServiceState.RUNNING && server?.running == true

    fun loadList() {
        val base = mutableSetOf<String>()
        val f = listFile
        if (f.exists() && f.length() > 1024) {
            f.readLines().asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .map { it.substringAfterLast('\t').substringAfter(' ') }
                .toSet()
                .let { base.addAll(it) }
        } else {
            base.addAll(defaultBlocklist)
        }
        prefs.adblockCustomBlock.value.split(',', '\n', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .let { base.addAll(it) }
        prefs.adblockAllow.value.split(',', '\n', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .forEach { allow -> base.removeAll { d -> d == allow || d.endsWith(".$allow") } }
        blockset = base
    }

    suspend fun updateBlocklist(context: Context, url: String = DEFAULT_LIST_URL): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                val tmp = File(context.filesDir, "adblock_hosts.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                val lines = tmp.readLines().count {
                    val t = it.trim()
                    t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!") && !t.startsWith(";")
                }
                if (lines < 1000) throw IllegalStateException("download too small ($lines lines)")
                tmp.renameTo(listFile) || (listFile.delete() && tmp.renameTo(listFile))
                loadList()
                prefs.setAdblockLastList(System.currentTimeMillis())
                server?.stop(); server?.start()
                lines
            }
        }

    companion object {
        private const val TAG = "AdBlock"
        const val DEFAULT_LIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        val defaultBlocklist: Set<String> = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "adservice.google.com",
            "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "appsflyer.com",
            "criteo.com", "casalemedia.com", "chartbeat.com", "scorecardresearch.com",
            "taboola.com", "outbrain.com", "moatads.com", "pubmatic.com",
            "rubiconproject.com", "openx.net", "adcolony.com", "unityads.unity3d.com",
            "applovin.com", "ironsrc.com", "mopub.com", "inmobi.com",
            "facebook.net", "connect.facebook.net", "analytics.tiktok.com",
            "ads-twitter.com", "static.ads-twitter.com", "analytics.twitter.com",
            "ads.youtube.com", "youtube.cleverads.vn", "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com", "securepubads.g.doubleclick.net",
            "stats.wp.com", "pixel.wp.com", "segment.io", "segment.com",
            "mixpanel.com", "amplitude.com", "branch.io", "kochava.com",
            "hotjar.com", "fullstory.com", "quantserve.com", "exelator.com",
            "bidswitch.net", "smartadserver.com", "yieldmo.com", "sharethrough.com",
            "33across.com", "gumgum.com", "sonobi.com", "indexexchange.com",
        )
    }
}
