package com.aifusion.scanner

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Setup for a self-powered 360 turntable.
 * The phone does not control the motor; it calibrates one revolution and schedules camera captures.
 */
class TurntableSetupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("turntable_scan", MODE_PRIVATE) }
    private var timingStartedAt: Long? = null
    private lateinit var info: TextView
    private lateinit var calibrateButton: Button
    private lateinit var enableButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(Color.rgb(11, 16, 24))
        }
        page.addView(TextView(this).apply {
            text = "Hands-Free 360° Scan"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        page.addView(TextView(this).apply {
            text = "Kapit telefon pada arm holder. Letak objek di tengah platform. Motor berpusing sendiri; telefon kekal pegun."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(16))
        })
        info = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(27, 36, 48))
        }
        page.addView(info)
        calibrateButton = Button(this).apply {
            text = "Mula ukur satu pusingan"
            isAllCaps = false
            setOnClickListener { handleCalibration() }
        }
        page.addView(calibrateButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(16) })
        enableButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                val period = prefs.getLong(KEY_ROTATION_MS, 0L)
                if (period <= 0L) {
                    Toast.makeText(this@TurntableSetupActivity, "Ukur satu pusingan penuh dahulu", Toast.LENGTH_LONG).show()
                } else {
                    val enabled = !prefs.getBoolean(KEY_ENABLED, false)
                    prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
                    refresh()
                    Toast.makeText(this@TurntableSetupActivity,
                        if (enabled) "360 Auto Capture diaktifkan" else "360 Auto Capture dimatikan",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
        page.addView(enableButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        page.addView(TextView(this).apply {
            text = "Cara kalibrasi: hidupkan platform. Tekan mula apabila tanda pada platform berada di depan, kemudian tekan sekali lagi apabila tanda itu kembali ke kedudukan asal. Buat sekurang-kurangnya satu pusingan penuh pada kelajuan biasa."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(16), 0, dp(12))
        })
        page.addView(TextView(this).apply {
            text = "Semasa scan, aplikasi merakam imej automatik pada sela masa berdasarkan tempoh pusingan (sasaran 24 sampel setiap pusingan, minimum 0.7 saat antara tangkapan). Ini tidak mengawal motor dan belum bermaksud imej sudah digabungkan menjadi mesh 360° penuh."
            textSize = 13f
            setTextColor(Color.rgb(255, 205, 120))
            setPadding(0, dp(4), 0, dp(18))
        })
        page.addView(Button(this).apply {
            text = "Kembali ke scanner"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(50)))
        setContentView(page)
        refresh()
    }

    private fun handleCalibration() {
        val now = SystemClock.elapsedRealtime()
        val started = timingStartedAt
        if (started == null) {
            timingStartedAt = now
            calibrateButton.text = "Tamat pusingan — tekan bila kembali"
            info.text = "Sedang mengukur masa satu pusingan…"
        } else {
            val elapsed = now - started
            timingStartedAt = null
            if (elapsed < 2000L || elapsed > 180000L) {
                Toast.makeText(this, "Masa tidak munasabah. Cuba ukur semula (2–180 saat).", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putLong(KEY_ROTATION_MS, elapsed).putBoolean(KEY_ENABLED, false).apply()
                Toast.makeText(this, "Kalibrasi disimpan: " + (elapsed / 1000.0).toString() + " saat", Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun refresh() {
        val period = prefs.getLong(KEY_ROTATION_MS, 0L)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        info.text = if (period > 0L) {
            "Tempoh pusingan: " + String.format(java.util.Locale.US, "%.1f saat", period / 1000.0) +
                "\nAnggaran sela tangkapan: " + String.format(java.util.Locale.US, "%.2f saat", (period / 24f / 1000f).coerceAtLeast(0.7f)) +
                "\nStatus: " + if (enabled) "360 Auto Capture AKTIF" else "360 Auto Capture tidak aktif"
        } else {
            "Tempoh pusingan belum diketahui. Ukur satu pusingan penuh untuk menetapkan sela tangkapan."
        }
        calibrateButton.text = if (timingStartedAt == null) "Mula ukur satu pusingan" else "Tamat pusingan — tekan bila kembali"
        enableButton.text = if (enabled) "Matikan 360 Auto Capture" else "Aktifkan 360 Auto Capture"
        enableButton.isEnabled = period > 0L && timingStartedAt == null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_ROTATION_MS = "rotation_ms"
        private const val KEY_ENABLED = "enabled"
    }
}
