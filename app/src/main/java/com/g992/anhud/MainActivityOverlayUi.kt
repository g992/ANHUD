package com.g992.anhud

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal fun MainActivity.updatePermissionStatus() {
    val overlayGranted = Settings.canDrawOverlays(this)
    permissionStatus.text = if (overlayGranted) {
        getString(R.string.overlay_permission_granted)
    } else {
        getString(R.string.overlay_permission_missing)
    }
    val overlayVisibility = if (overlayGranted) View.GONE else View.VISIBLE
    permissionStatus.visibility = overlayVisibility
    requestPermissionButton.visibility = overlayVisibility

    val notificationMissing = !isNotificationAccessGranted(this)
    val notificationVisibility = if (notificationMissing) View.VISIBLE else View.GONE
    notificationPermissionStatus.text = if (notificationMissing) {
        getString(R.string.notification_permission_missing)
    } else {
        getString(R.string.notification_permission_granted)
    }
    notificationPermissionStatus.visibility = notificationVisibility
    requestNotificationPermissionButton.visibility = notificationVisibility

    val storageMissing = isStoragePermissionMissing(this)
    val storageVisibility = if (storageMissing) View.VISIBLE else View.GONE
    storagePermissionStatus.text = getString(R.string.storage_permission_missing)
    storagePermissionStatus.visibility = storageVisibility
    requestStoragePermissionButton.visibility = storageVisibility

    val installMissing = isInstallPermissionMissing(this)
    val installVisibility = if (installMissing) View.VISIBLE else View.GONE
    installPermissionStatus.text = getString(R.string.install_permission_missing)
    installPermissionStatus.visibility = installVisibility
    requestInstallPermissionButton.visibility = installVisibility
}

internal fun MainActivity.syncUiFromPrefs() {
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

        val hudSpeedLimitEnabled = OverlayPrefs.hudSpeedLimitEnabled(this)
        val hudSpeedLimitAlertEnabled = OverlayPrefs.hudSpeedLimitAlertEnabled(this)
        val hudSpeedLimitAlertThreshold = OverlayPrefs.hudSpeedLimitAlertThreshold(this)
        hudSpeedLimitCheck.isChecked = hudSpeedLimitEnabled
        updateHudSpeedPreviewLayout(hudSpeedLimitEnabled)
        hudSpeedLimitAlertCheck.isChecked = hudSpeedLimitAlertEnabled
        hudSpeedLimitAlertThresholdRow.visibility =
            if (hudSpeedLimitAlertEnabled) View.VISIBLE else View.GONE
        hudSpeedLimitAlertThresholdSeek.progress = hudSpeedLimitAlertThreshold
        hudSpeedLimitAlertThresholdValue.text = getString(
            R.string.speed_limit_alert_threshold_value,
            hudSpeedLimitAlertThreshold
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

internal fun MainActivity.openOverlaySettings() {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.parse("package:$packageName")
    }
    startActivity(intent)
}

internal fun MainActivity.openNotificationListenerSettings() {
    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

internal fun MainActivity.openUnknownSourcesSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:$packageName")
    }
    startActivity(intent)
}

internal fun MainActivity.openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val packageUri = Uri.parse("package:$packageName")
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = packageUri
    }
    runCatching { startActivity(intent) }
        .onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
}

private const val NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"

private fun isNotificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        NOTIFICATION_LISTENERS_SETTING
    ).orEmpty()
    if (enabled.isBlank()) return false
    val component = ComponentName(context, NavigationNotificationListener::class.java)
    return enabled.split(':')
        .mapNotNull { ComponentName.unflattenFromString(it) }
        .any { it == component }
}

private fun isStoragePermissionMissing(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return !Environment.isExternalStorageManager()
    }
    val permission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
}

private fun isInstallPermissionMissing(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return false
    }
    return !context.packageManager.canRequestPackageInstalls()
}

internal fun MainActivity.renderTrafficLightPreview(container: LinearLayout, count: Int) {
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

internal fun MainActivity.showDonateDialog() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_donate, null)
    val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
        .setView(dialogView)
        .setPositiveButton(R.string.donate_dialog_close, null)
        .create()
    dialog.setOnShowListener {
        val width = (resources.displayMetrics.widthPixels * 0.8f).roundToInt()
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}
