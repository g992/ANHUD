package com.g992.anhud

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
        turnSignalsProjectionSwitch.isChecked = OverlayPrefs.turnSignalsEnabled(this)
        clockProjectionSwitch.isChecked = OverlayPrefs.clockEnabled(this)
        speedometerShowUnitTextCheck.isChecked = OverlayPrefs.speedometerShowUnitText(this)
        updateSpeedometerCardPreviewText(speedometerShowUnitTextCheck.isChecked)
        updateTurnSignalsIconPreview()
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

internal fun MainActivity.updateTurnSignalsIconPreview() {
    val styleId = OverlayPrefs.turnSignalsIconStyle(this)
    turnSignalsIconValue.text = TurnSignalIcons.summary(this, styleId)
    TurnSignalIcons.applyPair(this, turnSignalsCardPreviewLeft, turnSignalsCardPreviewRight, styleId)
}

internal fun MainActivity.showTurnSignalIconDialog() {
    val density = resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).roundToInt()

    val currentStyleId = OverlayPrefs.turnSignalsIconStyle(this)
    lateinit var dialog: AlertDialog
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), 0)
    }

    TurnSignalIcons.all().forEachIndexed { index, style ->
        val isSelected = style.id == currentStyleId
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@showTurnSignalIconDialog, R.drawable.rounded_menu_item_ripple)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index < TurnSignalIcons.all().lastIndex) {
                    bottomMargin = dp(8)
                }
            }
            isClickable = true
            isFocusable = true
        }

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val left = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val right = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        TurnSignalIcons.applyPair(this, left, right, style.id)
        preview.addView(left)
        preview.addView(right)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        val title = TextView(this).apply {
            text = TurnSignalIcons.label(this@showTurnSignalIconDialog, style.id)
            setTextColor(Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
        }
        val subtitle = TextView(this).apply {
            text = if (isSelected) {
                getString(R.string.turn_signal_icon_selected)
            } else {
                getString(R.string.turn_signal_icon_dialog_hint)
            }
            setTextColor(Color.parseColor(if (isSelected) "#B3FFFFFF" else "#80FFFFFF"))
            textSize = 12f
        }
        textColumn.addView(title)
        textColumn.addView(subtitle)

        row.addView(preview)
        row.addView(textColumn)
        content.addView(row)

        row.setOnClickListener {
            OverlayPrefs.setTurnSignalsIconStyle(this, style.id)
            updateTurnSignalsIconPreview()
            notifyOverlaySettingsChanged(turnSignalsIconStyle = style.id)
            dialog.dismiss()
        }
    }

    val customRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(this@showTurnSignalIconDialog, R.drawable.rounded_menu_item_ripple)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
        isClickable = true
        isFocusable = true
    }
    val customPreview = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val customLeft = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginEnd = dp(8)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val customRight = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    TurnSignalIcons.applyPair(this, customLeft, customRight, TurnSignalIcons.CUSTOM_STYLE_ID)
    customPreview.addView(customLeft)
    customPreview.addView(customRight)

    val customTextColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        }
    }
    val customTitle = TextView(this).apply {
        text = getString(R.string.turn_signal_icon_option_value_custom)
        setTextColor(Color.WHITE)
        textSize = 16f
        paint.isFakeBoldText = true
    }
    val customSubtitle = TextView(this).apply {
        val isSelected = currentStyleId == TurnSignalIcons.CUSTOM_STYLE_ID
        text = if (isSelected) {
            getString(R.string.turn_signal_icon_selected_custom, TurnSignalIcons.summary(this@showTurnSignalIconDialog, TurnSignalIcons.CUSTOM_STYLE_ID))
        } else {
            TurnSignalIcons.summary(this@showTurnSignalIconDialog, TurnSignalIcons.CUSTOM_STYLE_ID)
        }
        setTextColor(Color.parseColor(if (isSelected) "#B3FFFFFF" else "#80FFFFFF"))
        textSize = 12f
    }
    customTextColumn.addView(customTitle)
    customTextColumn.addView(customSubtitle)
    customRow.addView(customPreview)
    customRow.addView(customTextColumn)
    content.addView(customRow)

    customRow.setOnClickListener {
        dialog.dismiss()
        showTurnSignalCustomIconDialog()
    }

    dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
        .setTitle(R.string.turn_signal_icon_dialog_title)
        .setView(ScrollView(this).apply { addView(content) })
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    dialog.show()
}

internal fun MainActivity.showTurnSignalCustomIconDialog() {
    val density = resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).roundToInt()

    var pendingIcon = OverlayPrefs.turnSignalsCustomIcon(this)
    lateinit var dialog: AlertDialog

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val fileLabel = TextView(this).apply {
        text = getString(R.string.turn_signal_icon_custom_file_label)
        setTextColor(Color.WHITE)
        textSize = 14f
        paint.isFakeBoldText = true
    }
    val fileValue = TextView(this).apply {
        setTextColor(Color.parseColor("#B3FFFFFF"))
        textSize = 13f
    }
    val chooseFileButton = TextView(this).apply {
        text = getString(R.string.turn_signal_icon_custom_choose_file)
        setTextColor(Color.WHITE)
        textSize = 14f
        background = ContextCompat.getDrawable(this@showTurnSignalCustomIconDialog, R.drawable.rounded_menu_item_ripple)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        isFocusable = true
    }
    val hintText = TextView(this).apply {
        text = getString(R.string.turn_signal_icon_custom_hint)
        setTextColor(Color.parseColor("#B3FFFFFF"))
        textSize = 12f
    }
    val directionLabel = TextView(this).apply {
        text = getString(R.string.turn_signal_icon_custom_direction_label)
        setTextColor(Color.WHITE)
        textSize = 14f
        paint.isFakeBoldText = true
    }
    val directionGroup = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
    }
    val leftDirection = RadioButton(this).apply {
        id = View.generateViewId()
        text = getString(R.string.turn_signal_icon_direction_left)
        setTextColor(Color.WHITE)
    }
    val rightDirection = RadioButton(this).apply {
        id = View.generateViewId()
        text = getString(R.string.turn_signal_icon_direction_right)
        setTextColor(Color.WHITE)
    }
    directionGroup.addView(leftDirection)
    directionGroup.addView(rightDirection)

    val previewLabel = TextView(this).apply {
        text = getString(R.string.turn_signal_icon_custom_preview_label)
        setTextColor(Color.WHITE)
        textSize = 14f
        paint.isFakeBoldText = true
    }
    val previewRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, 0)
    }
    val previewLeft = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginEnd = dp(8)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val previewRight = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    previewRow.addView(previewLeft)
    previewRow.addView(previewRight)

    val actionsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.END
    }
    val clearButton = Button(this).apply {
        text = getString(R.string.turn_signal_icon_custom_clear)
        visibility = if (OverlayPrefs.turnSignalsCustomIcon(this@showTurnSignalCustomIconDialog) != null) View.VISIBLE else View.GONE
    }
    val saveButton = Button(this).apply {
        text = getString(R.string.turn_signal_icon_custom_save)
    }
    actionsRow.addView(clearButton, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = 0
    })
    actionsRow.addView(saveButton, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = dp(8)
    })

    fun selectedDirection(): TurnSignalBaseDirection {
        return if (directionGroup.checkedRadioButtonId == rightDirection.id) {
            TurnSignalBaseDirection.RIGHT
        } else {
            TurnSignalBaseDirection.LEFT
        }
    }

    fun updateCustomPreview() {
        fileValue.text = pendingIcon?.displayName ?: getString(R.string.turn_signal_icon_custom_missing)
        val applied = TurnSignalCustomIconLoader.applyPair(
            context = this,
            left = previewLeft,
            right = previewRight,
            icon = pendingIcon?.copy(baseDirection = selectedDirection())
        )
        if (!applied) {
            previewLeft.setImageDrawable(null)
            previewRight.setImageDrawable(null)
            previewLeft.scaleX = 1f
            previewRight.scaleX = 1f
        }
    }

    if (pendingIcon?.baseDirection == TurnSignalBaseDirection.RIGHT) {
        directionGroup.check(rightDirection.id)
    } else {
        directionGroup.check(leftDirection.id)
    }
    updateCustomPreview()

    content.addView(fileLabel)
    content.addView(fileValue)
    content.addView(chooseFileButton, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(8)
        bottomMargin = dp(16)
    })
    content.addView(hintText, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(16)
    })
    content.addView(directionLabel)
    content.addView(directionGroup)
    content.addView(previewLabel, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(8)
    })
    content.addView(previewRow)
    content.addView(actionsRow, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(20)
        bottomMargin = dp(8)
    })

    chooseFileButton.setOnClickListener {
        pickTurnSignalCustomIcon { uri ->
            if (uri == null) {
                return@pickTurnSignalCustomIcon
            }
            if (!TurnSignalCustomIconLoader.canLoad(this, uri)) {
                Toast.makeText(this, R.string.turn_signal_icon_custom_invalid_file, Toast.LENGTH_SHORT).show()
                return@pickTurnSignalCustomIcon
            }
            pendingIcon = TurnSignalCustomIcon(
                uriString = uri.toString(),
                displayName = TurnSignalCustomIconLoader.resolveDisplayName(this, uri),
                baseDirection = selectedDirection()
            )
            updateCustomPreview()
        }
    }

    directionGroup.setOnCheckedChangeListener { _, _ ->
        pendingIcon = pendingIcon?.copy(baseDirection = selectedDirection())
        updateCustomPreview()
    }

    val scrollView = ScrollView(this).apply {
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(content)
    }

    dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
        .setTitle(R.string.turn_signal_icon_custom_dialog_title)
        .setView(scrollView)
        .create()

    clearButton.setOnClickListener {
        TurnSignalCustomIconLoader.clearStoredIcon(this)
        OverlayPrefs.clearTurnSignalsCustomIcon(this)
        if (OverlayPrefs.turnSignalsIconStyle(this) == TurnSignalIcons.CUSTOM_STYLE_ID) {
            OverlayPrefs.setTurnSignalsIconStyle(this, OverlayPrefs.TURN_SIGNALS_ICON_STYLE_DEFAULT)
            updateTurnSignalsIconPreview()
            notifyOverlaySettingsChanged(turnSignalsIconStyle = OverlayPrefs.TURN_SIGNALS_ICON_STYLE_DEFAULT)
        }
        dialog.dismiss()
    }
    saveButton.setOnClickListener {
        val iconToSave = pendingIcon?.copy(baseDirection = selectedDirection())
        if (iconToSave == null) {
            Toast.makeText(this, R.string.turn_signal_icon_custom_pick_file_first, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val storedIcon = TurnSignalCustomIconLoader.storeInAppStorage(
            context = this,
            sourceUri = iconToSave.uri,
            displayName = iconToSave.displayName,
            baseDirection = iconToSave.baseDirection
        )
        if (storedIcon == null) {
            Toast.makeText(this, R.string.turn_signal_icon_custom_save_failed, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        OverlayPrefs.setTurnSignalsCustomIcon(
            context = this,
            uriString = storedIcon.uriString,
            displayName = storedIcon.displayName,
            baseDirection = storedIcon.baseDirection
        )
        OverlayPrefs.setTurnSignalsIconStyle(this, TurnSignalIcons.CUSTOM_STYLE_ID)
        updateTurnSignalsIconPreview()
        notifyOverlaySettingsChanged(turnSignalsIconStyle = TurnSignalIcons.CUSTOM_STYLE_ID)
        dialog.dismiss()
    }

    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.88f).roundToInt(),
        WindowManager.LayoutParams.WRAP_CONTENT
    )
}
