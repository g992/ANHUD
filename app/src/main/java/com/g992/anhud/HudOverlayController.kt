package com.g992.anhud

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class HudOverlayController(private val context: Context) {
    companion object {
        private const val CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA = 0.35f
        private const val CLOCK_TICK_MS = 5_000L
        private const val SPEED_LIMIT_TEXT_SIZE_SP = 16.8f
        private const val SPEED_LIMIT_ALERT_TEXT_SIZE_SP = 15.5f
        private const val NAV_PRIMARY_TEXT_SIZE_SP = 18f
        private const val NAV_SECONDARY_TEXT_SIZE_SP = 14f
        private const val NAV_TIME_TEXT_SIZE_SP = 12f
        private const val ROAD_CAMERA_TEXT_SIZE_SP = 14f
        private const val NAV_TEXT_SCALE_MIN = 1f
        private const val NAV_TEXT_SCALE_MAX = 3f
        private const val PREVIEW_HUDSPEED_CAM_TYPE = 1
        private const val HUDSPEED_HIDE_DISTANCE_METERS = 50
        private const val HUDSPEED_HIDE_TIMEOUT_MS = 3_000L
    }
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var navContainer: LinearLayout? = null
    private var maneuverContainer: FrameLayout? = null
    private var maneuverView: ImageView? = null
    private var maneuverLabel: TextView? = null
    private var arrowContainer: FrameLayout? = null
    private var arrowView: ImageView? = null
    private var arrowLabel: TextView? = null
    private var primaryView: TextView? = null
    private var secondaryView: TextView? = null
    private var timeView: TextView? = null
    private var speedLimitView: OutlinedTextView? = null
    private var speedometerView: TextView? = null
    private var hudSpeedContainer: LinearLayout? = null
    private var hudSpeedIcon: ImageView? = null
    private var hudSpeedDirectionView: ImageView? = null
    private var hudSpeedDistanceView: TextView? = null
    private var roadCameraContainer: LinearLayout? = null
    private var roadCameraIconView: ImageView? = null
    private var roadCameraDistanceView: TextView? = null
    private var trafficLightContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var currentDisplayId: Int? = null
    private var lastState: NavigationHudState = NavigationHudState()
    private var lastRenderSignature: RenderSignature? = null
    private var containerPositionDp: PointF = OverlayPrefs.containerPositionDp(context)
    private var containerWidthDp: Float = OverlayPrefs.containerSizeDp(context).x
    private var containerHeightDp: Float = OverlayPrefs.containerSizeDp(context).y
    private var navPositionDp: PointF = OverlayPrefs.navPositionDp(context)
    private var arrowPositionDp: PointF = OverlayPrefs.arrowPositionDp(context)
    private var speedPositionDp: PointF = OverlayPrefs.speedPositionDp(context)
    private var hudSpeedPositionDp: PointF = OverlayPrefs.hudSpeedPositionDp(context)
    private var roadCameraPositionDp: PointF = OverlayPrefs.roadCameraPositionDp(context)
    private var trafficLightPositionDp: PointF = OverlayPrefs.trafficLightPositionDp(context)
    private var speedometerPositionDp: PointF = OverlayPrefs.speedometerPositionDp(context)
    private var clockPositionDp: PointF = OverlayPrefs.clockPositionDp(context)
    private var navScale: Float = OverlayPrefs.navScale(context)
    private var navTextScale: Float = OverlayPrefs.navTextScale(context)
    private var arrowScale: Float = OverlayPrefs.arrowScale(context)
    private var speedScale: Float = OverlayPrefs.speedScale(context)
    private var hudSpeedScale: Float = OverlayPrefs.hudSpeedScale(context)
    private var roadCameraScale: Float = OverlayPrefs.roadCameraScale(context)
    private var trafficLightScale: Float = OverlayPrefs.trafficLightScale(context)
    private var speedometerScale: Float = OverlayPrefs.speedometerScale(context)
    private var clockScale: Float = OverlayPrefs.clockScale(context)
    private var navAlpha: Float = OverlayPrefs.navAlpha(context)
    private var arrowAlpha: Float = OverlayPrefs.arrowAlpha(context)
    private var speedAlpha: Float = OverlayPrefs.speedAlpha(context)
    private var hudSpeedAlpha: Float = OverlayPrefs.hudSpeedAlpha(context)
    private var roadCameraAlpha: Float = OverlayPrefs.roadCameraAlpha(context)
    private var trafficLightAlpha: Float = OverlayPrefs.trafficLightAlpha(context)
    private var speedometerAlpha: Float = OverlayPrefs.speedometerAlpha(context)
    private var clockAlpha: Float = OverlayPrefs.clockAlpha(context)
    private var containerAlpha: Float = OverlayPrefs.containerAlpha(context)
    private var navEnabled: Boolean = OverlayPrefs.navEnabled(context)
    private var arrowEnabled: Boolean = OverlayPrefs.arrowEnabled(context)
    private var arrowOnlyWhenNoIcon: Boolean = OverlayPrefs.arrowOnlyWhenNoIcon(context)
    private var speedEnabled: Boolean = OverlayPrefs.speedEnabled(context)
    private var hudSpeedEnabled: Boolean = OverlayPrefs.hudSpeedEnabled(context)
    private var roadCameraEnabled: Boolean = OverlayPrefs.roadCameraEnabled(context)
    private var trafficLightEnabled: Boolean = OverlayPrefs.trafficLightEnabled(context)
    private var speedLimitAlertEnabled: Boolean = OverlayPrefs.speedLimitAlertEnabled(context)
    private var trafficLightMaxActive: Int = OverlayPrefs.trafficLightMaxActive(context)
    private var speedLimitAlertThreshold: Int = OverlayPrefs.speedLimitAlertThreshold(context)
    private var speedometerEnabled: Boolean = OverlayPrefs.speedometerEnabled(context)
    private var clockEnabled: Boolean = OverlayPrefs.clockEnabled(context)
    private var previewMode: Boolean = false
    private var previewTarget: String? = null
    private var previewShowOthers: Boolean = false
    private var clearOnDisablePending: Boolean = false
    private val speedLimitNumberRegex = Regex("\\d+")
    private var hudSpeedHideRunnable: Runnable? = null
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClockText()
            handler.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    fun refresh() {
        handler.post {
            if (!OverlayPrefs.isEnabled(context) || !Settings.canDrawOverlays(context)) {
                clearOverlayForDisable()
                return@post
            }
            val display = HudDisplayUtils.resolveDisplay(context, OverlayPrefs.displayId(context))
            if (display == null) {
                removeOverlay()
                UiLogStore.append(LogCategory.SYSTEM, "Оверлей: экран не найден")
                return@post
            }
            if (overlayView != null && currentDisplayId == display.displayId) {
                applyLayout()
                applyState(lastState)
                return@post
            }
            removeOverlay()
            createOverlay(display)
            applyLayout()
            applyState(lastState)
        }
    }

    fun updateNavigation(state: NavigationHudState) {
        handler.post {
            val signature = buildRenderSignature(state)
            if (signature == lastRenderSignature) {
                return@post
            }
            lastRenderSignature = signature
            lastState = state
            applyState(state)
        }
    }

    private fun buildRenderSignature(state: NavigationHudState): RenderSignature {
        val showPreview = previewMode
        val target = previewTarget
        val previewNav = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_NAV ||
                previewShowOthers
            )
        val previewArrow = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_ARROW ||
                previewShowOthers
            )
        val previewSpeed = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_SPEED ||
                previewShowOthers
            )
        val previewSpeedometer = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER ||
                previewShowOthers
            )
        val previewHudSpeed = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED ||
                previewShowOthers
            )
        val previewTrafficLight = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT ||
                previewShowOthers
            )

        val primaryText = if (previewNav) {
            context.getString(R.string.preview_distance_text)
        } else {
            val rawPrimary = state.rawNextText.ifBlank { state.primaryText }
            if (state.rawNextText.isNotBlank()) {
                rawPrimary
            } else {
                appendUnitIfMissing(rawPrimary, state.distanceUnit)
            }
        }
        val secondaryText = if (previewNav) {
            context.getString(R.string.preview_street_text)
        } else {
            state.rawNextStreet.ifBlank { state.secondaryText }
        }
        val timeText = if (previewNav) {
            context.getString(R.string.preview_time_text)
        } else {
            buildTimeLine(state)
        }
        val speedValue = state.speedKmh?.coerceAtLeast(0)
        val speedometerText = if (previewSpeedometer) {
            context.getString(R.string.preview_speedometer_text)
        } else {
            speedValue?.toString().orEmpty()
        }
        val speedLimitText = if (previewSpeed) {
            context.getString(R.string.preview_speed_limit_text)
        } else {
            state.speedLimit
        }
        val roadCameraDistanceText = state.roadCameraDistance.orEmpty()
        val roadCameraBitmap = state.roadCameraIcon
        val bitmap = state.maneuverBitmap
        val trafficLightSignatures = state.trafficLights.map { light ->
            TrafficLightSignature(
                id = light.id,
                color = light.color,
                countdownText = light.countdownText,
                arrowGenId = light.arrowBitmap?.generationId ?: -1,
                arrowWidth = light.arrowBitmap?.width ?: 0,
                arrowHeight = light.arrowBitmap?.height ?: 0
            )
        }
        return RenderSignature(
            primaryText = primaryText,
            secondaryText = secondaryText,
            timeText = timeText,
            speedLimitText = speedLimitText,
            speedometerText = speedometerText,
            roadCameraDistanceText = roadCameraDistanceText,
            roadCameraGenId = roadCameraBitmap?.generationId ?: -1,
            roadCameraWidth = roadCameraBitmap?.width ?: 0,
            roadCameraHeight = roadCameraBitmap?.height ?: 0,
            maneuverGenId = bitmap?.generationId ?: -1,
            maneuverWidth = bitmap?.width ?: 0,
            maneuverHeight = bitmap?.height ?: 0,
            nativeTurnId = state.nativeTurnId,
            previewNav = previewNav,
            previewArrow = previewArrow,
            previewSpeed = previewSpeed,
            previewSpeedometer = previewSpeedometer,
            hudSpeedHasCamera = state.hudSpeedHasCamera,
            hudSpeedDistanceMeters = state.hudSpeedDistanceMeters,
            hudSpeedCamType = state.hudSpeedCamType,
            hudSpeedCamFlag = state.hudSpeedCamFlag,
            hudSpeedUpdatedAt = state.hudSpeedUpdatedAt,
            previewHudSpeed = previewHudSpeed,
            previewTrafficLight = previewTrafficLight,
            trafficLights = trafficLightSignatures
        )
    }

    private data class RenderSignature(
        val primaryText: String,
        val secondaryText: String,
        val timeText: String,
        val speedLimitText: String,
        val speedometerText: String,
        val roadCameraDistanceText: String,
        val roadCameraGenId: Int,
        val roadCameraWidth: Int,
        val roadCameraHeight: Int,
        val maneuverGenId: Int,
        val maneuverWidth: Int,
        val maneuverHeight: Int,
        val nativeTurnId: Int?,
        val previewNav: Boolean,
        val previewArrow: Boolean,
        val previewSpeed: Boolean,
        val previewSpeedometer: Boolean,
        val hudSpeedHasCamera: Boolean,
        val hudSpeedDistanceMeters: Int?,
        val hudSpeedCamType: Int?,
        val hudSpeedCamFlag: Int?,
        val hudSpeedUpdatedAt: Long,
        val previewHudSpeed: Boolean,
        val previewTrafficLight: Boolean,
        val trafficLights: List<TrafficLightSignature>
    )

    private data class TrafficLightSignature(
        val id: Int,
        val color: String,
        val countdownText: String,
        val arrowGenId: Int,
        val arrowWidth: Int,
        val arrowHeight: Int
    )

    fun clearNavigation() {
        handler.post {
            val container = overlayView ?: return@post
            // Hide all navigation elements
            navContainer?.visibility = View.GONE
            arrowContainer?.visibility = View.GONE
            roadCameraContainer?.visibility = View.GONE
            trafficLightContainer?.visibility = View.GONE
            clockView?.visibility = View.VISIBLE // Keep clock visible
            // Fill with transparent to clear any remnants
            container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            container.visibility = View.VISIBLE
            container.invalidate()
            android.util.Log.d("HudOverlayController", "Navigation cleared, display filled with transparent")
        }
    }

    fun updateLayout(
        containerPosition: PointF?,
        containerWidthDp: Float?,
        containerHeightDp: Float?,
        navPosition: PointF?,
        arrowPosition: PointF?,
        speedPosition: PointF?,
        hudSpeedPosition: PointF?,
        roadCameraPosition: PointF?,
        trafficLightPosition: PointF?,
        speedometerPosition: PointF?,
        clockPosition: PointF?,
        navScale: Float?,
        navTextScale: Float?,
        arrowScale: Float?,
        speedScale: Float?,
        hudSpeedScale: Float?,
        roadCameraScale: Float?,
        trafficLightScale: Float?,
        speedometerScale: Float?,
        clockScale: Float?,
        navAlpha: Float?,
        arrowAlpha: Float?,
        speedAlpha: Float?,
        hudSpeedAlpha: Float?,
        roadCameraAlpha: Float?,
        trafficLightAlpha: Float?,
        speedometerAlpha: Float?,
        clockAlpha: Float?,
        containerAlpha: Float?,
        navEnabled: Boolean?,
        arrowEnabled: Boolean?,
        arrowOnlyWhenNoIcon: Boolean?,
        speedEnabled: Boolean?,
        hudSpeedEnabled: Boolean?,
        roadCameraEnabled: Boolean?,
        trafficLightEnabled: Boolean?,
        speedLimitAlertEnabled: Boolean?,
        speedLimitAlertThreshold: Int?,
        speedometerEnabled: Boolean?,
        clockEnabled: Boolean?,
        trafficLightMaxActive: Int?,
        preview: Boolean? = null,
        previewTarget: String? = null,
        previewShowOthers: Boolean? = null
    ) {
        handler.post {
            if (containerPosition != null) {
                containerPositionDp = containerPosition
            }
            if (containerWidthDp != null) {
                this.containerWidthDp = containerWidthDp.coerceAtLeast(0f)
            }
            if (containerHeightDp != null) {
                this.containerHeightDp = containerHeightDp.coerceAtLeast(0f)
            }
            if (navPosition != null) {
                navPositionDp = navPosition
            }
            if (arrowPosition != null) {
                arrowPositionDp = arrowPosition
            }
            if (speedPosition != null) {
                speedPositionDp = speedPosition
            }
            if (hudSpeedPosition != null) {
                hudSpeedPositionDp = hudSpeedPosition
            }
            if (roadCameraPosition != null) {
                roadCameraPositionDp = roadCameraPosition
            }
            if (trafficLightPosition != null) {
                trafficLightPositionDp = trafficLightPosition
            }
            if (speedometerPosition != null) {
                speedometerPositionDp = speedometerPosition
            }
            if (clockPosition != null) {
                clockPositionDp = clockPosition
            }
            if (navScale != null) {
                this.navScale = navScale.coerceAtLeast(0f)
            }
            if (navTextScale != null) {
                this.navTextScale = navTextScale.coerceIn(NAV_TEXT_SCALE_MIN, NAV_TEXT_SCALE_MAX)
                applyNavTextScale()
            }
            if (arrowScale != null) {
                this.arrowScale = arrowScale.coerceAtLeast(0f)
            }
            if (speedScale != null) {
                this.speedScale = speedScale.coerceAtLeast(0f)
            }
            if (hudSpeedScale != null) {
                this.hudSpeedScale = hudSpeedScale.coerceAtLeast(0f)
            }
            if (roadCameraScale != null) {
                this.roadCameraScale = roadCameraScale.coerceAtLeast(0f)
            }
            if (trafficLightScale != null) {
                this.trafficLightScale = trafficLightScale.coerceAtLeast(0f)
            }
            if (speedometerScale != null) {
                this.speedometerScale = speedometerScale.coerceAtLeast(0f)
            }
            if (clockScale != null) {
                this.clockScale = clockScale.coerceAtLeast(0f)
            }
            if (navAlpha != null) {
                this.navAlpha = navAlpha.coerceIn(0f, 1f)
            }
            if (arrowAlpha != null) {
                this.arrowAlpha = arrowAlpha.coerceIn(0f, 1f)
            }
            if (speedAlpha != null) {
                this.speedAlpha = speedAlpha.coerceIn(0f, 1f)
            }
            if (hudSpeedAlpha != null) {
                this.hudSpeedAlpha = hudSpeedAlpha.coerceIn(0f, 1f)
            }
            if (roadCameraAlpha != null) {
                this.roadCameraAlpha = roadCameraAlpha.coerceIn(0f, 1f)
            }
            if (trafficLightAlpha != null) {
                this.trafficLightAlpha = trafficLightAlpha.coerceIn(0f, 1f)
            }
            if (speedometerAlpha != null) {
                this.speedometerAlpha = speedometerAlpha.coerceIn(0f, 1f)
            }
            if (clockAlpha != null) {
                this.clockAlpha = clockAlpha.coerceIn(0f, 1f)
            }
            if (containerAlpha != null) {
                this.containerAlpha = containerAlpha.coerceIn(0f, 1f)
            }
            if (navEnabled != null) {
                this.navEnabled = navEnabled
            }
            if (arrowEnabled != null) {
                this.arrowEnabled = arrowEnabled
            }
            if (arrowOnlyWhenNoIcon != null) {
                this.arrowOnlyWhenNoIcon = arrowOnlyWhenNoIcon
            }
            if (speedEnabled != null) {
                this.speedEnabled = speedEnabled
            }
            if (hudSpeedEnabled != null) {
                this.hudSpeedEnabled = hudSpeedEnabled
            }
            if (roadCameraEnabled != null) {
                this.roadCameraEnabled = roadCameraEnabled
            }
            if (trafficLightEnabled != null) {
                this.trafficLightEnabled = trafficLightEnabled
            }
            if (speedLimitAlertEnabled != null) {
                this.speedLimitAlertEnabled = speedLimitAlertEnabled
            }
            if (speedLimitAlertThreshold != null) {
                this.speedLimitAlertThreshold = speedLimitAlertThreshold
                    .coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
            }
            if (speedometerEnabled != null) {
                this.speedometerEnabled = speedometerEnabled
            }
            if (clockEnabled != null) {
                this.clockEnabled = clockEnabled
            }
            if (trafficLightMaxActive != null) {
                this.trafficLightMaxActive = trafficLightMaxActive.coerceAtLeast(1)
            }
            if (preview != null) {
                previewMode = preview
            }
            if (previewTarget != null) {
                this.previewTarget = previewTarget
            }
            if (previewShowOthers != null) {
                this.previewShowOthers = previewShowOthers
            }
            applyLayout()
            applyState(lastState)
        }
    }

    fun destroy() {
        handler.post {
            removeOverlay()
        }
    }

    private fun createOverlay(display: android.view.Display) {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java)
        if (wm == null) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: WindowManager недоступен")
            return
        }
        val metrics = displayContext.resources.displayMetrics
        val padding = (8 * metrics.density).roundToInt()
        val iconSize = (48 * metrics.density).roundToInt()
        val iconMargin = (8 * metrics.density).roundToInt()
        val speedSize = (40 * metrics.density).roundToInt()
        val (containerWidthPx, containerHeightPx) = resolveContainerSizePx(metrics)

        val root = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        val navBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val maneuverBox = FrameLayout(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconMargin
            }
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_direction_box)
        }

        val maneuverImage = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val maneuverText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.preview_direction_label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }

        maneuverBox.addView(maneuverImage)
        maneuverBox.addView(maneuverText)

        val textColumn = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val primaryText = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_PRIMARY_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(false)
        }

        val secondaryText = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_SECONDARY_TEXT_SIZE_SP)
            setSingleLine(false)
        }

        val timeText = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_TIME_TEXT_SIZE_SP)
            setSingleLine(false)
        }

        val roadCameraBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        val roadCameraIcon = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val roadCameraDistanceText = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ROAD_CAMERA_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
        }

        roadCameraBlock.addView(roadCameraIcon)
        roadCameraBlock.addView(roadCameraDistanceText)

        textColumn.addView(primaryText)
        textColumn.addView(secondaryText)
        textColumn.addView(timeText)
        navBlock.addView(maneuverBox)
        navBlock.addView(textColumn)

        val arrowBox = FrameLayout(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_direction_box)
        }

        val arrowImage = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val arrowText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.preview_direction_label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }

        arrowBox.addView(arrowImage)
        arrowBox.addView(arrowText)

        val speedText = OutlinedTextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(speedSize, speedSize)
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_speed_limit)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SPEED_LIMIT_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
        }

        val hudSpeedBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val trafficLightBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        val hudSpeedIconView = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.cam_type_1)
        }

        val hudSpeedDistanceText = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.preview_hudspeed_distance)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }

        hudSpeedBlock.addView(hudSpeedIconView)
        val hudSpeedDirectionIcon = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * metrics.density).roundToInt()
            }
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        hudSpeedBlock.addView(hudSpeedDirectionIcon)
        hudSpeedBlock.addView(hudSpeedDistanceText)

        val speedometerText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val clockText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }

        root.addView(navBlock)
        root.addView(arrowBox)
        root.addView(speedText)
        root.addView(hudSpeedBlock)
        root.addView(roadCameraBlock)
        root.addView(trafficLightBlock)
        root.addView(speedometerText)
        root.addView(clockText)

        val layoutParams = WindowManager.LayoutParams(
            containerWidthPx,
            containerHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(root, layoutParams)
            windowManager = wm
            overlayView = root
            overlayLayoutParams = layoutParams
            navContainer = navBlock
            maneuverContainer = maneuverBox
            maneuverView = maneuverImage
            maneuverLabel = maneuverText
            arrowContainer = arrowBox
            arrowView = arrowImage
            arrowLabel = arrowText
            primaryView = primaryText
            secondaryView = secondaryText
            timeView = timeText
            speedLimitView = speedText
            hudSpeedContainer = hudSpeedBlock
            hudSpeedIcon = hudSpeedIconView
            hudSpeedDirectionView = hudSpeedDirectionIcon
            hudSpeedDistanceView = hudSpeedDistanceText
            roadCameraContainer = roadCameraBlock
            roadCameraIconView = roadCameraIcon
            roadCameraDistanceView = roadCameraDistanceText
            trafficLightContainer = trafficLightBlock
            speedometerView = speedometerText
            clockView = clockText
            currentDisplayId = display.displayId
            applyNavTextScale()
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: показан на экране ${display.displayId}")
            startClockTicker()
        } catch (e: Exception) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: ошибка addView: ${e.message}")
            windowManager = null
            overlayView = null
            navContainer = null
            maneuverContainer = null
            maneuverView = null
            maneuverLabel = null
            arrowContainer = null
            arrowView = null
            arrowLabel = null
            primaryView = null
            secondaryView = null
            timeView = null
            speedLimitView = null
            hudSpeedContainer = null
            hudSpeedIcon = null
            hudSpeedDirectionView = null
            hudSpeedDistanceView = null
            roadCameraContainer = null
            roadCameraIconView = null
            roadCameraDistanceView = null
            trafficLightContainer = null
            speedometerView = null
            clockView = null
            currentDisplayId = null
            stopClockTicker()
        }
    }

    private fun removeOverlay() {
        cancelHudSpeedHide()
        val wm = windowManager
        val view = overlayView
        if (wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                UiLogStore.append(LogCategory.SYSTEM, "Оверлей: ошибка удаления: ${e.message}")
            }
        }
        windowManager = null
        overlayView = null
        overlayLayoutParams = null
        navContainer = null
        maneuverContainer = null
        maneuverView = null
        maneuverLabel = null
        arrowContainer = null
        arrowView = null
        arrowLabel = null
        primaryView = null
        secondaryView = null
        timeView = null
        speedLimitView = null
        hudSpeedContainer = null
        hudSpeedIcon = null
        hudSpeedDirectionView = null
        hudSpeedDistanceView = null
        roadCameraContainer = null
        roadCameraIconView = null
        roadCameraDistanceView = null
        trafficLightContainer = null
        speedometerView = null
        clockView = null
        currentDisplayId = null
        stopClockTicker()
    }

    private fun clearOverlayForDisable() {
        cancelHudSpeedHide()
        val container = overlayView
        if (container == null) {
            removeOverlay()
            return
        }
        if (clearOnDisablePending) {
            return
        }
        clearOnDisablePending = true
        navContainer?.visibility = View.GONE
        arrowContainer?.visibility = View.GONE
        speedLimitView?.visibility = View.GONE
        hudSpeedContainer?.visibility = View.GONE
        roadCameraContainer?.visibility = View.GONE
        trafficLightContainer?.visibility = View.GONE
        speedometerView?.visibility = View.GONE
        clockView?.visibility = View.GONE
        container.setBackgroundColor(Color.TRANSPARENT)
        container.visibility = View.VISIBLE
        container.invalidate()
        container.post {
            clearOnDisablePending = false
            if (!OverlayPrefs.isEnabled(context) || !Settings.canDrawOverlays(context)) {
                removeOverlay()
            }
        }
    }

    private fun applyState(state: NavigationHudState) {
        val primary = primaryView ?: return
        val secondary = secondaryView ?: return
        val time = timeView ?: return
        val container = overlayView
        val clock = clockView
        val speedometer = speedometerView
        val showPreview = previewMode
        val target = previewTarget
        val previewNav = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_NAV ||
                previewShowOthers
            )
        val previewArrow = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_ARROW ||
                previewShowOthers
            )
        val previewSpeed = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_SPEED ||
                previewShowOthers
            )
        val previewSpeedometer = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER ||
                previewShowOthers
            )
        val previewHudSpeed = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED ||
                previewShowOthers
            )
        val previewRoadCamera = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_ROAD_CAMERA ||
                previewShowOthers
            )
        val previewTrafficLight = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT ||
                previewShowOthers
            )
        val previewClock = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_CLOCK ||
                previewShowOthers
            )
        val navAllowed = navEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_NAV)
        val arrowAllowed = arrowEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_ARROW)
        val speedAllowed = speedEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_SPEED)
        val speedometerAllowed = speedometerEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER)
        val hudSpeedAllowed = hudSpeedEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED)
        val roadCameraAllowed = roadCameraEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_ROAD_CAMERA)
        val trafficLightAllowed = trafficLightEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT)
        val clockAllowed = clockEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_CLOCK)

        container?.visibility = View.VISIBLE

        val primaryText = if (previewNav) {
            context.getString(R.string.preview_distance_text)
        } else {
            val rawPrimary = state.rawNextText.ifBlank { state.primaryText }
            if (state.rawNextText.isNotBlank()) {
                rawPrimary
            } else {
                appendUnitIfMissing(rawPrimary, state.distanceUnit)
            }
        }

        val secondaryText = if (previewNav) {
            context.getString(R.string.preview_street_text)
        } else {
            state.rawNextStreet.ifBlank { state.secondaryText }
        }

        val timeText = if (previewNav) {
            context.getString(R.string.preview_time_text)
        } else {
            buildTimeLine(state)
        }

        val speedValue = state.speedKmh?.coerceAtLeast(0)
        val speedometerText = if (previewSpeedometer) {
            context.getString(R.string.preview_speedometer_text)
        } else {
            speedValue?.toString().orEmpty()
        }
        val speedLimitText = state.speedLimit
        val speedLimitValue = parseSpeedLimitValue(speedLimitText)
        val overspeed = !previewSpeed &&
            speedLimitAlertEnabled &&
            speedValue != null &&
            speedLimitValue != null &&
            speedValue > speedLimitValue + speedLimitAlertThreshold
        val hudSpeedDistanceText = if (previewHudSpeed) {
            context.getString(R.string.preview_hudspeed_distance)
        } else {
            formatHudSpeedDistance(state.hudSpeedDistanceMeters)
        }
        val hudSpeedDirectionIcon = if (previewHudSpeed) {
            null
        } else {
            resolveHudSpeedDirectionIcon(state.hudSpeedCamFlag)
        }
        val hudSpeedCamType = if (previewHudSpeed) {
            PREVIEW_HUDSPEED_CAM_TYPE
        } else {
            state.hudSpeedCamType
        }
        val roadCameraDistanceText = if (previewRoadCamera) {
            context.getString(R.string.preview_road_camera_distance)
        } else {
            state.roadCameraDistance.orEmpty()
        }
        val roadCameraHasData = state.roadCameraIcon != null && state.roadCameraDistance?.isNotBlank() == true
        val trafficLights = if (previewTrafficLight) {
            listOf(
                TrafficLightInfo(
                    id = -1,
                    color = "GREEN",
                    countdownText = context.getString(R.string.preview_traffic_light_countdown),
                    arrowBitmap = null,
                    arrowDirection = "",
                    lastUpdated = System.currentTimeMillis(),
                    expiresAt = Long.MAX_VALUE
                )
            )
        } else {
            state.trafficLights
        }

        setTextOrHide(primary, primaryText)
        setTextOrHide(secondary, secondaryText)
        setTextOrHide(time, timeText)
        updateSpeedLimit(speedLimitText, previewSpeed, overspeed)
        speedometer?.let { setTextOrHide(it, speedometerText) }
        updateHudSpeed(hudSpeedCamType, hudSpeedDirectionIcon, hudSpeedDistanceText)
        updateRoadCamera(state.roadCameraIcon, roadCameraDistanceText, roadCameraAllowed, previewRoadCamera)
        updateTrafficLights(trafficLights, trafficLightAllowed, previewTrafficLight)
        updateManeuver(state.maneuverBitmap, previewNav)
        updateArrowManeuver(state.maneuverBitmap, previewArrow)
        if (clock != null) {
            updateClockText()
        }

        val navHasContent = primaryText.isNotBlank() ||
            secondaryText.isNotBlank() ||
            timeText.isNotBlank() ||
            state.maneuverBitmap != null
        val navVisible = if (showPreview) previewNav else navHasContent
        val arrowEligible = if (arrowOnlyWhenNoIcon) {
            NativeNavigationController.isActive() &&
                state.nativeTurnId == NavigationReceiver.DEFAULT_NATIVE_TURN_ID
        } else {
            true
        }
        val arrowVisible = if (showPreview) {
            previewArrow
        } else {
            state.maneuverBitmap != null && arrowEligible
        }
        val speedVisible = if (showPreview) previewSpeed else state.speedLimit.isNotBlank()
        if (!showPreview && (!state.hudSpeedHasCamera || state.hudSpeedDistanceMeters == null)) {
            cancelHudSpeedHide()
        }
        val hudSpeedVisible = if (showPreview) {
            previewHudSpeed
        } else {
            state.hudSpeedHasCamera &&
                state.hudSpeedDistanceMeters != null &&
                !shouldHideHudSpeed(state, showPreview)
        }
        val speedometerVisible = if (showPreview) previewSpeedometer else state.speedKmh != null
        val clockVisible = if (showPreview) previewClock else true
        val roadCameraVisible = roadCameraAllowed && (previewRoadCamera || roadCameraHasData)
        val trafficLightVisible = trafficLightAllowed && (previewTrafficLight || trafficLights.isNotEmpty())
        navContainer?.visibility = if (navAllowed && navVisible) View.VISIBLE else View.GONE
        arrowContainer?.visibility = if (arrowAllowed && arrowVisible) View.VISIBLE else View.GONE
        speedLimitView?.visibility = if (speedAllowed && speedVisible) View.VISIBLE else View.GONE
        hudSpeedContainer?.visibility = if (hudSpeedAllowed && hudSpeedVisible) View.VISIBLE else View.GONE
        speedometer?.visibility = if (speedometerAllowed && speedometerVisible) View.VISIBLE else View.GONE
        clock?.visibility = if (clockAllowed && clockVisible) View.VISIBLE else View.GONE

        // Hide main container if nothing is visible
        val anyVisible = (navAllowed && navVisible) ||
            (arrowAllowed && arrowVisible) ||
            (speedAllowed && speedVisible) ||
            (hudSpeedAllowed && hudSpeedVisible) ||
            roadCameraVisible ||
            trafficLightVisible ||
            (speedometerAllowed && speedometerVisible) ||
            (clockAllowed && clockVisible)
        container?.visibility = if (showPreview || anyVisible) View.VISIBLE else View.GONE

        applyLayout()
    }

    private fun shouldHideHudSpeed(state: NavigationHudState, showPreview: Boolean): Boolean {
        if (showPreview) {
            cancelHudSpeedHide()
            return false
        }
        val distance = state.hudSpeedDistanceMeters ?: run {
            cancelHudSpeedHide()
            return false
        }
        val updatedAt = state.hudSpeedUpdatedAt
        if (distance >= HUDSPEED_HIDE_DISTANCE_METERS || updatedAt <= 0L) {
            cancelHudSpeedHide()
            return false
        }
        val elapsed = System.currentTimeMillis() - updatedAt
        val remaining = HUDSPEED_HIDE_TIMEOUT_MS - elapsed
        if (remaining <= 0L) {
            cancelHudSpeedHide()
            return true
        }
        scheduleHudSpeedHide(remaining)
        return false
    }

    private fun scheduleHudSpeedHide(delayMs: Long) {
        cancelHudSpeedHide()
        val runnable = Runnable {
            hudSpeedHideRunnable = null
            applyState(lastState)
        }
        hudSpeedHideRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelHudSpeedHide() {
        hudSpeedHideRunnable?.let { handler.removeCallbacks(it) }
        hudSpeedHideRunnable = null
    }

    private fun updateManeuver(bitmap: android.graphics.Bitmap?, preview: Boolean) {
        val image = maneuverView ?: return
        val label = maneuverLabel ?: return
        val container = maneuverContainer ?: return
        val boxBackground = if (preview) {
            ContextCompat.getDrawable(container.context, R.drawable.bg_direction_box)
        } else {
            null
        }
        container.background = boxBackground
        if (preview) {
            image.visibility = View.GONE
            label.visibility = View.VISIBLE
            return
        }
        if (bitmap != null) {
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            label.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            label.visibility = View.GONE
        }
    }

    private fun updateArrowManeuver(bitmap: android.graphics.Bitmap?, preview: Boolean) {
        val image = arrowView ?: return
        val label = arrowLabel ?: return
        val container = arrowContainer ?: return
        val boxBackground = if (preview) {
            ContextCompat.getDrawable(container.context, R.drawable.bg_direction_box)
        } else {
            null
        }
        container.background = boxBackground
        if (preview) {
            image.visibility = View.GONE
            label.visibility = View.VISIBLE
            return
        }
        if (bitmap != null) {
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            label.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            label.visibility = View.GONE
        }
    }

    private fun setTextOrHide(view: TextView, text: String) {
        if (text.isBlank()) {
            view.visibility = View.GONE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    private fun buildTimeLine(state: NavigationHudState): String {
        val time = state.time.trim()
        val arrival = state.arrival.trim()
        if (time.isNotBlank() && arrival.isNotBlank()) {
            return "$time ($arrival)"
        }
        if (time.isNotBlank()) {
            return time
        }
        if (arrival.isNotBlank()) {
            return "($arrival)"
        }
        return ""
    }

    private fun updateSpeedLimit(speedLimit: String, preview: Boolean, overspeed: Boolean) {
        val view = speedLimitView ?: return
        val text = when {
            preview -> context.getString(R.string.preview_speed_limit_text)
            speedLimit.isNotBlank() -> speedLimit
            else -> ""
        }
        if (text.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.text = text
        val textSizeSp = if (overspeed) {
            SPEED_LIMIT_ALERT_TEXT_SIZE_SP
        } else {
            SPEED_LIMIT_TEXT_SIZE_SP
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        val outlinePx = view.resources.displayMetrics.density * 2f
        view.strokeEnabled = overspeed
        view.strokeWidthPx = outlinePx
        view.strokeColor = Color.BLACK
        val background = if (overspeed) {
            R.drawable.bg_speed_limit_alert
        } else {
            R.drawable.bg_speed_limit
        }
        view.setBackgroundResource(background)
        view.visibility = View.VISIBLE
    }

    private fun updateHudSpeed(camType: Int?, directionIcon: Int?, distanceText: String) {
        val container = hudSpeedContainer ?: return
        val icon = hudSpeedIcon ?: return
        val direction = hudSpeedDirectionView ?: return
        val distance = hudSpeedDistanceView ?: return
        icon.setImageResource(resolveHudSpeedCamIcon(camType))
        if (directionIcon == null || distanceText.isBlank()) {
            direction.setImageDrawable(null)
            direction.visibility = View.GONE
        } else {
            direction.setImageResource(directionIcon)
            direction.visibility = View.VISIBLE
        }
        distance.text = distanceText
        if (distanceText.isBlank()) {
            container.visibility = View.GONE
        }
    }

    private fun updateRoadCamera(
        icon: android.graphics.Bitmap?,
        distanceText: String,
        allowed: Boolean,
        preview: Boolean
    ) {
        val container = roadCameraContainer ?: return
        val image = roadCameraIconView ?: return
        val distance = roadCameraDistanceView ?: return
        if (!allowed || distanceText.isBlank() || (!preview && icon == null)) {
            image.setImageDrawable(null)
            container.visibility = View.GONE
            return
        }
        if (preview) {
            image.setImageResource(R.drawable.road_events_camera_48)
        } else {
            image.setImageBitmap(icon)
        }
        distance.text = distanceText
        container.visibility = View.VISIBLE
    }

    private fun updateTrafficLights(
        lights: List<TrafficLightInfo>,
        allowed: Boolean,
        preview: Boolean
    ) {
        val container = trafficLightContainer ?: return
        if (!allowed) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }
        val maxActive = trafficLightMaxActive.coerceAtLeast(1)
        val displayLights = lights.take(maxActive)
        if (displayLights.isEmpty()) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }
        val inflater = LayoutInflater.from(container.context)
        val density = container.resources.displayMetrics.density
        val itemSpacingPx = (8 * density).roundToInt()
        container.removeAllViews()
        displayLights.forEachIndexed { index, light ->
            val item = inflater.inflate(R.layout.traffic_light_notification_view, container, false)
            val compactView = item.findViewById<FrameLayout>(R.id.traffic_light_view)
            val compactText = item.findViewById<TextView>(R.id.traffic_light_data)
            val expandedView = item.findViewById<LinearLayout>(R.id.traffic_light_view_expanded)
            val expandedCircle = item.findViewById<FrameLayout>(R.id.traffic_light_view_expanded_circle)
            val expandedText = item.findViewById<TextView>(R.id.traffic_light_data_expanded)
            val expandedIcon = item.findViewById<ImageView>(R.id.traffic_light_icon_expanded)

            val backgroundRes = resolveTrafficLightBackground(light.color)
            val countdownText = light.countdownText

            val useExpanded = !preview && light.arrowBitmap != null
            if (useExpanded) {
                compactView.visibility = View.GONE
                expandedView.visibility = View.VISIBLE
                expandedCircle.setBackgroundResource(backgroundRes)
                expandedText.text = countdownText
                expandedText.visibility = if (countdownText.isBlank()) View.INVISIBLE else View.VISIBLE
                expandedIcon.setImageBitmap(light.arrowBitmap)
                expandedIcon.visibility = View.VISIBLE
            } else {
                compactView.visibility = View.VISIBLE
                expandedView.visibility = View.GONE
                compactView.setBackgroundResource(backgroundRes)
                compactText.text = countdownText
                compactText.visibility = if (countdownText.isBlank()) View.INVISIBLE else View.VISIBLE
                expandedIcon.setImageDrawable(null)
            }

            val params = item.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            if (index < displayLights.lastIndex) {
                params.marginEnd = itemSpacingPx
            } else {
                params.marginEnd = 0
            }
            item.layoutParams = params
            container.addView(item)
        }
        container.visibility = View.VISIBLE
    }

    private fun resolveTrafficLightBackground(color: String): Int {
        return when (color.trim().uppercase(Locale.US)) {
            "RED" -> R.drawable.traffic_light_background_red
            "YELLOW" -> R.drawable.traffic_light_background_yellow
            "GREEN" -> R.drawable.traffic_light_background_green
            else -> R.drawable.traffic_light_background_green
        }
    }

    private fun resolveHudSpeedDirectionIcon(camFlag: Int?): Int? {
        return when (camFlag) {
            3 -> R.drawable.cam_dirtype_3_hud
            4 -> R.drawable.cam_dirtype_4_hud
            else -> null
        }
    }

    private fun parseSpeedLimitValue(speedLimit: String): Int? {
        val match = speedLimitNumberRegex.find(speedLimit)
        return match?.value?.toIntOrNull()
    }

    private fun resolveHudSpeedCamIcon(camType: Int?): Int {
        val type = camType ?: return R.drawable.cam_type_1
        val name = "cam_type_$type"
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (resId != 0) resId else R.drawable.cam_type_1
    }

    private fun formatHudSpeedDistance(distanceMeters: Int?): String {
        if (distanceMeters == null || distanceMeters < 0) {
            return ""
        }
        return "${distanceMeters}м"
    }

    private fun appendUnitIfMissing(text: String, unit: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank() || unit.isBlank()) {
            return text
        }
        if (!trimmed.matches(Regex("\\d+(?:[.,]\\d+)?"))) {
            return text
        }
        return "$trimmed $unit"
    }

    private fun applyLayout() {
        overlayView?.post { applyLayoutInternal() }
    }

    private fun applyLayoutInternal() {
        val displayId = currentDisplayId ?: return
        val display = HudDisplayUtils.resolveDisplay(context, displayId) ?: return
        val metrics = context.createDisplayContext(display).resources.displayMetrics
        val (containerWidthPx, containerHeightPx) = resolveContainerSizePx(metrics)
        val maxHeightPx = metrics.heightPixels.coerceAtLeast(0)
        val resolvedHeightPx = resolveContainerHeightPx(containerWidthPx, containerHeightPx)
            .coerceAtMost(maxHeightPx)
        updateContainerLayout(metrics, containerWidthPx, resolvedHeightPx)
        val containerWidth = containerWidthPx.toFloat()
        val containerHeight = resolvedHeightPx.toFloat()
        navContainer?.let {
            positionView(it, navPositionDp, navScale, navAlpha, metrics.density, containerWidth, containerHeight)
        }
        arrowContainer?.let {
            positionView(it, arrowPositionDp, arrowScale, arrowAlpha, metrics.density, containerWidth, containerHeight)
        }
        speedLimitView?.let {
            positionView(it, speedPositionDp, speedScale, speedAlpha, metrics.density, containerWidth, containerHeight)
        }
        hudSpeedContainer?.let {
            positionView(it, hudSpeedPositionDp, hudSpeedScale, hudSpeedAlpha, metrics.density, containerWidth, containerHeight)
        }
        roadCameraContainer?.let {
            positionView(it, roadCameraPositionDp, roadCameraScale, roadCameraAlpha, metrics.density, containerWidth, containerHeight)
        }
        trafficLightContainer?.let {
            positionView(it, trafficLightPositionDp, trafficLightScale, trafficLightAlpha, metrics.density, containerWidth, containerHeight)
        }
        speedometerView?.let {
            positionView(it, speedometerPositionDp, speedometerScale, speedometerAlpha, metrics.density, containerWidth, containerHeight)
        }
        clockView?.let {
            positionView(it, clockPositionDp, clockScale, clockAlpha, metrics.density, containerWidth, containerHeight)
        }
    }

    private fun updateContainerLayout(
        metrics: android.util.DisplayMetrics,
        containerWidthPx: Int,
        containerHeightPx: Int
    ) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = overlayLayoutParams ?: return
        val xPx = (containerPositionDp.x * metrics.density).roundToInt()
        val yPx = (containerPositionDp.y * metrics.density).roundToInt()
        val maxX = (metrics.widthPixels - containerWidthPx).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - containerHeightPx).coerceAtLeast(0)
        params.width = containerWidthPx
        params.height = containerHeightPx
        params.x = xPx.coerceIn(0, maxX)
        params.y = yPx.coerceIn(0, maxY)
        updateContainerOutlineAlpha(view)
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: ошибка обновления: ${e.message}")
        }
    }

    private fun updateContainerOutlineAlpha(container: FrameLayout) {
        if (previewMode && previewTarget == OverlayBroadcasts.PREVIEW_TARGET_CONTAINER) {
            if (container.background == null) {
                container.background = ContextCompat.getDrawable(container.context, R.drawable.bg_hud_container_outline)
            }
            val effectiveAlpha = max(containerAlpha, CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA)
            val alphaValue = (effectiveAlpha * 255).roundToInt().coerceIn(0, 255)
            container.background?.alpha = alphaValue
        } else {
            container.background = null
        }
    }

    private fun resolveContainerSizePx(metrics: android.util.DisplayMetrics): Pair<Int, Int> {
        val density = metrics.density
        val minPx = OverlayPrefs.CONTAINER_MIN_SIZE_PX.roundToInt().coerceAtLeast(1)
        val maxWidthPx = metrics.widthPixels.coerceAtLeast(minPx)
        val maxHeightPx = metrics.heightPixels.coerceAtLeast(minPx)
        val widthPx = (containerWidthDp * density).roundToInt()
        val heightPx = (containerHeightDp * density).roundToInt()
        val resolvedWidth = widthPx.coerceIn(minPx, maxWidthPx)
        val resolvedHeight = heightPx.coerceIn(minPx, maxHeightPx)
        return resolvedWidth to resolvedHeight
    }

    private fun resolveContainerHeightPx(containerWidthPx: Int, containerHeightPx: Int): Int {
        val navView = navContainer ?: return containerHeightPx
        if (navView.visibility != View.VISIBLE) {
            return containerHeightPx
        }
        val (_, rawHeight) = resolveViewSize(navView, containerWidthPx.toFloat(), true)
        val scaledHeight = rawHeight * navScale
        if (scaledHeight <= 0f) {
            return containerHeightPx
        }
        return max(containerHeightPx, scaledHeight.roundToInt())
    }

    private fun positionView(
        view: View,
        positionDp: PointF,
        scale: Float,
        alpha: Float,
        density: Float,
        containerWidthPx: Float,
        containerHeightPx: Float
    ) {
        val (rawWidth, rawHeight) = resolveViewSize(view, containerWidthPx, shouldMeasureExactWidth(view))
        if (rawWidth <= 0f || rawHeight <= 0f) {
            return
        }
        val scaledWidth = rawWidth * scale
        val scaledHeight = rawHeight * scale
        if (scaledWidth <= 0f || scaledHeight <= 0f) {
            return
        }
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = alpha
        val xPx = positionDp.x * density
        val yPx = positionDp.y * density
        val maxX = (containerWidthPx - scaledWidth).coerceAtLeast(0f)
        val maxY = (containerHeightPx - scaledHeight).coerceAtLeast(0f)
        view.x = xPx.coerceIn(0f, maxX)
        view.y = yPx.coerceIn(0f, maxY)
    }

    private fun resolveViewSize(view: View, maxWidthPx: Float, exactWidth: Boolean): Pair<Float, Float> {
        val widthSpec = if (maxWidthPx > 0f) {
            val mode = if (exactWidth) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            View.MeasureSpec.makeMeasureSpec(maxWidthPx.roundToInt(), mode)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val measuredWidth = view.measuredWidth
        val measuredHeight = view.measuredHeight
        if (measuredWidth > 0 && measuredHeight > 0 &&
            (view.width != measuredWidth || view.height != measuredHeight)
        ) {
            view.layout(0, 0, measuredWidth, measuredHeight)
        }
        val width = if (measuredWidth > 0) measuredWidth else view.width
        val height = if (measuredHeight > 0) measuredHeight else view.height
        return width.toFloat() to height.toFloat()
    }

    private fun shouldMeasureExactWidth(view: View): Boolean {
        val params = view.layoutParams ?: return false
        return params.width == ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun applyNavTextScale() {
        val scale = navTextScale.coerceIn(NAV_TEXT_SCALE_MIN, NAV_TEXT_SCALE_MAX)
        primaryView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_PRIMARY_TEXT_SIZE_SP * scale)
        secondaryView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_SECONDARY_TEXT_SIZE_SP * scale)
        timeView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_TIME_TEXT_SIZE_SP * scale)
    }

    private fun updateClockText() {
        val view = clockView ?: return
        view.text = clockFormatter.format(Date())
    }

    private fun startClockTicker() {
        handler.removeCallbacks(clockTicker)
        updateClockText()
        handler.postDelayed(clockTicker, CLOCK_TICK_MS)
    }

    private fun stopClockTicker() {
        handler.removeCallbacks(clockTicker)
    }
}
