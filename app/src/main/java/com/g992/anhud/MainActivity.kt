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

class MainActivity : AppCompatActivity() {
    private lateinit var permissionStatus: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var overlaySwitch: SwitchCompat
    private lateinit var displaySpinner: Spinner
    private lateinit var positionNavCard: View
    private lateinit var positionSpeedCard: View
    private lateinit var logsButton: Button
    private lateinit var navAppButton: Button
    private lateinit var navAppSelected: TextView
    private lateinit var virtualDisplaySwitch: SwitchCompat
    private lateinit var hudDisplaySpinner: Spinner
    private lateinit var virtualDisplaySection: View

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
        positionNavCard = findViewById(R.id.positionNavCard)
        positionSpeedCard = findViewById(R.id.positionSpeedCard)
        logsButton = findViewById(R.id.btnLogs)
        navAppButton = findViewById(R.id.navAppButton)
        navAppSelected = findViewById(R.id.navAppSelected)
        virtualDisplaySwitch = findViewById(R.id.virtualDisplaySwitch)
        hudDisplaySpinner = findViewById(R.id.hudDisplaySpinner)
        virtualDisplaySection = findViewById(R.id.virtualDisplaySection)

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

        virtualDisplaySwitch.isChecked = OverlayPrefs.isVirtualDisplayEnabled(this)
        virtualDisplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                virtualDisplaySwitch.isChecked = false
                openOverlaySettings()
                updatePermissionStatus()
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setVirtualDisplayEnabled(this, isChecked)
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
        navAppButton.setOnClickListener {
            handleNavAppSelection()
        }

        setupDisplaySpinners()
        updatePermissionStatus()
        updateNavAppSelection()
        updateVirtualDisplayVisibility()
        startCoreServices()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateNavAppSelection()
        updateVirtualDisplayVisibility()
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
        hudDisplaySpinner.adapter = buildAdapter()

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

        val savedHudDisplayId = OverlayPrefs.hudDisplayId(this)
        val hudSelectedIndex = displayOptions.indexOfFirst { it.id == savedHudDisplayId }.takeIf { it >= 0 } ?: 0
        hudDisplaySpinner.setSelection(hudSelectedIndex)
        hudDisplaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val option = displayOptions[position]
                OverlayPrefs.setHudDisplayId(this@MainActivity, option.id)
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

    private fun updateVirtualDisplayVisibility() {
        val hasPermission = HudDisplayUtils.hasVirtualDisplayPermission(this)
        virtualDisplaySection.visibility = if (hasPermission) View.VISIBLE else View.GONE
        if (hasPermission) {
            virtualDisplaySwitch.isChecked = OverlayPrefs.isVirtualDisplayEnabled(this)
        }
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
        navPosition: PointF? = null,
        speedPosition: PointF? = null,
        navScale: Float? = null,
        speedScale: Float? = null,
        navAlpha: Float? = null,
        speedAlpha: Float? = null,
        preview: Boolean = false,
        previewTarget: OverlayTarget? = null,
        previewShowOthers: Boolean? = null
    ) {
        val intent = Intent(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
        if (navPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_X_DP, navPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_Y_DP, navPosition.y)
        }
        if (speedPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, speedPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, speedPosition.y)
        }
        if (navScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_SCALE, navScale)
        }
        if (speedScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, speedScale)
        }
        if (navAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_ALPHA, navAlpha)
        }
        if (speedAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, speedAlpha)
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
        SPEED(OverlayBroadcasts.PREVIEW_TARGET_SPEED)
    }

    private fun openPositionDialog(target: OverlayTarget) {
        updateDisplayMetrics(OverlayPrefs.displayId(this))
        val dialogView = layoutInflater.inflate(R.layout.dialog_position_editor, null)
        val previewContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewContainer)
        val previewNavBlock = dialogView.findViewById<View>(R.id.dialogPreviewNavBlock)
        val previewSpeedLimit = dialogView.findViewById<View>(R.id.dialogPreviewSpeedLimit)
        val showOthersCheck = dialogView.findViewById<CheckBox>(R.id.dialogShowOthers)
        val scaleSeek = dialogView.findViewById<SeekBar>(R.id.dialogScaleSeek)
        val scaleValue = dialogView.findViewById<TextView>(R.id.dialogScaleValue)
        val brightnessSeek = dialogView.findViewById<SeekBar>(R.id.dialogBrightnessSeek)
        val brightnessValue = dialogView.findViewById<TextView>(R.id.dialogBrightnessValue)

        val navPosition = OverlayPrefs.navPositionDp(this)
        val speedPosition = OverlayPrefs.speedPositionDp(this)
        val navPoint = PointF(navPosition.x, navPosition.y)
        val speedPoint = PointF(speedPosition.x, speedPosition.y)
        val scalePercent = if (target == OverlayTarget.NAVIGATION) {
            (OverlayPrefs.navScale(this) * 100).toInt()
        } else {
            (OverlayPrefs.speedScale(this) * 100).toInt()
        }.coerceIn(50, 150)
        val brightnessPercent = if (target == OverlayTarget.NAVIGATION) {
            (OverlayPrefs.navAlpha(this) * 100).toInt()
        } else {
            (OverlayPrefs.speedAlpha(this) * 100).toInt()
        }.coerceIn(0, 100)

        val dialogTitle = if (target == OverlayTarget.NAVIGATION) {
            getString(R.string.position_nav_block_label)
        } else {
            getString(R.string.position_speed_block_label)
        }

        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                notifyOverlaySettingsChanged(preview = false, previewTarget = target, previewShowOthers = false)
            }
            .create()

        fun updateDialogVisibility() {
            val showNav = target == OverlayTarget.NAVIGATION
            val showSpeed = target == OverlayTarget.SPEED
            previewNavBlock.visibility = if (showNav) View.VISIBLE else View.GONE
            previewSpeedLimit.visibility = if (showSpeed) View.VISIBLE else View.GONE
            if (showNav) {
                positionPreviewView(previewContainer, previewNavBlock, navPoint.x, navPoint.y)
                previewNavBlock.alpha = brightnessSeek.progress.coerceIn(0, 100) / 100f
            }
            if (showSpeed) {
                positionPreviewView(previewContainer, previewSpeedLimit, speedPoint.x, speedPoint.y)
                previewSpeedLimit.alpha = brightnessSeek.progress.coerceIn(0, 100) / 100f
            }
        }

        fun updateOverlayPosition(previewX: Float, previewY: Float, persist: Boolean) {
            val view = if (target == OverlayTarget.NAVIGATION) previewNavBlock else previewSpeedLimit
            val (dpX, dpY) = positionDpFromPreview(previewContainer, view, previewX, previewY)
            val point = PointF(dpX, dpY)
            if (target == OverlayTarget.NAVIGATION) {
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
            } else {
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
        }

        setupDialogDrag(
            previewContainer,
            if (target == OverlayTarget.NAVIGATION) previewNavBlock else previewSpeedLimit
        ) { previewX, previewY, persist ->
            updateOverlayPosition(previewX, previewY, persist)
        }

        scaleSeek.progress = scalePercent - 50
        scaleValue.text = getString(R.string.scale_percent_format, scalePercent)
        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = (progress + 50).coerceIn(50, 150)
                val scale = percent / 100f
                scaleValue.text = getString(R.string.scale_percent_format, percent)
                if (target == OverlayTarget.NAVIGATION) {
                    notifyOverlaySettingsChanged(
                        navScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                } else {
                    notifyOverlaySettingsChanged(
                        speedScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val percent = ((seekBar?.progress ?: 50) + 50).coerceIn(50, 150)
                val scale = percent / 100f
                if (target == OverlayTarget.NAVIGATION) {
                    OverlayPrefs.setNavScale(this@MainActivity, scale)
                    notifyOverlaySettingsChanged(
                        navScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                } else {
                    OverlayPrefs.setSpeedScale(this@MainActivity, scale)
                    notifyOverlaySettingsChanged(
                        speedScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
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
                if (target == OverlayTarget.NAVIGATION) {
                    previewNavBlock.alpha = alpha
                    notifyOverlaySettingsChanged(
                        navAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                } else {
                    previewSpeedLimit.alpha = alpha
                    notifyOverlaySettingsChanged(
                        speedAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val percent = (seekBar?.progress ?: 100).coerceIn(0, 100)
                val alpha = percent / 100f
                if (target == OverlayTarget.NAVIGATION) {
                    OverlayPrefs.setNavAlpha(this@MainActivity, alpha)
                    notifyOverlaySettingsChanged(
                        navAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                } else {
                    OverlayPrefs.setSpeedAlpha(this@MainActivity, alpha)
                    notifyOverlaySettingsChanged(
                        speedAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
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

    private fun positionPreviewView(container: FrameLayout, view: View, dpX: Float, dpY: Float) {
        val posPxX = dpX * displayDensity
        val posPxY = dpY * displayDensity
        val maxX = maxPreviewX(container, view)
        val maxY = maxPreviewY(container, view)
        val previewX = if (displaySize.x > 0) (posPxX / displaySize.x) * maxX else 0f
        val previewY = if (displaySize.y > 0) (posPxY / displaySize.y) * maxY else 0f
        view.x = min(max(previewX, 0f), maxX)
        view.y = min(max(previewY, 0f), maxY)
    }

    private fun positionDpFromPreview(
        container: FrameLayout,
        view: View,
        previewX: Float,
        previewY: Float
    ): Pair<Float, Float> {
        val maxX = maxPreviewX(container, view).coerceAtLeast(1f)
        val maxY = maxPreviewY(container, view).coerceAtLeast(1f)
        val clampedX = min(max(previewX, 0f), maxX)
        val clampedY = min(max(previewY, 0f), maxY)
        val fractionX = clampedX / maxX
        val fractionY = clampedY / maxY
        val displayX = fractionX * displaySize.x
        val displayY = fractionY * displaySize.y
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

}
