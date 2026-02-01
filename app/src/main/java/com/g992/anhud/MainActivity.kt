package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ScaledActivity() {
    companion object {
        private const val CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA = 0.35f
        const val EXTRA_START_GUIDE = "extra_start_guide"
    }

    private var isSyncingUi = false
    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED) {
                return
            }
            syncUiFromPrefs()
        }
    }
    private lateinit var permissionStatus: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var overlaySwitch: SwitchCompat
    private lateinit var nativeNavSwitch: SwitchCompat
    private var mapToggleSwitch: SwitchCompat? = null
    private lateinit var settingsRoot: ScrollView
    private lateinit var displaySpinner: Spinner
    private lateinit var positionContainerCard: View
    private lateinit var positionNavCard: View
    private lateinit var positionArrowCard: View
    private lateinit var positionSpeedCard: View
    private lateinit var positionHudSpeedCard: View
    private lateinit var positionRoadCameraCard: View
    private lateinit var positionTrafficLightCard: View
    private lateinit var positionSpeedometerCard: View
    private lateinit var positionClockCard: View
    private lateinit var navProjectionSwitch: SwitchCompat
    private lateinit var arrowProjectionSwitch: SwitchCompat
    private lateinit var speedProjectionSwitch: SwitchCompat
    private lateinit var hudSpeedProjectionSwitch: SwitchCompat
    private lateinit var roadCameraProjectionSwitch: SwitchCompat
    private lateinit var trafficLightProjectionSwitch: SwitchCompat
    private lateinit var speedometerProjectionSwitch: SwitchCompat
    private lateinit var clockProjectionSwitch: SwitchCompat
    private lateinit var speedLimitFromHudSpeedCheck: CheckBox
    private lateinit var arrowOnlyWhenNoIconCheck: CheckBox
    private lateinit var arrowOnlyWhenNoIconDesc: TextView
    private lateinit var speedLimitAlertCheck: CheckBox
    private lateinit var speedLimitAlertThresholdRow: View
    private lateinit var speedLimitAlertThresholdSeek: SeekBar
    private lateinit var speedLimitAlertThresholdValue: TextView
    private lateinit var trafficLightMaxActiveSeek: SeekBar
    private lateinit var trafficLightMaxActiveValue: TextView
    private lateinit var trafficLightPreviewContainer: LinearLayout
    private lateinit var donateButton: ImageButton
    private lateinit var settingsButton: ImageButton

    private var displayOptions: List<DisplayOption> = emptyList()
    private var displaySize: Point = Point(1, 1)
    private var displayDensity = 1f
    private var guideController: GuideOverlayController? = null
    private var editorGuideController: GuideOverlayController? = null
    private var pendingGuideAfterDialog: List<GuideContent.GuideItem>? = null

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
        syncUiFromPrefs()
    }

    override fun onStop() {
        try {
            unregisterReceiver(settingsChangedReceiver)
        } catch (_: Exception) {
        }
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

    private fun syncUiFromPrefs() {
        isSyncingUi = true
        try {
            overlaySwitch.isChecked = OverlayPrefs.isEnabled(this)
            nativeNavSwitch.isChecked = OverlayPrefs.nativeNavEnabled(this)
            mapToggleSwitch?.isChecked = OverlayPrefs.mapEnabled(this)
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

            val speedLimitAlertEnabled = OverlayPrefs.speedLimitAlertEnabled(this)
            val speedLimitAlertThreshold = OverlayPrefs.speedLimitAlertThreshold(this)
            speedLimitAlertCheck.isChecked = speedLimitAlertEnabled
            speedLimitAlertThresholdRow.visibility =
                if (speedLimitAlertEnabled) View.VISIBLE else View.GONE
            speedLimitAlertThresholdSeek.progress = speedLimitAlertThreshold
            speedLimitAlertThresholdValue.text = getString(
                R.string.speed_limit_alert_threshold_value,
                speedLimitAlertThreshold
            )

            val trafficLightMaxActiveMin = 1
            val trafficLightMaxActiveMax = 3
            val trafficLightMaxActive = OverlayPrefs.trafficLightMaxActive(this)
                .coerceIn(trafficLightMaxActiveMin, trafficLightMaxActiveMax)
            trafficLightMaxActiveSeek.progress = (trafficLightMaxActive - trafficLightMaxActiveMin)
                .coerceIn(0, trafficLightMaxActiveSeek.max)
            trafficLightMaxActiveValue.text = getString(
                R.string.traffic_light_max_active_value,
                trafficLightMaxActive
            )
            renderTrafficLightPreview(trafficLightPreviewContainer, trafficLightMaxActive)
        } finally {
            isSyncingUi = false
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun renderTrafficLightPreview(container: LinearLayout, count: Int) {
        val resolvedCount = count.coerceIn(1, 3)
        val inflater = LayoutInflater.from(container.context)
        val density = resources.displayMetrics.density
        val itemSpacingPx = (8 * density).roundToInt()
        container.removeAllViews()
        repeat(resolvedCount) { index ->
            val item = inflater.inflate(R.layout.traffic_light_notification_view, container, false)
            val compactView = item.findViewById<FrameLayout>(R.id.traffic_light_view)
            val expandedView = item.findViewById<LinearLayout>(R.id.traffic_light_view_expanded)
            val expandedCircle = item.findViewById<FrameLayout>(R.id.traffic_light_view_expanded_circle)
            val expandedText = item.findViewById<TextView>(R.id.traffic_light_data_expanded)
            val expandedIcon = item.findViewById<ImageView>(R.id.traffic_light_icon_expanded)

            compactView.visibility = View.GONE
            expandedView.visibility = View.VISIBLE
            expandedCircle.setBackgroundResource(R.drawable.traffic_light_background_green)
            expandedText.text = "11"
            expandedText.visibility = View.VISIBLE
            expandedIcon.setImageResource(R.drawable.context_lane_straightahead_small_24)

            val params = item.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            if (index < resolvedCount - 1) {
                params.marginEnd = itemSpacingPx
            } else {
                params.marginEnd = 0
            }
            item.layoutParams = params
            container.addView(item)
        }
    }

    private fun showDonateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_donate, null)
        AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setView(dialogView)
            .setPositiveButton(R.string.donate_dialog_close, null)
            .show()
    }

    private fun setupDisplaySpinners() {
        val displays = HudDisplayUtils.availableDisplays(this)
        displayOptions = displays.map { display ->
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

        val preferredDisplayId = HudDisplayUtils.preferredDisplayId(this)
        val savedDisplayId = OverlayPrefs.displayId(this)
        val preferredIndex = displayOptions.indexOfFirst { it.id == preferredDisplayId }.takeIf { it >= 0 } ?: 0
        val savedIndex = displayOptions.indexOfFirst { it.id == savedDisplayId }
        val selectedIndex = if (savedIndex >= 0) savedIndex else preferredIndex
        val selectedOption = displayOptions.getOrNull(selectedIndex)
        if (selectedOption != null) {
            displaySpinner.setSelection(selectedIndex)
            displaySpinner.post {
                updateDisplayMetrics(selectedOption.id)
            }
        } else {
            updateDisplayMetrics(preferredDisplayId)
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

    private data class DisplayOption(val id: Int, val label: String)
    private enum class OverlayTarget(val previewKey: String) {
        NAVIGATION(OverlayBroadcasts.PREVIEW_TARGET_NAV),
        ARROW(OverlayBroadcasts.PREVIEW_TARGET_ARROW),
        SPEED(OverlayBroadcasts.PREVIEW_TARGET_SPEED),
        HUDSPEED(OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED),
        ROAD_CAMERA(OverlayBroadcasts.PREVIEW_TARGET_ROAD_CAMERA),
        TRAFFIC_LIGHT(OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT),
        SPEEDOMETER(OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER),
        CLOCK(OverlayBroadcasts.PREVIEW_TARGET_CLOCK),
        CONTAINER(OverlayBroadcasts.PREVIEW_TARGET_CONTAINER)
    }

    private fun openPositionDialog(
        target: OverlayTarget,
        onDialogShown: ((AlertDialog, View) -> Unit)? = null,
        onDialogDismissed: (() -> Unit)? = null
    ) {
        updateDisplayMetrics(OverlayPrefs.displayId(this))
        val dialogView = layoutInflater.inflate(R.layout.dialog_position_editor, null)
        val previewContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewContainer)
        val previewHudContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewHudContainer)
        val previewNavBlock = dialogView.findViewById<View>(R.id.dialogPreviewNavBlock)
        val previewNavPrimary = dialogView.findViewById<TextView>(R.id.dialogPreviewNavPrimary)
        val previewNavSecondary = dialogView.findViewById<TextView>(R.id.dialogPreviewNavSecondary)
        val previewNavTime = dialogView.findViewById<TextView>(R.id.dialogPreviewNavTime)
        val previewArrowBlock = dialogView.findViewById<View>(R.id.dialogPreviewArrowBlock)
        val previewSpeedLimit = dialogView.findViewById<TextView>(R.id.dialogPreviewSpeedLimit)
        val previewHudSpeedBlock = dialogView.findViewById<View>(R.id.dialogPreviewHudSpeedBlock)
        val previewRoadCameraBlock = dialogView.findViewById<View>(R.id.dialogPreviewRoadCameraBlock)
        val previewTrafficLightBlock = dialogView.findViewById<LinearLayout>(R.id.dialogPreviewTrafficLightBlock)
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
        val navTextScaleLabel = dialogView.findViewById<TextView>(R.id.dialogNavTextScaleLabel)
        val navTextScaleRow = dialogView.findViewById<View>(R.id.dialogNavTextScaleRow)
        val navTextScaleSeek = dialogView.findViewById<SeekBar>(R.id.dialogNavTextScaleSeek)
        val navTextScaleValue = dialogView.findViewById<TextView>(R.id.dialogNavTextScaleValue)
        val brightnessSeek = dialogView.findViewById<SeekBar>(R.id.dialogBrightnessSeek)
        val brightnessValue = dialogView.findViewById<TextView>(R.id.dialogBrightnessValue)

        val navPosition = OverlayPrefs.navPositionDp(this)
        val arrowPosition = OverlayPrefs.arrowPositionDp(this)
        val speedPosition = OverlayPrefs.speedPositionDp(this)
        val hudSpeedPosition = OverlayPrefs.hudSpeedPositionDp(this)
        val roadCameraPosition = OverlayPrefs.roadCameraPositionDp(this)
        val trafficLightPosition = OverlayPrefs.trafficLightPositionDp(this)
        val speedometerPosition = OverlayPrefs.speedometerPositionDp(this)
        val clockPosition = OverlayPrefs.clockPositionDp(this)
        val containerPosition = OverlayPrefs.containerPositionDp(this)
        val containerSize = OverlayPrefs.containerSizeDp(this)
        val navPoint = PointF(0f, navPosition.y)
        val arrowPoint = PointF(arrowPosition.x, arrowPosition.y)
        val speedPoint = PointF(speedPosition.x, speedPosition.y)
        val hudSpeedPoint = PointF(hudSpeedPosition.x, hudSpeedPosition.y)
        val roadCameraPoint = PointF(roadCameraPosition.x, roadCameraPosition.y)
        val trafficLightPoint = PointF(trafficLightPosition.x, trafficLightPosition.y)
        val speedometerPoint = PointF(speedometerPosition.x, speedometerPosition.y)
        val clockPoint = PointF(clockPosition.x, clockPosition.y)
        val containerPoint = PointF(containerPosition.x, containerPosition.y)
        var containerWidthDp = containerSize.x
        var containerHeightDp = containerSize.y
        val scalePercent = when (target) {
            OverlayTarget.NAVIGATION -> (OverlayPrefs.navScale(this) * 100).toInt()
            OverlayTarget.ARROW -> (OverlayPrefs.arrowScale(this) * 100).toInt()
            OverlayTarget.SPEED -> (OverlayPrefs.speedScale(this) * 100).toInt()
            OverlayTarget.HUDSPEED -> (OverlayPrefs.hudSpeedScale(this) * 100).toInt()
            OverlayTarget.ROAD_CAMERA -> (OverlayPrefs.roadCameraScale(this) * 100).toInt()
            OverlayTarget.TRAFFIC_LIGHT -> (OverlayPrefs.trafficLightScale(this) * 100).toInt()
            OverlayTarget.SPEEDOMETER -> (OverlayPrefs.speedometerScale(this) * 100).toInt()
            OverlayTarget.CLOCK -> (OverlayPrefs.clockScale(this) * 100).toInt()
            OverlayTarget.CONTAINER -> 100
        }
        val brightnessPercent = when (target) {
            OverlayTarget.NAVIGATION -> (OverlayPrefs.navAlpha(this) * 100).toInt()
            OverlayTarget.ARROW -> (OverlayPrefs.arrowAlpha(this) * 100).toInt()
            OverlayTarget.SPEED -> (OverlayPrefs.speedAlpha(this) * 100).toInt()
            OverlayTarget.HUDSPEED -> (OverlayPrefs.hudSpeedAlpha(this) * 100).toInt()
            OverlayTarget.ROAD_CAMERA -> (OverlayPrefs.roadCameraAlpha(this) * 100).toInt()
            OverlayTarget.TRAFFIC_LIGHT -> (OverlayPrefs.trafficLightAlpha(this) * 100).toInt()
            OverlayTarget.SPEEDOMETER -> (OverlayPrefs.speedometerAlpha(this) * 100).toInt()
            OverlayTarget.CLOCK -> (OverlayPrefs.clockAlpha(this) * 100).toInt()
            OverlayTarget.CONTAINER -> (OverlayPrefs.containerAlpha(this) * 100).toInt()
        }.coerceIn(0, 100)

        val scaledDensity = resources.displayMetrics.scaledDensity
        val navPrimaryBaseSp = previewNavPrimary.textSize / scaledDensity
        val navSecondaryBaseSp = previewNavSecondary.textSize / scaledDensity
        val navTimeBaseSp = previewNavTime.textSize / scaledDensity
        val speedLimitBaseSp = previewSpeedLimit.textSize / scaledDensity

        fun applyNavTextScale(scale: Float) {
            previewNavPrimary.setTextSize(TypedValue.COMPLEX_UNIT_SP, navPrimaryBaseSp * scale)
            previewNavSecondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, navSecondaryBaseSp * scale)
            previewNavTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, navTimeBaseSp * scale)
        }

        fun applySpeedTextScale(scale: Float) {
            previewSpeedLimit.setTextSize(TypedValue.COMPLEX_UNIT_SP, speedLimitBaseSp * scale)
        }

        val dialogTitle = when (target) {
            OverlayTarget.NAVIGATION -> getString(R.string.position_nav_block_label)
            OverlayTarget.ARROW -> getString(R.string.position_arrow_block_label)
            OverlayTarget.SPEED -> getString(R.string.position_speed_block_label)
            OverlayTarget.HUDSPEED -> getString(R.string.position_hudspeed_block_label)
            OverlayTarget.ROAD_CAMERA -> getString(R.string.position_road_camera_block_label)
            OverlayTarget.TRAFFIC_LIGHT -> getString(R.string.position_traffic_light_block_label)
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
                onDialogDismissed?.invoke()
            }
            .create()

        if (target == OverlayTarget.CONTAINER) {
            scaleLabel.visibility = View.GONE
            scaleSeek.visibility = View.GONE
            scaleValue.visibility = View.GONE
            navTextScaleLabel.visibility = View.GONE
            navTextScaleRow.visibility = View.GONE
        } else {
            containerWidthLabel.visibility = View.GONE
            containerWidthRow.visibility = View.GONE
            containerHeightLabel.visibility = View.GONE
            containerHeightRow.visibility = View.GONE
        }

        val showTextScale = target == OverlayTarget.NAVIGATION || target == OverlayTarget.SPEED
        if (!showTextScale) {
            navTextScaleLabel.visibility = View.GONE
            navTextScaleRow.visibility = View.GONE
        } else {
            navTextScaleLabel.visibility = View.VISIBLE
            navTextScaleRow.visibility = View.VISIBLE
            val isNav = target == OverlayTarget.NAVIGATION
            navTextScaleLabel.setText(
                if (isNav) R.string.position_nav_text_scale_label else R.string.position_speed_text_scale_label
            )
            val minPercent = if (isNav) 100 else 50
            val maxPercent = if (isNav) 300 else 200
            val currentScale = if (isNav) {
                OverlayPrefs.navTextScale(this)
            } else {
                OverlayPrefs.speedTextScale(this)
            }
            val currentPercent = (currentScale * 100).roundToInt().coerceIn(minPercent, maxPercent)
            navTextScaleSeek.max = (maxPercent - minPercent).coerceAtLeast(0)
            navTextScaleSeek.progress = (currentPercent - minPercent).coerceIn(0, navTextScaleSeek.max)
            navTextScaleValue.text = getString(R.string.scale_percent_format, currentPercent)
            if (isNav) {
                applyNavTextScale(currentPercent / 100f)
            } else {
                applySpeedTextScale(currentPercent / 100f)
            }
            navTextScaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = (progress + minPercent).coerceIn(minPercent, maxPercent)
                    val scale = percent / 100f
                    navTextScaleValue.text = getString(R.string.scale_percent_format, percent)
                    if (isNav) {
                        applyNavTextScale(scale)
                        notifyOverlaySettingsChanged(
                            navTextScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    } else {
                        applySpeedTextScale(scale)
                        notifyOverlaySettingsChanged(
                            speedTextScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = ((seekBar?.progress ?: 0) + minPercent)
                        .coerceIn(minPercent, maxPercent)
                    val scale = percent / 100f
                    if (isNav) {
                        OverlayPrefs.setNavTextScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            navTextScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    } else {
                        OverlayPrefs.setSpeedTextScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            speedTextScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                }
            })
        }

        renderTrafficLightPreview(previewTrafficLightBlock, OverlayPrefs.trafficLightMaxActive(this))

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
            val showArrow = target == OverlayTarget.ARROW
            val showSpeed = target == OverlayTarget.SPEED
            val showHudSpeed = target == OverlayTarget.HUDSPEED
            val showRoadCamera = target == OverlayTarget.ROAD_CAMERA
            val showTrafficLight = target == OverlayTarget.TRAFFIC_LIGHT
            val showSpeedometer = target == OverlayTarget.SPEEDOMETER
            val showClock = target == OverlayTarget.CLOCK
            previewNavBlock.visibility = if (showNav) View.VISIBLE else View.GONE
            previewArrowBlock.visibility = if (showArrow) View.VISIBLE else View.GONE
            previewSpeedLimit.visibility = if (showSpeed) View.VISIBLE else View.GONE
            previewHudSpeedBlock.visibility = if (showHudSpeed) View.VISIBLE else View.GONE
            previewRoadCameraBlock.visibility = if (showRoadCamera) View.VISIBLE else View.GONE
            previewTrafficLightBlock.visibility = if (showTrafficLight) View.VISIBLE else View.GONE
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
            if (showArrow) {
                positionPreviewView(
                    previewHudContainer,
                    previewArrowBlock,
                    arrowPoint.x,
                    arrowPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewArrowBlock.alpha = if (target == OverlayTarget.ARROW) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.arrowAlpha(this@MainActivity).coerceIn(0f, 1f)
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
            if (showHudSpeed) {
                positionPreviewView(
                    previewHudContainer,
                    previewHudSpeedBlock,
                    hudSpeedPoint.x,
                    hudSpeedPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewHudSpeedBlock.alpha = if (target == OverlayTarget.HUDSPEED) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.hudSpeedAlpha(this@MainActivity).coerceIn(0f, 1f)
                }
            }
            if (showRoadCamera) {
                positionPreviewView(
                    previewHudContainer,
                    previewRoadCameraBlock,
                    roadCameraPoint.x,
                    roadCameraPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewRoadCameraBlock.alpha = if (target == OverlayTarget.ROAD_CAMERA) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.roadCameraAlpha(this@MainActivity).coerceIn(0f, 1f)
                }
            }
            if (showTrafficLight) {
                positionPreviewView(
                    previewHudContainer,
                    previewTrafficLightBlock,
                    trafficLightPoint.x,
                    trafficLightPoint.y,
                    containerWidthPx,
                    containerHeightPx
                )
                previewTrafficLightBlock.alpha = if (target == OverlayTarget.TRAFFIC_LIGHT) {
                    brightnessSeek.progress.coerceIn(0, 100) / 100f
                } else {
                    OverlayPrefs.trafficLightAlpha(this@MainActivity).coerceIn(0f, 1f)
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
                OverlayTarget.ARROW -> previewArrowBlock
                OverlayTarget.SPEED -> previewSpeedLimit
                OverlayTarget.HUDSPEED -> previewHudSpeedBlock
                OverlayTarget.ROAD_CAMERA -> previewRoadCameraBlock
                OverlayTarget.TRAFFIC_LIGHT -> previewTrafficLightBlock
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
            val (dpXRaw, dpY) = positionDpFromPreview(dragContainer, view, previewX, previewY, boundsWidth, boundsHeight)
            val dpX = if (target == OverlayTarget.NAVIGATION) 0f else dpXRaw
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
                OverlayTarget.ARROW -> {
                    if (persist) {
                        OverlayPrefs.setArrowPositionDp(this, dpX, dpY)
                        arrowPoint.x = dpX
                        arrowPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        arrowPosition = point,
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
                OverlayTarget.HUDSPEED -> {
                    if (persist) {
                        OverlayPrefs.setHudSpeedPositionDp(this, dpX, dpY)
                        hudSpeedPoint.x = dpX
                        hudSpeedPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        hudSpeedPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.ROAD_CAMERA -> {
                    if (persist) {
                        OverlayPrefs.setRoadCameraPositionDp(this, dpX, dpY)
                        roadCameraPoint.x = dpX
                        roadCameraPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        roadCameraPosition = point,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.TRAFFIC_LIGHT -> {
                    if (persist) {
                        OverlayPrefs.setTrafficLightPositionDp(this, dpX, dpY)
                        trafficLightPoint.x = dpX
                        trafficLightPoint.y = dpY
                    }
                    notifyOverlaySettingsChanged(
                        trafficLightPosition = point,
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
                OverlayTarget.ARROW -> previewArrowBlock
                OverlayTarget.SPEED -> previewSpeedLimit
                OverlayTarget.HUDSPEED -> previewHudSpeedBlock
                OverlayTarget.ROAD_CAMERA -> previewRoadCameraBlock
                OverlayTarget.TRAFFIC_LIGHT -> previewTrafficLightBlock
                OverlayTarget.SPEEDOMETER -> previewSpeedometer
                OverlayTarget.CLOCK -> previewClock
                OverlayTarget.CONTAINER -> previewHudContainer
            },
            lockX = target == OverlayTarget.NAVIGATION
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
                    OverlayTarget.ARROW -> notifyOverlaySettingsChanged(
                        arrowScale = scale,
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
                    OverlayTarget.HUDSPEED -> notifyOverlaySettingsChanged(
                        hudSpeedScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                    OverlayTarget.ROAD_CAMERA -> notifyOverlaySettingsChanged(
                        roadCameraScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                    OverlayTarget.TRAFFIC_LIGHT -> notifyOverlaySettingsChanged(
                        trafficLightScale = scale,
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
                    OverlayTarget.ARROW -> {
                        OverlayPrefs.setArrowScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            arrowScale = scale,
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
                    OverlayTarget.HUDSPEED -> {
                        OverlayPrefs.setHudSpeedScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            hudSpeedScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.ROAD_CAMERA -> {
                        OverlayPrefs.setRoadCameraScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            roadCameraScale = scale,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.TRAFFIC_LIGHT -> {
                        OverlayPrefs.setTrafficLightScale(this@MainActivity, scale)
                        notifyOverlaySettingsChanged(
                            trafficLightScale = scale,
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
                    OverlayTarget.ARROW -> {
                        previewArrowBlock.alpha = alpha
                        notifyOverlaySettingsChanged(
                            arrowAlpha = alpha,
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
                    OverlayTarget.HUDSPEED -> {
                        previewHudSpeedBlock.alpha = alpha
                        notifyOverlaySettingsChanged(
                            hudSpeedAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.ROAD_CAMERA -> {
                        previewRoadCameraBlock.alpha = alpha
                        notifyOverlaySettingsChanged(
                            roadCameraAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.TRAFFIC_LIGHT -> {
                        previewTrafficLightBlock.alpha = alpha
                        notifyOverlaySettingsChanged(
                            trafficLightAlpha = alpha,
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
                    OverlayTarget.ARROW -> {
                        OverlayPrefs.setArrowAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            arrowAlpha = alpha,
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
                    OverlayTarget.HUDSPEED -> {
                        OverlayPrefs.setHudSpeedAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            hudSpeedAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.ROAD_CAMERA -> {
                        OverlayPrefs.setRoadCameraAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            roadCameraAlpha = alpha,
                            preview = true,
                            previewTarget = target,
                            previewShowOthers = showOthersCheck.isChecked
                        )
                    }
                    OverlayTarget.TRAFFIC_LIGHT -> {
                        OverlayPrefs.setTrafficLightAlpha(this@MainActivity, alpha)
                        notifyOverlaySettingsChanged(
                            trafficLightAlpha = alpha,
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
            dialog.window?.let { window ->
                val metrics = resources.displayMetrics
                val horizontalPaddingPx = (24 * metrics.density).roundToInt()
                val widthPx = (metrics.widthPixels - horizontalPaddingPx * 2).coerceAtLeast(1)
                window.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            notifyOverlaySettingsChanged(
                preview = true,
                previewTarget = target,
                previewShowOthers = showOthersCheck.isChecked
            )
            previewContainer.post {
                updateDialogVisibility()
            }
            onDialogShown?.invoke(dialog, dialogView)
        }

        dialog.show()
    }

    private fun setupDialogDrag(
        container: FrameLayout,
        view: View,
        lockX: Boolean = false,
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
                    v.x = if (lockX) 0f else min(max(newX, 0f), maxX)
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
