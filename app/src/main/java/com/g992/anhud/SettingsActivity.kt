package com.g992.anhud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
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

    private var isSyncingUi = false

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
        private const val MANEUVER_PREFS_NAME = "maneuver_match_prefs"
        private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"
    }
}
