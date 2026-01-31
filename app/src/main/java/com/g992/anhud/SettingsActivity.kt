package com.g992.anhud

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumMap
import kotlin.math.roundToInt

class SettingsActivity : ScaledActivity() {
    private lateinit var tabLayout: TabLayout
    private lateinit var tabGeneralContent: View
    private lateinit var tabManeuverContent: View
    private lateinit var tabDebugContent: View
    private lateinit var tabHelpContent: View
    private lateinit var cameraTimeoutNearInput: EditText
    private lateinit var cameraTimeoutFarInput: EditText
    private lateinit var trafficLightTimeoutInput: EditText
    private lateinit var roadCameraTimeoutInput: EditText
    private lateinit var navNotificationEndTimeoutInput: EditText
    private lateinit var speedCorrectionSeek: SeekBar
    private lateinit var speedCorrectionValue: TextView
    private lateinit var maneuverRowContainer: LinearLayout
    private lateinit var helpListContainer: LinearLayout
    private lateinit var helpStartGuideButton: View
    private lateinit var exportSettingsButton: View
    private lateinit var importSettingsButton: View

    private var isSyncingUi = false

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportSettings(uri)
        }
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importSettings(uri)
        }
    }

    private data class LogSection(
        val scrollView: ScrollView,
        val textView: TextView
    )

    private val logSections = EnumMap<LogCategory, LogSection>(LogCategory::class.java)
    private val logListener = object : UiLogStore.Listener {
        override fun onLogsUpdated(category: LogCategory, lines: List<String>) {
            runOnUiThread {
                val section = logSections[category] ?: return@runOnUiThread
                section.textView.text = lines.joinToString("\n")
                section.scrollView.post {
                    section.scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

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
        tabHelpContent = findViewById(R.id.tabHelpContent)
        cameraTimeoutNearInput = findViewById(R.id.cameraTimeoutNearInput)
        cameraTimeoutFarInput = findViewById(R.id.cameraTimeoutFarInput)
        trafficLightTimeoutInput = findViewById(R.id.trafficLightTimeoutInput)
        roadCameraTimeoutInput = findViewById(R.id.roadCameraTimeoutInput)
        navNotificationEndTimeoutInput = findViewById(R.id.navNotificationEndTimeoutInput)
        speedCorrectionSeek = findViewById(R.id.speedCorrectionSeek)
        speedCorrectionValue = findViewById(R.id.speedCorrectionValue)
        maneuverRowContainer = findViewById(R.id.maneuverRowContainer)
        helpListContainer = findViewById(R.id.helpListContainer)
        helpStartGuideButton = findViewById(R.id.helpStartGuideButton)
        exportSettingsButton = findViewById(R.id.exportSettingsButton)
        importSettingsButton = findViewById(R.id.importSettingsButton)

        setupTabs()
        setupGeneralSettings()
        setupManeuverTab()
        setupDebugTab()
        setupHelpTab()
        syncUiFromPrefs()
    }

    override fun onDestroy() {
        UiLogStore.unregisterListener(logListener)
        super.onDestroy()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_general_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_nav_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_debug))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_help))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        tabGeneralContent.visibility = View.VISIBLE
                        tabManeuverContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    1 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.VISIBLE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    2 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabDebugContent.visibility = View.VISIBLE
                        tabHelpContent.visibility = View.GONE
                    }
                    3 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupGeneralSettings() {
        exportSettingsButton.setOnClickListener {
            exportSettingsLauncher.launch("anhud_settings.json")
        }
        importSettingsButton.setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "*/*"))
        }

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

        roadCameraTimeoutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setRoadCameraTimeout(this@SettingsActivity, clamped)
            }
        })

        navNotificationEndTimeoutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setNavNotificationEndTimeout(this@SettingsActivity, clamped)
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

    private fun exportSettings(uri: Uri) {
        try {
            val payload = buildSettingsPayload()
            val json = payload.toString(2)
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: run {
                showToast(R.string.settings_export_failed)
                return
            }
            showToast(R.string.settings_export_success)
        } catch (_: Exception) {
            showToast(R.string.settings_export_failed)
        }
    }

    private fun importSettings(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: run {
                    showToast(R.string.settings_import_failed)
                    return
                }
            val payload = JSONObject(json)
            val prefsObject = payload.optJSONObject("prefs")
                ?: run {
                    showToast(R.string.settings_import_failed)
                    return
                }
            val overlayApplied = applyPrefsFromJson(OVERLAY_PREFS_NAME, prefsObject.optJSONArray(OVERLAY_PREFS_NAME))
            val maneuverApplied = applyPrefsFromJson(MANEUVER_PREFS_NAME, prefsObject.optJSONArray(MANEUVER_PREFS_NAME))
            if (!overlayApplied && !maneuverApplied) {
                showToast(R.string.settings_import_failed)
                return
            }
            syncUiFromPrefs()
            broadcastOverlayPrefs()
            showToast(R.string.settings_import_success)
        } catch (_: Exception) {
            showToast(R.string.settings_import_failed)
        }
    }

    private fun buildSettingsPayload(): JSONObject {
        val payload = JSONObject()
        payload.put("version", 1)
        val prefsObject = JSONObject()
        prefsObject.put(OVERLAY_PREFS_NAME, serializePrefs(getSharedPreferences(OVERLAY_PREFS_NAME, MODE_PRIVATE)))
        prefsObject.put(MANEUVER_PREFS_NAME, serializePrefs(getSharedPreferences(MANEUVER_PREFS_NAME, MODE_PRIVATE)))
        payload.put("prefs", prefsObject)
        return payload
    }

    private fun serializePrefs(prefs: android.content.SharedPreferences): JSONArray {
        val items = JSONArray()
        for ((key, value) in prefs.all) {
            val entry = JSONObject()
            entry.put("k", key)
            when (value) {
                is Boolean -> {
                    entry.put("t", "b")
                    entry.put("v", value)
                }
                is Float -> {
                    entry.put("t", "f")
                    entry.put("v", value.toDouble())
                }
                is Int -> {
                    entry.put("t", "i")
                    entry.put("v", value)
                }
                is Long -> {
                    entry.put("t", "l")
                    entry.put("v", value)
                }
                is String -> {
                    entry.put("t", "s")
                    entry.put("v", value)
                }
                is Set<*> -> {
                    val array = JSONArray()
                    value.filterIsInstance<String>().forEach { array.put(it) }
                    entry.put("t", "ss")
                    entry.put("v", array)
                }
                else -> continue
            }
            items.put(entry)
        }
        return items
    }

    private fun applyPrefsFromJson(prefName: String, entries: JSONArray?): Boolean {
        if (entries == null) {
            return false
        }
        val prefs = getSharedPreferences(prefName, MODE_PRIVATE)
        val editor = prefs.edit()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val key = entry.optString("k", "")
            val type = entry.optString("t", "")
            if (key.isBlank()) continue
            when (type) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "s" -> editor.putString(key, entry.optString("v", ""))
                "ss" -> {
                    val array = entry.optJSONArray("v")
                    val set = mutableSetOf<String>()
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            set.add(array.optString(i))
                        }
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
        return true
    }

    private fun broadcastOverlayPrefs() {
        val intent = Intent(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
            .setPackage(packageName)
        val navPos = OverlayPrefs.navPositionDp(this)
        val arrowPos = OverlayPrefs.arrowPositionDp(this)
        val speedPos = OverlayPrefs.speedPositionDp(this)
        val hudSpeedPos = OverlayPrefs.hudSpeedPositionDp(this)
        val roadCameraPos = OverlayPrefs.roadCameraPositionDp(this)
        val trafficLightPos = OverlayPrefs.trafficLightPositionDp(this)
        val speedometerPos = OverlayPrefs.speedometerPositionDp(this)
        val clockPos = OverlayPrefs.clockPositionDp(this)
        val containerPos = OverlayPrefs.containerPositionDp(this)
        val containerSize = OverlayPrefs.containerSizeDp(this)
        intent.putExtra(OverlayBroadcasts.EXTRA_NAV_X_DP, navPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_NAV_Y_DP, navPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_X_DP, arrowPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_Y_DP, arrowPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, speedPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, speedPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_X_DP, hudSpeedPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_Y_DP, hudSpeedPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_X_DP, roadCameraPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_Y_DP, roadCameraPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_X_DP, trafficLightPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_Y_DP, trafficLightPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_X_DP, speedometerPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_Y_DP, speedometerPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_X_DP, clockPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_Y_DP, clockPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_X_DP, containerPos.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_Y_DP, containerPos.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_WIDTH_DP, containerSize.x)
        intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_HEIGHT_DP, containerSize.y)
        intent.putExtra(OverlayBroadcasts.EXTRA_NAV_SCALE, OverlayPrefs.navScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_NAV_TEXT_SCALE, OverlayPrefs.navTextScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_TEXT_SCALE, OverlayPrefs.speedTextScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_SCALE, OverlayPrefs.arrowScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, OverlayPrefs.speedScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_SCALE, OverlayPrefs.hudSpeedScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_SCALE, OverlayPrefs.roadCameraScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_SCALE, OverlayPrefs.trafficLightScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_SCALE, OverlayPrefs.speedometerScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_SCALE, OverlayPrefs.clockScale(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_NAV_ALPHA, OverlayPrefs.navAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_ALPHA, OverlayPrefs.arrowAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, OverlayPrefs.speedAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ALPHA, OverlayPrefs.hudSpeedAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ALPHA, OverlayPrefs.roadCameraAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ALPHA, OverlayPrefs.trafficLightAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ALPHA, OverlayPrefs.speedometerAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_ALPHA, OverlayPrefs.clockAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_ALPHA, OverlayPrefs.containerAlpha(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_NAV_ENABLED, OverlayPrefs.navEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_ENABLED, OverlayPrefs.arrowEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_ONLY_WHEN_NO_ICON, OverlayPrefs.arrowOnlyWhenNoIcon(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED, OverlayPrefs.speedEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ENABLED, OverlayPrefs.hudSpeedEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ENABLED, OverlayPrefs.roadCameraEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ENABLED, OverlayPrefs.trafficLightEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ENABLED, OverlayPrefs.speedometerEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED, OverlayPrefs.clockEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_MAX_ACTIVE, OverlayPrefs.trafficLightMaxActive(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED, OverlayPrefs.speedLimitAlertEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD, OverlayPrefs.speedLimitAlertThreshold(this))
        sendBroadcast(intent)
    }

    private fun showToast(messageResId: Int) {
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }

    private fun setupManeuverTab() {
        val inflater = LayoutInflater.from(this)
        val container = maneuverRowContainer
        val items = loadContextDrawables()
        val basicIcons = loadBasicIcons()
        if (basicIcons.isEmpty()) {
            return
        }
        val spinnerAdapter = BasicIconAdapter(basicIcons)
        val indexById = basicIcons.mapIndexed { index, icon -> icon.id to index }.toMap()
        val defaultIndex = indexById[DEFAULT_BASIC_ICON_ID] ?: 0
        val prefs = getSharedPreferences(MANEUVER_PREFS_NAME, MODE_PRIVATE)
        val defaults = loadDefaultMappings()
        for ((name, resId) in items) {
            val row = inflater.inflate(R.layout.item_maneuver_match_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.maneuverIcon)
            val label = row.findViewById<TextView>(R.id.maneuverLabel)
            val spinner = row.findViewById<Spinner>(R.id.basicIconSpinner)
            icon.setImageResource(resId)
            icon.contentDescription = name
            label.text = name
            spinner.adapter = spinnerAdapter
            val savedId = prefs.getString(mappingKey(name), null)
            val resolvedId = savedId ?: defaults[name]
            val resolvedIndex = resolvedId?.let { indexById[it] } ?: defaultIndex
            spinner.setSelection(resolvedIndex)
            if (savedId == null && resolvedId != null) {
                prefs.edit().putString(mappingKey(name), resolvedId).apply()
            }
            spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
                val selected = basicIcons[position].id
                prefs.edit().putString(mappingKey(name), selected).apply()
            }
            container.addView(row)
        }
    }

    private fun setupDebugTab() {
        logSections[LogCategory.NAVIGATION] = LogSection(
            findViewById(R.id.navScroll),
            findViewById(R.id.navLogs)
        )
        logSections[LogCategory.SENSORS] = LogSection(
            findViewById(R.id.sensorScroll),
            findViewById(R.id.sensorLogs)
        )
        logSections[LogCategory.SYSTEM] = LogSection(
            findViewById(R.id.systemScroll),
            findViewById(R.id.systemLogs)
        )
        UiLogStore.registerListener(logListener)
    }

    private fun setupHelpTab() {
        helpListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val sectionSpacing = dp(16)
        val itemSpacing = dp(8)
        for ((sectionIndex, section) in GuideContent.helpSections().withIndex()) {
            val titleView = TextView(this).apply {
                setText(section.titleRes)
                setTextColor(getColor(R.color.white))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val titleParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (sectionIndex > 0) {
                titleParams.topMargin = sectionSpacing
            }
            helpListContainer.addView(titleView, titleParams)

            section.items.forEach { item ->
                val row = inflater.inflate(R.layout.item_help_entry, helpListContainer, false)
                row.findViewById<TextView>(R.id.helpItemTitle).setText(item.titleRes)
                row.findViewById<TextView>(R.id.helpItemBody).setText(item.bodyRes)
                val rowParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rowParams.topMargin = itemSpacing
                helpListContainer.addView(row, rowParams)
            }
        }

        helpStartGuideButton.setOnClickListener {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_START_GUIDE, true)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun syncUiFromPrefs() {
        isSyncingUi = true
        try {
            cameraTimeoutNearInput.setText(OverlayPrefs.cameraTimeoutNear(this).toString())
            cameraTimeoutFarInput.setText(OverlayPrefs.cameraTimeoutFar(this).toString())
            trafficLightTimeoutInput.setText(OverlayPrefs.trafficLightTimeout(this).toString())
            roadCameraTimeoutInput.setText(OverlayPrefs.roadCameraTimeout(this).toString())
            navNotificationEndTimeoutInput.setText(OverlayPrefs.navNotificationEndTimeout(this).toString())
            val correction = OverlayPrefs.speedCorrection(this)
            speedCorrectionSeek.progress = correction + 10
            speedCorrectionValue.text = getString(R.string.speed_correction_value, correction)
        } finally {
            isSyncingUi = false
        }
    }

    private fun loadContextDrawables(): List<Pair<String, Int>> {
        val result = ArrayList<Pair<String, Int>>()
        val fields = R.drawable::class.java.fields
        for (field in fields) {
            val name = field.name
            if (!name.startsWith("context_")) {
                continue
            }
            val resId = runCatching { field.getInt(null) }.getOrNull() ?: continue
            result.add(name to resId)
        }
        result.sortBy { it.first }
        return result
    }

    private fun loadBasicIcons(): List<BasicIcon> {
        val files = assets.list("basicIcons")
            ?.filter { it.endsWith(".png") || it.endsWith(".webp") }
            .orEmpty()
        val sorted = files.sortedBy {
            it.substringBefore(".").toIntOrNull() ?: Int.MAX_VALUE
        }
        val icons = ArrayList<BasicIcon>(sorted.size)
        for (file in sorted) {
            val bitmap = assets.open("basicIcons/$file").use {
                BitmapFactory.decodeStream(it)
            } ?: continue
            val id = file.substringBefore(".")
            icons.add(BasicIcon(id, file, bitmap))
        }
        return icons
    }

    private fun loadDefaultMappings(): Map<String, String> {
        val defaults = mutableMapOf<String, String>()
        val content = runCatching {
            assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return defaults
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.lastIndex) {
                return@forEach
            }
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                defaults[key] = value
            }
        }
        return defaults
    }

    private fun mappingKey(name: String): String = "mapping_$name"

    private data class BasicIcon(
        val id: String,
        val fileName: String,
        val bitmap: Bitmap
    )

    private inner class BasicIconAdapter(
        private val items: List<BasicIcon>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): BasicIcon = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createView(position, convertView, parent, false)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createView(position, convertView, parent, true)
        }

        private fun createView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
            dropdown: Boolean
        ): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_basic_icon_spinner, parent, false)
            val icon = view.findViewById<ImageView>(R.id.basicIconImage)
            val label = view.findViewById<TextView>(R.id.basicIconLabel)
            val item = getItem(position)
            icon.setImageBitmap(item.bitmap)
            label.text = item.id
            if (dropdown) {
                label.textSize = 16f
            } else {
                label.textSize = 14f
            }
            return view
        }
    }

    private class SimpleItemSelectedListener(
        private val onSelected: (Int) -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>,
            view: View?,
            position: Int,
            id: Long
        ) {
            onSelected(position)
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
    }

    companion object {
        private const val DEFAULT_BASIC_ICON_ID = "101"
        private const val OVERLAY_PREFS_NAME = "hud_overlay_prefs"
        private const val MANEUVER_PREFS_NAME = "maneuver_match_prefs"
        private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"
    }
}
