package com.g992.anhud

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout

class SettingsActivity : ScaledActivity() {
    private lateinit var tabLayout: TabLayout
    private lateinit var tabGeneralContent: View
    private lateinit var tabManeuverContent: View
    private lateinit var tabDebugContent: View
    private lateinit var cameraTimeoutNearInput: EditText
    private lateinit var cameraTimeoutFarInput: EditText
    private lateinit var trafficLightTimeoutInput: EditText
    private lateinit var speedCorrectionSeek: SeekBar
    private lateinit var speedCorrectionValue: TextView
    private lateinit var debugLogsButton: View
    private lateinit var maneuverMatchButton: View

    private var isSyncingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<TextView>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }

        tabLayout = findViewById(R.id.tabLayout)
        tabGeneralContent = findViewById(R.id.tabGeneralContent)
        tabManeuverContent = findViewById(R.id.tabManeuverContent)
        tabDebugContent = findViewById(R.id.tabDebugContent)
        cameraTimeoutNearInput = findViewById(R.id.cameraTimeoutNearInput)
        cameraTimeoutFarInput = findViewById(R.id.cameraTimeoutFarInput)
        trafficLightTimeoutInput = findViewById(R.id.trafficLightTimeoutInput)
        speedCorrectionSeek = findViewById(R.id.speedCorrectionSeek)
        speedCorrectionValue = findViewById(R.id.speedCorrectionValue)
        debugLogsButton = findViewById(R.id.btnDebugLogs)
        maneuverMatchButton = findViewById(R.id.btnManeuverMatch)

        setupTabs()
        setupGeneralSettings()
        setupManeuverTab()
        setupDebugTab()
        syncUiFromPrefs()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_general_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_nav_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_debug))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        tabGeneralContent.visibility = View.VISIBLE
                        tabManeuverContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                    }
                    1 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.VISIBLE
                        tabDebugContent.visibility = View.GONE
                    }
                    2 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabDebugContent.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupGeneralSettings() {
        cameraTimeoutNearInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setCameraTimeoutNear(this@SettingsActivity, clamped)
            }
        })

        cameraTimeoutFarInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setCameraTimeoutFar(this@SettingsActivity, clamped)
            }
        })

        trafficLightTimeoutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setTrafficLightTimeout(this@SettingsActivity, clamped)
            }
        })

        speedCorrectionSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val correction = progress - 10
                speedCorrectionValue.text = getString(R.string.speed_correction_value, correction)
                if (!isSyncingUi && fromUser) {
                    OverlayPrefs.setSpeedCorrection(this@SettingsActivity, correction)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupManeuverTab() {
        maneuverMatchButton.setOnClickListener {
            startActivity(Intent(this, ManeuverMatchActivity::class.java))
        }
    }

    private fun setupDebugTab() {
        debugLogsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
    }

    private fun syncUiFromPrefs() {
        isSyncingUi = true
        try {
            cameraTimeoutNearInput.setText(OverlayPrefs.cameraTimeoutNear(this).toString())
            cameraTimeoutFarInput.setText(OverlayPrefs.cameraTimeoutFar(this).toString())
            trafficLightTimeoutInput.setText(OverlayPrefs.trafficLightTimeout(this).toString())
            val correction = OverlayPrefs.speedCorrection(this)
            speedCorrectionSeek.progress = correction + 10
            speedCorrectionValue.text = getString(R.string.speed_correction_value, correction)
        } finally {
            isSyncingUi = false
        }
    }
}
