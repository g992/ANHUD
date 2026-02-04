package com.g992.anhud

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

internal fun MainActivity.updatePermissionStatus() {
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
    AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
        .setView(dialogView)
        .setPositiveButton(R.string.donate_dialog_close, null)
        .show()
}
