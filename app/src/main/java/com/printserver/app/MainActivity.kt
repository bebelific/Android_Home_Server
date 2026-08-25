package com.printserver.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.printserver.core.common.ServiceState
import com.printserver.core.print.PrintService

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var bound: HomeServerService? = null
    private var serviceBound = false

    private lateinit var textStatus: TextView
    private lateinit var textAddresses: TextView
    private lateinit var textPower: TextView
    private lateinit var textUptime: TextView
    private lateinit var textError: TextView
    private lateinit var textLogs: TextView
    private lateinit var buttonUsb: Button

    private data class Card(val id: String, val name: String, val subtitle: String, val icon: Int)
    private val cards = mutableMapOf<String, Pair<CardView, Card>>()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        buildCards()
        bindToService()
        requestRuntimePermissions()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun bindViews() {
        textStatus = findViewById(R.id.textStatus)
        textAddresses = findViewById(R.id.textAddresses)
        textPower = findViewById(R.id.textPower)
        textUptime = findViewById(R.id.textUptime)
        textError = findViewById(R.id.textError)
        textLogs = findViewById(R.id.textLogs)
        buttonUsb = findViewById(R.id.buttonUsb)
        buttonUsb.setOnClickListener {
            (bound?.printService())?.requestUsbPermission {
                runOnUiThread { refresh() }
            }
        }
        findViewById<Button>(R.id.buttonLogs).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<Button>(R.id.buttonSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.buttonRestartAll).setOnClickListener { bound?.restartAll() }
        findViewById<Button>(R.id.buttonAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun printServiceOf(): PrintService? =
        bound?.printService()

    private fun buildCards() {
        val container = findViewById<LinearLayout>(R.id.serviceCardsContainer)
        val defs = listOf(
            Card(HomeServerService.ID_PRINT, "Print Server", "Raw 9100 -> USB printer", R.drawable.ic_print),
            Card(HomeServerService.ID_FILES, "File Sharing", "WebDAV + FTP + browser", R.drawable.ic_folder),
            Card("media", "Media Server", "Stream videos & music", R.drawable.ic_media),
            Card(HomeServerService.ID_BACKUP, "Photo Backup", "DCIM → share + Drive", R.drawable.ic_backup),
            Card(HomeServerService.ID_WEBCAM, "Webcam", "MJPEG stream + snapshots", R.drawable.ic_camera),
            Card(HomeServerService.ID_ADBLOCK, "Ad Block DNS", "Pi-hole-style filter", R.drawable.ic_shield),
            Card(HomeServerService.ID_DISCOVERY, "Discovery", "mDNS/Bonjour advertise", R.drawable.ic_discovery),
        )
        for (d in defs) {
            val v = CardView(this)
            val serviceId = if (d.id == "media") HomeServerService.ID_FILES else d.id
            v.bind(d) { checked -> bound?.onToggle(serviceId, checked) }
            v.root.setOnClickListener {
                val target = when (d.id) {
                    HomeServerService.ID_PRINT -> PrintActivity::class.java
                    HomeServerService.ID_FILES -> FilesActivity::class.java
                    "media" -> MediaActivity::class.java
                    HomeServerService.ID_BACKUP -> BackupActivity::class.java
                    HomeServerService.ID_WEBCAM -> WebcamActivity::class.java
                    HomeServerService.ID_ADBLOCK -> AdBlockActivity::class.java
                    HomeServerService.ID_DISCOVERY -> DiscoveryActivity::class.java
                    else -> null
                }
                if (target != null) startActivity(Intent(this, target))
            }
            container.addView(v.root)
            cards[d.id] = v to d
        }
    }

    private fun bindToService() {
        val intent = Intent(this, HomeServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            bound = (service as HomeServerService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = null }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            wanted += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT <= 32) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun refresh() {
        val svcList = bound?.services()?.allServices ?: emptyList()
        var running = 0
        for ((pair, def) in cards.values) {
            val lookupId = if (def.id == "media") HomeServerService.ID_FILES else def.id
            val s = svcList.firstOrNull { it.id == lookupId }
            pair.update(s?.state?.value ?: ServiceState.DISABLED)
            if (s?.state?.value == ServiceState.RUNNING && def.id != "media") running++
        }
        val total = cards.values.count { it.second.id != "media" }
        textStatus.text = when {
            running == total && total > 0 -> "ALL RUNNING"
            running > 0 -> "$running / $total RUNNING"
            else -> "STOPPED"
        }
        textStatus.setTextColor(
            when {
                running == total && total > 0 -> Color.parseColor("#2E7D32")
                running > 0 -> Color.parseColor("#F57F17")
                else -> Color.GRAY
            }
        )
        val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this)
        val ports = bound?.portSummary() ?: ""
        textAddresses.text = if (ip != null) "$ip$ports" else "No Wi-Fi network"
        buttonUsb.visibility =
            if (printServiceOf()?.needsUsbPermission() == true) View.VISIBLE else View.GONE
        textError.visibility = View.GONE
        textLogs.text = bound?.logTail() ?: ""

        batteryLine()?.let { textPower.text = it } ?: run { textPower.text = "Battery: ?" }
        textUptime.text = uptimeLine()
    }

    private fun batteryLine(): String? {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (l >= 0 && s > 0) l * 100 / s else -1
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return "Battery: $pct%  ${"%.1f".format(t)}C  ${if (plugged) "charging" else "on battery"}  ${com.printserver.core.power.ChargeGuard.statusShort()}"
    }

    private fun uptimeLine(): String {
        val up = if (Build.VERSION.SDK_INT >= 24) {
            System.currentTimeMillis() - android.os.Process.getStartElapsedRealtime()
        } else {
            android.os.SystemClock.elapsedRealtime()
        }
        val d = up / 86400000L
        val h = up % 86400000L / 3600000L
        val m = up % 3600000L / 60000L
        val prefix = if (Build.VERSION.SDK_INT >= 24) "App uptime" else "Device uptime"
        return "$prefix: ${d}d ${h}h ${m}m"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class CardView(ctx: Context) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0x10888888.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = lp
        }
        private val icon = ImageView(ctx).apply {
            val lp = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) }
            layoutParams = lp
        }
        private val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        private val title = TextView(ctx).apply { textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        private val sub = TextView(ctx).apply { textSize = 12f; setTextColor(Color.GRAY) }
        private val sw = SwitchCompat(ctx)

        init {
            texts.addView(title); texts.addView(sub)
            root.addView(icon); root.addView(texts); root.addView(sw)
        }

        @Volatile private var suppressCallbacks = false
        private var subtitleText = ""
        fun hideSwitch() { sw.visibility = View.GONE }
        fun bind(def: Card, onChange: (Boolean) -> Unit) {
            icon.setImageResource(def.icon)
            title.text = def.name
            subtitleText = def.subtitle
            sub.text = def.subtitle
            sw.setOnCheckedChangeListener { _, checked ->
                if (!suppressCallbacks) onChange(checked)
            }
        }

        fun update(state: ServiceState) {
            suppressCallbacks = true
            sw.isChecked = state == ServiceState.RUNNING || state == ServiceState.STARTING
            suppressCallbacks = false
            sub.setTextColor(
                when (state) {
                    ServiceState.RUNNING -> Color.parseColor("#2E7D32")
                    ServiceState.STARTING, ServiceState.STOPPING -> Color.parseColor("#F57F17")
                    ServiceState.ERROR -> Color.RED
                    else -> Color.GRAY
                }
            )
            sub.text = when (state) {
                ServiceState.RUNNING -> "running"
                ServiceState.STARTING -> "starting..."
                ServiceState.STOPPING -> "stopping..."
                ServiceState.ERROR -> "error (see logs)"
                else -> subtitleText
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (serviceBound) runCatching { unbindService(connection) }
    }
}
