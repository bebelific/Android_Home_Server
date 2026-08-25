package com.printserver.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.printserver.core.common.PreferencesManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PreferencesManager(this)

        val swDark = findViewById<Switch>(R.id.swDarkMode)
        swDark.isChecked = prefs.darkMode.value
        swDark.setOnCheckedChangeListener { _, c ->
            prefs.setDarkMode(c)
            MainActivity.appliedDark = null
            recreate()
        }

        port(R.id.etPrintPort, prefs.printPort.value) { prefs.setPrintPort(it) }
        port(R.id.etWebdavPort, prefs.webdavPort.value) { prefs.setWebdavPort(it) }
        port(R.id.etFtpPort, prefs.ftpPort.value) { prefs.setFtpPort(it) }
        port(R.id.etMjpegPort, prefs.mjpegPort.value) { prefs.setMjpegPort(it) }

        val seek = findViewById<SeekBar>(R.id.sbChargeLimit)
        val label = findViewById<TextView>(R.id.tvChargeLimit)
        val guard = findViewById<TextView>(R.id.tvGuardStatus)
        fun guardText() { guard.text = com.printserver.core.power.ChargeGuard.statusLong() }
        seek.max = 50
        seek.progress = prefs.chargeLimit.value - 50
        label.text = "Charge limit: ${prefs.chargeLimit.value}%"
        guardText()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { prefs.setChargeLimit(p + 50); label.text = "Charge limit: ${p + 50}%" }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val thermal = findViewById<Switch>(R.id.swThermalThrottle)
        thermal.isChecked = prefs.thermalThrottleEnabled.value
        thermal.setOnCheckedChangeListener { _, c -> prefs.setThermalThrottleEnabled(c) }

        val netWatch = findViewById<Switch>(R.id.swNetWatch)
        val tvNet = findViewById<TextView>(R.id.tvNetWatch)
        fun netText() { tvNet.text = com.printserver.core.power.InternetWatchdog.statusLong() }
        netWatch.isChecked = prefs.netWatchEnabled.value
        netText()
        netWatch.setOnCheckedChangeListener { _, c ->
            prefs.setNetWatchEnabled(c)
            if (c) com.printserver.core.power.InternetWatchdog.start(applicationContext) { prefs.netWatchEnabled.value }
            else com.printserver.core.power.InternetWatchdog.stop()
            netText()
        }

        val keepScreen = findViewById<Switch>(R.id.swKeepScreen)
        keepScreen.isChecked = prefs.displayKeepOn.value
        keepScreen.setOnCheckedChangeListener { _, c -> prefs.setDisplayKeepOn(c) }

        val swLogo = findViewById<Switch>(R.id.swSaverLogo)
        swLogo.isChecked = prefs.saverShowLogo.value
        swLogo.setOnCheckedChangeListener { _, c -> prefs.setSaverShowLogo(c) }
        val swClock = findViewById<Switch>(R.id.swSaverClock)
        swClock.isChecked = prefs.saverShowClock.value
        swClock.setOnCheckedChangeListener { _, c -> prefs.setSaverShowClock(c) }
        val swStatus = findViewById<Switch>(R.id.swSaverStatus)
        swStatus.isChecked = prefs.saverShowStatus.value
        swStatus.setOnCheckedChangeListener { _, c -> prefs.setSaverShowStatus(c) }

        val speedBar = findViewById<SeekBar>(R.id.sbSaverSpeed)
        val speedLabel = findViewById<TextView>(R.id.tvSaverSpeed)
        val names = arrayOf("slow", "normal", "fast")
        speedBar.progress = prefs.saverSpeed.value
        speedLabel.text = "Drift speed: ${names[prefs.saverSpeed.value]}"
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { prefs.setSaverSpeed(p); speedLabel.text = "Drift speed: ${names[p]}" }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val user = findViewById<EditText>(R.id.etUsername)
        val pass = findViewById<EditText>(R.id.etPassword)
        user.setText(prefs.username.value)
        user.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) prefs.setUsername(user.text.toString()) }
        pass.hint = if (prefs.passwordHash.value.isBlank()) "Password (none set — open access)" else "New password"
        pass.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && pass.text.isNotBlank()) {
                prefs.setCredentials(user.text.toString(), pass.text.toString())
                pass.text.clear()
                Toast.makeText(this, "Credentials saved", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnBatteryWhitelist).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        }
        findViewById<Button>(R.id.btnOemWhitelist).setOnClickListener { openOemPanels() }
        findViewById<Button>(R.id.btnFactoryReset).setOnClickListener { confirmReset() }
    }

    private fun port(id: Int, current: Int, save: (Int) -> Unit) {
        val et = findViewById<EditText>(id)
        et.setText(current.toString())
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = et.text.toString().toIntOrNull()
                if (v != null && v in 1024..65535) save(v) else et.setText(current.toString())
            }
        }
    }

    private fun openOemPanels() {
        val comps = listOf(
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
        )
        var opened = false
        for (c in comps) {
            try {
                val pkg = c.substringBefore('/')
                val cls = c.substringAfter('/')
                startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                opened = true
                break
            } catch (_: Exception) {}
        }
        if (!opened) Toast.makeText(this, "No known OEM battery panel found", Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Factory reset")
            .setMessage("Clear all settings and stop all services?")
            .setPositiveButton("Reset") { _, _ ->
                getSharedPreferences("home_server_prefs", MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(this, "Settings cleared. Re-open app to restart services.", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
