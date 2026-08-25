package com.printserver.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.ServiceState
import com.printserver.core.power.InternetWatchdog
import com.printserver.core.print.PrintService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())
    private var bound: HomeServerService? = null
    private var serviceBound = false

    private lateinit var textStatus: TextView
    private lateinit var textNetwork: TextView
    private lateinit var textAddresses: TextView
    private lateinit var textPower: TextView
    private lateinit var textUptime: TextView
    private lateinit var textError: TextView
    private lateinit var textLogs: TextView
    private lateinit var buttonUsb: Button
    private lateinit var saverOverlay: View
    private lateinit var saverRain: MatrixRainView
    private lateinit var saverClockBlock: View
    private lateinit var tvSaverClock: TextView
    private lateinit var tvSaverDate: TextView
    private lateinit var tvSaverStatus: TextView
    private var logoBmp: android.graphics.Bitmap? = null

    private data class Card(val id: String, val name: String, val subtitle: String, val icon: Int)
    private val cards = LinkedHashMap<String, Pair<CardView, Card>>()

    private val refreshRunnable = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }
    private val idleRunnable = Runnable { showSaver() }

    private class Floater(val view: View, var vx: Float, var vy: Float)
    private val floaters = mutableListOf<Floater>()
    private var saverOn = false
    private var lastStatusText = 0L
    private val idleRunnable2: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        setContentView(R.layout.activity_main)
        bindViews()
        buildCards()
        bindToService()
        applyKeepScreenOn()
        requestRuntimePermissions()
    }

    override fun onStart() { super.onStart(); handler.post(refreshRunnable) }
    override fun onStop() { handler.removeCallbacks(refreshRunnable); super.onStop() }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (saverOn) hideSaver()
        resetIdle()
    }

    private fun applyKeepScreenOn() {
        if (prefs.displayKeepOn.value) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleRunnable)
        if (prefs.displayKeepOn.value) handler.postDelayed(idleRunnable, IDLE_MS)
    }

    private fun showSaver() {
        saverOn = true
        saverOverlay.visibility = View.VISIBLE
        val showRain = prefs.saverShowLogo.value
        saverRain.visibility = if (showRain) View.VISIBLE else View.GONE
        saverClockBlock.visibility = if (prefs.saverShowClock.value) View.VISIBLE else View.GONE
        tvSaverStatus.visibility = if (prefs.saverShowStatus.value) View.VISIBLE else View.GONE
        if (showRain) {
            if (logoBmp == null) {
                logoBmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.saver_logo)
            }
            logoBmp?.let { saverRain.setLogoSource(it) }
        }
        floaters.clear()
        saverOverlay.post {
            val w = saverOverlay.width
            val h = saverOverlay.height
            if (w <= 0 || h <= 0) return@post
            val base = floatSpeedDp()
            for (f in listOf(saverClockBlock, tvSaverStatus)) {
                if (f.visibility != View.VISIBLE) continue
                f.translationX = (0..(w - f.width).coerceAtLeast(0)).random().toFloat()
                f.translationY = (0..(h - f.height).coerceAtLeast(0)).random().toFloat()
                val angle = Math.toRadians((0..359).random().toDouble())
                val jitter = 0.85 + Math.random() * 0.3
                floaters.add(Floater(f, (base * jitter * Math.cos(angle)).toFloat(), (base * jitter * Math.sin(angle)).toFloat()))
            }
            handler.post(frameTick)
        }
    }

    private fun floatSpeedDp(): Float = when (prefs.saverSpeed.value) {
        0 -> 14f; 2 -> 55f; else -> 30f
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!saverOn) return
            if (saverRain.visibility == View.VISIBLE) {
                saverRain.step(prefs.saverSpeed.value)
                saverRain.invalidate()
            }
            val w = saverOverlay.width
            val h = saverOverlay.height
            val dt = 1f / 30f
            for (f in floaters) {
                var x = f.view.translationX + f.vx * dt * resources.displayMetrics.density
                var y = f.view.translationY + f.vy * dt * resources.displayMetrics.density
                val fw = f.view.width.coerceAtLeast(1)
                val fh = f.view.height.coerceAtLeast(1)
                if (x < 0) { x = 0f; f.vx = abs(f.vx) }
                if (x + fw > w) { x = (w - fw).toFloat(); f.vx = -abs(f.vx) }
                if (y < 0) { y = 0f; f.vy = abs(f.vy) }
                if (y + fh > h) { y = (h - fh).toFloat(); f.vy = -abs(f.vy) }
                f.view.translationX = x
                f.view.translationY = y
            }
            val now = System.currentTimeMillis()
            if (now - lastStatusText > 1000) {
                lastStatusText = now
                updateSaverStatusText()
            }
            handler.postDelayed(this, 33)
        }
    }

    private fun updateSaverStatusText() {
        val now = System.currentTimeMillis()
        tvSaverClock.text = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
        tvSaverDate.text = SimpleDateFormat("EEE · d MMM", Locale.US).format(Date(now))
        val running = bound?.services()?.allServices?.count { it.state.value == ServiceState.RUNNING } ?: 0
        val net = when (InternetWatchdog.status) {
            InternetWatchdog.Status.ONLINE -> "online"
            InternetWatchdog.Status.OFFLINE -> "offline"
            else -> "—"
        }
        val batt = batteryLine()?.replace("Battery: ", "") ?: ""
        tvSaverStatus.text = "$running services · net $net · $batt"
    }

    private fun hideSaver() {
        saverOn = false
        saverOverlay.visibility = View.GONE
        handler.removeCallbacks(frameTick)
        floaters.clear()
        resetIdle()
    }

    private fun bindViews() {
        textStatus = findViewById(R.id.textStatus)
        textNetwork = findViewById(R.id.textNetwork)
        textAddresses = findViewById(R.id.textAddresses)
        textPower = findViewById(R.id.textPower)
        textUptime = findViewById(R.id.textUptime)
        textError = findViewById(R.id.textError)
        textLogs = findViewById(R.id.textLogs)
        buttonUsb = findViewById(R.id.buttonUsb)
        saverOverlay = findViewById(R.id.saverOverlay)
        saverRain = findViewById(R.id.saverRain)
        saverClockBlock = findViewById(R.id.saverClockBlock)
        tvSaverClock = findViewById(R.id.tvSaverClock)
        tvSaverDate = findViewById(R.id.tvSaverDate)
        tvSaverStatus = findViewById(R.id.tvSaverStatus)

        textStatus.setBackgroundResource(R.drawable.bg_pill)

        buttonUsb.setOnClickListener {
            bound?.printService()?.requestUsbPermission { runOnUiThread { refresh() } }
        }
        findViewById<Button>(R.id.buttonLogs).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<Button>(R.id.buttonSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.buttonRestartAll).setOnClickListener { bound?.restartAll() }
        findViewById<Button>(R.id.buttonAbout).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        findViewById<Button>(R.id.buttonSaver).setOnClickListener { showSaver() }
    }

    private fun buildCards() {
        val grid = findViewById<GridLayout>(R.id.serviceCardsContainer)
        val defs = listOf(
            Card(HomeServerService.ID_PRINT, "Print Server", "Raw 9100 → USB", R.drawable.ic_print),
            Card(HomeServerService.ID_FILES, "File Sharing", "WebDAV + FTP", R.drawable.ic_folder),
            Card("media", "Media Server", "Stream + seek", R.drawable.ic_media),
            Card(HomeServerService.ID_BACKUP, "Photo Backup", "Share + Drive + USB", R.drawable.ic_backup),
            Card(HomeServerService.ID_WEBCAM, "Webcam", "MJPEG + snapshots", R.drawable.ic_camera),
            Card(HomeServerService.ID_ADBLOCK, "Ad Block DNS", "Pi-hole style", R.drawable.ic_shield),
            Card("parental", "Parental", "Kids filter + bedtime", R.drawable.ic_parent),
            Card(HomeServerService.ID_DISCOVERY, "Discovery", "mDNS adverts", R.drawable.ic_discovery),
        )
        for (d in defs) {
            val v = CardView(this)
            val serviceId = when (d.id) {
                "media" -> HomeServerService.ID_FILES
                "parental" -> null
                else -> d.id
            }
            if (serviceId != null) v.bind(d) { checked -> bound?.onToggle(serviceId, checked) }
            else v.bind(d) { checked -> prefs.setPcEnabled(checked) }
            v.root.setOnClickListener {
                val target = when (d.id) {
                    HomeServerService.ID_PRINT -> PrintActivity::class.java
                    HomeServerService.ID_FILES -> FilesActivity::class.java
                    "media" -> MediaActivity::class.java
                    HomeServerService.ID_BACKUP -> BackupActivity::class.java
                    HomeServerService.ID_WEBCAM -> WebcamActivity::class.java
                    HomeServerService.ID_ADBLOCK -> AdBlockActivity::class.java
                    "parental" -> ParentalActivity::class.java
                    HomeServerService.ID_DISCOVERY -> DiscoveryActivity::class.java
                    else -> null
                }
                if (target != null) startActivity(Intent(this, target))
            }
            val lp = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(3), dp(3), dp(3), dp(6))
            }
            v.root.layoutParams = lp
            grid.addView(v.root)
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
        if (Build.VERSION.SDK_INT <= 32 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun refresh() {
        val svcList = bound?.services()?.allServices ?: emptyList()
        var running = 0
        for ((pair, def) in cards.values) {
            val lookupId = if (def.id == "media") HomeServerService.ID_FILES else def.id
            val s = svcList.firstOrNull { it.id == lookupId }
            val state = when {
                def.id == "parental" ->
                    if (prefs.pcEnabled.value) ServiceState.RUNNING else ServiceState.DISABLED
                else -> s?.state?.value ?: ServiceState.DISABLED
            }
            pair.update(state)
            if (state == ServiceState.RUNNING && def.id != "media" && def.id != "parental") running++
        }
        val total = cards.values.count { it.second.id != "media" && it.second.id != "parental" }
        textStatus.text = when {
            running == total && total > 0 -> "ALL RUNNING"
            running > 0 -> "$running / $total RUNNING"
            else -> "STOPPED"
        }
        textStatus.setTextColor(
            when {
                running == total && total > 0 -> Color.parseColor("#4CAF93")
                running > 0 -> Color.parseColor("#F5A623")
                else -> Color.parseColor("#9AA3AF")
            }
        )

        val ip = com.printserver.core.discovery.DiscoveryService.localWifiIp(this)
        val ports = bound?.portSummary().orEmpty()
        textNetwork.text = when {
            !prefs.netWatchEnabled.value -> "Internet: not monitored (enable in Settings)"
            InternetWatchdog.status == InternetWatchdog.Status.ONLINE -> "Internet: ONLINE"
            InternetWatchdog.status == InternetWatchdog.Status.OFFLINE ->
                "Internet: DOWN — tap the alert to share this phone's data via hotspot"
            else -> "Internet: checking…"
        }
        textAddresses.text = (ip ?: "no Wi-Fi") + ports

        buttonUsb.visibility =
            if (bound?.printService()?.needsUsbPermission() == true) View.VISIBLE else View.GONE
        textError.visibility = View.GONE
        textLogs.text = bound?.logTail().orEmpty()

        batteryLine()?.let { textPower.text = it }
        textUptime.text = uptimeLine()
        if (saverOn) updateSaverStatusText()
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_card)
        }
        private val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val dot = View(ctx).apply {
            val lp = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
            layoutParams = lp
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dot)
        }
        private val title = TextView(ctx).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#E6E9EE"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val sw = SwitchCompat(ctx)
        private val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        private val icon = ImageView(ctx).apply {
            val lp = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) }
            layoutParams = lp
            imageTintList = ColorStateList.valueOf(Color.parseColor("#5B6673"))
        }
        private val sub = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#9AA3AF"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        init {
            row1.addView(dot); row1.addView(title); row1.addView(sw)
            row2.addView(icon); row2.addView(sub)
            root.addView(row1); root.addView(row2)
        }

        @Volatile private var suppressCallbacks = false
        private var subtitleText = ""
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
            val color = when (state) {
                ServiceState.RUNNING -> Color.parseColor("#4CAF93")
                ServiceState.STARTING, ServiceState.STOPPING -> Color.parseColor("#F5A623")
                ServiceState.ERROR -> Color.parseColor("#FF5370")
                else -> Color.parseColor("#3A414B")
            }
            dot.backgroundTintList = ColorStateList.valueOf(color)
            sub.setTextColor(
                when (state) {
                    ServiceState.RUNNING -> Color.parseColor("#4CAF93")
                    ServiceState.STARTING, ServiceState.STOPPING -> Color.parseColor("#F5A623")
                    ServiceState.ERROR -> Color.parseColor("#FF5370")
                    else -> Color.parseColor("#9AA3AF")
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

    companion object { private const val IDLE_MS = 5L * 60 * 1000 }
}
