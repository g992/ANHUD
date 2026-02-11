package com.g992.anhud

import android.Manifest
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import kotlin.math.roundToInt

class SettingsActivity : ScaledActivity() {
    private lateinit var tabLayout: TabLayout
    private lateinit var tabGeneralContent: View
    private lateinit var tabManeuverContent: View
    private lateinit var tabUpdatesContent: View
    private lateinit var tabDebugContent: View
    private lateinit var tabHelpContent: View
    private lateinit var cameraTimeoutNearInput: EditText
    private lateinit var cameraTimeoutFarInput: EditText
    private lateinit var trafficLightTimeoutInput: EditText
    private lateinit var roadCameraTimeoutInput: EditText
    private lateinit var navNotificationEndTimeoutInput: EditText
    private lateinit var navUpdatesEndTimeoutInput: EditText
    private lateinit var speedCorrectionSeek: SeekBar
    private lateinit var speedCorrectionValue: TextView
    private lateinit var speedFromGpsCheck: SwitchCompat
    private lateinit var hideTurnWhenFarSwitch: SwitchCompat
    private lateinit var hideTurnWhenFarDistanceSeek: SeekBar
    private lateinit var hideTurnWhenFarDistanceValue: TextView
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

    private var updateDownloadId: Long = -1L
    private var updatesTabIndex: Int = -1
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var isCheckingUpdates = false

    private var isSyncingUi = false

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
            OverlayPrefs.setSpeedFromGps(this, false)
            syncUiFromPrefs()
            showToast(R.string.speed_from_gps_permission_denied)
            return@registerForActivityResult
        }
        OverlayPrefs.setSpeedFromGps(this, true)
        startService(Intent(this, SensorDataService::class.java))
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
        tabManeuverContent = findViewById(R.id.tabManeuverContent)
        tabUpdatesContent = findViewById(R.id.tabUpdatesContent)
        tabDebugContent = findViewById(R.id.tabDebugContent)
        tabHelpContent = findViewById(R.id.tabHelpContent)
        cameraTimeoutNearInput = findViewById(R.id.cameraTimeoutNearInput)
        cameraTimeoutFarInput = findViewById(R.id.cameraTimeoutFarInput)
        trafficLightTimeoutInput = findViewById(R.id.trafficLightTimeoutInput)
        roadCameraTimeoutInput = findViewById(R.id.roadCameraTimeoutInput)
        navNotificationEndTimeoutInput = findViewById(R.id.navNotificationEndTimeoutInput)
        navUpdatesEndTimeoutInput = findViewById(R.id.navUpdatesEndTimeoutInput)
        speedCorrectionSeek = findViewById(R.id.speedCorrectionSeek)
        speedCorrectionValue = findViewById(R.id.speedCorrectionValue)
        speedFromGpsCheck = findViewById(R.id.speedFromGpsCheck)
        hideTurnWhenFarSwitch = findViewById(R.id.hideTurnWhenFarSwitch)
        hideTurnWhenFarDistanceSeek = findViewById(R.id.hideTurnWhenFarDistanceSeek)
        hideTurnWhenFarDistanceValue = findViewById(R.id.hideTurnWhenFarDistanceValue)
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

        setupTabs()
        setupGeneralSettings()
        setupManeuverTab()
        setupUpdatesTab()
        setupDebugTab()
        setupHelpTab()
        syncUiFromPrefs()
    }

    override fun onDestroy() {
        UiLogStore.unregisterListener(logListener)
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
        refreshUpdateUi()
    }

    override fun onStop() {
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
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_nav_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_updates))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_debug))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_help))

        updatesTabIndex = 2
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
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    1 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.VISIBLE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    2 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.VISIBLE
                        tabDebugContent.visibility = View.GONE
                        tabHelpContent.visibility = View.GONE
                    }
                    3 -> {
                        tabGeneralContent.visibility = View.GONE
                        tabManeuverContent.visibility = View.GONE
                        tabUpdatesContent.visibility = View.GONE
                        tabDebugContent.visibility = View.VISIBLE
                        tabHelpContent.visibility = View.GONE
                    }
                    4 -> {
                        tabGeneralContent.visibility = View.GONE
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
            OverlayPrefs.setSpeedFromGps(this, isChecked)
            startService(Intent(this, SensorDataService::class.java))
        }

        hideTurnWhenFarSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            OverlayPrefs.setHideTurnWhenFarEnabled(this, isChecked)
            updateHideTurnDistanceControls(isChecked)
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
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
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
            val correction = OverlayPrefs.speedCorrection(this)
            speedCorrectionSeek.progress = correction + 10
            speedCorrectionValue.text = getString(R.string.speed_correction_value, correction)
            speedFromGpsCheck.isChecked = OverlayPrefs.speedFromGps(this)
            val hideTurnWhenFarEnabled = OverlayPrefs.hideTurnWhenFarEnabled(this)
            hideTurnWhenFarSwitch.isChecked = hideTurnWhenFarEnabled
            val hideDistance = OverlayPrefs.hideTurnWhenFarDistanceMeters(this)
            hideTurnWhenFarDistanceSeek.progress = OverlayPrefs.hideTurnWhenFarDistanceProgress(this)
            hideTurnWhenFarDistanceValue.text = formatManeuverHideDistance(hideDistance)
            updateHideTurnSwitchLabel(hideDistance)
            updateHideTurnDistanceControls(hideTurnWhenFarEnabled)
        } finally {
            isSyncingUi = false
        }
    }

    private fun updateHideTurnDistanceControls(enabled: Boolean) {
        hideTurnWhenFarDistanceSeek.isEnabled = enabled
        hideTurnWhenFarDistanceSeek.alpha = if (enabled) 1f else 0.5f
        hideTurnWhenFarDistanceValue.alpha = if (enabled) 1f else 0.5f
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
        private const val DEFAULT_BASIC_ICON_ID = "101"
        private const val MAX_BASIC_ICON_INDEX = 150
        private const val MANEUVER_PREFS_NAME = "maneuver_match_prefs"
        private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"
    }
}
