package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.PointF
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ScaledActivity() {
    companion object {
        internal const val CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA = 0.35f
        const val EXTRA_START_GUIDE = "extra_start_guide"
    }

    internal var isSyncingUi = false
    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED) {
                return
            }
            syncUiFromPrefs()
        }
    }
    internal lateinit var permissionStatus: TextView
    internal lateinit var requestPermissionButton: Button
    internal lateinit var overlaySwitch: SwitchCompat
    internal lateinit var nativeNavSwitch: SwitchCompat
    internal var mapToggleSwitch: SwitchCompat? = null
    private lateinit var settingsRoot: ScrollView
    internal lateinit var presetSpinner: Spinner
    internal lateinit var savePresetButton: Button
    internal lateinit var presetFolderHint: TextView
    internal lateinit var displaySpinner: Spinner
    private lateinit var positionContainerCard: View
    private lateinit var positionNavCard: View
    private lateinit var positionArrowCard: View
    private lateinit var positionSpeedCard: View
    private lateinit var positionHudSpeedCard: View
    private lateinit var positionRoadCameraCard: View
    private lateinit var positionTrafficLightCard: View
    private lateinit var positionSpeedometerCard: View
    private lateinit var positionClockCard: View
    internal lateinit var navProjectionSwitch: SwitchCompat
    internal lateinit var arrowProjectionSwitch: SwitchCompat
    internal lateinit var speedProjectionSwitch: SwitchCompat
    internal lateinit var hudSpeedProjectionSwitch: SwitchCompat
    internal lateinit var roadCameraProjectionSwitch: SwitchCompat
    internal lateinit var trafficLightProjectionSwitch: SwitchCompat
    internal lateinit var speedometerProjectionSwitch: SwitchCompat
    internal lateinit var clockProjectionSwitch: SwitchCompat
    internal lateinit var speedLimitFromHudSpeedCheck: CheckBox
    internal lateinit var arrowOnlyWhenNoIconCheck: CheckBox
    private lateinit var arrowOnlyWhenNoIconDesc: TextView
    internal lateinit var speedLimitAlertCheck: CheckBox
    internal lateinit var speedLimitAlertThresholdRow: View
    internal lateinit var speedLimitAlertThresholdSeek: SeekBar
    internal lateinit var speedLimitAlertThresholdValue: TextView
    internal lateinit var hudSpeedLimitCheck: CheckBox
    internal lateinit var hudSpeedLimitAlertCheck: CheckBox
    internal lateinit var hudSpeedLimitAlertThresholdRow: View
    internal lateinit var hudSpeedLimitAlertThresholdSeek: SeekBar
    internal lateinit var hudSpeedLimitAlertThresholdValue: TextView
    internal lateinit var trafficLightMaxActiveSeek: SeekBar
    internal lateinit var trafficLightMaxActiveValue: TextView
    internal lateinit var trafficLightPreviewContainer: LinearLayout
    private lateinit var donateButton: ImageButton
    private lateinit var settingsButton: ImageButton

    internal var displayOptions: List<DisplayOption> = emptyList()
    internal var displaySize: Point = Point(1, 1)
    internal var displayDensity = 1f
    private var guideController: GuideOverlayController? = null
    private var editorGuideController: GuideOverlayController? = null
    private var pendingGuideAfterDialog: List<GuideContent.GuideItem>? = null

    internal var presetOptions: List<PresetManager.Preset> = emptyList()
    internal var presetAdapter: PresetAdapter? = null
    internal var activePresetId: String? = null
    internal var isPresetSelectionSyncing = false
    internal var isDisplaySelectionSyncing = false
    internal var isApplyingPreset = false
    private val presetPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (isApplyingPreset) return@OnSharedPreferenceChangeListener
        updatePresetModifiedState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_hud_display_settings)
        settingsRoot = findViewById(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        permissionStatus = findViewById(R.id.permissionStatus)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        nativeNavSwitch = findViewById(R.id.nativeNavSwitch)
//        mapToggleSwitch = findViewById(R.id.mapToggleSwitch)
        presetSpinner = findViewById(R.id.presetSpinner)
        savePresetButton = findViewById(R.id.savePresetButton)
        presetFolderHint = findViewById(R.id.presetFolderHint)
        displaySpinner = findViewById(R.id.displaySpinner)
        positionContainerCard = findViewById(R.id.positionContainerCard)
        positionNavCard = findViewById(R.id.positionNavCard)
        positionArrowCard = findViewById(R.id.positionArrowCard)
        positionSpeedCard = findViewById(R.id.positionSpeedCard)
        positionHudSpeedCard = findViewById(R.id.positionHudSpeedCard)
        positionRoadCameraCard = findViewById(R.id.positionRoadCameraCard)
        positionTrafficLightCard = findViewById(R.id.positionTrafficLightCard)
        positionSpeedometerCard = findViewById(R.id.positionSpeedometerCard)
        positionClockCard = findViewById(R.id.positionClockCard)
        navProjectionSwitch = findViewById(R.id.navProjectionSwitch)
        arrowProjectionSwitch = findViewById(R.id.arrowProjectionSwitch)
        speedProjectionSwitch = findViewById(R.id.speedProjectionSwitch)
        hudSpeedProjectionSwitch = findViewById(R.id.hudSpeedProjectionSwitch)
        roadCameraProjectionSwitch = findViewById(R.id.roadCameraProjectionSwitch)
        trafficLightProjectionSwitch = findViewById(R.id.trafficLightProjectionSwitch)
        speedometerProjectionSwitch = findViewById(R.id.speedometerProjectionSwitch)
        clockProjectionSwitch = findViewById(R.id.clockProjectionSwitch)
        speedLimitFromHudSpeedCheck = findViewById(R.id.speedLimitFromHudSpeedCheck)
        arrowOnlyWhenNoIconCheck = findViewById(R.id.arrowOnlyWhenNoIconCheck)
        arrowOnlyWhenNoIconDesc = findViewById(R.id.arrowOnlyWhenNoIconDesc)
        speedLimitAlertCheck = findViewById(R.id.speedLimitAlertCheck)
        speedLimitAlertThresholdRow = findViewById(R.id.speedLimitAlertThresholdRow)
        speedLimitAlertThresholdSeek = findViewById(R.id.speedLimitAlertThresholdSeek)
        speedLimitAlertThresholdValue = findViewById(R.id.speedLimitAlertThresholdValue)
        hudSpeedLimitCheck = findViewById(R.id.hudSpeedLimitCheck)
        hudSpeedLimitAlertCheck = findViewById(R.id.hudSpeedLimitAlertCheck)
        hudSpeedLimitAlertThresholdRow = findViewById(R.id.hudSpeedLimitAlertThresholdRow)
        hudSpeedLimitAlertThresholdSeek = findViewById(R.id.hudSpeedLimitAlertThresholdSeek)
        hudSpeedLimitAlertThresholdValue = findViewById(R.id.hudSpeedLimitAlertThresholdValue)
        trafficLightMaxActiveSeek = findViewById(R.id.trafficLightMaxActiveSeek)
        trafficLightMaxActiveValue = findViewById(R.id.trafficLightMaxActiveValue)
        trafficLightPreviewContainer = findViewById(R.id.trafficLightPreviewContainer)
        donateButton = findViewById(R.id.btnDonate)
        settingsButton = findViewById(R.id.btnSettings)

        donateButton.setOnClickListener {
            showDonateDialog()
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestPermissionButton.setOnClickListener {
            openOverlaySettings()
        }

        overlaySwitch.isChecked = OverlayPrefs.isEnabled(this)
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
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

        nativeNavSwitch.isChecked = OverlayPrefs.nativeNavEnabled(this)
        nativeNavSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setNativeNavEnabled(this, isChecked)
            if (!isChecked && NativeNavigationController.isActive()) {
                NativeNavigationController.stopNavigation(this)
            }
        }

        mapToggleSwitch?.apply {
            isChecked = OverlayPrefs.mapEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (isSyncingUi) {
                    return@setOnCheckedChangeListener
                }
                OverlayPrefs.setMapEnabled(this@MainActivity, isChecked)
                notifyOverlaySettingsChanged(mapEnabled = isChecked)
            }
            visibility = View.GONE
        }

        positionNavCard.setOnClickListener {
            openPositionDialog(OverlayTarget.NAVIGATION)
        }
        positionArrowCard.setOnClickListener {
            openPositionDialog(OverlayTarget.ARROW)
        }
        positionSpeedCard.setOnClickListener {
            openPositionDialog(OverlayTarget.SPEED)
        }
        positionHudSpeedCard.setOnClickListener {
            openPositionDialog(OverlayTarget.HUDSPEED)
        }
        positionRoadCameraCard.setOnClickListener {
            openPositionDialog(OverlayTarget.ROAD_CAMERA)
        }
        positionTrafficLightCard.setOnClickListener {
            openPositionDialog(OverlayTarget.TRAFFIC_LIGHT)
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

        navProjectionSwitch.isChecked = OverlayPrefs.navEnabled(this)
        arrowProjectionSwitch.isChecked = OverlayPrefs.arrowEnabled(this)
        speedProjectionSwitch.isChecked = OverlayPrefs.speedEnabled(this)
        hudSpeedProjectionSwitch.isChecked = OverlayPrefs.hudSpeedEnabled(this)
        roadCameraProjectionSwitch.isChecked = OverlayPrefs.roadCameraEnabled(this)
        trafficLightProjectionSwitch.isChecked = OverlayPrefs.trafficLightEnabled(this)
        speedometerProjectionSwitch.isChecked = OverlayPrefs.speedometerEnabled(this)
        clockProjectionSwitch.isChecked = OverlayPrefs.clockEnabled(this)
        speedLimitFromHudSpeedCheck.isChecked = OverlayPrefs.speedLimitFromHudSpeed(this)
        arrowOnlyWhenNoIconCheck.isChecked = OverlayPrefs.arrowOnlyWhenNoIcon(this)
        navProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setNavEnabled(this, isChecked)
            notifyOverlaySettingsChanged(navEnabled = isChecked)
        }
        arrowProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setArrowEnabled(this, isChecked)
            notifyOverlaySettingsChanged(arrowEnabled = isChecked)
        }
        speedProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setSpeedEnabled(this, isChecked)
            notifyOverlaySettingsChanged(speedEnabled = isChecked)
        }
        hudSpeedProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setHudSpeedEnabled(this, isChecked)
            notifyOverlaySettingsChanged(hudSpeedEnabled = isChecked)
        }
        roadCameraProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setRoadCameraEnabled(this, isChecked)
            notifyOverlaySettingsChanged(roadCameraEnabled = isChecked)
        }
        trafficLightProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setTrafficLightEnabled(this, isChecked)
            notifyOverlaySettingsChanged(trafficLightEnabled = isChecked)
        }
        speedometerProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setSpeedometerEnabled(this, isChecked)
            notifyOverlaySettingsChanged(speedometerEnabled = isChecked)
        }
        clockProjectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setClockEnabled(this, isChecked)
            notifyOverlaySettingsChanged(clockEnabled = isChecked)
        }
        speedLimitFromHudSpeedCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setSpeedLimitFromHudSpeed(this, isChecked)
        }
        arrowOnlyWhenNoIconCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setArrowOnlyWhenNoIcon(this, isChecked)
            notifyOverlaySettingsChanged(arrowOnlyWhenNoIcon = isChecked)
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
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
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
                if (fromUser && !isSyncingUi) {
                    OverlayPrefs.setSpeedLimitAlertThreshold(this@MainActivity, threshold)
                    notifyOverlaySettingsChanged(speedLimitAlertThreshold = threshold)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isSyncingUi) {
                    return
                }
                val threshold = (seekBar?.progress ?: 0)
                    .coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
                OverlayPrefs.setSpeedLimitAlertThreshold(this@MainActivity, threshold)
                notifyOverlaySettingsChanged(speedLimitAlertThreshold = threshold)
            }
        })

        val hudSpeedLimitEnabled = OverlayPrefs.hudSpeedLimitEnabled(this)
        val hudSpeedLimitAlertEnabled = OverlayPrefs.hudSpeedLimitAlertEnabled(this)
        val hudSpeedLimitAlertThreshold = OverlayPrefs.hudSpeedLimitAlertThreshold(this)
        hudSpeedLimitCheck.isChecked = hudSpeedLimitEnabled
        hudSpeedLimitAlertCheck.isChecked = hudSpeedLimitAlertEnabled
        hudSpeedLimitAlertThresholdRow.visibility =
            if (hudSpeedLimitAlertEnabled) View.VISIBLE else View.GONE
        hudSpeedLimitAlertThresholdSeek.max = OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX
        hudSpeedLimitAlertThresholdSeek.progress = hudSpeedLimitAlertThreshold
        hudSpeedLimitAlertThresholdValue.text = getString(
            R.string.speed_limit_alert_threshold_value,
            hudSpeedLimitAlertThreshold
        )
        hudSpeedLimitCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setHudSpeedLimitEnabled(this, isChecked)
            notifyOverlaySettingsChanged(hudSpeedLimitEnabled = isChecked)
        }
        hudSpeedLimitAlertCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) {
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setHudSpeedLimitAlertEnabled(this, isChecked)
            hudSpeedLimitAlertThresholdRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            notifyOverlaySettingsChanged(
                hudSpeedLimitAlertEnabled = isChecked,
                hudSpeedLimitAlertThreshold = hudSpeedLimitAlertThresholdSeek.progress
            )
        }
        hudSpeedLimitAlertThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress.coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
                hudSpeedLimitAlertThresholdValue.text = getString(
                    R.string.speed_limit_alert_threshold_value,
                    threshold
                )
                if (fromUser && !isSyncingUi) {
                    OverlayPrefs.setHudSpeedLimitAlertThreshold(this@MainActivity, threshold)
                    notifyOverlaySettingsChanged(hudSpeedLimitAlertThreshold = threshold)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isSyncingUi) {
                    return
                }
                val threshold = (seekBar?.progress ?: 0)
                    .coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
                OverlayPrefs.setHudSpeedLimitAlertThreshold(this@MainActivity, threshold)
                notifyOverlaySettingsChanged(hudSpeedLimitAlertThreshold = threshold)
            }
        })

        val trafficLightMaxActiveMin = 1
        val trafficLightMaxActiveMax = 3
        trafficLightMaxActiveSeek.max = (trafficLightMaxActiveMax - trafficLightMaxActiveMin)
            .coerceAtLeast(0)
        fun updateTrafficLightMaxActive(value: Int) {
            trafficLightMaxActiveValue.text = getString(
                R.string.traffic_light_max_active_value,
                value
            )
            renderTrafficLightPreview(trafficLightPreviewContainer, value)
        }
        val trafficLightMaxActive = OverlayPrefs.trafficLightMaxActive(this)
            .coerceIn(trafficLightMaxActiveMin, trafficLightMaxActiveMax)
        trafficLightMaxActiveSeek.progress = (trafficLightMaxActive - trafficLightMaxActiveMin)
            .coerceIn(0, trafficLightMaxActiveSeek.max)
        updateTrafficLightMaxActive(trafficLightMaxActive)
        trafficLightMaxActiveSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val resolved = (progress + trafficLightMaxActiveMin)
                    .coerceIn(trafficLightMaxActiveMin, trafficLightMaxActiveMax)
                updateTrafficLightMaxActive(resolved)
                if (fromUser && !isSyncingUi) {
                    OverlayPrefs.setTrafficLightMaxActive(this@MainActivity, resolved)
                    notifyOverlaySettingsChanged(trafficLightMaxActive = resolved)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isSyncingUi) {
                    return
                }
                val resolved = ((seekBar?.progress ?: 0) + trafficLightMaxActiveMin)
                    .coerceIn(trafficLightMaxActiveMin, trafficLightMaxActiveMax)
                OverlayPrefs.setTrafficLightMaxActive(this@MainActivity, resolved)
                notifyOverlaySettingsChanged(trafficLightMaxActive = resolved)
            }
        })

        setupPresetSelector()
        setupDisplaySpinners()
        updatePermissionStatus()
        startCoreServices()
        settingsRoot.post { maybeStartGuideFromIntent() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        settingsRoot.post { maybeStartGuideFromIntent() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
        ContextCompat.registerReceiver(
            this,
            settingsChangedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        getSharedPreferences(PrefsJson.OVERLAY_PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(presetPrefsListener)
        getSharedPreferences(PrefsJson.MANEUVER_PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(presetPrefsListener)
        syncUiFromPrefs()
        updatePresetModifiedState()
    }

    override fun onStop() {
        try {
            unregisterReceiver(settingsChangedReceiver)
        } catch (_: Exception) {
        }
        getSharedPreferences(PrefsJson.OVERLAY_PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(presetPrefsListener)
        getSharedPreferences(PrefsJson.MANEUVER_PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(presetPrefsListener)
        super.onStop()
    }

    override fun onDestroy() {
        guideController?.stop()
        guideController = null
        editorGuideController?.stop()
        editorGuideController = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshPresets(keepSelection = true)
    }

    private fun maybeStartGuideFromIntent() {
        val forceStart = intent?.getBooleanExtra(EXTRA_START_GUIDE, false) ?: false
        if (!forceStart && OverlayPrefs.guideShown(this)) {
            return
        }
        if (guideController != null) {
            return
        }
        intent?.removeExtra(EXTRA_START_GUIDE)
        startGuide()
    }

    private fun startGuide() {
        startMainGuide()
    }

    private fun startMainGuide() {
        val steps = GuideContent.mainItems()
        if (steps.isEmpty()) {
            OverlayPrefs.setGuideShown(this, true)
            return
        }
        val splitIndex = steps.indexOfFirst { it.targetId == R.id.positionNavCard }
            .takeIf { it >= 0 } ?: (steps.size - 1)
        val beforeSteps = steps.subList(0, splitIndex + 1)
        val afterSteps = steps.subList(splitIndex + 1, steps.size)
        guideController = GuideOverlayController(
            activity = this,
            scrollView = settingsRoot,
            steps = beforeSteps,
            onFinish = { reason ->
                guideController = null
                if (reason == GuideOverlayController.FinishReason.Skipped) {
                    OverlayPrefs.setGuideShown(this, true)
                } else {
                    startEditorGuide(afterSteps)
                }
            }
        )
        guideController?.start()
    }

    private fun startEditorGuide(remainingSteps: List<GuideContent.GuideItem>) {
        openPositionDialog(
            OverlayTarget.NAVIGATION,
            onDialogShown = onDialogShown@{ dialog, dialogView ->
                val dialogScroll = dialogView as? ScrollView
                if (dialogScroll == null) {
                    dialog.dismiss()
                    OverlayPrefs.setGuideShown(this, true)
                    return@onDialogShown
                }
                val steps = GuideContent.editorDialogItems()
                if (steps.isEmpty()) {
                    dialog.dismiss()
                    OverlayPrefs.setGuideShown(this, true)
                    return@onDialogShown
                }
                val decorView = dialog.window?.decorView as? ViewGroup
                if (decorView == null) {
                    dialog.dismiss()
                    OverlayPrefs.setGuideShown(this, true)
                    return@onDialogShown
                }
                editorGuideController = GuideOverlayController(
                    activity = this,
                    scrollView = dialogScroll,
                    steps = steps,
                    onFinish = { reason ->
                        editorGuideController = null
                        if (reason == GuideOverlayController.FinishReason.Skipped) {
                            pendingGuideAfterDialog = null
                            OverlayPrefs.setGuideShown(this, true)
                        } else {
                            pendingGuideAfterDialog = remainingSteps
                        }
                        dialog.dismiss()
                    },
                    overlayRootProvider = { decorView },
                    viewFinder = { id -> dialogView.findViewById(id) }
                )
                editorGuideController?.start()
            },
            onDialogDismissed = {
                editorGuideController?.stop()
                editorGuideController = null
                val remaining = pendingGuideAfterDialog
                pendingGuideAfterDialog = null
                if (remaining != null) {
                    startRemainingGuide(remaining)
                } else {
                    OverlayPrefs.setGuideShown(this, true)
                }
            }
        )
    }

    private fun startRemainingGuide(steps: List<GuideContent.GuideItem>) {
        if (steps.isEmpty()) {
            OverlayPrefs.setGuideShown(this, true)
            return
        }
        guideController = GuideOverlayController(
            activity = this,
            scrollView = settingsRoot,
            steps = steps,
            onFinish = { _ ->
                guideController = null
                OverlayPrefs.setGuideShown(this, true)
            }
        )
        guideController?.start()
    }


    internal fun notifyOverlaySettingsChanged(
        containerPosition: PointF? = null,
        containerWidthDp: Float? = null,
        containerHeightDp: Float? = null,
        navPosition: PointF? = null,
        navWidthDp: Float? = null,
        arrowPosition: PointF? = null,
        speedPosition: PointF? = null,
        hudSpeedPosition: PointF? = null,
        roadCameraPosition: PointF? = null,
        trafficLightPosition: PointF? = null,
        speedometerPosition: PointF? = null,
        clockPosition: PointF? = null,
        navScale: Float? = null,
        navTextScale: Float? = null,
        arrowScale: Float? = null,
        speedScale: Float? = null,
        speedTextScale: Float? = null,
        hudSpeedScale: Float? = null,
        roadCameraScale: Float? = null,
        trafficLightScale: Float? = null,
        speedometerScale: Float? = null,
        clockScale: Float? = null,
        navAlpha: Float? = null,
        arrowAlpha: Float? = null,
        speedAlpha: Float? = null,
        hudSpeedAlpha: Float? = null,
        roadCameraAlpha: Float? = null,
        trafficLightAlpha: Float? = null,
        speedometerAlpha: Float? = null,
        clockAlpha: Float? = null,
        containerAlpha: Float? = null,
        navEnabled: Boolean? = null,
        arrowEnabled: Boolean? = null,
        speedEnabled: Boolean? = null,
        hudSpeedEnabled: Boolean? = null,
        hudSpeedLimitEnabled: Boolean? = null,
        hudSpeedLimitAlertEnabled: Boolean? = null,
        hudSpeedLimitAlertThreshold: Int? = null,
        roadCameraEnabled: Boolean? = null,
        trafficLightEnabled: Boolean? = null,
        arrowOnlyWhenNoIcon: Boolean? = null,
        speedLimitAlertEnabled: Boolean? = null,
        speedLimitAlertThreshold: Int? = null,
        speedometerEnabled: Boolean? = null,
        clockEnabled: Boolean? = null,
        trafficLightMaxActive: Int? = null,
        mapEnabled: Boolean? = null,
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
        if (navWidthDp != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_WIDTH_DP, navWidthDp)
        }
        if (arrowPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_X_DP, arrowPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_Y_DP, arrowPosition.y)
        }
        if (speedPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, speedPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, speedPosition.y)
        }
        if (hudSpeedPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_X_DP, hudSpeedPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_Y_DP, hudSpeedPosition.y)
        }
        if (roadCameraPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_X_DP, roadCameraPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_Y_DP, roadCameraPosition.y)
        }
        if (trafficLightPosition != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_X_DP, trafficLightPosition.x)
            intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_Y_DP, trafficLightPosition.y)
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
        if (navTextScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_NAV_TEXT_SCALE, navTextScale)
        }
        if (arrowScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_SCALE, arrowScale)
        }
        if (speedScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, speedScale)
        }
        if (speedTextScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_TEXT_SCALE, speedTextScale)
        }
        if (hudSpeedScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_SCALE, hudSpeedScale)
        }
        if (roadCameraScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_SCALE, roadCameraScale)
        }
        if (trafficLightScale != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_SCALE, trafficLightScale)
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
        if (arrowAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_ALPHA, arrowAlpha)
        }
        if (speedAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, speedAlpha)
        }
        if (hudSpeedAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ALPHA, hudSpeedAlpha)
        }
        if (roadCameraAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ALPHA, roadCameraAlpha)
        }
        if (trafficLightAlpha != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ALPHA, trafficLightAlpha)
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
        if (arrowEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_ENABLED, arrowEnabled)
        }
        if (speedEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED, speedEnabled)
        }
        if (hudSpeedEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ENABLED, hudSpeedEnabled)
        }
        if (hudSpeedLimitEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ENABLED, hudSpeedLimitEnabled)
        }
        if (hudSpeedLimitAlertEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ALERT_ENABLED, hudSpeedLimitAlertEnabled)
        }
        if (hudSpeedLimitAlertThreshold != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ALERT_THRESHOLD, hudSpeedLimitAlertThreshold)
        }
        if (roadCameraEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ENABLED, roadCameraEnabled)
        }
        if (trafficLightEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ENABLED, trafficLightEnabled)
        }
        if (arrowOnlyWhenNoIcon != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_ARROW_ONLY_WHEN_NO_ICON, arrowOnlyWhenNoIcon)
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
        if (trafficLightMaxActive != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_MAX_ACTIVE, trafficLightMaxActive)
        }
        if (mapEnabled != null) {
            intent.putExtra(OverlayBroadcasts.EXTRA_MAP_ENABLED, mapEnabled)
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


}
