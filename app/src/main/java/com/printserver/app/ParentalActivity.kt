package com.printserver.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.printserver.core.adblock.AdBlockService
import com.printserver.core.adblock.ParentalControl
import com.printserver.core.common.PreferencesManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParentalActivity : ServiceBoundActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var text: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var etAdd: EditText
    private lateinit var swEnabled: Switch
    private lateinit var swAdult: Switch
    private lateinit var swSocial: Switch
    private lateinit var swSchedule: Switch
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private val fmt = SimpleDateFormat("HH:mm", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parental)
        title = getString(R.string.title_parental)
        prefs = PreferencesManager(this)
        text = findViewById(R.id.textParental)
        devicesBox = findViewById(R.id.devicesBox)
        etAdd = findViewById(R.id.etAddDevice)
        swEnabled = findViewById(R.id.swPcEnabled)
        swAdult = findViewById(R.id.swAdult)
        swSocial = findViewById(R.id.swSocial)
        swSchedule = findViewById(R.id.swSchedule)
        etStart = findViewById(R.id.etStart)
        etEnd = findViewById(R.id.etEnd)

        swEnabled.setOnCheckedChangeListener { _, c -> prefs.setPcEnabled(c); refresh() }
        swAdult.setOnCheckedChangeListener { _, c -> prefs.setPcBlockAdult(c) }
        swSocial.setOnCheckedChangeListener { _, c -> prefs.setPcBlockSocial(c) }
        swSchedule.setOnCheckedChangeListener { _, c -> prefs.setPcScheduleEnabled(c); refresh() }

        findViewById<Button>(R.id.buttonAddDevice).setOnClickListener {
            val ip = etAdd.text.toString().trim()
            if (android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) {
                val set = ParentalControl.devices(prefs) + ip
                prefs.setPcDevices(set.joinToString(","))
                etAdd.text.clear()
                refresh()
            } else Toast.makeText(this, "Enter a valid IPv4 address", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.buttonPause15).setOnClickListener { pause(15) }
        findViewById<Button>(R.id.buttonPause60).setOnClickListener { pause(60) }
        findViewById<Button>(R.id.buttonResume).setOnClickListener { prefs.setPcPauseUntil(0); refresh() }
        etStart.setOnFocusChangeListener { _, f -> if (!f) saveTimes() }
        etEnd.setOnFocusChangeListener { _, f -> if (!f) saveTimes() }

        val swGateway = findViewById<Switch>(R.id.swGateway)
        swGateway.isChecked = com.printserver.core.adblock.GatewayMode.active
        swGateway.setOnCheckedChangeListener { _, c ->
            val adblock = server?.services()?.get(HomeServerService.ID_ADBLOCK) as? AdBlockService
            val port = adblock?.actualPort?.takeIf { it > 0 } ?: prefs.adblockPort.value
            if (c) {
                com.printserver.core.adblock.GatewayMode.apply(applicationContext, prefs, port)
                    .onSuccess { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "Gateway mode: ${it.message}", Toast.LENGTH_LONG).show(); swGateway.isChecked = false }
            } else {
                com.printserver.core.adblock.GatewayMode.clear(applicationContext)
                Toast.makeText(this, "Gateway mode off", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
    }

    private fun pause(minutes: Int) {
        prefs.setPcPauseUntil(System.currentTimeMillis() + minutes * 60_000L)
        Toast.makeText(this, "Internet paused $minutes min for kids devices", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun saveTimes() {
        val s = etStart.text.toString().trim()
        val e = etEnd.text.toString().trim()
        val ok = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(s) && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(e)
        if (ok) { prefs.setPcScheduleStart(s); prefs.setPcScheduleEnd(e) }
        else { etStart.setText(prefs.pcScheduleStart.value); etEnd.setText(prefs.pcScheduleEnd.value) }
    }

    override fun onResume() { super.onResume(); refresh(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val adblock = server?.services()?.get(HomeServerService.ID_ADBLOCK) as? AdBlockService
        swEnabled.setOnCheckedChangeListener(null); swEnabled.isChecked = prefs.pcEnabled.value
        swEnabled.setOnCheckedChangeListener { _, c -> prefs.setPcEnabled(c); refresh() }
        swAdult.setOnCheckedChangeListener(null); swAdult.isChecked = prefs.pcBlockAdult.value
        swAdult.setOnCheckedChangeListener { _, c -> prefs.setPcBlockAdult(c) }
        swSocial.setOnCheckedChangeListener(null); swSocial.isChecked = prefs.pcBlockSocial.value
        swSocial.setOnCheckedChangeListener { _, c -> prefs.setPcBlockSocial(c) }
        swSchedule.setOnCheckedChangeListener(null); swSchedule.isChecked = prefs.pcScheduleEnabled.value
        swSchedule.setOnCheckedChangeListener { _, c -> prefs.setPcScheduleEnabled(c); refresh() }
        if (etStart.text.isBlank()) etStart.setText(prefs.pcScheduleStart.value)
        if (etEnd.text.isBlank()) etEnd.setText(prefs.pcScheduleEnd.value)

        val pausedFor = prefs.pcPauseUntil.value - System.currentTimeMillis()
        val stats = adblock?.clientStats().orEmpty()
        val known = ParentalControl.devices(prefs)
        val clients = (stats.keys + known).sorted()

        text.text = buildString {
            append("Kids filter: ${if (prefs.pcEnabled.value) "ON" else "off"}")
            append(if (prefs.pcEnabled.value && adblock?.state?.value != com.printserver.core.common.ServiceState.RUNNING)
                "  ⚠ requires Ad Block DNS running (devices must use this phone as DNS)" else "\n")
            if (pausedFor > 0) append("Paused for ${pausedFor / 60000} min more\n")
            if (prefs.pcScheduleEnabled.value)
                append("Bedtime: ${prefs.pcScheduleStart.value}–${prefs.pcScheduleEnd.value}" +
                    (if (ParentalControl.inScheduleWindow(prefs)) "  (active now)" else "") + "\n")
            append("Blocked (parental): ${adblock?.pcBlockedCount() ?: 0}\n")
            append("Gateway mode: ${com.printserver.core.adblock.GatewayMode.lastStatus.ifBlank { "off" }}")
        }

        devicesBox.removeAllViews()
        for (ip in clients) {
            val st = stats[ip]
            val isKid = ip in known
            val row = TextView(this).apply {
                text = (if (isKid) "🧒 " else "📱 ") + ip +
                    (st?.let { "   ${it[0]} queries · ${it[1]} blocked" } ?: "") +
                    if (isKid) "   [tap to remove]" else "   [tap to add]"
                textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(0x10888888.toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4)
                layoutParams = lp
                setOnClickListener {
                    val set = ParentalControl.devices(prefs).toMutableSet()
                    if (isKid) set.remove(ip) else set.add(ip)
                    prefs.setPcDevices(set.joinToString(","))
                    refresh()
                }
            }
            devicesBox.addView(row)
        }
        if (clients.isEmpty()) devicesBox.addView(TextView(this).apply {
            text = "No clients yet. Point devices at this phone's DNS, or add an IP above."
            textSize = 12f
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
