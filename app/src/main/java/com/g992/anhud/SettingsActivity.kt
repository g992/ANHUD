package com.g992.anhud

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.tabs.TabLayout
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.net.ConnectivityReceiver
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import kotlin.math.roundToInt

private const val SETTINGS_BUTTON_PRIMARY = 0
private const val SETTINGS_BUTTON_SECONDARY = 1
private const val SETTINGS_BUTTON_DANGER = 2

class SettingsActivity : ScaledActivity() {
    private lateinit var tabLayout: TabLayout
    private lateinit var tabGeneralContent: View
    private lateinit var tabMapContent: LinearLayout
    private lateinit var tabManeuverContent: View
    private lateinit var tabUpdatesContent: View
    private lateinit var tabDebugContent: View
    private lateinit var tabHelpContent: View
    private lateinit var timeoutSettingsToggle: View
    private lateinit var timeoutSettingsContent: View
    private lateinit var timeoutSettingsToggleLabel: TextView
    private lateinit var cameraTimeoutNearInput: EditText
    private lateinit var cameraTimeoutFarInput: EditText
    private lateinit var trafficLightTimeoutInput: EditText
    private lateinit var roadCameraTimeoutInput: EditText
    private lateinit var navNotificationEndTimeoutInput: EditText
    private lateinit var navUpdatesEndTimeoutInput: EditText
    private lateinit var speedometerFreezeTimeoutInput: EditText
    private lateinit var speedCorrectionSeek: SeekBar
    private lateinit var speedCorrectionValue: TextView
    private lateinit var speedFromGpsCheck: SwitchCompat
    private lateinit var infoMirrorStarsheep7Switch: SwitchCompat
    private lateinit var hideTurnWhenFarSwitch: SwitchCompat
    private lateinit var hideTurnWhenFarDistanceSeek: SeekBar
    private lateinit var hideTurnWhenFarDistanceValue: TextView
    private lateinit var hideTurnDynamicSwitch: SwitchCompat
    private lateinit var hideTurnDynamicConfigButton: Button
    private lateinit var maneuverRowContainer: LinearLayout
    private lateinit var helpListContainer: LinearLayout
    private lateinit var helpStartGuideButton: View
    private lateinit var exportSettingsButton: View
    private lateinit var importSettingsButton: View
    private lateinit var updateStatusText: TextView
    private lateinit var updateCurrentVersion: TextView
    private lateinit var updateLatestVersion: TextView
    private lateinit var updateReleaseNotes: TextView
    private lateinit var updateCheckButton: View
    private lateinit var updateInstallButton: View
    private lateinit var updateProgressBar: ProgressBar
    private lateinit var updateProgressText: TextView
    private lateinit var cpuGraph: PerformanceGraphView
    private lateinit var ramGraph: PerformanceGraphView
    private lateinit var cpuGraphSummary: TextView
    private lateinit var ramGraphSummary: TextView
    private lateinit var topCpuTable: TableLayout
    private lateinit var topCpuNote: TextView
    private lateinit var mapZoomValue: TextView
    private lateinit var mapAutoZoomZeroValue: TextView
    private lateinit var mapAutoZoomSixtyValue: TextView
    private lateinit var mapAutoZoomNinetyValue: TextView
    private lateinit var mapTiltValue: TextView
    private lateinit var mapArrowValue: TextView
    private lateinit var mapTileProviderValue: TextView
    private lateinit var mapStyleModeValue: TextView
    private lateinit var mapStyleModeHint: TextView
    private lateinit var mapCacheValue: TextView
    private lateinit var mapCacheClearButton: Button
    private lateinit var mapRouteSnapDistanceValue: TextView
    private lateinit var mapOfflineRegionValue: TextView
    private lateinit var mapAutoZoomSwitch: SwitchCompat
    private lateinit var mapVignetteSwitch: SwitchCompat
    private lateinit var mapRouteDownloadSwitch: SwitchCompat
    private lateinit var mapRouteSnapSwitch: SwitchCompat
    private lateinit var mapLocationSnapSwitch: SwitchCompat
    private var mapAutoZoomConfigRows: List<View> = emptyList()
    private var mapRouteSnapConfigRows: List<View> = emptyList()
    private var mapTileProviderButtons: Map<MapTileProvider, Button> = emptyMap()
    private var mapStyleModeButtons: Map<MapStyleMode, Button> = emptyMap()
    private var mapCacheButtons: List<Button> = emptyList()
    private var offlineDownloadsAdapter: OfflineDownloadsAdapter? = null
    private var offlineDownloadsRecommendationsSection: OfflineDownloadsRecommendationsSection? = null
    private var offlineCachePreviewSession: OfflineCachePreviewSession? = null

    private var updateDownloadId: Long = -1L
    private var updatesTabIndex: Int = -1
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var isCheckingUpdates = false

    private var isSyncingUi = false
    private var areTimeoutSettingsExpanded = false
    private var pendingSpeedFromGpsAfterBackgroundPermission = false

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            exportSettingsToDownloads()
        } else {
            showToast(R.string.settings_export_failed)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            disableSpeedFromGpsForPermission(R.string.speed_from_gps_permission_denied)
            return@registerForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            requestBackgroundLocationPermissionForGpsSpeed()
            return@registerForActivityResult
        }
        enableSpeedFromGps()
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (!hasBackgroundLocationPermission()) {
            disableSpeedFromGpsForPermission(R.string.speed_from_gps_background_permission_denied)
            return@registerForActivityResult
        }
        enableSpeedFromGps()
    }

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

    private val customMapStylePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importCustomMapStyle(uri)
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

    private val performanceListener = object : PerformanceDebugMonitor.Listener {
        override fun onSnapshotUpdated(snapshot: PerformanceDebugMonitor.Snapshot) {
            runOnUiThread {
                renderPerformanceSnapshot(snapshot)
            }
        }
    }

    private val mapCacheListener: (MapCacheSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            syncMapCacheUi(snapshot)
            offlineDownloadsAdapter?.updateSnapshot(snapshot)
            offlineDownloadsRecommendationsSection?.updateSnapshot(snapshot)
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UpdateBroadcasts.ACTION_UPDATE_STATUS_CHANGED) {
                return
            }
            refreshUpdateUi()
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                return
            }
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val expectedId = UpdatePrefs.getDownloadId(context)
            if (downloadId == -1L || expectedId == -1L || downloadId != expectedId) {
                return
            }
            UpdatePrefs.clearDownloadId(context)
            handleDownloadComplete(downloadId)
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
        tabMapContent = findViewById(R.id.tabMapContent)
        tabManeuverContent = findViewById(R.id.tabManeuverContent)
        tabUpdatesContent = findViewById(R.id.tabUpdatesContent)
        tabDebugContent = findViewById(R.id.tabDebugContent)
        tabHelpContent = findViewById(R.id.tabHelpContent)
        timeoutSettingsToggle = findViewById(R.id.timeoutSettingsToggle)
        timeoutSettingsContent = findViewById(R.id.timeoutSettingsContent)
        timeoutSettingsToggleLabel = findViewById(R.id.timeoutSettingsToggleLabel)
        cameraTimeoutNearInput = findViewById(R.id.cameraTimeoutNearInput)
        cameraTimeoutFarInput = findViewById(R.id.cameraTimeoutFarInput)
        trafficLightTimeoutInput = findViewById(R.id.trafficLightTimeoutInput)
        roadCameraTimeoutInput = findViewById(R.id.roadCameraTimeoutInput)
        navNotificationEndTimeoutInput = findViewById(R.id.navNotificationEndTimeoutInput)
        navUpdatesEndTimeoutInput = findViewById(R.id.navUpdatesEndTimeoutInput)
        speedometerFreezeTimeoutInput = findViewById(R.id.speedometerFreezeTimeoutInput)
        speedCorrectionSeek = findViewById(R.id.speedCorrectionSeek)
        speedCorrectionValue = findViewById(R.id.speedCorrectionValue)
        speedFromGpsCheck = findViewById(R.id.speedFromGpsCheck)
        infoMirrorStarsheep7Switch = findViewById(R.id.infoMirrorStarsheep7Switch)
        hideTurnWhenFarSwitch = findViewById(R.id.hideTurnWhenFarSwitch)
        hideTurnWhenFarDistanceSeek = findViewById(R.id.hideTurnWhenFarDistanceSeek)
        hideTurnWhenFarDistanceValue = findViewById(R.id.hideTurnWhenFarDistanceValue)
        hideTurnDynamicSwitch = findViewById(R.id.hideTurnDynamicSwitch)
        hideTurnDynamicConfigButton = findViewById(R.id.hideTurnDynamicConfigButton)
        styleSettingsButton(hideTurnDynamicConfigButton, SETTINGS_BUTTON_SECONDARY)
        maneuverRowContainer = findViewById(R.id.maneuverRowContainer)
        helpListContainer = findViewById(R.id.helpListContainer)
        helpStartGuideButton = findViewById(R.id.helpStartGuideButton)
        exportSettingsButton = findViewById(R.id.exportSettingsButton)
        importSettingsButton = findViewById(R.id.importSettingsButton)
        updateStatusText = findViewById(R.id.updateStatusText)
        updateCurrentVersion = findViewById(R.id.updateCurrentVersion)
        updateLatestVersion = findViewById(R.id.updateLatestVersion)
        updateReleaseNotes = findViewById(R.id.updateReleaseNotes)
        updateCheckButton = findViewById(R.id.updateCheckButton)
        updateInstallButton = findViewById(R.id.updateInstallButton)
        updateProgressBar = findViewById(R.id.updateProgressBar)
        updateProgressText = findViewById(R.id.updateProgressText)
        cpuGraph = findViewById(R.id.cpuGraphView)
        ramGraph = findViewById(R.id.ramGraphView)
        cpuGraphSummary = findViewById(R.id.cpuGraphSummary)
        ramGraphSummary = findViewById(R.id.ramGraphSummary)
        topCpuTable = findViewById(R.id.topCpuTable)
        topCpuNote = findViewById(R.id.topCpuNote)

        setupTabs()
        setupGeneralSettings()
        setupMapTab()
        setupManeuverTab()
        setupUpdatesTab()
        setupDebugTab()
        setupHelpTab()
        areTimeoutSettingsExpanded = savedInstanceState?.getBoolean(STATE_TIMEOUTS_EXPANDED, false) ?: false
        updateTimeoutSettingsSection()
        syncUiFromPrefs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_TIMEOUTS_EXPANDED, areTimeoutSettingsExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        UiLogStore.unregisterListener(logListener)
        PerformanceDebugMonitor.unregisterListener(performanceListener)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(UpdateBroadcasts.ACTION_UPDATE_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        MapCacheController.addListener(mapCacheListener)
        syncMapCacheUi(MapCacheController.current())
        refreshUpdateUi()
        if (pendingSpeedFromGpsAfterBackgroundPermission) {
            pendingSpeedFromGpsAfterBackgroundPermission = false
            if (hasBackgroundLocationPermission()) {
                enableSpeedFromGps()
            }
            syncUiFromPrefs()
        }
    }

    override fun onStop() {
        offlineCachePreviewSession?.dismiss()
        MapCacheController.removeListener(mapCacheListener)
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {
        }
        stopProgressPolling()
        super.onStop()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_general_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_map_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_nav_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_updates))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_debug))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_help))

        updatesTabIndex = 3
        val badge = tabLayout.getTabAt(updatesTabIndex)?.orCreateBadge
        badge?.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.update_badge_red))
        badge?.setVisible(false)
        badge?.setHorizontalOffset(dp(-2))
        badge?.setVerticalOffset(dp(1))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        tabGeneralContent.visibility = View.VISIBLE
                        tabMapContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    1 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabMapContent.visibility = View.VISIBLE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    2 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabMapContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.VISIBLE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    3 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabMapContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.VISIBLE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    4 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabMapContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.VISIBLE
                        tabHelpContent.visibility = View.GONE
                    }
                    5 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabMapContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.GONE
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
        timeoutSettingsToggle.setOnClickListener {
            areTimeoutSettingsExpanded = !areTimeoutSettingsExpanded
            updateTimeoutSettingsSection()
        }

        exportSettingsButton.setOnClickListener {
            if (needsStoragePermission()) {
                requestStoragePermission()
            } else {
                exportSettingsToDownloads()
            }
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

        navUpdatesEndTimeoutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setNavUpdatesEndTimeout(this@SettingsActivity, clamped)
            }
        })

        speedometerFreezeTimeoutInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSyncingUi) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, OverlayPrefs.TIMEOUT_MAX)
                OverlayPrefs.setSpeedometerFreezeTimeout(this@SettingsActivity, clamped)
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

        speedFromGpsCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (isChecked && !hasLocationPermission()) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return@setOnCheckedChangeListener
            }
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                requestBackgroundLocationPermissionForGpsSpeed()
                syncUiFromPrefs()
                return@setOnCheckedChangeListener
            }
            OverlayPrefs.setSpeedFromGps(this, isChecked)
            startService(Intent(this, SensorDataService::class.java))
        }

        infoMirrorStarsheep7Switch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            OverlayPrefs.setInfoMirrorStarsheep7Enabled(this, isChecked)
            broadcastInfoMirrorStarsheep7(isChecked)
        }

        hideTurnWhenFarSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            OverlayPrefs.setHideTurnWhenFarEnabled(this, isChecked)
            updateHideTurnDistanceControls(isChecked)
            updateHideTurnDynamicControls(
                hideTurnEnabled = isChecked,
                dynamicEnabled = OverlayPrefs.hideTurnDynamicEnabled(this)
            )
        }

        hideTurnWhenFarDistanceSeek.max = OverlayPrefs.hideTurnDistanceStepsMeters().lastIndex
        hideTurnWhenFarDistanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distance = OverlayPrefs.hideTurnWhenFarDistanceMetersByProgress(progress)
                hideTurnWhenFarDistanceValue.text = formatManeuverHideDistance(distance)
                updateHideTurnSwitchLabel(distance)
                if (!isSyncingUi && fromUser) {
                    OverlayPrefs.setHideTurnWhenFarDistanceMeters(this@SettingsActivity, distance)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isSyncingUi) return
                val distance = OverlayPrefs.hideTurnWhenFarDistanceMetersByProgress(
                    seekBar?.progress ?: 0
                )
                OverlayPrefs.setHideTurnWhenFarDistanceMeters(this@SettingsActivity, distance)
            }
        })

        hideTurnDynamicSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            OverlayPrefs.setHideTurnDynamicEnabled(this, isChecked)
            updateHideTurnDynamicControls(
                hideTurnEnabled = OverlayPrefs.hideTurnWhenFarEnabled(this),
                dynamicEnabled = isChecked
            )
        }
        hideTurnDynamicConfigButton.setOnClickListener {
            showHideTurnDynamicSettingsDialog()
        }
    }

    private fun setupMapTab() {
        MapRenderSettingsStore.initialize(applicationContext)
        val settings = MapRenderSettingsStore.current()

        mapZoomValue = createMapValueView()
        mapAutoZoomZeroValue = createMapValueView()
        mapAutoZoomSixtyValue = createMapValueView()
        mapAutoZoomNinetyValue = createMapValueView()
        mapTiltValue = createMapValueView()
        mapArrowValue = createMapValueView()
        mapTileProviderValue = createMapValueView()
        mapStyleModeValue = createMapValueView()
        mapStyleModeHint = createMapHintView("")
        mapCacheValue = createMapValueView()
        mapRouteSnapDistanceValue = createMapValueView()
        mapOfflineRegionValue = createMapValueView()
        mapAutoZoomSwitch = SwitchCompat(this).apply {
            text = getString(R.string.map_settings_auto_zoom)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setOnCheckedChangeListener { _, isChecked ->
                if (!isSyncingUi) {
                    MapRenderSettingsStore.update { it.copy(autoZoomEnabled = isChecked) }
                    syncMapUiFromPrefs()
                }
            }
        }
        mapVignetteSwitch = SwitchCompat(this).apply {
            text = getString(R.string.map_settings_vignette)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setOnCheckedChangeListener { _, isChecked ->
                if (!isSyncingUi) {
                    MapRenderSettingsStore.update { it.copy(mapVignetteEnabled = isChecked) }
                }
            }
        }
        mapRouteDownloadSwitch = SwitchCompat(this).apply {
            text = getString(R.string.map_settings_route_download)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setOnCheckedChangeListener { _, isChecked ->
                if (!isSyncingUi) {
                    MapRenderSettingsStore.update { it.copy(downloadRouteEnabled = isChecked) }
                }
            }
        }
        mapRouteSnapSwitch = SwitchCompat(this).apply {
            text = getString(R.string.map_settings_route_snap)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setOnCheckedChangeListener { _, isChecked ->
                if (!isSyncingUi) {
                    MapRenderSettingsStore.update { it.copy(snapRouteToRoadsEnabled = isChecked) }
                    syncMapUiFromPrefs()
                }
            }
        }
        mapLocationSnapSwitch = SwitchCompat(this).apply {
            text = getString(R.string.map_settings_location_snap)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setOnCheckedChangeListener { _, isChecked ->
                if (!isSyncingUi) {
                    MapRenderSettingsStore.update { it.copy(snapLocationToRoadsEnabled = isChecked) }
                    syncMapUiFromPrefs()
                }
            }
        }

        val zoomSeek = SeekBar(this).apply {
            max = ((MAP_ZOOM_MAX - MAP_ZOOM_MIN) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MAP_ZOOM_MIN + (progress / 10.0)
                    mapZoomValue.text = formatMapDecimal(value)
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(zoom = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val autoZoomZeroSeek = SeekBar(this).apply {
            max = ((MAP_ZOOM_MAX - MAP_ZOOM_MIN) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MAP_ZOOM_MIN + (progress / 10.0)
                    mapAutoZoomZeroValue.text = formatMapDecimal(value)
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(autoZoomAt0Kmh = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val autoZoomSixtySeek = SeekBar(this).apply {
            max = ((MAP_ZOOM_MAX - MAP_ZOOM_MIN) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MAP_ZOOM_MIN + (progress / 10.0)
                    mapAutoZoomSixtyValue.text = formatMapDecimal(value)
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(autoZoomAt60Kmh = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val autoZoomNinetySeek = SeekBar(this).apply {
            max = ((MAP_ZOOM_MAX - MAP_ZOOM_MIN) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MAP_ZOOM_MIN + (progress / 10.0)
                    mapAutoZoomNinetyValue.text = formatMapDecimal(value)
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(autoZoomAt90Kmh = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val tiltSeek = SeekBar(this).apply {
            max = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress.toDouble()
                    mapTiltValue.text = getString(R.string.map_settings_tilt_value, value.roundToInt())
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(tilt = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val arrowSeek = SeekBar(this).apply {
            max = MAP_ARROW_SCALE_MAX_PERCENT - MAP_ARROW_SCALE_MIN_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MAP_ARROW_SCALE_MIN_PERCENT + progress
                    mapArrowValue.text = value.toString()
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(arrowScalePercent = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val routeSnapDistanceSeek = SeekBar(this).apply {
            max = MAP_ROUTE_SNAP_MAX_METERS - MAP_ROUTE_SNAP_MIN_METERS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MAP_ROUTE_SNAP_MIN_METERS + progress
                    mapRouteSnapDistanceValue.text = getString(R.string.map_settings_route_snap_distance_value, value)
                    if (fromUser && !isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(routeSnapDistanceMeters = value) }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        val cacheButtons = MapCacheSizeOptionsMb.mapIndexed { index, valueMb ->
            createSettingsButton(formatCacheStepLabel(valueMb), SETTINGS_BUTTON_SECONDARY).apply {
                text = formatCacheStepLabel(valueMb)
                setOnClickListener {
                    if (!isSyncingUi) {
                        MapRenderSettingsStore.update { it.copy(cacheSizeStep = index) }
                        syncMapUiFromPrefs()
                    }
                }
            }
        }
        mapCacheClearButton = createSettingsButton(
            getString(R.string.map_settings_cache_clear),
            SETTINGS_BUTTON_DANGER
        ).apply {
            setOnClickListener {
                MapCacheController.clearCache()
            }
        }
        mapTileProviderButtons = MapTileProvider.entries.associateWith { provider ->
            createSettingsButton(provider.displayName, SETTINGS_BUTTON_SECONDARY).apply {
                setOnClickListener {
                    if (isSyncingUi || provider.id == MapRenderSettingsStore.current().tileProviderId) {
                        return@setOnClickListener
                    }
                    confirmMapTileProviderChange(provider)
                }
            }
        }
        mapStyleModeButtons = MapStyleMode.entries.associateWith { mode ->
            createSettingsButton(
                getString(
                    when (mode) {
                        MapStyleMode.SYSTEM -> R.string.map_settings_style_system
                        MapStyleMode.USER -> R.string.map_settings_style_custom
                    }
                ),
                SETTINGS_BUTTON_SECONDARY
            ).apply {
                setOnClickListener {
                    if (isSyncingUi) {
                        return@setOnClickListener
                    }
                    handleMapStyleModeClick(mode)
                }
            }
        }
        mapCacheButtons = cacheButtons
        val cacheRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = cacheButtons.size.toFloat()
            cacheButtons.forEachIndexed { index, button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    }
                )
            }
        }
        val tileProviderRow = createMapButtonRow(
            MapTileProvider.entries.mapNotNull { provider -> mapTileProviderButtons[provider] }
        )
        val styleModeRow = createMapButtonRow(
            MapStyleMode.entries.mapNotNull { mode -> mapStyleModeButtons[mode] }
        )
        val mapDisplaySettingsButton = createSettingsButton(
            getString(R.string.map_settings_display_settings),
            SETTINGS_BUTTON_SECONDARY
        ).apply {
            setOnClickListener { showMapDisplaySettingsDialog() }
        }

        val offlineDownloadsButton = createSettingsButton(
            getString(R.string.map_settings_offline_downloads),
            SETTINGS_BUTTON_PRIMARY
        ).apply {
            setOnClickListener { showOfflineDownloadsDialog() }
        }

        val autoZoomRows = listOf(
            createMapSliderRow(getString(R.string.map_settings_auto_zoom_at_0), mapAutoZoomZeroValue, autoZoomZeroSeek),
            createMapSliderRow(getString(R.string.map_settings_auto_zoom_at_60), mapAutoZoomSixtyValue, autoZoomSixtySeek),
            createMapSliderRow(getString(R.string.map_settings_auto_zoom_at_90), mapAutoZoomNinetyValue, autoZoomNinetySeek),
        )
        mapAutoZoomConfigRows = autoZoomRows
        val routeSnapRows = listOf(
            createMapSliderRow(getString(R.string.map_settings_route_snap_distance), mapRouteSnapDistanceValue, routeSnapDistanceSeek),
        )
        mapRouteSnapConfigRows = routeSnapRows

        tabMapContent.removeAllViews()
        tabMapContent.addView(
            createMapSection(
                title = getString(R.string.map_settings_title),
                subtitle = getString(R.string.map_settings_subtitle),
                body = listOf(
                    createMapSliderRow(getString(R.string.map_settings_zoom), mapZoomValue, zoomSeek),
                    createMapSwitchRow(mapAutoZoomSwitch, getString(R.string.map_settings_auto_zoom_hint)),
                    *autoZoomRows.toTypedArray(),
                    createMapSliderRow(getString(R.string.map_settings_tilt), mapTiltValue, tiltSeek),
                    createMapSliderRow(getString(R.string.map_settings_arrow_scale), mapArrowValue, arrowSeek),
                    createMapValueRow(getString(R.string.map_settings_tile_source), mapTileProviderValue, tileProviderRow),
                    createMapHintView(getString(R.string.map_settings_tile_source_hint)),
                    createMapSwitchRow(mapVignetteSwitch, getString(R.string.map_settings_vignette_hint)),
                    createMapValueRow(getString(R.string.map_settings_style), mapStyleModeValue, styleModeRow),
                    mapStyleModeHint,
                    createMapButtonRow(listOf(mapDisplaySettingsButton)),
                    createMapSwitchRow(mapLocationSnapSwitch, getString(R.string.map_settings_location_snap_hint)),
                    *routeSnapRows.toTypedArray(),
                    createMapValueRow(getString(R.string.map_settings_cache), mapCacheValue, cacheRow),
                    createMapButtonRow(listOf(mapCacheClearButton)),
                    createMapButtonRow(listOf(offlineDownloadsButton)),
                )
            )
        )

        isSyncingUi = true
        try {
            zoomSeek.progress = ((settings.zoom - MAP_ZOOM_MIN) * 10).roundToInt()
                .coerceIn(0, zoomSeek.max)
            autoZoomZeroSeek.progress = ((settings.autoZoomAt0Kmh - MAP_ZOOM_MIN) * 10).roundToInt()
                .coerceIn(0, autoZoomZeroSeek.max)
            autoZoomSixtySeek.progress = ((settings.autoZoomAt60Kmh - MAP_ZOOM_MIN) * 10).roundToInt()
                .coerceIn(0, autoZoomSixtySeek.max)
            autoZoomNinetySeek.progress = ((settings.autoZoomAt90Kmh - MAP_ZOOM_MIN) * 10).roundToInt()
                .coerceIn(0, autoZoomNinetySeek.max)
            tiltSeek.progress = settings.tilt.roundToInt().coerceIn(0, 80)
            arrowSeek.progress = settings.arrowScalePercent
                .coerceIn(MAP_ARROW_SCALE_MIN_PERCENT, MAP_ARROW_SCALE_MAX_PERCENT) - MAP_ARROW_SCALE_MIN_PERCENT
            routeSnapDistanceSeek.progress = settings.routeSnapDistanceMeters
                .coerceIn(MAP_ROUTE_SNAP_MIN_METERS, MAP_ROUTE_SNAP_MAX_METERS) - MAP_ROUTE_SNAP_MIN_METERS
        } finally {
            isSyncingUi = false
        }
        syncMapUiFromPrefs()
    }

    private fun createMapSection(
        title: String,
        subtitle: String,
        body: List<View>,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_log_section)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(context).apply {
                text = title
                setTextColor(ContextCompat.getColor(context, R.color.white))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.parseColor("#808080"))
                textSize = 12f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            })
            body.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (index == 0) dp(16) else dp(12)
                })
            }
        }
    }

    private fun createMapSliderRow(label: String, valueView: TextView, seekBar: SeekBar): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueView)
            })
            addView(seekBar)
        }
    }

    private fun createMapValueRow(label: String, valueView: TextView, trailing: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueView)
            })
            addView(trailing, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun createMapSwitchRow(switch: SwitchCompat, hint: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(switch)
            addView(TextView(context).apply {
                text = hint
                setTextColor(Color.parseColor("#808080"))
                textSize = 12f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            })
        }
    }

    private fun createMapValueView(): TextView {
        return TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.white))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun syncMapUiFromPrefs() {
        val settings = MapRenderSettingsStore.current()
        mapZoomValue.text = formatMapDecimal(settings.zoom)
        mapAutoZoomZeroValue.text = formatMapDecimal(settings.autoZoomAt0Kmh)
        mapAutoZoomSixtyValue.text = formatMapDecimal(settings.autoZoomAt60Kmh)
        mapAutoZoomNinetyValue.text = formatMapDecimal(settings.autoZoomAt90Kmh)
        mapTiltValue.text = getString(R.string.map_settings_tilt_value, settings.tilt.roundToInt())
        mapArrowValue.text = settings.arrowScalePercent.toString()
        mapTileProviderValue.text = resolveConfiguredMapTileProvider(settings.tileProviderId).displayName
        val styleMode = settings.effectiveMapStyleMode()
        mapStyleModeValue.text = getString(
            if (styleMode == MapStyleMode.USER) {
                R.string.map_settings_style_custom
            } else {
                R.string.map_settings_style_system
            }
        )
        mapStyleModeHint.text = if (styleMode == MapStyleMode.USER) {
            getString(
                R.string.map_settings_style_custom_hint,
                settings.customStyleName ?: getString(R.string.map_settings_style_custom_default_name)
            )
        } else {
            getString(R.string.map_settings_style_system_hint)
        }
        mapRouteSnapDistanceValue.text = getString(
            R.string.map_settings_route_snap_distance_value,
            settings.routeSnapDistanceMeters
        )
        mapOfflineRegionValue.text = formatOfflineRegion(settings)
        mapAutoZoomSwitch.isChecked = settings.autoZoomEnabled
        mapVignetteSwitch.isChecked = settings.mapVignetteEnabled
        mapRouteDownloadSwitch.isChecked = settings.downloadRouteEnabled
        mapRouteSnapSwitch.isChecked = settings.snapRouteToRoadsEnabled
        mapLocationSnapSwitch.isChecked = settings.snapLocationToRoadsEnabled
        val autoZoomVisibility = if (settings.autoZoomEnabled) View.VISIBLE else View.GONE
        mapAutoZoomConfigRows.forEach { it.visibility = autoZoomVisibility }
        val routeSnapVisibility = if (
            settings.snapRouteToRoadsEnabled ||
            settings.snapLocationToRoadsEnabled
        ) View.VISIBLE else View.GONE
        mapRouteSnapConfigRows.forEach { it.visibility = routeSnapVisibility }
        refreshMapTileProviderButtons(settings.tileProviderId)
        refreshMapStyleModeButtons(styleMode)
        refreshMapCacheButtons(settings.cacheSizeStep)
        syncMapCacheUi(MapCacheController.current())
    }

    private fun refreshMapTileProviderButtons(selectedProviderId: String) {
        val selectedProvider = resolveConfiguredMapTileProvider(selectedProviderId)
        val clearingCache = MapCacheController.current().clearingCache
        mapTileProviderButtons.forEach { (provider, button) ->
            val configured = provider.isConfigured()
            styleSettingsButton(
                button = button,
                variant = if (provider == selectedProvider) {
                    SETTINGS_BUTTON_PRIMARY
                } else {
                    SETTINGS_BUTTON_SECONDARY
                }
            )
            button.isEnabled = configured && !clearingCache
            button.alpha = when {
                !configured -> 0.45f
                clearingCache -> 0.65f
                else -> 1f
            }
        }
    }

    private fun refreshMapStyleModeButtons(selectedMode: MapStyleMode) {
        mapStyleModeButtons.forEach { (mode, button) ->
            styleSettingsButton(
                button = button,
                variant = if (mode == selectedMode) {
                    SETTINGS_BUTTON_PRIMARY
                } else {
                    SETTINGS_BUTTON_SECONDARY
                }
            )
            button.alpha = 1f
            button.isEnabled = true
        }
    }

    private fun refreshMapCacheButtons(selectedStep: Int) {
        mapCacheButtons.forEachIndexed { index, button ->
            val selected = index == selectedStep
            styleSettingsButton(
                button = button,
                variant = if (selected) SETTINGS_BUTTON_PRIMARY else SETTINGS_BUTTON_SECONDARY
            )
        }
    }

    private fun formatMapCacheValue(snapshot: MapCacheSnapshot): String {
        val base = getString(
            R.string.map_settings_cache_value,
            formatStorageBytes(snapshot.usedBytes),
            formatStorageBytes(snapshot.configuredBytes)
        )
        val error = snapshot.cacheLastError?.takeIf { it.isNotBlank() } ?: return base
        return "$base\n${getString(R.string.map_settings_cache_error, error)}"
    }

    private fun formatMapDecimal(value: Double): String {
        return String.format(Locale.getDefault(), "%.1f", value)
    }

    private fun formatStorageBytes(bytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024L
        val gb = mb * 1024L
        return when {
            bytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", bytes.toDouble() / gb.toDouble())
            bytes >= mb -> String.format(Locale.getDefault(), "%.0f MB", bytes.toDouble() / mb.toDouble())
            bytes >= kb -> String.format(Locale.getDefault(), "%.0f KB", bytes.toDouble() / kb.toDouble())
            else -> "$bytes B"
        }
    }

    private fun formatCacheStepLabel(valueMb: Int): String {
        return if (valueMb >= 1024) "${valueMb / 1024}G" else "${valueMb}M"
    }

    private fun formatOfflineRegion(settings: MapRenderSettings): String {
        settings.manualOfflineBoundsOrNull()?.let { return "${it.label} · bbox" }
        val entry = OfflineRegionCatalog.findById(this, settings.offlineRegionId)
        return entry?.displayLabel ?: getString(R.string.map_settings_region_none)
    }

    private fun showOfflineRegionPicker() {
        val allEntries = OfflineRegionCatalog.all(this)
        if (allEntries.isEmpty()) {
            showToast(R.string.map_settings_region_empty)
            return
        }
        val regionAdapter = OfflineRegionAdapter(allEntries.toMutableList())
        var dialog: AlertDialog? = null
        val searchInput = EditText(this).apply {
            hint = getString(R.string.map_settings_region_search)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            textSize = 18f
        }
        val listView = ListView(this).apply {
            dividerHeight = 0
            adapter = regionAdapter
            setBackgroundColor(Color.BLACK)
            setOnItemClickListener { _, _, position, _ ->
                val item = regionAdapter.getItem(position)
                MapRenderSettingsStore.update {
                    it.copy(
                        offlineRegionId = item.id,
                        offlineManualLabel = null,
                        offlineManualLat1 = null,
                        offlineManualLon1 = null,
                        offlineManualLat2 = null,
                        offlineManualLon2 = null,
                    )
                }
                syncMapUiFromPrefs()
                dialog?.dismiss()
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                regionAdapter.replace(OfflineRegionCatalog.search(this@SettingsActivity, s?.toString().orEmpty()))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog = AlertDialog.Builder(this)
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.BLACK)
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                    addView(searchInput)
                    addView(
                        FrameLayout(context).apply {
                            addView(
                                listView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    dp(420)
                                )
                            )
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(16)
                        }
                    )
                }
            )
            .setNegativeButton(R.string.map_settings_region_close, null)
            .create()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private inner class OfflineRegionAdapter(
        private val items: MutableList<OfflineRegionEntry>,
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): OfflineRegionEntry = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        fun replace(entries: List<OfflineRegionEntry>) {
            items.clear()
            items.addAll(entries)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                addView(
                    TextView(context).apply {
                        id = android.R.id.text1
                        setTextColor(ContextCompat.getColor(context, R.color.white))
                        textSize = 16f
                    }
                )
                addView(
                    TextView(context).apply {
                        id = android.R.id.text2
                        setTextColor(Color.parseColor("#99FFFFFF"))
                        textSize = 12f
                    }
                )
            }
            val item = getItem(position)
            row.findViewById<TextView>(android.R.id.text1).text = item.name
            row.findViewById<TextView>(android.R.id.text2).text = item.secondaryLabel
            return row
        }
    }

    private inner class OfflineDownloadsAdapter(
        private val items: MutableList<OfflineRegionEntry>,
        private var snapshot: MapCacheSnapshot,
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): OfflineRegionEntry = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        fun replace(entries: List<OfflineRegionEntry>) {
            items.clear()
            items.addAll(entries)
            notifyDataSetChanged()
        }

        fun updateSnapshot(snapshot: MapCacheSnapshot) {
            this.snapshot = snapshot
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder = convertView?.tag as? OfflineDownloadRowViews
            val row = if (holder == null) {
                createOfflineDownloadRow()
            } else {
                convertView as LinearLayout
            }
            val views = row.tag as OfflineDownloadRowViews
            val item = getItem(position)
            bindOfflineDownloadRow(row, views, item, snapshot)
            return row
        }
    }

    private data class OfflineDownloadRowViews(
        val title: TextView,
        val subtitle: TextView,
        val button: Button,
        val progress: ProgressBar,
    )

    private inner class OfflineDownloadsRecommendationsSection(
        private val container: LinearLayout,
        private val entries: List<OfflineRegionEntry>,
        private var snapshot: MapCacheSnapshot,
    ) {
        private var requestedVisible = false

        fun updateSnapshot(updatedSnapshot: MapCacheSnapshot) {
            snapshot = updatedSnapshot
            syncVisibilityAndRender()
        }

        fun setVisible(visible: Boolean) {
            requestedVisible = visible
            syncVisibilityAndRender()
        }

        private fun syncVisibilityAndRender() {
            val items = buildRecommendedOfflineRegions(entries, snapshot)
            container.visibility = if (requestedVisible && items.isNotEmpty()) View.VISIBLE else View.GONE
            if (container.visibility == View.VISIBLE) {
                render(items)
            } else {
                container.removeAllViews()
            }
        }

        private fun render(items: List<OfflineRegionEntry>) {
            container.removeAllViews()
            if (items.isEmpty()) return
            container.addView(
                TextView(this@SettingsActivity).apply {
                    text = getString(R.string.map_settings_offline_recommended_title)
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            items.forEachIndexed { index, item ->
                container.addView(
                    createOfflineDownloadRow().also { row ->
                        val views = row.tag as OfflineDownloadRowViews
                        bindOfflineDownloadRow(row, views, item, snapshot)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = if (index == 0) dp(12) else dp(8)
                    }
                )
            }
        }
    }

    private fun createOfflineDownloadRow(): LinearLayout {
        val title = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.white))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 12f
        }
        val button = Button(this).apply {
            isAllCaps = false
            minHeight = dp(44)
            minWidth = dp(96)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = roundedDrawable(
                fill = Color.parseColor("#171717"),
                stroke = Color.parseColor("#2A2A2A"),
                radiusDp = 10
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(subtitle)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                FrameLayout(context).apply {
                    addView(
                        button,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    )
                    addView(
                        progress,
                        FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
                    )
                },
                LinearLayout.LayoutParams(dp(108), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            tag = OfflineDownloadRowViews(title, subtitle, button, progress)
        }
    }

    private fun bindOfflineDownloadRow(
        row: LinearLayout,
        views: OfflineDownloadRowViews,
        item: OfflineRegionEntry,
        snapshot: MapCacheSnapshot,
    ) {
        val downloaded = item.id in snapshot.offlineDownloadedRegionIds
        val isCurrent = snapshot.offlineDownloadedRegionId == item.id
        val anyProgress = snapshot.offlineDownloadStatus != OFFLINE_STATUS_IDLE
        val inProgress = isCurrent && snapshot.offlineDownloadStatus != OFFLINE_STATUS_IDLE
        views.title.text = item.name
        views.subtitle.text = offlineRegionDetailsText(item, snapshot, downloaded, isCurrent, inProgress)
        views.progress.visibility = if (inProgress) View.VISIBLE else View.GONE
        views.button.apply {
            visibility = if (inProgress) View.GONE else View.VISIBLE
            isEnabled = !anyProgress
            text = if (downloaded) {
                getString(R.string.map_settings_offline_delete_short)
            } else {
                getString(R.string.map_settings_offline_download_short)
            }
            styleSettingsButton(
                button = this,
                variant = if (downloaded) SETTINGS_BUTTON_DANGER else SETTINGS_BUTTON_PRIMARY
            )
            setOnClickListener {
                if (downloaded) {
                    MapCacheController.deleteOfflineRegion(item.id)
                } else {
                    MapCacheController.startOfflineRegionDownload(item)
                }
            }
        }
    }

    private fun offlineRegionDetailsText(
        item: OfflineRegionEntry,
        snapshot: MapCacheSnapshot,
        downloaded: Boolean,
        isCurrent: Boolean,
        inProgress: Boolean,
    ): String {
        val sourceText = when (item.source) {
            OfflineRegionSource.BUNDLED -> null
            OfflineRegionSource.ONLINE_ONLY -> getString(R.string.map_settings_offline_source_online_only)
        }
        val statusText = when {
            inProgress && snapshot.offlineDownloadStatus == OFFLINE_STATUS_RESOLVING -> {
                getString(R.string.map_settings_offline_row_preparing)
            }
            inProgress && snapshot.offlineDownloadStatus == OFFLINE_STATUS_DOWNLOADING -> {
                buildOfflineDownloadStatusText(snapshot)
            }
            isCurrent && !snapshot.offlineLastError.isNullOrBlank() -> {
                getString(R.string.map_settings_offline_row_error, snapshot.offlineLastError.orEmpty())
            }
            else -> null
        }
        val sizeText = if (downloaded) {
            snapshot.offlineDownloadedRegionSizesBytes[item.id]?.let { formatStorageBytes(it) }
                ?: getString(R.string.map_settings_offline_size_unknown)
        } else {
            null
        }
        return listOfNotNull(item.countryRu, sourceText, statusText, sizeText).joinToString(" · ")
    }

    private fun buildOfflineDownloadStatusText(snapshot: MapCacheSnapshot): String {
        return if (snapshot.offlineDownloadProgressPrecise) {
            getString(R.string.map_settings_offline_row_downloading_percent, snapshot.offlineDownloadPercent)
        } else {
            getString(R.string.map_settings_offline_row_packaging)
        }
    }

    private fun buildRecommendedOfflineRegions(
        entries: List<OfflineRegionEntry>,
        snapshot: MapCacheSnapshot,
    ): List<OfflineRegionEntry> {
        val anchor = resolveOfflineRecommendationsAnchorLocation()
        val prioritizedIds = LinkedHashSet<String>()
        val prioritizedEntries = mutableListOf<OfflineRegionEntry>()
        val activeRegionId = snapshot.offlineDownloadedRegionId
            ?.takeIf { snapshot.offlineDownloadStatus != OFFLINE_STATUS_IDLE }

        activeRegionId?.let { regionId ->
            entries.firstOrNull { it.id == regionId }?.let { entry ->
                prioritizedIds += entry.id
                prioritizedEntries += entry
            }
        }

        val downloadedEntries = entries
            .asSequence()
            .filter { it.id in snapshot.offlineDownloadedRegionIds && it.id !in prioritizedIds }
            .let { sequence ->
                if (anchor == null) {
                    sequence.sortedWith(
                        compareBy<OfflineRegionEntry>(
                            { it.countryRu },
                            { it.level.recommendationLevelRank() },
                            { it.name }
                        )
                    )
                } else {
                    sequence.sortedWith(
                        compareBy<OfflineRegionEntry>(
                            { !it.boundsPreview().contains(anchor) },
                            { it.boundsPreview().distanceMetersTo(anchor) },
                            { it.level.recommendationLevelRank() },
                            { it.name }
                        )
                    )
                }
            }
            .toList()
        downloadedEntries.forEach { entry ->
            prioritizedIds += entry.id
            prioritizedEntries += entry
        }

        if (anchor == null) {
            return prioritizedEntries
        }

        val contains = entries
            .asSequence()
            .filterNot { it.id in prioritizedIds }
            .filter { it.boundsPreview().contains(anchor) }
            .sortedWith(
                compareBy<OfflineRegionEntry>(
                    { it.level.recommendationLevelRank() },
                    { it.boundsPreview().areaApprox() }
                )
            )
            .toList()
        val containsIds = contains.asSequence().map { it.id }.toHashSet()
        val nearest = entries
            .asSequence()
            .filterNot { it.id in prioritizedIds || containsIds.contains(it.id) }
            .sortedBy { it.boundsPreview().distanceMetersTo(anchor) }
            .take(6)
            .toList()
        return buildList(prioritizedEntries.size + 6) {
            addAll(prioritizedEntries)
            addAll(contains.take(4))
            nearest.forEach { candidate ->
                if (none { it.id == candidate.id }) {
                    add(candidate)
                }
            }
        }
    }

    private fun resolveOfflineRecommendationsAnchorLocation(): Location? {
        latestKnownSettingsLocation()?.let { return it }
        return MapRouteTelemetryStore.current().startLocation
    }

    private fun latestKnownSettingsLocation(): Location? {
        if (!hasPreviewLocationPermission()) return null
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun createMapButtonRow(buttons: List<Button>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = buttons.size.toFloat()
            buttons.forEachIndexed { index, button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    }
                )
            }
        }
    }

    private fun createMapHintView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#808080"))
            textSize = 12f
        }
    }

    private fun createSettingsButton(
        label: String,
        variant: Int = SETTINGS_BUTTON_SECONDARY,
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(44)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            styleSettingsButton(this, variant)
        }
    }

    private fun styleSettingsButton(button: Button, variant: Int) {
        val fill = when (variant) {
            SETTINGS_BUTTON_PRIMARY -> ContextCompat.getColor(this, R.color.hud_blue)
            SETTINGS_BUTTON_DANGER -> Color.parseColor("#3A1515")
            else -> Color.parseColor("#222222")
        }
        val stroke = when (variant) {
            SETTINGS_BUTTON_PRIMARY -> Color.parseColor("#4EA3FF")
            SETTINGS_BUTTON_DANGER -> Color.parseColor("#FF4433")
            else -> Color.parseColor("#3A3A3A")
        }
        val textColor = when (variant) {
            SETTINGS_BUTTON_DANGER -> Color.parseColor("#FFE1E1")
            else -> ContextCompat.getColor(this, R.color.white)
        }
        button.background = roundedDrawable(fill = fill, stroke = stroke, radiusDp = 10)
        button.setTextColor(textColor)
    }

    private fun roundedDrawable(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun syncMapCacheUi(snapshot: MapCacheSnapshot) {
        if (::mapCacheValue.isInitialized) {
            mapCacheValue.text = formatMapCacheValue(snapshot)
        }
        if (::mapTileProviderValue.isInitialized) {
            refreshMapTileProviderButtons(MapRenderSettingsStore.current().tileProviderId)
        }
        if (::mapCacheClearButton.isInitialized) {
            mapCacheClearButton.isEnabled = !snapshot.clearingCache
            mapCacheClearButton.text = getString(
                if (snapshot.clearingCache) {
                    R.string.map_settings_cache_clearing
                } else {
                    R.string.map_settings_cache_clear
                }
            )
            styleSettingsButton(
                button = mapCacheClearButton,
                variant = if (snapshot.clearingCache) {
                    SETTINGS_BUTTON_SECONDARY
                } else {
                    SETTINGS_BUTTON_DANGER
                }
            )
        }
        if (::mapOfflineRegionValue.isInitialized) {
            mapOfflineRegionValue.text = getString(
                R.string.map_settings_offline_downloaded_count,
                snapshot.offlineDownloadedRegionIds.size
            )
        }
    }

    private fun handleMapStyleModeClick(mode: MapStyleMode) {
        when (mode) {
            MapStyleMode.SYSTEM -> confirmSystemMapStyleSelection()
            MapStyleMode.USER -> customMapStylePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }

    private fun importCustomMapStyle(uri: Uri) {
        val settings = MapRenderSettingsStore.current()
        val currentStyleJson = settings.customStyleJson
        val displayName = resolveDocumentDisplayName(uri)
            ?: getString(R.string.map_settings_style_custom_default_name)
        runCatching {
            val rawJson = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("не удалось прочитать файл")
            validateCustomMapStyleJson(rawJson)
        }.onSuccess { styleJson ->
            if (
                settings.effectiveMapStyleMode() == MapStyleMode.USER &&
                currentStyleJson == styleJson &&
                settings.customStyleName == displayName
            ) {
                return
            }
            MapRenderSettingsStore.update {
                it.copy(
                    styleModeId = MapStyleMode.USER.id,
                    customStyleName = displayName,
                    customStyleJson = styleJson,
                )
            }
            syncMapUiFromPrefs()
            showToast(getString(R.string.map_settings_style_custom_loaded, displayName))
        }.onFailure { error ->
            showToast(
                getString(
                    R.string.map_settings_style_custom_invalid,
                    error.message ?: getString(R.string.map_settings_style_custom_unknown_error)
                )
            )
        }
    }

    private fun resolveDocumentDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun confirmSystemMapStyleSelection() {
        val settings = MapRenderSettingsStore.current()
        if (settings.effectiveMapStyleMode() != MapStyleMode.USER) {
            return
        }
        showStyledConfirmationDialog(
            title = getString(R.string.map_settings_style_reset_title),
            message = getString(R.string.map_settings_style_reset_message),
            confirmLabel = getString(R.string.map_settings_style_reset_confirm),
            confirmVariant = SETTINGS_BUTTON_DANGER,
        ) {
            MapRenderSettingsStore.update {
                it.copy(
                    styleModeId = MapStyleMode.SYSTEM.id,
                    customStyleName = null,
                    customStyleJson = null,
                )
            }
            syncMapUiFromPrefs()
            showToast(R.string.map_settings_style_system_restored)
        }
    }

    private fun showMapDisplaySettingsDialog() {
        val settings = MapRenderSettingsStore.current()
        var dialog: AlertDialog? = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val buildingsCheckBox = CheckBox(this).apply {
            text = getString(R.string.map_settings_show_buildings)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.white))
            isChecked = settings.buildingsEnabled
        }
        val buildings3dCheckBox = CheckBox(this).apply {
            text = getString(R.string.map_settings_buildings_3d)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.white))
            isChecked = settings.buildings3dEnabled
        }
        fun syncBuildings3dState() {
            buildings3dCheckBox.isEnabled = buildingsCheckBox.isChecked
            buildings3dCheckBox.alpha = if (buildingsCheckBox.isChecked) 1f else 0.45f
        }
        buildingsCheckBox.setOnCheckedChangeListener { _, _ ->
            syncBuildings3dState()
        }
        syncBuildings3dState()
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(
                fill = Color.parseColor("#101010"),
                stroke = ContextCompat.getColor(this@SettingsActivity, R.color.hud_blue),
                radiusDp = 12
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        contentColumn.addView(TextView(this).apply {
            text = getString(R.string.map_settings_display_settings)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        contentColumn.addView(
            buildingsCheckBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )
        contentColumn.addView(
            buildings3dCheckBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
        val cancelButton = createSettingsButton(
            getString(android.R.string.cancel),
            SETTINGS_BUTTON_SECONDARY
        ).apply {
            minHeight = dp(36)
            minimumHeight = dp(36)
        }
        val confirmButton = createSettingsButton(
            getString(android.R.string.ok),
            SETTINGS_BUTTON_PRIMARY
        ).apply {
            minHeight = dp(36)
            minimumHeight = dp(36)
        }
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancelButton)
            addView(
                confirmButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(10)
                }
            )
        }
        contentColumn.addView(
            buttonsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        )
        root.addView(contentColumn)
        val container = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        cancelButton.setOnClickListener {
            dialog?.dismiss()
        }
        confirmButton.setOnClickListener {
            MapRenderSettingsStore.update {
                it.copy(
                    buildingsEnabled = buildingsCheckBox.isChecked,
                    buildings3dEnabled = buildings3dCheckBox.isChecked,
                )
            }
            syncMapUiFromPrefs()
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setView(container)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showStyledConfirmationDialog(
        title: String,
        message: String,
        confirmLabel: String,
        confirmVariant: Int = SETTINGS_BUTTON_PRIMARY,
        onConfirm: () -> Unit,
    ) {
        var dialog: AlertDialog? = null
        val cancelButton = createSettingsButton(
            getString(android.R.string.cancel),
            SETTINGS_BUTTON_SECONDARY
        ).apply {
            minHeight = dp(36)
            minimumHeight = dp(36)
            maxLines = 2
            isSingleLine = false
            gravity = Gravity.CENTER
            textSize = 13f
            includeFontPadding = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val confirmButton = createSettingsButton(confirmLabel, confirmVariant).apply {
            minHeight = dp(36)
            minimumHeight = dp(36)
            maxLines = 2
            isSingleLine = false
            gravity = Gravity.CENTER
            textSize = 13f
            includeFontPadding = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val buttonsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                confirmButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                cancelButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(14)
                }
            )
        }
        val content = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedDrawable(
                        fill = Color.parseColor("#101010"),
                        stroke = ContextCompat.getColor(this@SettingsActivity, R.color.hud_blue),
                        radiusDp = 12
                    )
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    addView(TextView(context).apply {
                        text = title
                        setTextColor(ContextCompat.getColor(context, R.color.white))
                        textSize = 20f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = message
                        setTextColor(Color.parseColor("#D0D0D0"))
                        textSize = 15f
                        setLineSpacing(0f, 1.15f)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(10)
                    })
                    addView(
                        buttonsColumn,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(18)
                        }
                    )
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setView(content)
            .create()
        cancelButton.setOnClickListener {
            dialog?.dismiss()
        }
        confirmButton.setOnClickListener {
            dialog?.dismiss()
            onConfirm()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun confirmMapTileProviderChange(provider: MapTileProvider) {
        if (!provider.isConfigured()) {
            showToast(R.string.map_settings_tile_source_unavailable)
            return
        }
        showStyledConfirmationDialog(
            title = getString(R.string.map_settings_tile_source_change_title),
            message = getString(R.string.map_settings_tile_source_change_message, provider.displayName),
            confirmLabel = getString(R.string.map_settings_tile_source_change_confirm),
        ) {
            MapRenderSettingsStore.update { it.copy(tileProviderId = provider.id) }
            syncMapUiFromPrefs()
            MapCacheController.clearCache()
            showToast(R.string.map_settings_tile_source_changed)
        }
    }

    private fun currentOfflineMaxZoom(): Double {
        val settings = MapRenderSettingsStore.current()
        return if (settings.autoZoomEnabled) {
            maxOf(settings.autoZoomAt0Kmh, settings.autoZoomAt60Kmh, settings.autoZoomAt90Kmh)
        } else {
            settings.zoom
        }.coerceIn(MAP_ZOOM_MIN, OFFLINE_REGION_MAX_ZOOM)
    }

    private fun showOfflineDownloadsDialog() {
        val allEntries = OfflineRegionCatalog.all(this)
        if (allEntries.isEmpty()) {
            showToast(R.string.map_settings_region_empty)
            return
        }
        val initialSnapshot = MapCacheController.current()
        val adapter = OfflineDownloadsAdapter(
            items = allEntries.toMutableList(),
            snapshot = initialSnapshot
        )
        offlineDownloadsAdapter = adapter
        val recommendedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        offlineDownloadsRecommendationsSection = OfflineDownloadsRecommendationsSection(
            container = recommendedContainer,
            entries = allEntries,
            snapshot = initialSnapshot,
        )
        val recommendedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                recommendedContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            )
        }
        val searchInput = EditText(this).apply {
            hint = getString(R.string.map_settings_region_search)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            background = roundedDrawable(
                fill = Color.parseColor("#111111"),
                stroke = ContextCompat.getColor(this@SettingsActivity, R.color.hud_blue),
                radiusDp = 10
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            textSize = 18f
        }
        val previewButton = createSettingsButton(
            getString(R.string.map_settings_cache_preview_button),
            SETTINGS_BUTTON_PRIMARY
        ).apply {
            setOnClickListener {
                showOfflineCachePreviewDialog()
            }
        }
        val closeButton = createSettingsButton(
            getString(R.string.map_settings_region_close),
            SETTINGS_BUTTON_SECONDARY
        )
        val listView = ListView(this).apply {
            dividerHeight = 0
            addHeaderView(recommendedHeader, null, false)
            setAdapter(adapter)
            setBackgroundColor(Color.parseColor("#101010"))
            cacheColorHint = Color.TRANSPARENT
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                offlineDownloadsRecommendationsSection?.setVisible(query.isBlank())
                adapter.replace(
                    if (query.isBlank()) {
                        OfflineRegionCatalog.all(this@SettingsActivity)
                    } else {
                        OfflineRegionCatalog.searchAllSources(this@SettingsActivity, query)
                    }
                )
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val dialogHeight = (resources.displayMetrics.heightPixels * 0.82f).roundToInt()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.map_settings_offline_downloads)
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedDrawable(
                        fill = Color.parseColor("#101010"),
                        stroke = ContextCompat.getColor(this@SettingsActivity, R.color.hud_blue),
                        radiusDp = 12
                    )
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                    minimumHeight = dialogHeight - dp(32)
                    addView(
                        LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                searchInput,
                                LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                )
                            )
                            addView(
                                previewButton,
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginStart = dp(12)
                                }
                            )
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(12)
                        }
                    )
                    addView(
                        listView,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        ).apply {
                            topMargin = dp(12)
                        }
                    )
                    addView(
                        createMapButtonRow(listOf(closeButton)),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(12)
                        }
                    )
                }
            )
            .create()
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (offlineDownloadsAdapter === adapter) {
                offlineDownloadsAdapter = null
            }
            offlineDownloadsRecommendationsSection = null
        }
        dialog.show()
        offlineDownloadsRecommendationsSection?.setVisible(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dialogHeight)
    }

    private fun showOfflineCachePreviewDialog() {
        offlineCachePreviewSession?.dismiss()
        OfflineCachePreviewSession().also { session ->
            offlineCachePreviewSession = session
            session.show()
        }
    }

    private inner class OfflineCachePreviewSession {
        private val connectivityReceiver = ConnectivityReceiver.instance(applicationContext)
        private val centerUserButton = createSettingsIconButton(
            R.drawable.my_location_24,
            SETTINGS_BUTTON_PRIMARY
        )
        private val closeButton = createSettingsButton(
            getString(R.string.map_settings_region_close),
            SETTINGS_BUTTON_SECONDARY
        )
        private val mapView = MapView(
            this@SettingsActivity,
            MapLibreMapOptions()
                .textureMode(true)
                .rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .zoomGesturesEnabled(true)
                .doubleTapGesturesEnabled(true)
                .quickZoomGesturesEnabled(true)
                .scrollGesturesEnabled(true)
                .horizontalScrollGesturesEnabled(true)
                .compassEnabled(false)
                .logoEnabled(false)
                .attributionEnabled(false)
        ).apply {
            setBackgroundColor(Color.BLACK)
        }
        private val locationUpdateListener = LocationListener { location ->
            applyPreviewLocation(location)
        }
        private val dialogHeight = (resources.displayMetrics.heightPixels * 0.82f).roundToInt()
        private val dialog = AlertDialog.Builder(this@SettingsActivity)
            .setTitle(R.string.map_settings_cache_preview_title)
            .setView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedDrawable(
                        fill = Color.parseColor("#101010"),
                        stroke = ContextCompat.getColor(this@SettingsActivity, R.color.hud_blue),
                        radiusDp = 12
                    )
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                    minimumHeight = dialogHeight - dp(32)
                    addView(
                        mapView,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                    addView(
                        LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                centerUserButton,
                                LinearLayout.LayoutParams(dp(52), dp(52))
                            )
                            addView(
                                closeButton,
                                LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                ).apply {
                                    marginStart = dp(12)
                                }
                            )
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(12)
                        }
                    )
                }
            )
            .create()
        private var mapStarted = false
        private var disposed = false
        private var previewMap: MapLibreMap? = null
        private var locationComponent: LocationComponent? = null
        private var locationManager: LocationManager? = null
        private var currentUserLocation: Location? = null

        fun show() {
            centerUserButton.setOnClickListener {
                centerOnUserLocation()
            }
            closeButton.setOnClickListener {
                dialog.dismiss()
            }
            dialog.setOnShowListener {
                if (mapStarted) return@setOnShowListener
                mapStarted = true
                connectivityReceiver.setConnected(false)
                mapView.onCreate(null)
                mapView.getMapAsync(::onMapReady)
                mapView.onStart()
                mapView.onResume()
            }
            dialog.setOnDismissListener {
                dispose()
            }
            dialog.show()
            updateCenterUserButtonState()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dialogHeight)
        }

        fun dismiss() {
            if (dialog.isShowing) {
                dialog.dismiss()
            } else {
                dispose()
            }
        }

        private fun onMapReady(map: MapLibreMap) {
            previewMap = map
            map.uiSettings.setAllGesturesEnabled(true)
            map.uiSettings.setRotateGesturesEnabled(false)
            map.uiSettings.setTiltGesturesEnabled(false)
            map.uiSettings.setCompassEnabled(false)
            map.uiSettings.setLogoEnabled(false)
            map.uiSettings.setAttributionEnabled(false)
            map.setPrefetchesTiles(false)
            map.setTileCacheEnabled(true)
            map.setStyle(buildOfflineCachePreviewMapStyle()) {
                enablePreviewLocationComponent(map, it)
                startPreviewLocationTracking()
                mapView.post {
                    if (!disposed) {
                        applyOfflineCachePreviewCamera(map)
                    }
                }
            }
        }

        private fun dispose() {
            if (disposed) return
            disposed = true
            if (offlineCachePreviewSession === this) {
                offlineCachePreviewSession = null
            }
            stopPreviewLocationTracking()
            previewMap = null
            if (mapStarted) {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
                (mapView.parent as? ViewGroup)?.removeView(mapView)
            }
            connectivityReceiver.setConnected(null)
        }

        private fun enablePreviewLocationComponent(map: MapLibreMap, style: org.maplibre.android.maps.Style) {
            if (!hasPreviewLocationPermission()) {
                updateCenterUserButtonState()
                return
            }
            val component = map.locationComponent
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(this@SettingsActivity, style)
                    .locationComponentOptions(buildPreviewLocationOptions())
                    .useDefaultLocationEngine(false)
                    .build()
            )
            component.isLocationComponentEnabled = true
            component.renderMode = RenderMode.GPS
            locationComponent = component
            previewLastKnownLocation()?.let(::applyPreviewLocation)
            updateCenterUserButtonState()
        }

        private fun startPreviewLocationTracking() {
            if (!hasPreviewLocationPermission()) {
                updateCenterUserButtonState()
                return
            }
            val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = manager
            previewLastKnownLocation(manager)?.let(::applyPreviewLocation)
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).forEach { provider ->
                val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(provider == LocationManager.PASSIVE_PROVIDER)
                if (!enabled) return@forEach
                runCatching {
                    manager.requestLocationUpdates(provider, 1000L, 0f, locationUpdateListener, Looper.getMainLooper())
                }
            }
            updateCenterUserButtonState()
        }

        private fun stopPreviewLocationTracking() {
            locationManager?.let { manager ->
                runCatching { manager.removeUpdates(locationUpdateListener) }
            }
            locationManager = null
            locationComponent = null
        }

        private fun applyPreviewLocation(location: Location) {
            currentUserLocation = Location(location)
            locationComponent?.forceLocationUpdate(location)
            updateCenterUserButtonState()
        }

        private fun centerOnUserLocation() {
            val map = previewMap ?: return
            val location = currentUserLocation
            if (location == null) {
                showToast(R.string.map_settings_cache_preview_no_location)
                updateCenterUserButtonState()
                return
            }
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude),
                    14.0
                )
            )
        }

        private fun updateCenterUserButtonState() {
            val enabled = currentUserLocation != null
            centerUserButton.isEnabled = enabled
            centerUserButton.alpha = if (enabled) 1f else 0.5f
        }

        private fun previewLastKnownLocation(manager: LocationManager? = locationManager): Location? {
            val locationManager = manager ?: return null
            return listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        }
    }

    private fun applyOfflineCachePreviewCamera(map: MapLibreMap) {
        resolveOfflineCachePreviewBounds()?.let { bounds ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 14.0))
            return
        }
        val startLocation = MapRouteTelemetryStore.current().startLocation
        if (startLocation != null) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(startLocation.latitude, startLocation.longitude),
                    14.0
                )
            )
            return
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 14.0))
    }

    private fun resolveOfflineCachePreviewBounds(): LatLngBounds? {
        val settings = MapRenderSettingsStore.current()
        settings.manualOfflineBoundsOrNull()?.boundsPreview()?.toLatLngBounds()?.let { return it }
        OfflineRegionCatalog.findById(this, settings.offlineRegionId)?.boundsPreview()?.toLatLngBounds()
            ?.let { return it }
        val routePoints = MapRouteTelemetryStore.current().routePoints
        if (routePoints.size < 2) return null
        val west = routePoints.minOf { it.longitude }
        val east = routePoints.maxOf { it.longitude }
        val south = routePoints.minOf { it.latitude }
        val north = routePoints.maxOf { it.latitude }
        if (west == east || south == north) return null
        return LatLngBounds.from(north, east, south, west)
    }

    private fun OfflineRegionBounds.toLatLngBounds(): LatLngBounds {
        return LatLngBounds.from(north, east, south, west)
    }

    private fun OfflineRegionBounds.contains(location: Location): Boolean {
        return location.longitude in west..east && location.latitude in south..north
    }

    private fun OfflineRegionBounds.distanceMetersTo(location: Location): Float {
        val clampedLat = location.latitude.coerceIn(south, north)
        val clampedLon = location.longitude.coerceIn(west, east)
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, clampedLat, clampedLon, result)
        return result[0]
    }

    private fun OfflineRegionBounds.areaApprox(): Double {
        return (east - west) * (north - south)
    }

    private fun String.recommendationLevelRank(): Int {
        return when (uppercase(Locale.US)) {
            "ADM3" -> 0
            "ADM2" -> 1
            "ADM1" -> 2
            else -> 3
        }
    }

    private fun hasPreviewLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildPreviewLocationOptions(): LocationComponentOptions {
        val arrowScale = (MapRenderSettingsStore.current().arrowScalePercent / 100f)
            .coerceIn(
                MAP_ARROW_SCALE_MIN_PERCENT / 100f,
                MAP_ARROW_SCALE_MAX_PERCENT / 100f
            )
        return LocationComponentOptions.builder(this)
            .gpsDrawable(R.drawable.ic_nav_arrow)
            .foregroundDrawable(R.drawable.ic_nav_arrow)
            .foregroundDrawableStale(R.drawable.ic_nav_arrow)
            .backgroundDrawable(R.drawable.ic_transparent_puck)
            .backgroundDrawableStale(R.drawable.ic_transparent_puck)
            .bearingDrawable(R.drawable.ic_transparent_puck)
            .maxZoomIconScale(arrowScale)
            .minZoomIconScale(arrowScale)
            .build()
    }

    private fun createSettingsIconButton(
        drawableRes: Int,
        variant: Int = SETTINGS_BUTTON_SECONDARY,
    ): AppCompatImageButton {
        return AppCompatImageButton(this).apply {
            setImageResource(drawableRes)
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = when (variant) {
                SETTINGS_BUTTON_PRIMARY -> roundedDrawable(
                    fill = ContextCompat.getColor(context, R.color.hud_blue),
                    stroke = Color.parseColor("#4EA3FF"),
                    radiusDp = 10
                )
                SETTINGS_BUTTON_DANGER -> roundedDrawable(
                    fill = Color.parseColor("#3A1515"),
                    stroke = Color.parseColor("#FF4433"),
                    radiusDp = 10
                )
                else -> roundedDrawable(
                    fill = Color.parseColor("#222222"),
                    stroke = Color.parseColor("#3A3A3A"),
                    radiusDp = 10
                )
            }
        }
    }

    private fun buildOfflineCachePreviewMapStyle(): org.maplibre.android.maps.Style.Builder {
        val templateJson = MapStyleTemplateStore.loadTemplateJson(this)
        val baseStyleJson = prepareHudMapStyleJson(
            templateJson = templateJson,
            provider = currentMapTileProvider(this),
            settings = MapRenderSettingsStore.current()
        )
        return org.maplibre.android.maps.Style.Builder()
            .fromJson(normalizeOfflineCachePreviewStyleJson(baseStyleJson))
    }

    private fun normalizeOfflineCachePreviewStyleJson(rawJson: String): String {
        val style = JSONObject(rawJson)
        val sources = style.optJSONObject("sources")
        if (sources != null) {
            val iterator = sources.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val source = sources.optJSONObject(key) ?: continue
                source.put("minzoom", 0)
                source.put("maxzoom", 24)
                sources.put(key, source)
            }
            style.put("sources", sources)
        }
        val layers = style.optJSONArray("layers")
        if (layers != null) {
            for (index in 0 until layers.length()) {
                val layer = layers.optJSONObject(index) ?: continue
                layer.put("minzoom", 0)
                layer.put("maxzoom", 24)
                layers.put(index, layer)
            }
            style.put("layers", layers)
        }
        return style.toString()
    }

    private fun exportSettingsToDownloads() {
        try {
            val fileName = generateExportFileName()
            val payload = PrefsJson.buildPayload(this)
            val json = payload.toString(2)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ использует MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    showToast(getString(R.string.settings_export_success, fileName))
                } else {
                    showToast(R.string.settings_export_failed)
                }
            } else {
                // Android 9 и ниже - прямое сохранение в Downloads
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                showToast(getString(R.string.settings_export_success, fileName))
            }
        } catch (e: Exception) {
            showToast(R.string.settings_export_failed)
        }
    }

    private fun generateExportFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        return "anhud_settings_$timestamp.json"
    }

    private fun needsStoragePermission(): Boolean {
        // Android 10+ не требует разрешения для MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return false
        }
        // Android 6-9 требует WRITE_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return hasForegroundLocationPermission()
    }

    private fun enableSpeedFromGps() {
        pendingSpeedFromGpsAfterBackgroundPermission = false
        OverlayPrefs.setSpeedFromGps(this, true)
        startService(Intent(this, SensorDataService::class.java))
    }

    private fun disableSpeedFromGpsForPermission(messageId: Int) {
        pendingSpeedFromGpsAfterBackgroundPermission = false
        OverlayPrefs.setSpeedFromGps(this, false)
        syncUiFromPrefs()
        showToast(messageId)
    }

    private fun requestBackgroundLocationPermissionForGpsSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            enableSpeedFromGps()
            return
        }
        pendingSpeedFromGpsAfterBackgroundPermission = true
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        showToast(R.string.speed_from_gps_background_permission_denied)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun exportSettings(uri: Uri) {
        try {
            val payload = PrefsJson.buildPayload(this)
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
            val applied = PrefsJson.applyPayload(this, payload)
            if (!applied) {
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
        intent.putExtra(
            OverlayBroadcasts.EXTRA_SPEEDOMETER_SHOW_UNIT_TEXT,
            OverlayPrefs.speedometerShowUnitText(this)
        )
        intent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED, OverlayPrefs.clockEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_MAX_ACTIVE, OverlayPrefs.trafficLightMaxActive(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED, OverlayPrefs.speedLimitAlertEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD, OverlayPrefs.speedLimitAlertThreshold(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ENABLED, OverlayPrefs.hudSpeedLimitEnabled(this))
        intent.putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ALERT_ENABLED, OverlayPrefs.hudSpeedLimitAlertEnabled(this))
        intent.putExtra(
            OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ALERT_THRESHOLD,
            OverlayPrefs.hudSpeedLimitAlertThreshold(this)
        )
        intent.putExtra(
            OverlayBroadcasts.EXTRA_INFO_MIRROR_STARSHEEP7,
            OverlayPrefs.infoMirrorStarsheep7Enabled(this)
        )
        sendBroadcast(intent)
    }

    private fun broadcastInfoMirrorStarsheep7(enabled: Boolean) {
        val intent = Intent(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
            .setPackage(packageName)
            .putExtra(OverlayBroadcasts.EXTRA_INFO_MIRROR_STARSHEEP7, enabled)
        sendBroadcast(intent)
    }

    private fun showToast(messageResId: Int) {
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("ANHUD ADB", text)
        clipboard.setPrimaryClip(clip)
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
            val key = mappingKey(name)
            val savedId = prefs.getString(key, null)
            val defaultId = sanitizeBasicIconId(defaults[name])
            val candidateId = sanitizeBasicIconId(savedId ?: defaultId)
            val resolvedId = if (indexById.containsKey(candidateId)) {
                candidateId
            } else {
                DEFAULT_BASIC_ICON_ID
            }
            val resolvedIndex = resolvedId?.let { indexById[it] } ?: defaultIndex
            spinner.setSelection(resolvedIndex)
            if (savedId != resolvedId) {
                prefs.edit().putString(key, resolvedId).apply()
            }
            spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
                val selected = basicIcons[position].id
                prefs.edit().putString(key, selected).apply()
            }
            container.addView(row)
        }
    }

    private fun setupUpdatesTab() {
        updateCheckButton.setOnClickListener {
            isCheckingUpdates = true
            setUpdateLoading(true)
            UpdateManager.checkForUpdates(this, force = true) { _, _ ->
                isCheckingUpdates = false
                setUpdateLoading(false)
                refreshUpdateUi()
            }
        }
        updateInstallButton.setOnClickListener {
            startUpdateDownload()
        }
        refreshUpdateUi()
    }

    private fun setupDebugTab() {
        cpuGraph.setLineColor(0xFFE57373.toInt())
        ramGraph.setLineColor(0xFF4DB6AC.toInt())

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
        PerformanceDebugMonitor.registerListener(performanceListener)
        renderPerformanceSnapshot(PerformanceDebugMonitor.currentSnapshot())
    }

    private fun renderPerformanceSnapshot(snapshot: PerformanceDebugMonitor.Snapshot) {
        val cpuValues = snapshot.samples.map { it.systemCpuPercent }
        val ramValues = snapshot.samples.map { it.ramUsedPercent }
        cpuGraph.setValues(cpuValues)
        ramGraph.setValues(ramValues)

        val latest = snapshot.samples.lastOrNull()
        if (latest == null) {
            cpuGraphSummary.text = getString(R.string.performance_waiting_data)
            ramGraphSummary.text = getString(R.string.performance_waiting_data)
            renderTopCpuRows(emptyList())
            topCpuNote.visibility = View.VISIBLE
            topCpuNote.text = getString(R.string.performance_waiting_data)
            return
        }

        cpuGraphSummary.text = getString(
            R.string.performance_cpu_summary,
            PerformanceDebugMonitor.formatPercent(latest.systemCpuPercent),
            PerformanceDebugMonitor.formatPercent(latest.appCpuPercent)
        ) + snapshot.systemCpuNote?.let { "\n$it" }.orEmpty()
        ramGraphSummary.text = getString(
            R.string.performance_ram_summary,
            PerformanceDebugMonitor.formatPercent(latest.ramUsedPercent),
            PerformanceDebugMonitor.formatMb(latest.ramUsedMb),
            PerformanceDebugMonitor.formatMb(latest.ramTotalMb),
            PerformanceDebugMonitor.formatMb(latest.appPssMb)
        )

        renderTopCpuRows(snapshot.topApps)
        if (snapshot.topAppsNote.isNullOrBlank()) {
            topCpuNote.visibility = View.GONE
        } else {
            topCpuNote.visibility = View.VISIBLE
            topCpuNote.text = snapshot.topAppsNote
        }
    }

    private fun renderTopCpuRows(topApps: List<PerformanceDebugMonitor.TopAppCpu>) {
        if (topCpuTable.childCount > 1) {
            topCpuTable.removeViews(1, topCpuTable.childCount - 1)
        }

        if (topApps.isEmpty()) {
            val row = TableRow(this)
            row.addView(makeTopCpuCell("—", 0.9f, Gravity.CENTER))
            row.addView(makeTopCpuCell("—", 1.1f, Gravity.END))
            row.addView(makeTopCpuCell("—", 1.0f, Gravity.END))
            row.addView(makeTopCpuCell(getString(R.string.performance_no_active_apps), 4.0f, Gravity.START))
            topCpuTable.addView(row)
            return
        }

        topApps.take(10).forEachIndexed { index, app ->
            val row = TableRow(this)
            row.addView(makeTopCpuCell((index + 1).toString(), 0.9f, Gravity.CENTER))
            row.addView(
                makeTopCpuCell(
                    PerformanceDebugMonitor.formatPercent(app.cpuPercent),
                    1.1f,
                    Gravity.END
                )
            )
            row.addView(makeTopCpuCell(app.pid.toString(), 1.0f, Gravity.END))
            row.addView(makeTopCpuCell(app.processName, 4.0f, Gravity.START))
            topCpuTable.addView(row)
        }
    }

    private fun makeTopCpuCell(text: String, weight: Float, gravity: Int): TextView {
        return TextView(this).apply {
            this.text = text
            this.gravity = gravity
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = dp(4)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
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
                val commandView = row.findViewById<TextView>(R.id.helpItemCommand)
                val copyButton = row.findViewById<View>(R.id.helpCopyCommandButton)
                val commandRes = item.copyCommandRes
                if (commandRes != null) {
                    val command = getString(commandRes)
                    commandView.text = command
                    commandView.visibility = View.VISIBLE
                    copyButton.visibility = View.VISIBLE
                    copyButton.setOnClickListener {
                        copyTextToClipboard(command)
                        showToast(R.string.help_copy_command_done)
                    }
                } else {
                    commandView.visibility = View.GONE
                    copyButton.visibility = View.GONE
                    copyButton.setOnClickListener(null)
                }
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

    private fun refreshUpdateUi() {
        val currentVersion = BuildConfig.VERSION_NAME
        val latestVersion = UpdatePrefs.getLatestVersion(this)
        val notes = UpdatePrefs.getReleaseNotes(this)
        val available = UpdatePrefs.isUpdateAvailable(this)
        val downloading = UpdatePrefs.getDownloadId(this) != -1L

        updateCurrentVersion.text = getString(R.string.update_current_version, currentVersion)
        updateLatestVersion.text = if (latestVersion.isNotBlank()) {
            getString(R.string.update_latest_version, latestVersion)
        } else {
            getString(R.string.update_latest_version_unknown)
        }
        updateReleaseNotes.text = if (notes.isNotBlank()) {
            notes
        } else {
            getString(R.string.update_no_release_notes)
        }

        updateStatusText.text = when {
            downloading -> getString(R.string.update_status_downloading)
            available && latestVersion.isNotBlank() -> getString(R.string.update_status_available, latestVersion)
            latestVersion.isNotBlank() -> getString(R.string.update_status_none)
            else -> getString(R.string.update_status_unknown)
        }

        val canInstall = available && UpdatePrefs.getApkUrl(this).isNotBlank() && !downloading && !isCheckingUpdates
        updateInstallButton.isEnabled = canInstall
        updateInstallButton.alpha = if (canInstall) 1f else 0.5f
        val canCheck = !downloading && !isCheckingUpdates
        updateCheckButton.isEnabled = canCheck
        updateCheckButton.alpha = if (canCheck) 1f else 0.5f
        if (downloading) {
            startProgressPolling(UpdatePrefs.getDownloadId(this))
        } else {
            stopProgressPolling()
            hideDownloadProgress()
        }

        setUpdatesBadgeVisible(available)
    }

    private fun setUpdateLoading(loading: Boolean) {
        if (loading) {
            updateProgressBar.isIndeterminate = true
            updateProgressBar.progress = 0
            updateProgressBar.visibility = View.VISIBLE
            updateProgressText.visibility = View.GONE
        } else if (!isDownloadInProgress()) {
            updateProgressBar.visibility = View.GONE
            updateProgressText.visibility = View.GONE
        }
        updateCheckButton.isEnabled = !loading
        updateCheckButton.alpha = if (!loading) 1f else 0.5f
        if (loading) {
            updateInstallButton.isEnabled = false
            updateInstallButton.alpha = 0.5f
        }
        if (loading) {
            updateStatusText.text = getString(R.string.update_status_checking)
        }
    }

    private fun setUpdatesBadgeVisible(visible: Boolean) {
        if (updatesTabIndex < 0) return
        val tab = tabLayout.getTabAt(updatesTabIndex) ?: return
        val badge = if (visible) tab.orCreateBadge else tab.badge
        badge?.setVisible(visible)
    }

    private fun startUpdateDownload() {
        val apkUrl = UpdatePrefs.getApkUrl(this)
        if (apkUrl.isBlank()) {
            showToast(R.string.update_download_missing)
            return
        }
        if (!ensureInstallPermission()) {
            return
        }
        val version = UpdatePrefs.getLatestVersion(this).ifBlank { BuildConfig.VERSION_NAME }
        val fileName = "ANHUD-$version.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(getString(R.string.update_download_title, version))
            .setDescription(getString(R.string.update_download_desc))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = getSystemService(DownloadManager::class.java)
        updateDownloadId = downloadManager.enqueue(request)
        UpdatePrefs.setDownloadId(this, updateDownloadId)
        updateCheckButton.isEnabled = false
        updateCheckButton.alpha = 0.5f
        updateInstallButton.isEnabled = false
        updateInstallButton.alpha = 0.5f
        startProgressPolling(updateDownloadId)
        updateStatusText.text = getString(R.string.update_status_downloading)
    }

    private fun handleDownloadComplete(downloadId: Long) {
        val downloadManager = getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        cursor.use {
            if (!it.moveToFirst()) {
                showToast(R.string.update_download_failed)
                refreshUpdateUi()
                return
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                showToast(R.string.update_download_failed)
                refreshUpdateUi()
                return
            }
            val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            if (localUri.isNullOrBlank()) {
                showToast(R.string.update_download_failed)
                refreshUpdateUi()
                return
            }
            installApk(localUri)
        }
        stopProgressPolling()
        refreshUpdateUi()
    }

    private fun installApk(localUri: String) {
        val fileUri = Uri.parse(localUri)
        val path = fileUri.path
        if (path.isNullOrBlank()) {
            showToast(R.string.update_install_failed)
            return
        }
        val apkFile = File(path)
        if (!apkFile.exists()) {
            showToast(R.string.update_install_failed)
            return
        }
        val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            showToast(R.string.update_install_failed)
        }
    }

    private fun startProgressPolling(downloadId: Long) {
        if (downloadId <= 0L) {
            return
        }
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val info = queryDownload(downloadId)
                if (info == null) {
                    hideDownloadProgress()
                    return
                }
                when (info.status) {
                    DownloadManager.STATUS_FAILED -> {
                        hideDownloadProgress()
                        refreshUpdateUi()
                        return
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        hideDownloadProgress()
                        return
                    }
                    else -> {
                        updateDownloadProgress(info.downloaded, info.total)
                    }
                }
                progressHandler.postDelayed(this, 1000L)
            }
        }
        progressRunnable = runnable
        progressHandler.post(runnable)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private data class DownloadInfo(
        val status: Int,
        val downloaded: Long,
        val total: Long
    )

    private fun queryDownload(downloadId: Long): DownloadInfo? {
        val downloadManager = getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return DownloadInfo(status, downloaded, total)
        }
    }

    private fun updateDownloadProgress(downloaded: Long, total: Long) {
        updateProgressBar.visibility = View.VISIBLE
        updateProgressText.visibility = View.VISIBLE
        if (total > 0) {
            updateProgressBar.isIndeterminate = false
            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
            updateProgressBar.progress = percent
            updateProgressText.text = "${formatBytes(downloaded)} / ${formatBytes(total)} ($percent%)"
        } else {
            updateProgressBar.isIndeterminate = true
            updateProgressText.text = formatBytes(downloaded)
        }
    }

    private fun hideDownloadProgress() {
        updateProgressBar.visibility = View.GONE
        updateProgressText.visibility = View.GONE
    }

    private fun isDownloadInProgress(): Boolean = UpdatePrefs.getDownloadId(this) != -1L

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.getDefault(), "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.getDefault(), "%.2f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        if (packageManager.canRequestPackageInstalls()) {
            return true
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
        showToast(R.string.update_install_permission)
        return false
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
            navUpdatesEndTimeoutInput.setText(OverlayPrefs.navUpdatesEndTimeout(this).toString())
            speedometerFreezeTimeoutInput.setText(OverlayPrefs.speedometerFreezeTimeout(this).toString())
            val correction = OverlayPrefs.speedCorrection(this)
            speedCorrectionSeek.progress = correction + 10
            speedCorrectionValue.text = getString(R.string.speed_correction_value, correction)
            speedFromGpsCheck.isChecked = OverlayPrefs.speedFromGps(this)
            infoMirrorStarsheep7Switch.isChecked = OverlayPrefs.infoMirrorStarsheep7Enabled(this)
            val hideTurnWhenFarEnabled = OverlayPrefs.hideTurnWhenFarEnabled(this)
            hideTurnWhenFarSwitch.isChecked = hideTurnWhenFarEnabled
            val hideDistance = OverlayPrefs.hideTurnWhenFarDistanceMeters(this)
            hideTurnWhenFarDistanceSeek.progress = OverlayPrefs.hideTurnWhenFarDistanceProgress(this)
            hideTurnWhenFarDistanceValue.text = formatManeuverHideDistance(hideDistance)
            val hideTurnDynamicEnabled = OverlayPrefs.hideTurnDynamicEnabled(this)
            hideTurnDynamicSwitch.isChecked = hideTurnDynamicEnabled
            updateHideTurnSwitchLabel(hideDistance)
            updateHideTurnDistanceControls(hideTurnWhenFarEnabled)
            updateHideTurnDynamicControls(hideTurnWhenFarEnabled, hideTurnDynamicEnabled)
            syncMapUiFromPrefs()
        } finally {
            isSyncingUi = false
        }
    }

    private fun updateTimeoutSettingsSection() {
        timeoutSettingsContent.visibility = if (areTimeoutSettingsExpanded) View.VISIBLE else View.GONE
        timeoutSettingsToggleLabel.setText(
            if (areTimeoutSettingsExpanded) {
                R.string.settings_section_collapse
            } else {
                R.string.settings_section_expand
            }
        )
    }

    private fun updateHideTurnDistanceControls(enabled: Boolean) {
        hideTurnWhenFarDistanceSeek.isEnabled = enabled
        hideTurnWhenFarDistanceSeek.alpha = if (enabled) 1f else 0.5f
        hideTurnWhenFarDistanceValue.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateHideTurnDynamicControls(hideTurnEnabled: Boolean, dynamicEnabled: Boolean) {
        hideTurnDynamicSwitch.isEnabled = hideTurnEnabled
        hideTurnDynamicSwitch.alpha = if (hideTurnEnabled) 1f else 0.5f
        hideTurnDynamicConfigButton.isEnabled = hideTurnEnabled && dynamicEnabled
        hideTurnDynamicConfigButton.alpha = if (hideTurnEnabled && dynamicEnabled) 1f else 0.5f
    }

    private fun showHideTurnDynamicSettingsDialog() {
        val current = OverlayPrefs.hideTurnDynamicDistances(this)
        var dialog: AlertDialog? = null
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(
                fill = Color.parseColor("#101010"),
                stroke = ContextCompat.getColor(this@SettingsActivity, R.color.hud_blue),
                radiusDp = 12
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        contentColumn.addView(TextView(this).apply {
            text = getString(R.string.hide_turn_dynamic_dialog_title)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        contentColumn.addView(TextView(this).apply {
            text = getString(R.string.hide_turn_dynamic_dialog_hint)
            setTextColor(Color.parseColor("#B3B3B3"))
            textSize = 13f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })

        val table = TableLayout(this).apply {
            isStretchAllColumns = true
            isShrinkAllColumns = true
        }
        val inputs = linkedMapOf<String, EditText>()

        fun createCellText(text: String, bold: Boolean = false): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.white))
                textSize = 14f
                if (bold) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
        }

        fun createCellInput(initialValue: Int): EditText {
            return EditText(this).apply {
                setText(initialValue.toString())
                setTextColor(ContextCompat.getColor(context, R.color.white))
                setHintTextColor(Color.parseColor("#808080"))
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.hud_blue)
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }

        table.addView(TableRow(this).apply {
            addView(createCellText(getString(R.string.hide_turn_dynamic_speed_column), true))
            addView(createCellText(getString(R.string.hide_turn_dynamic_distance_column), true))
        })

        val rows = listOf(
            Triple("upTo40", getString(R.string.hide_turn_dynamic_speed_up_to_40), current.upTo40Kmh),
            Triple("from40To60", getString(R.string.hide_turn_dynamic_speed_40_to_60), current.from40To60Kmh),
            Triple("from60To100", getString(R.string.hide_turn_dynamic_speed_60_to_100), current.from60To100Kmh),
            Triple("from100", getString(R.string.hide_turn_dynamic_speed_100_plus), current.from100Kmh),
        )
        rows.forEach { (key, label, value) ->
            val input = createCellInput(value)
            inputs[key] = input
            table.addView(TableRow(this).apply {
                addView(createCellText(label))
                addView(input)
            })
        }

        contentColumn.addView(table, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
        })

        val cancelButton = createSettingsButton(
            getString(android.R.string.cancel),
            SETTINGS_BUTTON_SECONDARY
        ).apply {
            minHeight = dp(36)
            minimumHeight = dp(36)
        }
        val confirmButton = createSettingsButton(
            getString(android.R.string.ok),
            SETTINGS_BUTTON_PRIMARY
        ).apply {
            minHeight = dp(36)
            minimumHeight = dp(36)
        }
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancelButton)
            addView(
                confirmButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(10)
                }
            )
        }
        contentColumn.addView(buttonsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(18)
        })

        val container = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                contentColumn,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        cancelButton.setOnClickListener { dialog?.dismiss() }
        confirmButton.setOnClickListener {
            val parsed = inputs.mapValues { (_, input) ->
                input.text.toString().trim().toIntOrNull()
            }
            val values = parsed.values
            if (values.any { it == null }) {
                showToast(R.string.hide_turn_dynamic_validation_invalid_number)
                return@setOnClickListener
            }
            val distances = OverlayPrefs.DynamicHideTurnDistances(
                upTo40Kmh = parsed.getValue("upTo40")!!,
                from40To60Kmh = parsed.getValue("from40To60")!!,
                from60To100Kmh = parsed.getValue("from60To100")!!,
                from100Kmh = parsed.getValue("from100")!!,
            )
            val invalid = listOf(
                distances.upTo40Kmh,
                distances.from40To60Kmh,
                distances.from60To100Kmh,
                distances.from100Kmh,
            ).any {
                it !in OverlayPrefs.HIDE_TURN_DYNAMIC_DISTANCE_MIN_METERS..
                    OverlayPrefs.HIDE_TURN_DYNAMIC_DISTANCE_MAX_METERS
            }
            if (invalid) {
                showToast(
                    getString(
                        R.string.hide_turn_dynamic_validation_range,
                        OverlayPrefs.HIDE_TURN_DYNAMIC_DISTANCE_MIN_METERS,
                        OverlayPrefs.HIDE_TURN_DYNAMIC_DISTANCE_MAX_METERS
                    )
                )
                return@setOnClickListener
            }
            OverlayPrefs.setHideTurnDynamicDistances(this, distances)
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setView(container)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun formatManeuverHideDistance(distanceMeters: Int): String {
        return if (distanceMeters < 1000) {
            getString(R.string.hide_turn_when_far_distance_meters_value, distanceMeters)
        } else {
            getString(R.string.hide_turn_when_far_distance_km_value, distanceMeters / 1000)
        }
    }

    private fun updateHideTurnSwitchLabel(distanceMeters: Int) {
        val distanceLabel = formatManeuverHideDistance(distanceMeters)
        hideTurnWhenFarSwitch.text = getString(
            R.string.hide_turn_when_far_enabled_label_value,
            distanceLabel
        )
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
            ?.mapNotNull { file ->
                if (!file.endsWith(".png") && !file.endsWith(".webp")) {
                    return@mapNotNull null
                }
                val id = file.substringBefore(".").toIntOrNull() ?: return@mapNotNull null
                if (id > MAX_BASIC_ICON_INDEX) {
                    return@mapNotNull null
                }
                id to file
            }
            .orEmpty()
            .sortedBy { it.first }
        val icons = ArrayList<BasicIcon>(files.size)
        for ((id, file) in files) {
            val bitmap = assets.open("basicIcons/$file").use {
                BitmapFactory.decodeStream(it)
            } ?: continue
            icons.add(BasicIcon(id.toString(), file, bitmap))
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
                defaults[key] = sanitizeBasicIconId(value)
            }
        }
        return defaults
    }

    private fun sanitizeBasicIconId(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val numeric = value.toIntOrNull() ?: return DEFAULT_BASIC_ICON_ID
        return if (numeric > MAX_BASIC_ICON_INDEX) {
            DEFAULT_BASIC_ICON_ID
        } else {
            numeric.toString()
        }
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
        private const val STATE_TIMEOUTS_EXPANDED = "state_timeouts_expanded"
        private const val DEFAULT_BASIC_ICON_ID = "101"
        private const val MAX_BASIC_ICON_INDEX = 150
        private const val MANEUVER_PREFS_NAME = "maneuver_match_prefs"
        private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"
    }
}
