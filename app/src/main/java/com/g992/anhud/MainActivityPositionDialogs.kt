package com.g992.anhud

import android.graphics.PointF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class OverlayTarget(val previewKey: String) {
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

internal fun MainActivity.openPositionDialog(
    target: OverlayTarget,
    onDialogShown: ((AlertDialog, View) -> Unit)? = null,
    onDialogDismissed: (() -> Unit)? = null
) {
    val activity = this
    updateDisplayMetrics(OverlayPrefs.displayId(this))
    val dialogView = layoutInflater.inflate(R.layout.dialog_position_editor, null)
    val previewContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewContainer)
    val previewHudContainer = dialogView.findViewById<FrameLayout>(R.id.dialogPreviewHudContainer)
    val previewNavBlock = dialogView.findViewById<View>(R.id.dialogPreviewNavBlock)
    val previewNavTextColumn = dialogView.findViewById<LinearLayout>(R.id.dialogPreviewNavTextColumn)
    val previewNavPrimary = dialogView.findViewById<TextView>(R.id.dialogPreviewNavPrimary)
    val previewNavSecondary = dialogView.findViewById<TextView>(R.id.dialogPreviewNavSecondary)
    val previewNavTime = dialogView.findViewById<TextView>(R.id.dialogPreviewNavTime)
    val previewArrowBlock = dialogView.findViewById<View>(R.id.dialogPreviewArrowBlock)
    val previewSpeedLimit = dialogView.findViewById<TextView>(R.id.dialogPreviewSpeedLimit)
    val previewHudSpeedBlock = dialogView.findViewById<View>(R.id.dialogPreviewHudSpeedBlock)
    val previewHudSpeedFull = dialogView.findViewById<View>(R.id.dialogPreviewHudSpeedFull)
    val previewHudSpeedCompact = dialogView.findViewById<View>(R.id.dialogPreviewHudSpeedCompact)
    val previewRoadCameraBlock = dialogView.findViewById<View>(R.id.dialogPreviewRoadCameraBlock)
    val previewTrafficLightBlock = dialogView.findViewById<LinearLayout>(R.id.dialogPreviewTrafficLightBlock)
    val previewSpeedometer = dialogView.findViewById<TextView>(R.id.dialogPreviewSpeedometer)
    val previewClock = dialogView.findViewById<TextView>(R.id.dialogPreviewClock)
    val showOthersCheck = dialogView.findViewById<CheckBox>(R.id.dialogShowOthers)
    val hudSpeedGpsStatusCheck = dialogView.findViewById<CheckBox>(R.id.dialogHudSpeedShowGpsStatus)
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
    val navWidthLabel = dialogView.findViewById<TextView>(R.id.dialogNavWidthLabel)
    val navWidthRow = dialogView.findViewById<View>(R.id.dialogNavWidthRow)
    val navWidthSeek = dialogView.findViewById<SeekBar>(R.id.dialogNavWidthSeek)
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
    val navPoint = PointF(navPosition.x, navPosition.y)
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
    var navWidthDp = OverlayPrefs.navWidthDp(this)
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

    val showHudSpeedGpsStatusSetting = target == OverlayTarget.HUDSPEED
    hudSpeedGpsStatusCheck.visibility = if (showHudSpeedGpsStatusSetting) View.VISIBLE else View.GONE
    hudSpeedGpsStatusCheck.isChecked = OverlayPrefs.hudSpeedGpsStatusEnabled(activity)

    if (target == OverlayTarget.CONTAINER) {
        scaleLabel.visibility = View.GONE
        scaleSeek.visibility = View.GONE
        scaleValue.visibility = View.GONE
        navTextScaleLabel.visibility = View.GONE
        navTextScaleRow.visibility = View.GONE
        navWidthLabel.visibility = View.GONE
        navWidthRow.visibility = View.GONE
    } else {
        containerWidthLabel.visibility = View.GONE
        containerWidthRow.visibility = View.GONE
        containerHeightLabel.visibility = View.GONE
        containerHeightRow.visibility = View.GONE
    }

    if (target != OverlayTarget.NAVIGATION) {
        navWidthLabel.visibility = View.GONE
        navWidthRow.visibility = View.GONE
    } else {
        navWidthLabel.visibility = View.VISIBLE
        navWidthRow.visibility = View.VISIBLE
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
                    OverlayPrefs.setNavTextScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        navTextScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                } else {
                    OverlayPrefs.setSpeedTextScale(activity, scale)
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

    fun updateDialogVisibility() {
        if (target == OverlayTarget.NAVIGATION) {
            navWidthDp = navWidthDp.coerceIn(OverlayPrefs.NAV_WIDTH_MIN_DP, containerWidthDp)
        }
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
        val showHudSpeedLimit = OverlayPrefs.hudSpeedLimitEnabled(activity)
        previewHudSpeedFull.visibility = if (showHudSpeedLimit) View.VISIBLE else View.GONE
        previewHudSpeedCompact.visibility = if (showHudSpeedLimit) View.GONE else View.VISIBLE
        previewRoadCameraBlock.visibility = if (showRoadCamera) View.VISIBLE else View.GONE
        previewTrafficLightBlock.visibility = if (showTrafficLight) View.VISIBLE else View.GONE
        previewSpeedometer.visibility = if (showSpeedometer) View.VISIBLE else View.GONE
        previewClock.visibility = if (showClock) View.VISIBLE else View.GONE
        if (target == OverlayTarget.CONTAINER) {
            previewHudContainer.background = ContextCompat.getDrawable(activity, R.drawable.bg_hud_container_outline)
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
            val navWidthPx = navWidthDp * density
            val iconSizePx = (48 * density).roundToInt()
            val iconMarginPx = (8 * density).roundToInt()
            val textColumnWidthPx = (navWidthPx.roundToInt() - iconSizePx - iconMarginPx).coerceAtLeast(0)

            previewNavBlock.layoutParams = (previewNavBlock.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = navWidthPx.roundToInt()
            } ?: FrameLayout.LayoutParams(navWidthPx.roundToInt(), FrameLayout.LayoutParams.WRAP_CONTENT)

            previewNavTextColumn.layoutParams = (previewNavTextColumn.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = textColumnWidthPx
            } ?: LinearLayout.LayoutParams(textColumnWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)

            if (target == OverlayTarget.NAVIGATION) {
                previewNavBlock.background = ContextCompat.getDrawable(activity, R.drawable.bg_nav_block_outline)
            } else {
                previewNavBlock.background = null
            }
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
                OverlayPrefs.navAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.arrowAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.speedAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.hudSpeedAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.roadCameraAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.trafficLightAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.speedometerAlpha(activity).coerceIn(0f, 1f)
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
                OverlayPrefs.clockAlpha(activity).coerceIn(0f, 1f)
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

    if (target == OverlayTarget.NAVIGATION) {
        val minNavWidthDp = OverlayPrefs.NAV_WIDTH_MIN_DP
        val maxNavWidthDp = containerWidthDp.coerceAtLeast(minNavWidthDp)
        val minNavWidthInt = minNavWidthDp.roundToInt()
        val maxNavWidthInt = maxNavWidthDp.roundToInt()
        navWidthSeek.max = (maxNavWidthInt - minNavWidthInt).coerceAtLeast(0)
        navWidthSeek.progress = (navWidthDp.roundToInt() - minNavWidthInt)
            .coerceIn(0, navWidthSeek.max)
        navWidthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val widthInt = (progress + minNavWidthInt).coerceIn(minNavWidthInt, maxNavWidthInt)
                navWidthDp = widthInt.toFloat()
                updateDialogVisibility()
                notifyOverlaySettingsChanged(
                    navWidthDp = navWidthDp,
                    preview = true,
                    previewTarget = target,
                    previewShowOthers = showOthersCheck.isChecked
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val widthInt = ((seekBar?.progress ?: 0) + minNavWidthInt)
                    .coerceIn(minNavWidthInt, maxNavWidthInt)
                navWidthDp = widthInt.toFloat()
                OverlayPrefs.setNavWidthDp(activity, navWidthDp)
                updateDialogVisibility()
                notifyOverlaySettingsChanged(
                    navWidthDp = navWidthDp,
                    preview = true,
                    previewTarget = target,
                    previewShowOthers = showOthersCheck.isChecked
                )
            }
        })
    }

    clampContainerSize()
    containerWidthSeek.max = (maxWidthInt - minSizeInt).coerceAtLeast(0)
    containerHeightSeek.max = (maxHeightInt - minSizeInt).coerceAtLeast(0)
    containerWidthSeek.progress = (containerWidthDp.roundToInt() - minSizeInt)
        .coerceIn(0, containerWidthSeek.max)
    containerHeightSeek.progress = (containerHeightDp.roundToInt() - minSizeInt)
        .coerceIn(0, containerHeightSeek.max)


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
        lockX = false
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
            OverlayPrefs.setContainerSizeDp(activity, containerWidthDp, containerHeightDp)
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
            OverlayPrefs.setContainerSizeDp(activity, containerWidthDp, containerHeightDp)
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
                    OverlayPrefs.setNavScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        navScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.ARROW -> {
                    OverlayPrefs.setArrowScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        arrowScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.SPEED -> {
                    OverlayPrefs.setSpeedScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        speedScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.HUDSPEED -> {
                    OverlayPrefs.setHudSpeedScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        hudSpeedScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.ROAD_CAMERA -> {
                    OverlayPrefs.setRoadCameraScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        roadCameraScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.TRAFFIC_LIGHT -> {
                    OverlayPrefs.setTrafficLightScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        trafficLightScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.SPEEDOMETER -> {
                    OverlayPrefs.setSpeedometerScale(activity, scale)
                    notifyOverlaySettingsChanged(
                        speedometerScale = scale,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.CLOCK -> {
                    OverlayPrefs.setClockScale(activity, scale)
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
                    OverlayPrefs.setNavAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        navAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.ARROW -> {
                    OverlayPrefs.setArrowAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        arrowAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.SPEED -> {
                    OverlayPrefs.setSpeedAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        speedAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.HUDSPEED -> {
                    OverlayPrefs.setHudSpeedAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        hudSpeedAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.ROAD_CAMERA -> {
                    OverlayPrefs.setRoadCameraAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        roadCameraAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.TRAFFIC_LIGHT -> {
                    OverlayPrefs.setTrafficLightAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        trafficLightAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.SPEEDOMETER -> {
                    OverlayPrefs.setSpeedometerAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        speedometerAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.CLOCK -> {
                    OverlayPrefs.setClockAlpha(activity, alpha)
                    notifyOverlaySettingsChanged(
                        clockAlpha = alpha,
                        preview = true,
                        previewTarget = target,
                        previewShowOthers = showOthersCheck.isChecked
                    )
                }
                OverlayTarget.CONTAINER -> {
                    OverlayPrefs.setContainerAlpha(activity, alpha)
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

    hudSpeedGpsStatusCheck.setOnCheckedChangeListener { _, isChecked ->
        OverlayPrefs.setHudSpeedGpsStatusEnabled(activity, isChecked)
        notifyOverlaySettingsChanged(preview = true, previewTarget = target, previewShowOthers = showOthersCheck.isChecked)
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

private fun MainActivity.setupDialogDrag(
    container: FrameLayout,
    view: View,
    lockX: Boolean = false,
    scrollParent: ViewGroup? = null,
    onDrag: (Float, Float, Boolean) -> Unit
) {
    var dragOffsetX = 0f
    var dragOffsetY = 0f

    view.setOnTouchListener { v, event ->
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Запрещаем ScrollView перехватывать вертикальные движения
                scrollParent?.requestDisallowInterceptTouchEvent(true)
                v.parent?.requestDisallowInterceptTouchEvent(true)

                dragOffsetX = event.rawX - (containerLocation[0] + v.x)
                dragOffsetY = event.rawY - (containerLocation[1] + v.y)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                scrollParent?.requestDisallowInterceptTouchEvent(true)
                v.parent?.requestDisallowInterceptTouchEvent(true)

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
                scrollParent?.requestDisallowInterceptTouchEvent(false)
                v.parent?.requestDisallowInterceptTouchEvent(false)

                onDrag(v.x, v.y, true)
                v.performClick()
                true
            }

            else -> false
        }
    }
}

private fun MainActivity.positionPreviewView(
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

private fun MainActivity.positionDpFromPreview(
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

private fun MainActivity.maxPreviewX(container: FrameLayout, view: View): Float {
    return (container.width - view.width).toFloat().coerceAtLeast(0f)
}

private fun MainActivity.maxPreviewY(container: FrameLayout, view: View): Float {
    return (container.height - view.height).toFloat().coerceAtLeast(0f)
}

private fun MainActivity.updatePreviewContainerSize(
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

private fun MainActivity.updatePreviewContainerAlpha(container: FrameLayout, alphaOverride: Float? = null) {
    val background = container.background ?: return
    val rawAlpha = alphaOverride ?: OverlayPrefs.containerAlpha(this)
    val effectiveAlpha = max(rawAlpha, MainActivity.CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA)
    val alphaValue = (effectiveAlpha * 255)
        .roundToInt()
        .coerceIn(0, 255)
    background.alpha = alphaValue
}
