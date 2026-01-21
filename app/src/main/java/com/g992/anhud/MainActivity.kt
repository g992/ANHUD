package com.g992.anhud

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.app.AppOpsManager
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA = 0.35f
    }
    private lateinit var permissionStatus: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var overlaySwitch: SwitchCompat
    private lateinit var displaySpinner: Spinner
    private lateinit var positionContainerCard: View
    private lateinit var positionNavCard: View
    private lateinit var positionSpeedCard: View
    private lateinit var positionSpeedometerCard: View
    private lateinit var positionClockCard: View
    private lateinit var navProjectionSwitch: SwitchCompat
    private lateinit var speedProjectionSwitch: SwitchCompat
    private lateinit var speedometerProjectionSwitch: SwitchCompat
    private lateinit var clockProjectionSwitch: SwitchCompat
    private lateinit var speedLimitAlertCheck: CheckBox
    private lateinit var speedLimitAlertThresholdRow: View
    private lateinit var speedLimitAlertThresholdSeek: SeekBar
    private lateinit var speedLimitAlertThresholdValue: TextView
    private lateinit var logsButton: Button
    private lateinit var navAppButton: Button
    private lateinit var navAppSelected: TextView

    private var displayOptions: List<DisplayOption> = emptyList()
    private var displaySize: Point = Point(1, 1)
    private var displayDensity = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_hud_display_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        permissionStatus = findViewById(R.id.permissionStatus)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        displaySpinner = findViewById(R.id.displaySpinner)
        positionContainerCard = findViewById(R.id.positionContainerCard)
        positionNavCard = findViewById(R.id.positionNavCard)
        positionSpeedCard = findViewById(R.id.positionSpeedCard)
        positionSpeedometerCard = findViewById(R.id.positionSpeedometerCard)
        positionClockCard = findViewById(R.id.positionClockCard)
        navProjectionSwitch = findViewById(R.id.navProjectionSwitch)
        speedProjectionSwitch = findViewById(R.id.speedProjectionSwitch)
        speedometerProjectionSwitch = findViewById(R.id.speedometerProjectionSwitch)
        clockProjectionSwitch = findViewById(R.id.clockProjectionSwitch)
        speedLimitAlertCheck = findViewById(R.id.speedLimitAlertCheck)
        speedLimitAlertThresholdRow = findViewById(R.id.speedLimitAlertThresholdRow)
        speedLimitAlertThresholdSeek = findViewById(R.id.speedLimitAlertThresholdSeek)
        speedLimitAlertThresholdValue = findViewById(R.id.speedLimitAlertThresholdValue)
        logsButton = findViewById(R.id.btnLogs)
        navAppButton = findViewById(R.id.navAppButton)
        navAppSelected = findViewById(R.id.navAppSelected)

        logsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        requestPermissionButton.setOnClickListener {
            openOverlaySettings()
        }

        overlaySwitch.isChecked = OverlayPrefs.isEnabled(this)
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                overlaySwitch.isChecked = false
                openOverlaySettings()
                updatePermissionStatus()
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setEnabled(this, isChecked)
            notifyOverlaySettingsChanged()
            if (isChecked) {
                ContextCompat.startForegroundService(this, Intent(this, HudBackgroundService::class.java))
            }
        }

        positionNavCard.setOnClickListener {
            openPositionDialog(OverlayTarget.NAVIGATION)
        }
        positionSpeedCard.setOnClickListener {
            openPositionDialog(OverlayTarget.SPEED)
        }
        positionSpeedometerCard.setOnClickListener {
            openPositionDialog(OverlayTarget.SPEEDOMETER)
        }
        positionClockCard.setOnClickListener {
            openPositionDialog(OverlayTarget.CLOCK)
        }
        positionContainerCard.setOnClickListener {
            openPositionDialog(OverlayTarget.CONTAINER)
        }
        navAppButton.setOnClickListener {
            handleNavAppSelection()
        }

        navProjectionSwitch.isChecked = OverlayPrefs.navEnabled(this)
        speedProjectionSwitch.isChecked = OverlayPrefs.speedEnabled(this)
        speedometerProjectionSwitch.isChecked = OverlayPrefs.speedometerEnabled(this)
        clockProjectionSwitch.isChecked = OverlayPrefs.clockEnabled(this)
        navProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            OverlayPrefs.setNavEnabled(this, isChecked)
            notifyOverlaySettingsChanged(navEnabled = isChecked)
        }
        speedProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            OverlayPrefs.setSpeedEnabled(this, isChecked)
            notifyOverlaySettingsChanged(speedEnabled = isChecked)
        }
        speedometerProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            OverlayPrefs.setSpeedometerEnabled(this, isChecked)
            notifyOverlaySettingsChanged(speedometerEnabled = isChecked)
        }
        clockProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            OverlayPrefs.setClockEnabled(this, isChecked)
            notifyOverlaySettingsChanged(clockEnabled = isChecked)
        }

        val speedLimitAlertEnabled = OverlayPrefs.speedLimitAlertEnabled(this)
        val speedLimitAlertThreshold = OverlayPrefs.speedLimitAlertThreshold(this)
        speedLimitAlertCheck.isChecked = speedLimitAlertEnabled
        speedLimitAlertThresholdRow.visibility = if (speedLimitAlertEnabled) View.VISIBLE else View.GONE
        speedLimitAlertThresholdSeek.max = OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX
        speedLimitAlertThresholdSeek.progress = speedLimitAlertThreshold
        speedLimitAlertThresholdValue.text = getString(
            R.string.speed_limit_alert_threshold_value,
            speedLimitAlertThreshold
        )
        speedLimitAlertCheck.setOnCheckedChangeListener { _, isChecked ->
            OverlayPrefs.setSpeedLimitAlertEnabled(this, isChecked)
            speedLimitAlertThresholdRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            notifyOverlaySettingsChanged(
                speedLimitAlertEnabled = isChecked,
                speedLimitAlertThreshold = speedLimitAlertThresholdSeek.progress
            )
        }
        speedLimitAlertThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress.coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
                speedLimitAlertThresholdValue.text = getString(
                    R.string.speed_limit_alert_threshold_value,
                    threshold
                )
                notifyOverlaySettingsChanged(speedLimitAlertThreshold = threshold)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val threshold = (seekBar?.progress ?: 0)
                    .coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
                OverlayPrefs.setSpeedLimitAlertThreshold(this@MainActivity, threshold)
                notifyOverlaySettingsChanged(speedLimitAlertThreshold = threshold)
            }
        })

        setupDisplaySpinners()
        updatePermissionStatus()
        updateNavAppSelection()
        startCoreServices()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateNavAppSelection()
    }

    private fun updatePermissionStatus() {
        val granted = Settings.canDrawOverlays(this)
        permissionStatus.text = if (granted) {
            getString(R.string.overlay_permission_granted)
        } else {
            getString(R.string.overlay_permission_missing)
        }
        val visibility = if (granted) View.GONE else View.VISIBLE
        permissionStatus.visibility = visibility
        requestPermissionButton.visibility = visibility
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun setupDisplaySpinners() {
        val autoOption = DisplayOption(
            OverlayPrefs.DISPLAY_ID_AUTO,
            getString(R.string.display_auto_option)
        )
        val displays = HudDisplayUtils.availableDisplays(this)
        displayOptions = listOf(autoOption) + displays.map { display ->
            val size = HudDisplayUtils.displaySize(display)
            DisplayOption(
                display.displayId,
                getString(
                    R.string.display_option_label,
                    display.displayId,
                    display.name,
                    size.x,
                    size.y
                )
            )
        }
        val labels = displayOptions.map { it.label }
        fun buildAdapter(): ArrayAdapter<String> {
            val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            return adapter
        }
        displaySpinner.adapter = buildAdapter()

        val savedDisplayId = OverlayPrefs.displayId(this)
        val selectedIndex = displayOptions.indexOfFirst { it.id == savedDisplayId }.takeIf { it >= 0 } ?: 0
        displaySpinner.setSelection(selectedIndex)
        displaySpinner.post {
            updateDisplayMetrics(displayOptions[selectedIndex].id)
        }

        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val option = displayOptions[position]
                OverlayPrefs.setDisplayId(this@MainActivity, option.id)
                updateDisplayMetrics(option.id)
                notifyOverlaySettingsChanged()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
            }
        }
    }

    private fun updateNavAppSelection() {
        val packageName = OverlayPrefs.navAppPackage(this)
        val storedLabel = OverlayPrefs.navAppLabel(this)
        if (packageName.isBlank()) {
            navAppSelected.text = getString(R.string.nav_app_not_selected)
            return
        }
        val label = storedLabel.ifBlank {
            resolveAppLabel(packageName)?.also { resolved ->
                OverlayPrefs.setNavApp(this, packageName, resolved)
            } ?: packageName
        }
        navAppSelected.text = label
    }

    private fun resolveAppLabel(packageName: String): String? {
        val pm = packageManager
        return try {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun handleNavAppSelection() {
        if (!hasUsageAccess()) {
            AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
                .setTitle(getString(R.string.usage_access_title))
                .setMessage(getString(R.string.usage_access_message))
                .setPositiveButton(getString(R.string.usage_access_open)) { _, _ ->
                    openUsageAccessSettings()
                }
                .setNegativeButton(getString(R.string.usage_access_continue)) { _, _ ->
                    showNavAppPicker()
                }
                .show()
            return
        }
        showNavAppPicker()
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun showNavAppPicker() {
        val apps = loadLaunchableApps()
        if (apps.isEmpty()) {
            UiLogStore.append(LogCategory.SYSTEM, "Список приложений пуст")
            return
        }
        val adapter = NavAppAdapter(this, apps)
        val builder = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setTitle(getString(R.string.nav_app_picker_title))
            .setAdapter(adapter) { _, which ->
                val selected = apps[which]
                OverlayPrefs.setNavApp(this, selected.packageName, selected.label)
                updateNavAppSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (OverlayPrefs.navAppPackage(this).isNotBlank()) {
            builder.setNeutralButton(getString(R.string.nav_app_clear)) { _, _ ->
                OverlayPrefs.clearNavApp(this)
                updateNavAppSelection()
            }
        }
        builder.show()
    }

    private fun loadLaunchableApps(): List<NavAppOption> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        @Suppress("DEPRECATION")
        val results = packageManager.queryIntentActivities(intent, 0)
        val seen = HashSet<String>(results.size)
        val apps = ArrayList<NavAppOption>(results.size)
        for (resolveInfo in results) {
            val packageName = resolveInfo.activityInfo?.packageName ?: continue
            if (!seen.add(packageName)) {
                continue
            }
            val label = resolveInfo.loadLabel(packageManager)?.toString()?.ifBlank { packageName } ?: packageName
            val icon = resolveInfo.loadIcon(packageManager)
            apps.add(NavAppOption(label, packageName, icon))
        }
        return apps.sortedBy { it.label.lowercase() }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateDisplayMetrics(displayId: Int) {
        val display = HudDisplayUtils.resolveDisplay(this, displayId)
        if (display != null) {
            displaySize = HudDisplayUtils.displaySize(display)
            val displayContext = createDisplayContext(display)
            displayDensity = displayContext.resources.displayMetrics.density
        } else {
            displaySize = Point(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            displayDensity = resources.displayMetrics.density
        }
    }

    private fun notifyOverlaySettingsChanged(
        containerPosition: PointF? = null,
        containerWidthDp: Float? = null,
        containerHeightDp: Float? = null,
        navPosition: PointF? = null,
        speedPosition: PointF? = null,
        speedometerPosition: PointF? = null,
        clockPosition: PointF? = null,
        navScale: Float? = null,
        speedScale: Float? = null,
        speedometerScale: Float? = null,
        clockScale: Float? = null,
        navAlpha: Float? = null,
        speedAlpha: Float? = null,
        speedometerAlpha: Float? = null,
        clockAlpha: Float? = null,
        containerAlpha: Float? = null,
        navEnabled: Boolean? = null,
        speedEnabled: Boolean? = null,
        speedLimitAlertEnabled: Boolean? = null,
        speedLimitAlertThreshold: Int? = null,
        speedometerEnabled: Boolean? = null,
        clockEnabled: Boolean? = null,
        preview: Boolean = false,
        previewTarget: OverlayTarget? = null,
        previewShowOthers: Boolean? = null
    ) {
        val intent = Intent(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
            .setPackage(packageName)
        if (containerPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_X_DP, containerPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_Y_DP, containerPosition.y)
        }
        if (containerWidthDp != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_WIDTH_DP, containerWidthDp)
        }
        if (containerHeightDp != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_HEIGHT_DP, containerHeightDp)
        }
        if (navPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_X_DP, navPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_Y_DP, navPosition.y)
        }
        if (speedPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, speedPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, speedPosition.y)
        }
        if (speedometerPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_X_DP, speedometerPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_Y_DP, speedometerPosition.y)
        }
        if (clockPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_X_DP, clockPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_Y_DP, clockPosition.y)
        }
        if (navScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_SCALE, navScale)
        }
        if (speedScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, speedScale)
        }
        if (speedometerScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_SCALE, speedometerScale)
        }
        if (clockScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_SCALE, clockScale)
        }
        if (navAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_ALPHA, navAlpha)
        }
        if (speedAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, speedAlpha)
        }
        if (speedometerAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ALPHA, speedometerAlpha)
        }
        if (clockAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_ALPHA, clockAlpha)
        }
        if (containerAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CONTAINER_ALPHA, containerAlpha)
        }
        if (navEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_ENABLED, navEnabled)
        }
        if (speedEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED, speedEnabled)
        }
        if (speedLimitAlertEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED, speedLimitAlertEnabled)
        }
        if (speedLimitAlertThreshold != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD, speedLimitAlertThreshold)
        }
        if (speedometerEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ENABLED, speedometerEnabled)
        }
        if (clockEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED, clockEnabled)
        }
        intent.putExtra(OverlayBroadcasts.EXTRA_PREVIEW, preview)
        if (previewTarget != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_PREVIEW_TARGET, previewTarget.previewKey)
        }
        if (previewShowOthers != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_PREVIEW_SHOW_OTHERS, previewShowOthers)
        }
        sendBroadcast(intent)
    }

    private fun startCoreServices() {
        startService(Intent(this, NavigationService::class.java))
        startService(Intent(this, SensorDataService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, HudBackgroundService::class.java))
    }

    private data class DisplayOption(val id: Int, val label: String)
    private data class NavAppOption(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    private class NavAppAdapter(
        context: Context,
        private val items: List<NavAppOption>
    ) : ArrayAdapter<NavAppOption>(context, R.layout.app_list_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.app_list_item, parent, false)
            val iconView = view.findViewById<ImageView>(R.id.appIcon)
            val nameView = view.findViewById<TextView>(R.id.appName)
            val item = items[position]
            iconView.setImageDrawable(item.icon)
            nameView.text = item.label
            return view
        }
    }

    private enum class OverlayTarget(val previewKey: String) {
        NAVIGATION(OverlayBroadcasts.PREVIEW_TARGET_NAV),
        SPEED(OverlayBroadcasts.PREVIEW_TARGET_SPEED),
        SPEEDOMETER(OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER),
        CLOCK(OverlayBroadcasts.PREVIEW_TARGET_CLOCK),
        CONTAINER(OverlayBroadcasts.PREVIEW_TARGET_CONTAINER)
    }

    private fun openPositionDialog(target: OverlayTarget) {
        updateDisplayMetrics(OverlayPrefs.displayId(this))
        val dialogView = layoutInflater.inflate(R.layout.dialog_position_editor, null)
        val previewContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewContainer)
        val previewHudContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewHudContainer)
        val previewNavBlock = dialogView.findViewById<View>(R.id.dialogPreviewNavBlock)
        val previewSpeedLimit = dialogView.findViewById<View>(R.id.dialogPreviewSpeedLimit)
        val previewSpeedometer = dialogView.findViewById<TextView>(R.id.dialogPreviewSpeedometer)
        val previewClock = dialogView.findViewById<TextView>(R.id.dialogPreviewClock)
        val showOthersCheck = dialogView.findViewById<CheckBox>(R.id.dialogShowOthers)
        val containerWidthLabel = dialogView.findViewById<TextView>(R.id.dialogContainerWidthLabel)
        val containerWidthRow = dialogView.findViewById<View>(R.id.dialogContainerWidthRow)
        val containerWidthSeek = dialogView.findViewById<SeekBar>(R.id.dialogContainerWidthSeek)
        val containerHeightLabel = dialogView.findViewById<TextView>(R.id.dialogContainerHeightLabel)
        val containerHeightRow = dialogView.findViewById<View>(R.id.dialogContainerHeightRow)
        val containerHeightSeek = dialogView.findViewById<SeekBar>(R.id.dialogContainerHeightSeek)
        val scaleLabel = dialogView.findViewById<TextView>(R.id.dialogScaleLabel)
        val scaleSeek = dialogView.findViewById<SeekBar>(R.id.dialogScaleSeek)
        val scaleValue = dialogView.findViewById<TextView>(R.id.dialogScaleValue)
        val brightnessSeek = dialogView.findViewById<SeekBar>(R.id.dialogBrightnessSeek)
        val brightnessValue = dialogView.findViewById<TextView>(R.id.dialogBrightnessValue)

        val navPosition = OverlayPrefs.navPositionDp(this)
        val speedPosition = OverlayPrefs.speedPositionDp(this)
        val speedometerPosition = OverlayPrefs.speedometerPositionDp(this)
        val clockPosition = OverlayPrefs.clockPositionDp(this)
        val containerPosition = OverlayPrefs.containerPositionDp(this)
        val containerSize = OverlayPrefs.containerSizeDp(this)
        val navPoint = PointF(navPosition.x, navPosition.y)
        val speedPoint = PointF(speedPosition.x, speedPosition.y)
        val speedometerPoint = PointF(speedometerPosition.x, speedometerPosition.y)
        val clockPoint = PointF(clockPosition.x, clockPosition.y)
        val containerPoint = PointF(containerPosition.x, containerPosition.y)
        var containerWidthDp = containerSize.x
        var containerHeightDp = containerSize.y
        val scalePercent = when (target) {
            OverlayTarget.NAVIGATION -> (OverlayPrefs.navScale(this) * 100).toInt()
            OverlayTarget.SPEED -> (OverlayPrefs.speedScale(this) * 100).toInt()
            OverlayTarget.SPEEDOMETER -> (OverlayPrefs.speedometerScale(this) * 100).toInt()
            OverlayTarget.CLOCK -> (OverlayPrefs.clockScale(this) * 100).toInt()
            OverlayTarget.CONTAINER -> 100
        }
        val brightnessPercent = when (target) {
            OverlayTarget.NAVIGATION -> (OverlayPrefs.navAlpha(this) * 100).toInt()
            OverlayTarget.SPEED -> (OverlayPrefs.speedAlpha(this) * 100).toInt()
            OverlayTarget.SPEEDOMETER -> (OverlayPrefs.speedometerAlpha(this) * 100).toInt()
            OverlayTarget.CLOCK -> (OverlayPrefs.clockAlpha(this) * 100).toInt()
            OverlayTarget.CONTAINER -> (OverlayPrefs.containerAlpha(this) * 100).toInt()
        }.coerceIn(0, 100)

        val dialogTitle = when (target) {
            OverlayTarget.NAVIGATION -> getString(R.string.position_nav_block_label)
            OverlayTarget.SPEED -> getString(R.string.position_speed_block_label)
            OverlayTarget.SPEEDOMETER -> getString(R.string.position_speedometer_block_label)
            OverlayTarget.CLOCK -> getString(R.string.position_clock_block_label)
            OverlayTarget.CONTAINER -> getString(R.string.position_container_label)
        }

        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                notifyOverlaySettingsChanged(preview = false, previewTarget = target, previewShowOthers = false)
            }
            .create()

        if (target == OverlayTarget.CONTAINER) {
            scaleLabel.visibility = View.GONE
            scaleSeek.visibility = View.GONE
            scaleValue.visibility = View.GONE
        } else {
            containerWidthLabel.visibility = View.GONE
            containerWidthRow.visibility = View.GONE
            containerHeightLabel.visibility = View.GONE
            containerHeightRow.visibility = View.GONE
        }

        val density = displayDensity.takeIf { it > 0f } ?: resources.displayMetrics.density
        val minContainerSizeDp = OverlayPrefs.CONTAINER_MIN_SIZE_PX / density
        val maxWidthDp = (displaySize.x.coerceAtLeast(1) / density).coerceAtLeast(minContainerSizeDp)
        val maxHeightDp = (displaySize.y.coerceAtLeast(1) / density).coerceAtLeast(minContainerSizeDp)
        val minSizeInt = minContainerSizeDp.roundToInt()
        val maxWidthInt = maxWidthDp.roundToInt().coerceAtLeast(minSizeInt)
        val maxHeightInt = maxHeightDp.roundToInt().coerceAtLeast(minSizeInt)

        fun clampContainerSize() {
            containerWidthDp = containerWidthDp.coerceIn(minContainerSizeDp, maxWidthDp)
            containerHeightDp = containerHeightDp.coerceIn(minContainerSizeDp, maxHeightDp)
        }

        clampContainerSize()
        containerWidthSeek.max = (maxWidthInt - minSizeInt).coerceAtLeast(0)
        containerHeightSeek.max = (maxHeightInt - minSizeInt).coerceAtLeast(0)
        containerWidthSeek.progress = (containerWidthDp.roundToInt() - minSizeInt)
            .coerceIn(0, containerWidthSeek.max)
        containerHeightSeek.progress = (containerHeightDp.roundToInt() - minSizeInt)
            .coerceIn(0, containerHeightSeek.max)

        fun updateDialogVisibility() {
            val showNav = target == OverlayTarget.NAVIGATION
            val showSpeed = target == OverlayTarget.SPEED
            val showSpeedometer = target == OverlayTarget.SPEEDOMETER
            val showClock = target == OverlayTarget.CLOCK
            previewNavBlock.visibility = if (showNav) View.VISIBLE else View.GONE
            previewSpeedLimit.visibility = if (showSpeed) View.VISIBLE else View.GONE
            previewSpeedometer.visibility = if (showSpeedometer) View.VISIBLE else View.GONE
            previewClock.visibility = if (showClock) View.VISIBLE else View.GONE
            if (target == OverlayTarget.CONTAINER) {
                previewHudContainer.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_hud_container_outline)
                updatePreviewContainerSize(previewContainer, previewHudContainer, containerWidthDp, containerHeightDp)
                positionPreviewView(
                    previewContainer,
                    previewHudContainer,
                    containerPoint.x,
                    containerPoint.y,
                    displaySize.x.toFloat(),
                    displaySize.y.toFloat()
                )
                val containerAlpha = brightnessSeek.progress.coerceIn(0, 100) / 100f
                updatePreviewContainerAlpha(previewHudContainer, containerAlpha)
            } else {
                previewHudContainer.background = null
            }
            val containerWidthPx = containerWidthDp * density
            val containerHeightPx = containerHeightDp * density
            if (showNav) {
                positionPreviewView(
                    previewHudContainer,
                    previewNavBlock,
                    navPoint.x,
                    navPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewNavBlock.alpha = if (target == OverlayTarget.NAVIGATION) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.navAlpha(this@MainActivity).coerceIn(0f, 1f)
                }
            }
            if (showSpeed) {
                positionPreviewView(
                    previewHudContainer,
                    previewSpeedLimit,
                    speedPoint.x,
                    speedPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewSpeedLimit.alpha = if (target == OverlayTarget.SPEED) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.speedAlpha(this@MainActivity).coerceIn(0f, 1f)
                }
            }
            if (showSpeedometer) {
                positionPreviewView(
                    previewHudContainer,
                    previewSpeedometer,
                    speedometerPoint.x,
                    speedometerPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewSpeedometer.alpha = if (target == OverlayTarget.SPEEDOMETER) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.speedometerAlpha(this@MainActivity).coerceIn(0f, 1f)
                }
            }
            if (showClock) {
                positionPreviewView(
                    previewHudContainer,
                    previewClock,
                    clockPoint.x,
                    clockPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewClock.alpha = if (target == OverlayTarget.CLOCK) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.clockAlpha(this@MainActivity).coerceIn(0f, 1f)
                }
            }
        }

        fun updateOverlayPosition(previewX: Float, previewY: Float, persist: Boolean) {
            val view = when (target) {
                OverlayTarget.NAVIGATION -> previewNavBlock
                OverlayTarget.SPEED -> previewSpeedLimit
                OverlayTarget.SPEEDOMETER -> previewSpeedometer
                OverlayTarget.CLOCK -> previewClock
                OverlayTarget.CONTAINER -> previewHudContainer
            }
            val boundsWidth = if (target == OverlayTarget.CONTAINER) {
                displaySize.x.toFloat()
            } else {
                containerWidthDp * density
            }
            val boundsHeight = if (target == OverlayTarget.CONTAINER) {
                displaySize.y.toFloat()
            } else {
                containerHeightDp * density
            }
            val dragContainer = if (target == OverlayTarget.CONTAINER) {
                previewContainer
            } else {
                previewHudContainer
            }
            val (dpX, dpY) = positionDpFromPreview(dragContainer, view, previewX, previewY, boundsWidth, boundsHeight)
            val point = PointF(dpX, dpY)
            when (target) {
                OverlayTarget.NAVIGATION -> {
                    if (persist) {
                        OverlayPrefs.setNavPositionDp(this, dpX, dpY)
                        navPoint.x = dpX
                        navPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        navPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.SPEED -> {
                    if (persist) {
                        OverlayPrefs.setSpeedPositionDp(this, dpX, dpY)
                        speedPoint.x = dpX
                        speedPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        speedPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.SPEEDOMETER -> {
                    if (persist) {
                        OverlayPrefs.setSpeedometerPositionDp(this, dpX, dpY)
                        speedometerPoint.x = dpX
                        speedometerPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        speedometerPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.CLOCK -> {
                    if (persist) {
                        OverlayPrefs.setClockPositionDp(this, dpX, dpY)
                        clockPoint.x = dpX
                        clockPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        clockPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.CONTAINER -> {
                    if (persist) {
                        OverlayPrefs.setContainerPositionDp(this, dpX, dpY)
                        containerPoint.x = dpX
                        containerPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        containerPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
            }
        }

        setupDialogDrag(
            if (target == OverlayTarget.CONTAINER) previewContainer else previewHudContainer,
            when (target) {
                OverlayTarget.NAVIGATION -> previewNavBlock
                OverlayTarget.SPEED -> previewSpeedLimit
                OverlayTarget.SPEEDOMETER -> previewSpeedometer
                OverlayTarget.CLOCK -> previewClock
                OverlayTarget.CONTAINER -> previewHudContainer
            }
        ) { previewX, previewY, persist ->
            updateOverlayPosition(previewX, previewY, persist)
        }

        containerWidthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (target != OverlayTarget.CONTAINER) {
                    return
                }
                containerWidthDp = (minSizeInt + progress).toFloat().coerceIn(minContainerSizeDp, maxWidthDp)
                updateDialogVisibility()
                notifyOverlaySettingsChanged(
                    containerWidthDp = containerWidthDp,
                    containerHeightDp = containerHeightDp,
                    preview = true,
                    previewTarget = target,
                    previewShowOthers = showOthersCheck.isChecked
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (target != OverlayTarget.CONTAINER) {
                    return
                }
                OverlayPrefs.setContainerSizeDp(this@MainActivity, containerWidthDp, containerHeightDp)
                notifyOverlaySettingsChanged(
                    containerWidthDp = containerWidthDp,
                    containerHeightDp = containerHeightDp,
                    preview = true,
                    previewTarget = target,
                    previewShowOthers = showOthersCheck.isChecked
                )
            }
        })

        containerHeightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (target != OverlayTarget.CONTAINER) {
                    return
                }
                containerHeightDp = (minSizeInt + progress).toFloat().coerceIn(minContainerSizeDp, maxHeightDp)
                updateDialogVisibility()
                notifyOverlaySettingsChanged(
                    containerWidthDp = containerWidthDp,
                    containerHeightDp = containerHeightDp,
                    preview = true,
                    previewTarget = target,
                    previewShowOthers = showOthersCheck.isChecked
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (target != OverlayTarget.CONTAINER) {
                    return
                }
                OverlayPrefs.setContainerSizeDp(this@MainActivity, containerWidthDp, containerHeightDp)
                notifyOverlaySettingsChanged(
                    containerWidthDp = containerWidthDp,
                    containerHeightDp = containerHeightDp,
                    preview = true,
                    previewTarget = target,
                    previewShowOthers = showOthersCheck.isChecked
                )
            }
        })

        val scaleMinPercent = 50
        val scaleMaxPercent = when (target) {
            OverlayTarget.SPEEDOMETER -> 300
            OverlayTarget.CONTAINER -> 100
            else -> 150
        }
        val scaleRange = (scaleMaxPercent - scaleMinPercent).coerceAtLeast(0)
        val resolvedScalePercent = scalePercent.coerceIn(scaleMinPercent, scaleMaxPercent)
        scaleSeek.max = scaleRange
        scaleSeek.progress = (resolvedScalePercent - scaleMinPercent).coerceIn(0, scaleRange)
        scaleValue.text = getString(R.string.scale_percent_format, resolvedScalePercent)
        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (target == OverlayTarget.CONTAINER) {
                    return
                }
                val percent = (progress + scaleMinPercent).coerceIn(scaleMinPercent, scaleMaxPercent)
                val scale = percent / 100f
                scaleValue.text = getString(R.string.scale_percent_format, percent)
                when (target) {
                    OverlayTarget.NAVIGATION -> notifyOverlaySettingsChanged(
                        navScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                    OverlayTarget.SPEED -> notifyOverlaySettingsChanged(
                        speedScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                    OverlayTarget.SPEEDOMETER -> notifyOverlaySettingsChanged(
                        speedometerScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                    OverlayTarget.CLOCK -> notifyOverlaySettingsChanged(
                        clockScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                    OverlayTarget.CONTAINER -> Unit
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (target == OverlayTarget.CONTAINER) {
                    return
                }
                val percent = ((seekBar?.progress ?: 0) + scaleMinPercent)
                    .coerceIn(scaleMinPercent, scaleMaxPercent)
                val scale = percent / 100f
                when (target) {
                    OverlayTarget.NAVIGATION -> {
                        OverlayPrefs.setNavScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            navScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.SPEED -> {
                        OverlayPrefs.setSpeedScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            speedScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.SPEEDOMETER -> {
                        OverlayPrefs.setSpeedometerScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            speedometerScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.CLOCK -> {
                        OverlayPrefs.setClockScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            clockScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.CONTAINER -> Unit
                }
            }
        })

        brightnessSeek.progress = brightnessPercent
        brightnessValue.text = getString(R.string.scale_percent_format, brightnessPercent)
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress.coerceIn(0, 100)
                val alpha = percent / 100f
                brightnessValue.text = getString(R.string.scale_percent_format, percent)
                when (target) {
                    OverlayTarget.NAVIGATION -> {
                        previewNavBlock.alpha = alpha
                        notifyOverlaySettingsChanged(
                            navAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.SPEED -> {
                        previewSpeedLimit.alpha = alpha
                        notifyOverlaySettingsChanged(
                            speedAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.SPEEDOMETER -> {
                        previewSpeedometer.alpha = alpha
                        notifyOverlaySettingsChanged(
                            speedometerAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.CLOCK -> {
                        previewClock.alpha = alpha
                        notifyOverlaySettingsChanged(
                            clockAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.CONTAINER -> {
                        updatePreviewContainerAlpha(previewHudContainer, alpha)
                        notifyOverlaySettingsChanged(
                            containerAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val percent = (seekBar?.progress ?: 100).coerceIn(0, 100)
                val alpha = percent / 100f
                when (target) {
                    OverlayTarget.NAVIGATION -> {
                        OverlayPrefs.setNavAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            navAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.SPEED -> {
                        OverlayPrefs.setSpeedAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            speedAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.SPEEDOMETER -> {
                        OverlayPrefs.setSpeedometerAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            speedometerAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.CLOCK -> {
                        OverlayPrefs.setClockAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            clockAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.CONTAINER -> {
                        OverlayPrefs.setContainerAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            containerAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                }
            }
        })

        showOthersCheck.setOnCheckedChangeListener { _, isChecked ->
            notifyOverlaySettingsChanged(preview = true, previewTarget = target, previewShowOthers = isChecked)
            updateDialogVisibility()
        }

        dialog.setOnShowListener {
            notifyOverlaySettingsChanged(
                preview = true,
                previewTarget = target,
                previewShowOthers = showOthersCheck.isChecked
            )
            previewContainer.post {
                updateDialogVisibility()
            }
        }

        dialog.show()
    }

    private fun setupDialogDrag(
        container: FrameLayout,
        view: View,
        onDrag: (Float, Float, Boolean) -> Unit
    ) {
        var dragOffsetX = 0f
        var dragOffsetY = 0f
        view.setOnTouchListener { v, event ->
            val containerLocation = IntArray(2)
            container.getLocationOnScreen(containerLocation)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragOffsetX = event.rawX - (containerLocation[0] + v.x)
                    dragOffsetY = event.rawY - (containerLocation[1] + v.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = maxPreviewX(container, v)
                    val maxY = maxPreviewY(container, v)
                    val newX = event.rawX - containerLocation[0] - dragOffsetX
                    val newY = event.rawY - containerLocation[1] - dragOffsetY
                    v.x = min(max(newX, 0f), maxX)
                    v.y = min(max(newY, 0f), maxY)
                    onDrag(v.x, v.y, false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDrag(v.x, v.y, true)
                    true
                }
                else -> false
            }
        }
    }

    private fun positionPreviewView(
        container: FrameLayout,
        view: View,
        dpX: Float,
        dpY: Float,
        boundsWidthPx: Float,
        boundsHeightPx: Float
    ) {
        val posPxX = dpX * displayDensity
        val posPxY = dpY * displayDensity
        val maxX = maxPreviewX(container, view)
        val maxY = maxPreviewY(container, view)
        val previewX = if (boundsWidthPx > 0f) (posPxX / boundsWidthPx) * maxX else 0f
        val previewY = if (boundsHeightPx > 0f) (posPxY / boundsHeightPx) * maxY else 0f
        view.x = min(max(previewX, 0f), maxX)
        view.y = min(max(previewY, 0f), maxY)
    }

    private fun positionDpFromPreview(
        container: FrameLayout,
        view: View,
        previewX: Float,
        previewY: Float,
        boundsWidthPx: Float,
        boundsHeightPx: Float
    ): Pair<Float, Float> {
        val maxX = maxPreviewX(container, view).coerceAtLeast(1f)
        val maxY = maxPreviewY(container, view).coerceAtLeast(1f)
        val clampedX = min(max(previewX, 0f), maxX)
        val clampedY = min(max(previewY, 0f), maxY)
        val fractionX = clampedX / maxX
        val fractionY = clampedY / maxY
        val displayX = fractionX * boundsWidthPx
        val displayY = fractionY * boundsHeightPx
        val dpX = (displayX / displayDensity).toFloat()
        val dpY = (displayY / displayDensity).toFloat()
        return dpX to dpY
    }

    private fun maxPreviewX(container: FrameLayout, view: View): Float {
        return (container.width - view.width).toFloat().coerceAtLeast(0f)
    }

    private fun maxPreviewY(container: FrameLayout, view: View): Float {
        return (container.height - view.height).toFloat().coerceAtLeast(0f)
    }

    private fun updatePreviewContainerSize(
        previewContainer: FrameLayout,
        previewHudContainer: FrameLayout,
        containerWidthDp: Float,
        containerHeightDp: Float
    ) {
        val width = previewContainer.width
        val height = previewContainer.height
        if (width <= 0 || height <= 0) {
            return
        }
        val containerWidthPx = containerWidthDp * displayDensity
        val containerHeightPx = containerHeightDp * displayDensity
        val displayWidth = displaySize.x.coerceAtLeast(1).toFloat()
        val displayHeight = displaySize.y.coerceAtLeast(1).toFloat()
        val previewWidth = (containerWidthPx / displayWidth) * width
        val previewHeight = (containerHeightPx / displayHeight) * height
        val params = previewHudContainer.layoutParams
        params.width = previewWidth.roundToInt().coerceAtLeast(1)
        params.height = previewHeight.roundToInt().coerceAtLeast(1)
        previewHudContainer.layoutParams = params
    }

    private fun updatePreviewContainerAlpha(container: FrameLayout, alphaOverride: Float? = null) {
        val background = container.background ?: return
        val rawAlpha = alphaOverride ?: OverlayPrefs.containerAlpha(this)
        val effectiveAlpha = max(rawAlpha, CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA)
        val alphaValue = (effectiveAlpha * 255)
            .roundToInt()
            .coerceIn(0, 255)
        background.alpha = alphaValue
    }

}
