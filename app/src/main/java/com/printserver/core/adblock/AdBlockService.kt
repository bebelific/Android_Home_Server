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

    fun blocklistSize(): Int = blockset.size
    fun stats() = server?.let { Triple(it.total.get(), it.blocked.get(), it.recentBlocked()) }
    fun usingCustomList(): Boolean = listFile.exists() && listFile.length() > 1024

    override suspend fun start(context: Context): Result<Unit> {
        _state.value = ServiceState.STARTING
        return try {
            listFile = File(context.filesDir, "adblock_hosts.txt")
            loadList()
            server = DnsFilterServer({ prefs.adblockPort.value }, { blockset })
            server?.start()
            _state.value = ServiceState.RUNNING
            PrinterLog.i(TAG, "Running on port ${prefs.adblockPort.value}")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            PrinterLog.e(TAG, "Start failed: ${e.message}")
            Result.failure(e)
        }
    }

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
        val f = listFile
        if (f.exists() && f.length() > 1024) {
            blockset = f.readLines().asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .map { it.substringAfterLast('\t').substringAfter(' ') }
                .toSet()
        } else {
            blockset = defaultBlocklist
        }
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
