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
    private var primaryView: TextView? = null
    private var secondaryView: TextView? = null
    private var timeView: TextView? = null
    private var speedLimitView: OutlinedTextView? = null
    private var speedometerView: TextView? = null
    private var clockView: TextView? = null
    private var currentDisplayId: Int? = null
    private var lastState: NavigationHudState = NavigationHudState()
    private var containerPositionDp: PointF = OverlayPrefs.containerPositionDp(context)
    private var containerWidthDp: Float = OverlayPrefs.containerSizeDp(context).x
    private var containerHeightDp: Float = OverlayPrefs.containerSizeDp(context).y
    private var navPositionDp: PointF = OverlayPrefs.navPositionDp(context)
    private var speedPositionDp: PointF = OverlayPrefs.speedPositionDp(context)
    private var speedometerPositionDp: PointF = OverlayPrefs.speedometerPositionDp(context)
    private var clockPositionDp: PointF = OverlayPrefs.clockPositionDp(context)
    private var navScale: Float = OverlayPrefs.navScale(context)
    private var speedScale: Float = OverlayPrefs.speedScale(context)
    private var speedometerScale: Float = OverlayPrefs.speedometerScale(context)
    private var clockScale: Float = OverlayPrefs.clockScale(context)
    private var navAlpha: Float = OverlayPrefs.navAlpha(context)
    private var speedAlpha: Float = OverlayPrefs.speedAlpha(context)
    private var speedometerAlpha: Float = OverlayPrefs.speedometerAlpha(context)
    private var clockAlpha: Float = OverlayPrefs.clockAlpha(context)
    private var containerAlpha: Float = OverlayPrefs.containerAlpha(context)
    private var navEnabled: Boolean = OverlayPrefs.navEnabled(context)
    private var speedEnabled: Boolean = OverlayPrefs.speedEnabled(context)
    private var speedLimitAlertEnabled: Boolean = OverlayPrefs.speedLimitAlertEnabled(context)
    private var speedLimitAlertThreshold: Int = OverlayPrefs.speedLimitAlertThreshold(context)
    private var speedometerEnabled: Boolean = OverlayPrefs.speedometerEnabled(context)
    private var clockEnabled: Boolean = OverlayPrefs.clockEnabled(context)
    private var previewMode: Boolean = false
    private var previewTarget: String? = null
    private var previewShowOthers: Boolean = false
    private var clearOnDisablePending: Boolean = false
    private val speedLimitNumberRegex = Regex("\\d+")
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
            lastState = state
            applyState(state)
        }
    }

    fun updateLayout(
        containerPosition: PointF?,
        containerWidthDp: Float?,
        containerHeightDp: Float?,
        navPosition: PointF?,
        speedPosition: PointF?,
        speedometerPosition: PointF?,
        clockPosition: PointF?,
        navScale: Float?,
        speedScale: Float?,
        speedometerScale: Float?,
        clockScale: Float?,
        navAlpha: Float?,
        speedAlpha: Float?,
        speedometerAlpha: Float?,
        clockAlpha: Float?,
        containerAlpha: Float?,
        navEnabled: Boolean?,
        speedEnabled: Boolean?,
        speedLimitAlertEnabled: Boolean?,
        speedLimitAlertThreshold: Int?,
        speedometerEnabled: Boolean?,
        clockEnabled: Boolean?,
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
            if (speedPosition != null) {
                speedPositionDp = speedPosition
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
            if (speedScale != null) {
                this.speedScale = speedScale.coerceAtLeast(0f)
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
            if (speedAlpha != null) {
                this.speedAlpha = speedAlpha.coerceIn(0f, 1f)
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
            if (speedEnabled != null) {
                this.speedEnabled = speedEnabled
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
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val secondaryText = TextView(displayContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val timeText = TextView(displayContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        textColumn.addView(primaryText)
        textColumn.addView(secondaryText)
        textColumn.addView(timeText)
        navBlock.addView(maneuverBox)
        navBlock.addView(textColumn)

        val speedText = OutlinedTextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(speedSize, speedSize)
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_speed_limit)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SPEED_LIMIT_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
        }

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
        root.addView(speedText)
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
            primaryView = primaryText
            secondaryView = secondaryText
            timeView = timeText
            speedLimitView = speedText
            speedometerView = speedometerText
            clockView = clockText
            currentDisplayId = display.displayId
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
            primaryView = null
            secondaryView = null
            timeView = null
            speedLimitView = null
            speedometerView = null
            clockView = null
            currentDisplayId = null
            stopClockTicker()
        }
    }

    private fun removeOverlay() {
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
        primaryView = null
        secondaryView = null
        timeView = null
        speedLimitView = null
        speedometerView = null
        clockView = null
        currentDisplayId = null
        stopClockTicker()
    }

    private fun clearOverlayForDisable() {
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
        speedLimitView?.visibility = View.GONE
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
        val previewClock = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_CLOCK ||
                previewShowOthers
            )
        val navAllowed = navEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_NAV)
        val speedAllowed = speedEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_SPEED)
        val speedometerAllowed = speedometerEnabled || (showPreview && target == OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER)
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

        setTextOrHide(primary, primaryText)
        setTextOrHide(secondary, secondaryText)
        setTextOrHide(time, timeText)
        updateSpeedLimit(speedLimitText, previewSpeed, overspeed)
        speedometer?.let { setTextOrHide(it, speedometerText) }
        updateManeuver(state.maneuverBitmap, previewNav)
        if (clock != null) {
            updateClockText()
        }

        val navHasContent = primaryText.isNotBlank() ||
            secondaryText.isNotBlank() ||
            timeText.isNotBlank() ||
            state.maneuverBitmap != null
        val navVisible = if (showPreview) previewNav else navHasContent
        val speedVisible = if (showPreview) previewSpeed else state.speedLimit.isNotBlank()
        val speedometerVisible = if (showPreview) previewSpeedometer else state.speedKmh != null
        val clockVisible = if (showPreview) previewClock else true
        navContainer?.visibility = if (navAllowed && navVisible) View.VISIBLE else View.GONE
        speedLimitView?.visibility = if (speedAllowed && speedVisible) View.VISIBLE else View.GONE
        speedometer?.visibility = if (speedometerAllowed && speedometerVisible) View.VISIBLE else View.GONE
        clock?.visibility = if (clockAllowed && clockVisible) View.VISIBLE else View.GONE
        container?.visibility = View.VISIBLE
        applyLayout()
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

    private fun parseSpeedLimitValue(speedLimit: String): Int? {
        val match = speedLimitNumberRegex.find(speedLimit)
        return match?.value?.toIntOrNull()
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
        speedLimitView?.let {
            positionView(it, speedPositionDp, speedScale, speedAlpha, metrics.density, containerWidth, containerHeight)
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
