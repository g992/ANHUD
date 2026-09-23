package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
import com.g992.anhud.hudbridge.HudBridgeFrameSink
import com.g992.anhud.hudbridge.HudBridgeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class HudOverlayController(private val context: Context) {
    companion object {
        private const val CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA = 0.35f
        private const val CLOCK_TICK_MS = 5_000L
        private const val MAP_HIDE_BY_MANEUVER_DELAY_MS = 5_000L
        private const val DISPLAY_CHANGE_REFRESH_DELAY_MS = 500L
        private const val DISPLAY_RETRY_DELAY_MS = 2_000L
        private const val DISPLAY_RETRY_MAX_ATTEMPTS = 100
        private const val SPEED_LIMIT_TEXT_SIZE_SP = 16.8f
        private const val SPEED_LIMIT_ALERT_TEXT_SIZE_SP = 15.5f
        private const val NAV_PRIMARY_TEXT_SIZE_SP = 18f
        private const val NAV_SECONDARY_TEXT_SIZE_SP = 14f
        private const val NAV_TIME_TEXT_SIZE_SP = 12f
        private const val LANE_GUIDANCE_DISTANCE_TEXT_SIZE_SP = 14f
        private const val ROAD_CAMERA_TEXT_SIZE_SP = 14f
        private const val NAV_TEXT_SCALE_MIN = 1f
        private const val NAV_TEXT_SCALE_MAX = 3f
        private const val SPEED_TEXT_SCALE_MIN = 0.5f
        private const val SPEED_TEXT_SCALE_MAX = 2f
        private const val PREVIEW_HUDSPEED_CAM_TYPE = 1
        private const val PREVIEW_HUDSPEED_LIMIT_VALUE = 100
        private const val PREVIEW_HUDSPEED_CAM_FLAG = 3
        private const val HUDSPEED_HIDE_DISTANCE_METERS = 50
        private const val HUDSPEED_OVERSPEED_PADDING_DP = 8f
        private const val PREVIEW_SPEED_LIMIT_TEXT_SCALE = 1.3f
        private const val SPEEDOMETER_UNIT_RELATIVE_SIZE = 1f / 3f
        private const val TURN_SIGNAL_BLINK_INTERVAL_MS = 400L
        private const val CLEAR_BEFORE_REDRAW_DELAY_MS = 32L
        private const val HUD_OVERLAY_TAG = "HudOverlayController"
    }
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var displayChangeRefreshPending = false
    private val displayChangeRefreshRunnable = Runnable {
        displayChangeRefreshPending = false
        refresh()
    }
    private var displayRetryAttempt = 0
    private var displayRetryRunnable: Runnable? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (isBridgeDisplay(displayId)) return
            requestDisplayChangeRefresh()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (isBridgeDisplay(displayId)) return
            requestDisplayChangeRefresh()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (isBridgeDisplay(displayId)) return
            requestDisplayChangeRefresh()
        }
    }
    private val clockFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var navContainer: LinearLayout? = null
    private var maneuverContainer: FrameLayout? = null
    private var maneuverView: ImageView? = null
    private var maneuverLabel: TextView? = null
    private var laneGuidanceContainer: LinearLayout? = null
    private var laneGuidanceImageView: ImageView? = null
    private var laneGuidancePlaceholderView: TextView? = null
    private var laneGuidanceDistanceView: TextView? = null
    private var arrowContainer: FrameLayout? = null
    private var arrowView: ImageView? = null
    private var arrowLabel: TextView? = null
    private var primaryView: TextView? = null
    private var secondaryView: TextView? = null
    private var timeView: TextView? = null
    private var speedLimitView: OutlinedTextView? = null
    private var speedometerView: TextView? = null
    private var hudSpeedContainer: FrameLayout? = null
    private var hudSpeedContent: FrameLayout? = null
    private var hudSpeedOverspeedView: View? = null
    private var hudSpeedFullLayout: LinearLayout? = null
    private var hudSpeedFullIcon: ImageView? = null
    private var hudSpeedFullGpsBadge: ImageView? = null
    private var hudSpeedFullRight: LinearLayout? = null
    private var hudSpeedFullDirection: ImageView? = null
    private var hudSpeedFullDistance: TextView? = null
    private var hudSpeedFullLimit: TextView? = null
    private var hudSpeedCompactLayout: LinearLayout? = null
    private var hudSpeedCompactLeft: LinearLayout? = null
    private var hudSpeedCompactRight: LinearLayout? = null
    private var hudSpeedCompactIcon: ImageView? = null
    private var hudSpeedCompactGpsBadge: ImageView? = null
    private var hudSpeedCompactDirection: ImageView? = null
    private var hudSpeedCompactDistance: TextView? = null
    private var strelkaImageView: ImageView? = null
    private var hudSpeedActiveLayout: View? = null
    private var roadCameraContainer: LinearLayout? = null
    private var roadCameraIconView: ImageView? = null
    private var roadCameraDistanceView: TextView? = null
    private var trafficLightContainer: LinearLayout? = null
    private var turnSignalsContainer: LinearLayout? = null
    private var turnSignalLeftView: ImageView? = null
    private var turnSignalRightView: ImageView? = null
    private var clockView: TextView? = null
    private var mapContainerView: FrameLayout? = null
    private var mapContentView: FrameLayout? = null
    private var mapTripStatusView: MapTripStatusView? = null
    private var hudMapController: HudMapController? = null
    private var mapPlaceholderView: FrameLayout? = null
    private var mapPlaceholderIconView: ImageView? = null
    private var mapPlaceholderLabelView: TextView? = null
    private var customBlocksLayer: FrameLayout? = null
    private val customBlockViewHolders = linkedMapOf<String, CustomBlockViewFactory.ViewHolder>()
    private var customBlocksDocument: CustomBlocksDocument = CustomBlocksDocument()
    private var customBlockRenderStates: Map<String, CustomBlockRenderState> = emptyMap()
    private var currentDisplayId: Int? = null
    private var lastState: NavigationHudState = NavigationHudState()
    private var lastRenderSignature: RenderSignature? = null
    private var lastMapDebugKey: String? = null
    private var trafficLightPreviewArrow: Bitmap? = null
    private var laneGuidanceHudBitmapSourceToken: Int = Int.MIN_VALUE
    private var laneGuidanceHudBitmapSourceGenId: Int = -1
    private var laneGuidanceHudBitmapSourceWidth: Int = 0
    private var laneGuidanceHudBitmapSourceHeight: Int = 0
    private var laneGuidanceHudBitmap: Bitmap? = null
    private var containerPositionDp: PointF = OverlayPrefs.containerPositionDp(context)
    private var containerWidthDp: Float = OverlayPrefs.containerSizeDp(context).x
    private var containerHeightDp: Float = OverlayPrefs.containerSizeDp(context).y
    private var mapPositionDp: PointF = OverlayPrefs.mapPositionDp(context)
    private var mapWidthDp: Float = OverlayPrefs.mapSizeDp(context).x
    private var mapHeightDp: Float = OverlayPrefs.mapSizeDp(context).y
    private var navPositionDp: PointF = OverlayPrefs.navPositionDp(context)
    private var navWidthDp: Float = OverlayPrefs.navWidthDp(context)
    private var laneGuidancePositionDp: PointF = OverlayPrefs.laneGuidancePositionDp(context)
    private var arrowPositionDp: PointF = OverlayPrefs.arrowPositionDp(context)
    private var speedPositionDp: PointF = OverlayPrefs.speedPositionDp(context)
    private var hudAlertSource: OverlayPrefs.HudAlertSource = OverlayPrefs.hudAlertSource(context)
    private var hudSpeedPositionDp: PointF = OverlayPrefs.hudSpeedPositionDp(context)
    private var strelkaPositionDp: PointF = OverlayPrefs.strelkaPositionDp(context)
    private var roadCameraPositionDp: PointF = OverlayPrefs.roadCameraPositionDp(context)
    private var trafficLightPositionDp: PointF = OverlayPrefs.trafficLightPositionDp(context)
    private var speedometerPositionDp: PointF = OverlayPrefs.speedometerPositionDp(context)
    private var turnSignalsPositionDp: PointF = OverlayPrefs.turnSignalsPositionDp(context)
    private var clockPositionDp: PointF = OverlayPrefs.clockPositionDp(context)
    private var navScale: Float = OverlayPrefs.navScale(context)
    private var laneGuidanceScale: Float = OverlayPrefs.laneGuidanceScale(context)
    private var navTextScale: Float = OverlayPrefs.navTextScale(context)
    private var speedTextScale: Float = OverlayPrefs.speedTextScale(context)
    private var arrowScale: Float = OverlayPrefs.arrowScale(context)
    private var speedScale: Float = OverlayPrefs.speedScale(context)
    private var hudSpeedScale: Float = OverlayPrefs.hudSpeedScale(context)
    private var strelkaScale: Float = OverlayPrefs.strelkaScale(context)
    private var roadCameraScale: Float = OverlayPrefs.roadCameraScale(context)
    private var trafficLightScale: Float = OverlayPrefs.trafficLightScale(context)
    private var speedometerScale: Float = OverlayPrefs.speedometerScale(context)
    private var turnSignalsScale: Float = OverlayPrefs.turnSignalsScale(context)
    private var turnSignalsSpacingDp: Float = OverlayPrefs.turnSignalsSpacingDp(context)
    private var turnSignalsIconStyle: Int = OverlayPrefs.turnSignalsIconStyle(context)
    private var clockScale: Float = OverlayPrefs.clockScale(context)
    private var navAlpha: Float = OverlayPrefs.navAlpha(context)
    private var laneGuidanceAlpha: Float = OverlayPrefs.laneGuidanceAlpha(context)
    private var arrowAlpha: Float = OverlayPrefs.arrowAlpha(context)
    private var speedAlpha: Float = OverlayPrefs.speedAlpha(context)
    private var hudSpeedAlpha: Float = OverlayPrefs.hudSpeedAlpha(context)
    private var strelkaAlpha: Float = OverlayPrefs.strelkaAlpha(context)
    private var roadCameraAlpha: Float = OverlayPrefs.roadCameraAlpha(context)
    private var trafficLightAlpha: Float = OverlayPrefs.trafficLightAlpha(context)
    private var speedometerAlpha: Float = OverlayPrefs.speedometerAlpha(context)
    private var turnSignalsAlpha: Float = OverlayPrefs.turnSignalsAlpha(context)
    private var clockAlpha: Float = OverlayPrefs.clockAlpha(context)
    private var containerAlpha: Float = OverlayPrefs.containerAlpha(context)
    private var mapAlpha: Float = OverlayPrefs.mapAlpha(context)
    private var navEnabled: Boolean = OverlayPrefs.navEnabled(context)
    private var laneGuidanceEnabled: Boolean = OverlayPrefs.laneGuidanceEnabled(context)
    private var arrowEnabled: Boolean = OverlayPrefs.arrowEnabled(context)
    private var arrowOnlyWhenNoIcon: Boolean = OverlayPrefs.arrowOnlyWhenNoIcon(context)
    private var speedEnabled: Boolean = OverlayPrefs.speedEnabled(context)
    private var hudSpeedEnabled: Boolean = OverlayPrefs.hudSpeedEnabled(context)
    private var hudSpeedLimitEnabled: Boolean = OverlayPrefs.hudSpeedLimitEnabled(context)
    private var hudSpeedLimitAlertEnabled: Boolean = OverlayPrefs.hudSpeedLimitAlertEnabled(context)
    private var hudSpeedLimitAlertThreshold: Int = OverlayPrefs.hudSpeedLimitAlertThreshold(context)
    private var roadCameraEnabled: Boolean = OverlayPrefs.roadCameraEnabled(context)
    private var trafficLightEnabled: Boolean = OverlayPrefs.trafficLightEnabled(context)
    private var speedLimitAlertEnabled: Boolean = OverlayPrefs.speedLimitAlertEnabled(context)
    private var trafficLightMaxActive: Int = OverlayPrefs.trafficLightMaxActive(context)
    private var speedLimitAlertThreshold: Int = OverlayPrefs.speedLimitAlertThreshold(context)
    private var speedometerEnabled: Boolean = OverlayPrefs.speedometerEnabled(context)
    private var speedometerShowUnitText: Boolean = OverlayPrefs.speedometerShowUnitText(context)
    private var turnSignalsEnabled: Boolean = OverlayPrefs.turnSignalsEnabled(context)
    private var clockEnabled: Boolean = OverlayPrefs.clockEnabled(context)
    private var mapEnabled: Boolean = OverlayPrefs.mapEnabled(context)
    private var infoMirrorStarsheep7Enabled: Boolean = OverlayPrefs.infoMirrorStarsheep7Enabled(context)
    private var infoMirrorGalaxyEnabled: Boolean = OverlayPrefs.infoMirrorGalaxyEnabled(context)
    private var previewMode: Boolean = false
    private var previewTarget: String? = null
    private var previewShowOthers: Boolean = false
    private var clearOnDisablePending: Boolean = false
    private var clearBeforeRedrawPending: Boolean = false
    private var delayedRedrawRunnable: Runnable? = null
    private val speedLimitNumberRegex = Regex("\\d+")
    private var hudSpeedHideRunnable: Runnable? = null
    private var turnSignalBlinkRunnable: Runnable? = null
    private var turnSignalBlinkVisible: Boolean = true
    private var turnSignalLeftActive: Boolean = false
    private var turnSignalRightActive: Boolean = false
    private var turnSignalsPreviewMode: Boolean = false
    private var laneGuidanceHadVisibleContent: Boolean = false
    private var mapHadVisibleContent: Boolean = false
    private var dynamicHideTurnSpeedBucket: OverlayPrefs.DynamicHideTurnSpeedBucket? = null
    private var hideMapByManeuverActive: Boolean = false
    private var pendingMapHideByManeuverRunnable: Runnable? = null
    private var mapTransparentFillPending: Boolean = false
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClockText()
            handler.postDelayed(this, CLOCK_TICK_MS)
        }
    }
    private val strelkaPreviewBitmap by lazy(LazyThreadSafetyMode.NONE) {
        StrelkaPreviewBitmapFactory.create(context)
    }

    init {
        displayManager?.registerDisplayListener(displayListener, handler)
    }

    fun refresh() {
        handler.post {
            val bridgeDisplay = HudBridgeManager.renderDisplay()
            if (!OverlayPrefs.isEnabled(context) || (bridgeDisplay == null && !Settings.canDrawOverlays(context))) {
                clearDisplayRetry()
                clearOverlayForDisable()
                return@post
            }
            val targetDisplayId = OverlayPrefs.displayId(context)
            val display = bridgeDisplay
                ?: HudDisplayUtils.resolveDisplay(context, targetDisplayId, allowFallback = false)
            if (display == null) {
                removeOverlay()
                scheduleDisplayRetry(targetDisplayId)
                return@post
            }
            clearDisplayRetry()
            if (overlayView != null && currentDisplayId == display.displayId) {
                if (clearBeforeRedrawPending) {
                    applyLayoutInternal()
                    clearOverlayForRedraw()
                    return@post
                }
                applyLayout()
                applyState(lastState)
                return@post
            }
            removeOverlay()
            createOverlay(display)
            if (clearBeforeRedrawPending) {
                applyLayoutInternal()
                clearOverlayForRedraw()
                return@post
            }
            applyLayout()
            applyState(lastState)
        }
    }

    fun updateNavigation(state: NavigationHudState) {
        handler.post {
            lastState = state
            if (clearBeforeRedrawPending) {
                lastRenderSignature = null
                return@post
            }
            val signature = buildRenderSignature(state)
            if (signature == lastRenderSignature) {
                return@post
            }
            lastRenderSignature = signature
            applyState(state)
        }
    }

    fun updateCustomBlocks(
        document: CustomBlocksDocument,
        states: Map<String, CustomBlockRenderState>
    ) {
        handler.post {
            customBlocksDocument = document
            customBlockRenderStates = states
            syncCustomBlockViews()
            applyCustomBlockStates()
            applyState(lastState)
        }
    }

    private fun shouldPreviewBlock(blockTarget: String, enabled: Boolean): Boolean {
        return HudOverlayPreviewPolicy.shouldShowBlock(
            previewMode = previewMode,
            previewTarget = previewTarget,
            previewShowOthers = previewShowOthers,
            blockTarget = blockTarget,
            enabled = enabled
        )
    }

    private fun isPreviewTarget(blockTarget: String): Boolean {
        return HudOverlayPreviewPolicy.isTargeted(
            previewMode = previewMode,
            previewTarget = previewTarget,
            blockTarget = blockTarget
        )
    }

    private fun isSharedAlertPreviewTarget(): Boolean {
        return isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED) ||
            isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_STRELKA)
    }

    private fun resolveActiveHudAlertSource(): OverlayPrefs.HudAlertSource {
        return when {
            isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_STRELKA) ->
                OverlayPrefs.HudAlertSource.STRELKA
            isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED) ->
                OverlayPrefs.HudAlertSource.HUDSPEED
            else -> hudAlertSource
        }
    }

    fun shouldRefreshForMapRouteTelemetry(): Boolean {
        val previewMap = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_MAP, mapEnabled)
        val previewLaneGuidance = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_LANE_GUIDANCE,
            laneGuidanceEnabled
        )
        return mapEnabled || laneGuidanceEnabled || previewMap || previewLaneGuidance
    }

    private fun buildRenderSignature(state: NavigationHudState): RenderSignature {
        val showPreview = previewMode
        val activeHudAlertSource = resolveActiveHudAlertSource()
        val previewNav = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_NAV, navEnabled)
        val previewLaneGuidance = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_LANE_GUIDANCE,
            laneGuidanceEnabled
        )
        val previewArrow = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_ARROW, arrowEnabled)
        val previewSpeed = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_SPEED, speedEnabled)
        val previewSpeedometer = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER,
            speedometerEnabled
        )
        val previewTurnSignals = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_TURN_SIGNALS,
            turnSignalsEnabled
        )
        val previewHudSpeed = activeHudAlertSource == OverlayPrefs.HudAlertSource.HUDSPEED &&
            shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED, hudSpeedEnabled)
        val previewStrelka = activeHudAlertSource == OverlayPrefs.HudAlertSource.STRELKA &&
            shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_STRELKA, hudSpeedEnabled)
        val previewTrafficLight = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT,
            trafficLightEnabled
        )
        val hideNavigationByDistance = shouldHideManeuverByDistance(state, showPreview)

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
            context.getString(
                if (OverlayPrefs.navShowDistance(context)) R.string.preview_time_text else R.string.preview_time_text_no_distance
            )
        } else {
            buildTimeLine(state)
        }
        val speedValue = state.speedKmh?.coerceAtLeast(0)
        val speedometerBaseText = if (previewSpeedometer) {
            context.getString(R.string.preview_speedometer_text)
        } else {
            speedValue?.toString().orEmpty()
        }
        val speedometerText = formatSpeedometerText(speedometerBaseText)
        val speedLimitText = if (previewSpeed) {
            context.getString(R.string.preview_speed_limit_text)
        } else {
            state.speedLimit
        }
        val roadCameraDistanceText = state.roadCameraDistance.orEmpty()
        val roadCameraBitmap = state.roadCameraIcon
        val bitmap = state.maneuverBitmap
        val laneGuidanceManeuver = MapRouteTelemetryStore.current().laneManeuver
        val previewMap = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_MAP, mapEnabled)
        val includeTripStatusInSignature = previewMap || mapEnabled || mapHadVisibleContent
        val tripStatusDistanceText = if (!includeTripStatusInSignature) {
            ""
        } else if (previewMap) {
            context.getString(R.string.preview_distance_text)
        } else {
            state.distance.trim()
        }
        val tripStatusArrivalText = if (!includeTripStatusInSignature) {
            ""
        } else if (previewMap) {
            context.getString(R.string.preview_trip_status_arrival_text)
        } else {
            resolveArrivalText(state)
        }
        val tripStatusTimeText = if (!includeTripStatusInSignature) {
            ""
        } else if (previewMap) {
            context.getString(R.string.preview_trip_status_eta_text)
        } else {
            state.time.trim()
        }
        val tripStatusBitmap = if (includeTripStatusInSignature) {
            state.tripStatusBitmap?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        } else {
            null
        }
        val trafficLightSignatures = state.trafficLights.map { light ->
            TrafficLightSignature(
                id = light.id,
                color = light.color,
                countdownText = light.countdownText,
                arrowDirection = light.arrowDirection,
                position = light.position,
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
            laneGuidanceToken = laneGuidanceManeuver?.token ?: -1,
            laneGuidanceGenId = laneGuidanceManeuver?.bitmap?.generationId ?: -1,
            laneGuidanceWidth = laneGuidanceManeuver?.bitmap?.width ?: 0,
            laneGuidanceHeight = laneGuidanceManeuver?.bitmap?.height ?: 0,
            laneGuidanceDistanceMeters = laneGuidanceManeuver?.distanceMeters ?: -1,
            laneGuidanceShowDistance = OverlayPrefs.laneGuidanceShowDistance(context),
            tripStatusDistanceText = tripStatusDistanceText,
            tripStatusArrivalText = tripStatusArrivalText,
            tripStatusTimeText = tripStatusTimeText,
            tripStatusGenId = tripStatusBitmap?.generationId ?: -1,
            tripStatusWidth = tripStatusBitmap?.width ?: 0,
            tripStatusHeight = tripStatusBitmap?.height ?: 0,
            maneuverGenId = bitmap?.generationId ?: -1,
            maneuverWidth = bitmap?.width ?: 0,
            maneuverHeight = bitmap?.height ?: 0,
            nativeTurnId = state.nativeTurnId,
            previewNav = previewNav,
            previewLaneGuidance = previewLaneGuidance,
            previewArrow = previewArrow,
            previewSpeed = previewSpeed,
            previewSpeedometer = previewSpeedometer,
            previewTurnSignals = previewTurnSignals,
            turnSignalLeft = state.turnSignalLeft,
            turnSignalRight = state.turnSignalRight,
            turnSignalHazard = state.turnSignalHazard,
            hudSpeedHasCamera = state.hudSpeedHasCamera,
            hudSpeedHasGps = state.hudSpeedHasGps,
            hudSpeedDistanceMeters = state.hudSpeedDistanceMeters,
            hudSpeedCamType = state.hudSpeedCamType,
            hudSpeedCamFlag = state.hudSpeedCamFlag,
            hudSpeedLimit1 = state.hudSpeedLimit1,
            hudSpeedUpdatedAt = state.hudSpeedUpdatedAt,
            strelkaActive = state.strelkaActive,
            strelkaGenId = state.strelkaBitmap?.generationId ?: -1,
            strelkaWidth = state.strelkaBitmap?.width ?: 0,
            strelkaHeight = state.strelkaBitmap?.height ?: 0,
            strelkaUpdatedAt = state.strelkaUpdatedAt,
            hudAlertSource = activeHudAlertSource,
            previewHudSpeed = previewHudSpeed,
            previewStrelka = previewStrelka,
            previewTrafficLight = previewTrafficLight,
            hideNavigationByDistance = hideNavigationByDistance,
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
        val laneGuidanceToken: Int,
        val laneGuidanceGenId: Int,
        val laneGuidanceWidth: Int,
        val laneGuidanceHeight: Int,
        val laneGuidanceDistanceMeters: Int,
        val laneGuidanceShowDistance: Boolean,
        val tripStatusDistanceText: String,
        val tripStatusArrivalText: String,
        val tripStatusTimeText: String,
        val tripStatusGenId: Int,
        val tripStatusWidth: Int,
        val tripStatusHeight: Int,
        val maneuverGenId: Int,
        val maneuverWidth: Int,
        val maneuverHeight: Int,
        val nativeTurnId: Int?,
        val previewNav: Boolean,
        val previewLaneGuidance: Boolean,
        val previewArrow: Boolean,
        val previewSpeed: Boolean,
        val previewSpeedometer: Boolean,
        val previewTurnSignals: Boolean,
        val turnSignalLeft: Boolean,
        val turnSignalRight: Boolean,
        val turnSignalHazard: Boolean,
        val hudSpeedHasCamera: Boolean,
        val hudSpeedHasGps: Boolean,
        val hudSpeedDistanceMeters: Int?,
        val hudSpeedCamType: Int?,
        val hudSpeedCamFlag: Int?,
        val hudSpeedLimit1: Int?,
        val hudSpeedUpdatedAt: Long,
        val strelkaActive: Boolean,
        val strelkaGenId: Int,
        val strelkaWidth: Int,
        val strelkaHeight: Int,
        val strelkaUpdatedAt: Long,
        val hudAlertSource: OverlayPrefs.HudAlertSource,
        val previewHudSpeed: Boolean,
        val previewStrelka: Boolean,
        val previewTrafficLight: Boolean,
        val hideNavigationByDistance: Boolean,
        val trafficLights: List<TrafficLightSignature>
    )

    private data class TrafficLightSignature(
        val id: Int,
        val color: String,
        val countdownText: String,
        val arrowDirection: String,
        val position: Int,
        val arrowGenId: Int,
        val arrowWidth: Int,
        val arrowHeight: Int
    )

    fun clearNavigation() {
        handler.post {
            val container = overlayView ?: return@post
            // Hide navigation-specific elements.
            navContainer?.visibility = View.GONE
            laneGuidanceContainer?.visibility = View.GONE
            arrowContainer?.visibility = View.GONE
            trafficLightContainer?.visibility = View.GONE
            // Keep clock visible only when enabled in settings.
            clockView?.visibility = if (clockEnabled) View.VISIBLE else View.GONE
            if (!roadCameraEnabled) {
                roadCameraContainer?.visibility = View.GONE
            }
            if (!hudSpeedEnabled) {
                hudSpeedContainer?.visibility = View.GONE
            }
            dynamicHideTurnSpeedBucket = null
            cancelMapHideByManeuverDelay()
            hideMapByManeuverActive = false
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
        mapPosition: PointF?,
        mapWidthDp: Float?,
        mapHeightDp: Float?,
        navPosition: PointF?,
        navWidthDp: Float?,
        laneGuidancePosition: PointF?,
        arrowPosition: PointF?,
        speedPosition: PointF?,
        hudAlertSource: String?,
        hudSpeedPosition: PointF?,
        strelkaPosition: PointF?,
        roadCameraPosition: PointF?,
        trafficLightPosition: PointF?,
        speedometerPosition: PointF?,
        turnSignalsPosition: PointF?,
        clockPosition: PointF?,
        navScale: Float?,
        laneGuidanceScale: Float?,
        navTextScale: Float?,
        speedTextScale: Float?,
        arrowScale: Float?,
        speedScale: Float?,
        hudSpeedScale: Float?,
        strelkaScale: Float?,
        roadCameraScale: Float?,
        trafficLightScale: Float?,
        speedometerScale: Float?,
        turnSignalsScale: Float?,
        turnSignalsSpacingDp: Float?,
        turnSignalsIconStyle: Int?,
        clockScale: Float?,
        navAlpha: Float?,
        laneGuidanceAlpha: Float?,
        arrowAlpha: Float?,
        speedAlpha: Float?,
        hudSpeedAlpha: Float?,
        strelkaAlpha: Float?,
        roadCameraAlpha: Float?,
        trafficLightAlpha: Float?,
        speedometerAlpha: Float?,
        turnSignalsAlpha: Float?,
        clockAlpha: Float?,
        containerAlpha: Float?,
        mapAlpha: Float?,
        navEnabled: Boolean?,
        laneGuidanceEnabled: Boolean?,
        arrowEnabled: Boolean?,
        arrowOnlyWhenNoIcon: Boolean?,
        speedEnabled: Boolean?,
        hudSpeedEnabled: Boolean?,
        hudSpeedLimitEnabled: Boolean?,
        hudSpeedLimitAlertEnabled: Boolean?,
        hudSpeedLimitAlertThreshold: Int?,
        roadCameraEnabled: Boolean?,
        trafficLightEnabled: Boolean?,
        speedLimitAlertEnabled: Boolean?,
        speedLimitAlertThreshold: Int?,
        speedometerEnabled: Boolean?,
        speedometerShowUnitText: Boolean?,
        turnSignalsEnabled: Boolean?,
        clockEnabled: Boolean?,
        trafficLightMaxActive: Int?,
        mapEnabled: Boolean?,
        preview: Boolean? = null,
        previewTarget: String? = null,
        previewShowOthers: Boolean? = null,
        infoMirrorStarsheep7Enabled: Boolean? = null,
        infoMirrorGalaxyEnabled: Boolean? = null
    ) {
        handler.post {
            val shouldClearForPreviewTransition = (preview != null && previewMode != preview) ||
                (previewTarget != null && this.previewTarget != previewTarget) ||
                (previewShowOthers != null && this.previewShowOthers != previewShowOthers)

            if (containerPosition != null) {
                containerPositionDp = containerPosition
            }
            if (containerWidthDp != null) {
                this.containerWidthDp = containerWidthDp.coerceAtLeast(0f)
            }
            if (containerHeightDp != null) {
                this.containerHeightDp = containerHeightDp.coerceAtLeast(0f)
            }
            if (mapPosition != null) {
                mapPositionDp = mapPosition
            }
            if (mapWidthDp != null) {
                this.mapWidthDp = mapWidthDp.coerceAtLeast(OverlayPrefs.MAP_MIN_SIZE_DP)
            }
            if (mapHeightDp != null) {
                this.mapHeightDp = mapHeightDp.coerceAtLeast(OverlayPrefs.MAP_MIN_SIZE_DP)
            }
            if (navPosition != null) {
                navPositionDp = navPosition
            }
            if (navWidthDp != null) {
                this.navWidthDp = navWidthDp.coerceAtLeast(OverlayPrefs.NAV_WIDTH_MIN_DP)
            }
            if (laneGuidancePosition != null) {
                laneGuidancePositionDp = laneGuidancePosition
            }
            if (arrowPosition != null) {
                arrowPositionDp = arrowPosition
            }
            if (speedPosition != null) {
                speedPositionDp = speedPosition
            }
            if (hudAlertSource != null) {
                OverlayPrefs.HudAlertSource.entries.firstOrNull { it.storedValue == hudAlertSource }
                    ?.let { this.hudAlertSource = it }
            }
            if (hudSpeedPosition != null) {
                hudSpeedPositionDp = hudSpeedPosition
            }
            if (strelkaPosition != null) {
                strelkaPositionDp = strelkaPosition
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
            if (turnSignalsPosition != null) {
                turnSignalsPositionDp = turnSignalsPosition
            }
            if (clockPosition != null) {
                clockPositionDp = clockPosition
            }
            if (navScale != null) {
                this.navScale = navScale.coerceAtLeast(0f)
            }
            if (laneGuidanceScale != null) {
                this.laneGuidanceScale = laneGuidanceScale.coerceAtLeast(0f)
            }
            if (navTextScale != null) {
                this.navTextScale = navTextScale.coerceIn(NAV_TEXT_SCALE_MIN, NAV_TEXT_SCALE_MAX)
                applyNavTextScale()
            }
            if (speedTextScale != null) {
                this.speedTextScale = speedTextScale.coerceIn(SPEED_TEXT_SCALE_MIN, SPEED_TEXT_SCALE_MAX)
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
            if (strelkaScale != null) {
                this.strelkaScale = strelkaScale.coerceAtLeast(0f)
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
            if (turnSignalsScale != null) {
                this.turnSignalsScale = turnSignalsScale.coerceAtLeast(0f)
            }
            if (turnSignalsSpacingDp != null) {
                this.turnSignalsSpacingDp = turnSignalsSpacingDp.coerceAtLeast(OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP)
            }
            this.turnSignalsSpacingDp = this.turnSignalsSpacingDp.coerceIn(
                OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP,
                resolveTurnSignalsMaxSpacingDp()
            )
            if (turnSignalsIconStyle != null) {
                this.turnSignalsIconStyle = TurnSignalIcons.sanitize(turnSignalsIconStyle)
                applyTurnSignalsIconStyle()
            }
            if (clockScale != null) {
                this.clockScale = clockScale.coerceAtLeast(0f)
            }
            if (navAlpha != null) {
                this.navAlpha = navAlpha.coerceIn(0f, 1f)
            }
            if (laneGuidanceAlpha != null) {
                this.laneGuidanceAlpha = laneGuidanceAlpha.coerceIn(0f, 1f)
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
            if (strelkaAlpha != null) {
                this.strelkaAlpha = strelkaAlpha.coerceIn(0f, 1f)
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
            if (turnSignalsAlpha != null) {
                this.turnSignalsAlpha = turnSignalsAlpha.coerceIn(0f, 1f)
            }
            if (clockAlpha != null) {
                this.clockAlpha = clockAlpha.coerceIn(0f, 1f)
            }
            if (containerAlpha != null) {
                this.containerAlpha = containerAlpha.coerceIn(0f, 1f)
            }
            if (mapAlpha != null) {
                this.mapAlpha = mapAlpha.coerceIn(0f, 1f)
            }
            if (navEnabled != null) {
                this.navEnabled = navEnabled
            }
            if (laneGuidanceEnabled != null) {
                this.laneGuidanceEnabled = laneGuidanceEnabled
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
            if (hudSpeedLimitEnabled != null) {
                this.hudSpeedLimitEnabled = hudSpeedLimitEnabled
            }
            if (hudSpeedLimitAlertEnabled != null) {
                this.hudSpeedLimitAlertEnabled = hudSpeedLimitAlertEnabled
            }
            if (hudSpeedLimitAlertThreshold != null) {
                this.hudSpeedLimitAlertThreshold = hudSpeedLimitAlertThreshold
                    .coerceIn(0, OverlayPrefs.SPEED_LIMIT_ALERT_THRESHOLD_MAX)
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
            if (speedometerShowUnitText != null) {
                this.speedometerShowUnitText = speedometerShowUnitText
            }
            if (turnSignalsEnabled != null) {
                this.turnSignalsEnabled = turnSignalsEnabled
            }
            if (clockEnabled != null) {
                this.clockEnabled = clockEnabled
            }
            if (trafficLightMaxActive != null) {
                this.trafficLightMaxActive = trafficLightMaxActive.coerceAtLeast(1)
            }
            if (mapEnabled != null) {
                this.mapEnabled = mapEnabled
            }
            if (infoMirrorStarsheep7Enabled != null) {
                this.infoMirrorStarsheep7Enabled = infoMirrorStarsheep7Enabled
            }
            if (infoMirrorGalaxyEnabled != null) {
                this.infoMirrorGalaxyEnabled = infoMirrorGalaxyEnabled
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
            if (shouldClearForPreviewTransition && scheduleClearBeforeRedraw()) {
                return@post
            }
            applyLayout()
            applyState(lastState)
        }
    }

    fun destroy() {
        handler.post {
            clearDisplayRetry()
            handler.removeCallbacks(displayChangeRefreshRunnable)
            displayChangeRefreshPending = false
            stopTurnSignalBlinking()
            cancelMapHideByManeuverDelay()
            displayManager?.unregisterDisplayListener(displayListener)
            removeOverlay()
        }
    }

    private fun isBridgeDisplay(displayId: Int): Boolean {
        return displayManager?.getDisplay(displayId)?.name == HudBridgeFrameSink.VIRTUAL_DISPLAY_NAME
    }

    private fun requestDisplayChangeRefresh() {
        if (displayChangeRefreshPending) {
            return
        }
        displayChangeRefreshPending = true
        handler.postDelayed(displayChangeRefreshRunnable, DISPLAY_CHANGE_REFRESH_DELAY_MS)
    }

    private fun scheduleDisplayRetry(targetDisplayId: Int) {
        if (displayRetryRunnable != null) {
            return
        }
        if (displayRetryAttempt >= DISPLAY_RETRY_MAX_ATTEMPTS) {
            return
        }
        displayRetryAttempt += 1
        val runnable = Runnable {
            displayRetryRunnable = null
            refresh()
        }
        displayRetryRunnable = runnable
        handler.postDelayed(runnable, DISPLAY_RETRY_DELAY_MS)
        if (displayRetryAttempt == 1) {
            UiLogStore.append(
                LogCategory.SYSTEM,
                "Оверлей: экран $targetDisplayId недоступен, повтор через ${DISPLAY_RETRY_DELAY_MS / 1000}s"
            )
        }
    }

    private fun clearDisplayRetry() {
        displayRetryRunnable?.let { handler.removeCallbacks(it) }
        displayRetryRunnable = null
        displayRetryAttempt = 0
    }

    private fun createOverlay(display: android.view.Display) {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java)
        if (wm == null) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: WindowManager недоступен")
            return
        }
        val metrics = displayContext.resources.displayMetrics
        val iconSize = (48 * metrics.density).roundToInt()
        val iconMargin = (8 * metrics.density).roundToInt()
        val speedSize = (40 * metrics.density).roundToInt()
        val (containerWidthPx, containerHeightPx) = resolveContainerSizePx(metrics)

        val root = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        val navWidthPx = resolveScaledLayoutWidthPx(navWidthDp, navScale, metrics.density)
        val navBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                navWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            pivotX = 0f
            pivotY = 0f
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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAV_SECONDARY_TEXT_SIZE_SP)
            setSingleLine(false)
        }

        val timeText = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
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

        val laneGuidancePlaceholderWidth = (96 * metrics.density).roundToInt().coerceAtLeast(1)
        val laneGuidancePlaceholderHeight = (48 * metrics.density).roundToInt().coerceAtLeast(1)
        val laneGuidanceBlock = LinearLayout(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }

        val laneGuidanceImage = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        val laneGuidancePlaceholder = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                laneGuidancePlaceholderWidth,
                laneGuidancePlaceholderHeight
            )
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.position_lane_guidance_block_label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setPadding(8, 8, 8, 8)
        }

        val laneGuidanceDistance = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LANE_GUIDANCE_DISTANCE_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
        }

        laneGuidanceBlock.addView(laneGuidancePlaceholder)
        laneGuidanceBlock.addView(laneGuidanceImage)
        laneGuidanceBlock.addView(laneGuidanceDistance)

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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
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
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_speed_limit)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SPEED_LIMIT_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
        }

        val hudSpeedBlock = FrameLayout(displayContext).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hudSpeedOverspeedViewLocal = View(displayContext).apply {
            visibility = View.GONE
            setBackgroundResource(R.drawable.bg_hudspeed_overspeed)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hudSpeedContentView = FrameLayout(displayContext).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hudSpeedFullLayoutView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hudSpeedFullLeftView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val hudSpeedFullRightView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hudSpeedCompactLayoutView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hudSpeedCompactLeftView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val hudSpeedCompactRightView = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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

        val gpsBadgeSize = (iconSize / 2f).roundToInt().coerceAtLeast(1)
        val gpsBadgeOffsetRight = (6 * metrics.density).roundToInt()

        val hudSpeedFullIconContainer = FrameLayout(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            clipChildren = false
            clipToPadding = false
        }

        val hudSpeedFullIconView = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.cam_type_1)
        }

        val hudSpeedFullGpsBadgeView = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(gpsBadgeSize, gpsBadgeSize).apply {
                gravity = Gravity.CENTER
                marginStart = gpsBadgeOffsetRight
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        val hudSpeedFullLimitTextView = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_hudspeed_limit)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SPEED_LIMIT_TEXT_SIZE_SP)
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
        }

        val hudSpeedFullDistanceTextView = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.preview_hudspeed_distance)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val hudSpeedFullDirectionIconView = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        val hudSpeedCompactIconContainer = FrameLayout(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            clipChildren = false
            clipToPadding = false
        }

        val hudSpeedCompactIconView = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.cam_type_1)
        }

        val hudSpeedCompactGpsBadgeView = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(gpsBadgeSize, gpsBadgeSize).apply {
                gravity = Gravity.CENTER
                marginStart = gpsBadgeOffsetRight
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        val halfIconSize = (iconSize / 2f).roundToInt().coerceAtLeast(1)

        val hudSpeedCompactDirectionIconView = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, halfIconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        val hudSpeedCompactDistanceTextView = TextView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, halfIconSize)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.preview_hudspeed_distance)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val strelkaImageViewLocal = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }

        hudSpeedFullIconContainer.addView(hudSpeedFullIconView)
        hudSpeedFullIconContainer.addView(hudSpeedFullGpsBadgeView)
        hudSpeedFullLeftView.addView(hudSpeedFullIconContainer)
        hudSpeedFullLeftView.addView(hudSpeedFullDirectionIconView)
        hudSpeedFullRightView.addView(hudSpeedFullLimitTextView)
        hudSpeedFullRightView.addView(hudSpeedFullDistanceTextView)
        hudSpeedFullLayoutView.addView(hudSpeedFullLeftView)
        hudSpeedFullLayoutView.addView(hudSpeedFullRightView)

        hudSpeedCompactIconContainer.addView(hudSpeedCompactIconView)
        hudSpeedCompactIconContainer.addView(hudSpeedCompactGpsBadgeView)
        hudSpeedCompactLeftView.addView(hudSpeedCompactIconContainer)
        hudSpeedCompactRightView.addView(hudSpeedCompactDirectionIconView)
        hudSpeedCompactRightView.addView(hudSpeedCompactDistanceTextView)
        hudSpeedCompactLayoutView.addView(hudSpeedCompactLeftView)
        hudSpeedCompactLayoutView.addView(hudSpeedCompactRightView)

        hudSpeedContentView.addView(hudSpeedFullLayoutView)
        hudSpeedContentView.addView(hudSpeedCompactLayoutView)
        hudSpeedContentView.addView(strelkaImageViewLocal)

        hudSpeedBlock.addView(hudSpeedOverspeedViewLocal)
        hudSpeedBlock.addView(hudSpeedContentView)

        val speedometerText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            val speedometerWidthPx = max(
                paint.measureText("888"),
                paint.measureText(displayContext.getString(R.string.speedometer_unit_text))
            ).roundToInt().coerceAtLeast(1)
            minWidth = speedometerWidthPx
            maxWidth = speedometerWidthPx
        }

        val turnSignalsBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        val turnArrowSize = (OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP * metrics.density).roundToInt().coerceAtLeast(1)
        val turnArrowGap = resolveTurnSignalsGapPx(metrics.density)
        val turnSignalLeft = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(turnArrowSize, turnArrowSize).apply {
                marginEnd = turnArrowGap
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val turnSignalRight = ImageView(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(turnArrowSize, turnArrowSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        TurnSignalIcons.applyPair(context, turnSignalLeft, turnSignalRight, turnSignalsIconStyle)
        turnSignalsBlock.addView(turnSignalLeft)
        turnSignalsBlock.addView(turnSignalRight)

        val clockText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val mapBlock = FrameLayout(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            clipChildren = true
            clipToPadding = true
            visibility = View.GONE
        }

        val mapContent = FrameLayout(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }
        val initialMapHeightPx = (mapHeightDp * metrics.density).roundToInt().coerceAtLeast(1)
        val mapTripStatus = MapTripStatusView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                resolveMapTripStatusHeightPx(initialMapHeightPx, false),
                Gravity.BOTTOM
            )
            visibility = View.GONE
        }

        val mapPlaceholder = FrameLayout(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = ColorDrawable(Color.argb(96, 33, 150, 243))
            visibility = View.GONE
        }
        val mapPlaceholderContent = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(4, 4, 4, 4)
        }
        val mapPlaceholderLabel = TextView(displayContext).apply {
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.position_map_block_label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val mapPlaceholderIcon = ImageView(displayContext).apply {
            setImageResource(R.drawable.pin_alerts_speed_camera)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        mapPlaceholderContent.addView(mapPlaceholderLabel)
        mapPlaceholderContent.addView(mapPlaceholderIcon)
        mapPlaceholder.addView(mapPlaceholderContent)

        mapBlock.addView(mapContent)
        mapBlock.addView(mapPlaceholder)
        mapBlock.addView(mapTripStatus)

        root.addView(mapBlock)
        root.addView(navBlock)
        root.addView(laneGuidanceBlock)
        root.addView(arrowBox)
        root.addView(speedText)
        root.addView(hudSpeedBlock)
        root.addView(roadCameraBlock)
        root.addView(trafficLightBlock)
        root.addView(speedometerText)
        root.addView(turnSignalsBlock)
        root.addView(clockText)
        val customLayer = FrameLayout(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }
        root.addView(customLayer)

        val windowType = if (display.displayId == HudBridgeManager.renderDisplay()?.displayId) {
            // Our own private virtual display for the QNX HUD bridge.
            WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val layoutParams = WindowManager.LayoutParams(
            containerWidthPx,
            containerHeightPx,
            windowType,
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
            laneGuidanceContainer = laneGuidanceBlock
            laneGuidanceImageView = laneGuidanceImage
            laneGuidancePlaceholderView = laneGuidancePlaceholder
            laneGuidanceDistanceView = laneGuidanceDistance
            arrowContainer = arrowBox
            arrowView = arrowImage
            arrowLabel = arrowText
            primaryView = primaryText
            secondaryView = secondaryText
            timeView = timeText
            speedLimitView = speedText
            hudSpeedContainer = hudSpeedBlock
            hudSpeedContent = hudSpeedContentView
            hudSpeedOverspeedView = hudSpeedOverspeedViewLocal
            hudSpeedFullLayout = hudSpeedFullLayoutView
            hudSpeedFullIcon = hudSpeedFullIconView
            hudSpeedFullGpsBadge = hudSpeedFullGpsBadgeView
            hudSpeedFullRight = hudSpeedFullRightView
            hudSpeedFullDirection = hudSpeedFullDirectionIconView
            hudSpeedFullDistance = hudSpeedFullDistanceTextView
            hudSpeedFullLimit = hudSpeedFullLimitTextView
            hudSpeedCompactLayout = hudSpeedCompactLayoutView
            hudSpeedCompactLeft = hudSpeedCompactLeftView
            hudSpeedCompactRight = hudSpeedCompactRightView
            hudSpeedCompactIcon = hudSpeedCompactIconView
            hudSpeedCompactGpsBadge = hudSpeedCompactGpsBadgeView
            hudSpeedCompactDirection = hudSpeedCompactDirectionIconView
            hudSpeedCompactDistance = hudSpeedCompactDistanceTextView
            strelkaImageView = strelkaImageViewLocal
            roadCameraContainer = roadCameraBlock
            roadCameraIconView = roadCameraIcon
            roadCameraDistanceView = roadCameraDistanceText
            trafficLightContainer = trafficLightBlock
            speedometerView = speedometerText
            turnSignalsContainer = turnSignalsBlock
            turnSignalLeftView = turnSignalLeft
            turnSignalRightView = turnSignalRight
            clockView = clockText
            mapContainerView = mapBlock
            mapContentView = mapContent
            mapTripStatusView = mapTripStatus
            mapPlaceholderView = mapPlaceholder
            mapPlaceholderIconView = mapPlaceholderIcon
            mapPlaceholderLabelView = mapPlaceholderLabel
            customBlocksLayer = customLayer
            currentDisplayId = display.displayId
            syncCustomBlockViews()
            applyCustomBlockStates()
            applyTurnSignalsSpacing(metrics.density)
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
            laneGuidanceContainer = null
            laneGuidanceImageView = null
            laneGuidancePlaceholderView = null
            laneGuidanceDistanceView = null
            arrowContainer = null
            arrowView = null
            arrowLabel = null
            primaryView = null
            secondaryView = null
            timeView = null
            speedLimitView = null
            hudSpeedContainer = null
            hudSpeedContent = null
            hudSpeedOverspeedView = null
            hudSpeedFullLayout = null
            hudSpeedFullIcon = null
            hudSpeedFullGpsBadge = null
            hudSpeedFullRight = null
            hudSpeedFullDirection = null
            hudSpeedFullDistance = null
            hudSpeedFullLimit = null
            hudSpeedCompactLayout = null
            hudSpeedCompactLeft = null
            hudSpeedCompactRight = null
            hudSpeedCompactIcon = null
            hudSpeedCompactGpsBadge = null
            hudSpeedCompactDirection = null
            hudSpeedCompactDistance = null
            strelkaImageView = null
            hudSpeedActiveLayout = null
            roadCameraContainer = null
            roadCameraIconView = null
            roadCameraDistanceView = null
            trafficLightContainer = null
            speedometerView = null
            turnSignalsContainer = null
            turnSignalLeftView = null
            turnSignalRightView = null
            clockView = null
            mapContainerView = null
            mapContentView = null
            mapTripStatusView = null
            mapPlaceholderView = null
            mapPlaceholderIconView = null
            mapPlaceholderLabelView = null
            customBlocksLayer = null
            customBlockViewHolders.clear()
            currentDisplayId = null
            stopTurnSignalBlinking()
            stopClockTicker()
        }
    }

    private fun removeOverlay() {
        cancelDelayedRedraw()
        cancelHudSpeedHide()
        cancelMapHideByManeuverDelay()
        stopTurnSignalBlinking()
        removeMapView()
        laneGuidanceHadVisibleContent = false
        mapHadVisibleContent = false
        hideMapByManeuverActive = false
        mapTransparentFillPending = false
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
        laneGuidanceContainer = null
        laneGuidanceImageView = null
        laneGuidancePlaceholderView = null
        laneGuidanceDistanceView = null
        arrowContainer = null
        arrowView = null
        arrowLabel = null
        primaryView = null
        secondaryView = null
        timeView = null
        speedLimitView = null
        hudSpeedContainer = null
        hudSpeedContent = null
        hudSpeedOverspeedView = null
        hudSpeedFullLayout = null
        hudSpeedFullIcon = null
        hudSpeedFullGpsBadge = null
        hudSpeedFullRight = null
        hudSpeedFullDirection = null
        hudSpeedFullDistance = null
        hudSpeedFullLimit = null
        hudSpeedCompactLayout = null
        hudSpeedCompactLeft = null
        hudSpeedCompactRight = null
        hudSpeedCompactIcon = null
        hudSpeedCompactGpsBadge = null
        hudSpeedCompactDirection = null
        hudSpeedCompactDistance = null
        strelkaImageView = null
        hudSpeedActiveLayout = null
        roadCameraContainer = null
        roadCameraIconView = null
        roadCameraDistanceView = null
        trafficLightContainer = null
        speedometerView = null
        turnSignalsContainer = null
        turnSignalLeftView = null
        turnSignalRightView = null
        clockView = null
        mapContainerView = null
        mapContentView = null
        mapTripStatusView = null
        mapPlaceholderView = null
        mapPlaceholderIconView = null
        mapPlaceholderLabelView = null
        customBlocksLayer = null
        customBlockViewHolders.clear()
        currentDisplayId = null
        stopClockTicker()
    }

    private fun clearOverlayForDisable() {
        cancelDelayedRedraw()
        cancelHudSpeedHide()
        cancelMapHideByManeuverDelay()
        stopTurnSignalBlinking()
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
        laneGuidanceContainer?.visibility = View.GONE
        arrowContainer?.visibility = View.GONE
        speedLimitView?.visibility = View.GONE
        hudSpeedContainer?.visibility = View.GONE
        roadCameraContainer?.visibility = View.GONE
        trafficLightContainer?.visibility = View.GONE
        speedometerView?.visibility = View.GONE
        turnSignalsContainer?.visibility = View.GONE
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

    private fun clearOverlayForRedraw() {
        stopTurnSignalBlinking()
        val container = overlayView ?: return
        navContainer?.visibility = View.GONE
        laneGuidanceContainer?.visibility = View.GONE
        arrowContainer?.visibility = View.GONE
        speedLimitView?.visibility = View.GONE
        hudSpeedContainer?.visibility = View.GONE
        roadCameraContainer?.visibility = View.GONE
        trafficLightContainer?.visibility = View.GONE
        speedometerView?.visibility = View.GONE
        turnSignalsContainer?.visibility = View.GONE
        clockView?.visibility = View.GONE
        container.background = null
        container.setBackgroundColor(Color.TRANSPARENT)
        container.visibility = View.VISIBLE
        container.invalidate()
        container.postInvalidateOnAnimation()
    }

    private fun scheduleClearBeforeRedraw(): Boolean {
        val container = overlayView ?: return false
        cancelDelayedRedraw()
        clearBeforeRedrawPending = true
        applyLayoutInternal()
        clearOverlayForRedraw()
        val redrawRunnable = Runnable {
            delayedRedrawRunnable = null
            clearBeforeRedrawPending = false
            val view = overlayView ?: return@Runnable
            applyLayoutInternal()
            lastRenderSignature = buildRenderSignature(lastState)
            applyState(lastState)
            view.postInvalidateOnAnimation()
        }
        delayedRedrawRunnable = redrawRunnable
        container.postDelayed(redrawRunnable, CLEAR_BEFORE_REDRAW_DELAY_MS)
        return true
    }

    private fun cancelDelayedRedraw() {
        delayedRedrawRunnable?.let { handler.removeCallbacks(it) }
        delayedRedrawRunnable = null
        clearBeforeRedrawPending = false
    }

    private fun applyState(state: NavigationHudState) {
        val primary = primaryView ?: return
        val secondary = secondaryView ?: return
        val time = timeView ?: return
        val container = overlayView
        val clock = clockView
        val speedometer = speedometerView
        val mapContainer = mapContainerView
        val showPreview = previewMode
        val activeHudAlertSource = resolveActiveHudAlertSource()
        val previewNav = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_NAV, navEnabled)
        val previewArrow = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_ARROW, arrowEnabled)
        val previewSpeed = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_SPEED, speedEnabled)
        val previewSpeedometer = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER,
            speedometerEnabled
        )
        val previewTurnSignals = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_TURN_SIGNALS,
            turnSignalsEnabled
        )
        val previewHudSpeed = activeHudAlertSource == OverlayPrefs.HudAlertSource.HUDSPEED &&
            shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED, hudSpeedEnabled)
        val previewStrelka = activeHudAlertSource == OverlayPrefs.HudAlertSource.STRELKA &&
            shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_STRELKA, hudSpeedEnabled)
        val previewRoadCamera = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_ROAD_CAMERA,
            roadCameraEnabled
        )
        val previewTrafficLight = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT,
            trafficLightEnabled
        )
        val previewClock = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_CLOCK, clockEnabled)
        val previewLaneGuidance = shouldPreviewBlock(
            OverlayBroadcasts.PREVIEW_TARGET_LANE_GUIDANCE,
            laneGuidanceEnabled
        )
        val previewMap = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_MAP, mapEnabled)
        val navAllowed = navEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_NAV)
        val laneGuidanceAllowed =
            laneGuidanceEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_LANE_GUIDANCE)
        val routeSnapshot = MapRouteTelemetryStore.current()
        val hasMapRoute = routeSnapshot.hasRoute
        val mapAllowed = mapEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_MAP)
        val arrowAllowed = arrowEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_ARROW)
        val speedAllowed = speedEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_SPEED)
        val speedometerAllowed = speedometerEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER)
        val turnSignalsAllowed = turnSignalsEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_TURN_SIGNALS)
        val hudSpeedAllowed = hudSpeedEnabled || isSharedAlertPreviewTarget()
        val roadCameraAllowed = roadCameraEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_ROAD_CAMERA)
        val trafficLightAllowed = trafficLightEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT)
        val clockAllowed = clockEnabled || isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_CLOCK)

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
            context.getString(
                if (OverlayPrefs.navShowDistance(context)) R.string.preview_time_text else R.string.preview_time_text_no_distance
            )
        } else {
            buildTimeLine(state)
        }

        val speedValue = state.speedKmh?.coerceAtLeast(0)
        val speedometerBaseText = if (previewSpeedometer) {
            context.getString(R.string.preview_speedometer_text)
        } else {
            speedValue?.toString().orEmpty()
        }
        val speedometerText = formatSpeedometerText(speedometerBaseText)
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
        val hudSpeedGpsStatusEnabled = activeHudAlertSource == OverlayPrefs.HudAlertSource.HUDSPEED &&
            OverlayPrefs.hudSpeedGpsStatusEnabled(context)
        val hudSpeedHasGps = if (previewHudSpeed) false else state.hudSpeedHasGps
        val hudSpeedGpsStatusVisible = !previewHudSpeed &&
            hudSpeedGpsStatusEnabled &&
            !state.hudSpeedHasCamera
        val hudSpeedTransparentFillVisible = !previewHudSpeed && !state.hudSpeedHasCamera
        val hudSpeedLimitValue = if (previewHudSpeed) {
            PREVIEW_HUDSPEED_LIMIT_VALUE
        } else {
            state.hudSpeedLimit1
        }
        val hudSpeedLimitText = hudSpeedLimitValue
            ?.takeIf { it > 0 }
            ?.toString()
            .orEmpty()
        val hudSpeedLimitOverspeed = if (previewHudSpeed) {
            hudSpeedLimitEnabled && hudSpeedLimitValue != null
        } else {
            hudSpeedLimitEnabled &&
                hudSpeedLimitAlertEnabled &&
                speedValue != null &&
                hudSpeedLimitValue != null &&
                speedValue > hudSpeedLimitValue + hudSpeedLimitAlertThreshold
        }
        if (hudSpeedLimitOverspeed) {
            Log.d(
                "HudOverlayController",
                "HUD Speed overspeed: speed=$speedValue limit=$hudSpeedLimitValue threshold=$hudSpeedLimitAlertThreshold"
            )
        }
        val hudSpeedDirectionIcon = if (previewHudSpeed) {
            resolveHudSpeedDirectionIcon(PREVIEW_HUDSPEED_CAM_FLAG)
        } else {
            resolveHudSpeedDirectionIcon(state.hudSpeedCamFlag)
        }
        val hudSpeedCamType = if (previewHudSpeed) {
            PREVIEW_HUDSPEED_CAM_TYPE
        } else {
            state.hudSpeedCamType
        }
        val strelkaBitmap = if (previewStrelka) {
            strelkaPreviewBitmap
        } else {
            state.strelkaBitmap?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        }
        val roadCameraDistanceText = if (previewRoadCamera) {
            context.getString(R.string.preview_road_camera_distance)
        } else {
            state.roadCameraDistance.orEmpty()
        }
        val hideNavigationByDistance = shouldHideManeuverByDistance(state, showPreview)
        val roadCameraHasData = state.roadCameraIcon != null && state.roadCameraDistance?.isNotBlank() == true
        val trafficLights = if (previewTrafficLight) {
            val previewCount = trafficLightMaxActive.coerceAtLeast(1)
            val previewArrow = resolveTrafficLightPreviewArrow()
            val previewCountdown = context.getString(R.string.preview_traffic_light_countdown)
            List(previewCount) { index ->
                TrafficLightInfo(
                    id = index + 1,
                    color = "GREEN",
                    countdownText = previewCountdown,
                    arrowBitmap = previewArrow,
                    arrowDirection = "FORWARD",
                    lastUpdated = System.currentTimeMillis(),
                    expiresAt = Long.MAX_VALUE
                )
            }
        } else {
            state.trafficLights
        }
        val laneGuidanceManeuver = routeSnapshot.laneManeuver
        val hideMapByManeuver = resolveMapHideByManeuver(showPreview, hideNavigationByDistance)
        hideMapByManeuverActive = hideMapByManeuver
        val mapVisible = mapAllowed && if (showPreview) {
            previewMap
        } else {
            hasMapRoute && !hideMapByManeuver
        }
        val mapTransparentFillVisible = !mapVisible && mapHadVisibleContent
        mapTransparentFillPending = mapTransparentFillVisible
        val navHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.navHideWhenMapActive(context)
        )
        val laneGuidanceHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.laneGuidanceHideWhenMapActive(context)
        )
        val arrowHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.arrowHideWhenMapActive(context)
        )
        val speedHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.speedHideWhenMapActive(context)
        )
        val hudSpeedHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = when (activeHudAlertSource) {
                OverlayPrefs.HudAlertSource.HUDSPEED -> OverlayPrefs.hudSpeedHideWhenMapActive(context)
                OverlayPrefs.HudAlertSource.STRELKA -> OverlayPrefs.strelkaHideWhenMapActive(context)
            }
        )
        val roadCameraHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.roadCameraHideWhenMapActive(context)
        )
        val trafficLightHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.trafficLightHideWhenMapActive(context)
        )
        val speedometerHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.speedometerHideWhenMapActive(context)
        )
        val turnSignalsHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.turnSignalsHideWhenMapActive(context)
        )
        val clockHiddenByMap = shouldHideBlockWhenMapVisible(
            showPreview = showPreview,
            mapVisible = mapVisible,
            hideWhenMapActive = OverlayPrefs.clockHideWhenMapActive(context)
        )
        val laneGuidanceVisible = laneGuidanceAllowed && if (showPreview) {
            previewLaneGuidance
        } else {
            laneGuidanceManeuver != null && !laneGuidanceHiddenByMap
        }
        val laneGuidanceTransparentFillVisible = !laneGuidanceHiddenByMap &&
            !laneGuidanceVisible &&
            laneGuidanceHadVisibleContent

        setTextOrHide(primary, primaryText)
        setTextOrHide(secondary, secondaryText)
        setTextOrHide(time, timeText)
        updateSpeedLimit(speedLimitText, previewSpeed, overspeed)
        speedometer?.let { setSpeedometerTextOrHide(it, speedometerText) }
        val turnSignalLeft = if (previewTurnSignals) {
            true
        } else {
            state.turnSignalLeft || state.turnSignalHazard
        }
        val turnSignalRight = if (previewTurnSignals) {
            true
        } else {
            state.turnSignalRight || state.turnSignalHazard
        }
        val turnSignalsTransparentFillVisible = !turnSignalsHiddenByMap &&
            !previewTurnSignals &&
            !turnSignalLeft &&
            !turnSignalRight
        updateTurnSignals(
            allowed = turnSignalsAllowed && !turnSignalsHiddenByMap,
            preview = previewTurnSignals,
            leftActive = turnSignalLeft,
            rightActive = turnSignalRight,
            fillTransparentBackground = turnSignalsTransparentFillVisible
        )
        if (activeHudAlertSource == OverlayPrefs.HudAlertSource.HUDSPEED) {
            val hudSpeedLimitTextScale = PREVIEW_SPEED_LIMIT_TEXT_SCALE
            updateHudSpeed(
                hudSpeedCamType,
                hudSpeedDirectionIcon,
                hudSpeedDistanceText,
                hudSpeedLimitText,
                hudSpeedLimitEnabled,
                hudSpeedLimitTextScale,
                hudSpeedGpsStatusVisible,
                hudSpeedHasGps,
                hudSpeedTransparentFillVisible
            )
            updateHudSpeedOverspeed(hudSpeedLimitOverspeed)
        } else {
            updateStrelka(strelkaBitmap)
            updateHudSpeedOverspeed(false)
        }
        updateRoadCamera(state.roadCameraIcon, roadCameraDistanceText, roadCameraAllowed, previewRoadCamera)
        updateTrafficLights(trafficLights, trafficLightAllowed, previewTrafficLight)
        updateLaneGuidance(
            maneuver = laneGuidanceManeuver,
            preview = previewLaneGuidance,
            fillTransparentBackground = laneGuidanceTransparentFillVisible
        )
        updateManeuver(state.maneuverBitmap, previewNav)
        updateArrowManeuver(state.maneuverBitmap, previewArrow)
        if (clock != null) {
            updateClockText()
        }

        val navHasContent = primaryText.isNotBlank() ||
            secondaryText.isNotBlank() ||
            timeText.isNotBlank() ||
            state.maneuverBitmap != null
        val navVisible = if (showPreview) {
            previewNav
        } else {
            navHasContent && !hideNavigationByDistance && !navHiddenByMap
        }
        val arrowEligible = if (arrowOnlyWhenNoIcon) {
            NativeNavigationController.isActive() &&
                state.nativeTurnId == NavigationReceiver.DEFAULT_NATIVE_TURN_ID
        } else {
            true
        }
        val arrowVisible = if (showPreview) {
            previewArrow
        } else {
            state.maneuverBitmap != null && arrowEligible && !hideNavigationByDistance && !arrowHiddenByMap
        }
        val speedVisible = if (showPreview) previewSpeed else state.speedLimit.isNotBlank() && !speedHiddenByMap
        if (
            activeHudAlertSource == OverlayPrefs.HudAlertSource.HUDSPEED &&
            !showPreview &&
            (!state.hudSpeedHasCamera || state.hudSpeedDistanceMeters == null)
        ) {
            cancelHudSpeedHide()
        }
        val hudSpeedVisible = if (showPreview) {
            previewHudSpeed || previewStrelka
        } else {
            when (activeHudAlertSource) {
                OverlayPrefs.HudAlertSource.HUDSPEED -> {
                    val hasCameraData = state.hudSpeedHasCamera &&
                        state.hudSpeedDistanceMeters != null &&
                        !shouldHideHudSpeed(state, showPreview)
                    (hasCameraData || hudSpeedGpsStatusVisible || hudSpeedTransparentFillVisible) &&
                        !hudSpeedHiddenByMap
                }
                OverlayPrefs.HudAlertSource.STRELKA -> {
                    state.strelkaActive && strelkaBitmap != null && !hudSpeedHiddenByMap
                }
            }
        }
        val speedometerVisible = if (showPreview) previewSpeedometer else state.speedKmh != null && !speedometerHiddenByMap
        val turnSignalsVisible = if (showPreview) {
            turnSignalsAllowed && previewTurnSignals
        } else {
            turnSignalsAllowed &&
                (turnSignalLeft || turnSignalRight || turnSignalsTransparentFillVisible) &&
                !turnSignalsHiddenByMap
        }
        val clockVisible = if (showPreview) previewClock else !clockHiddenByMap
        val roadCameraVisible = if (showPreview) {
            roadCameraAllowed && previewRoadCamera
        } else {
            roadCameraAllowed && roadCameraHasData && !roadCameraHiddenByMap
        }
        val trafficLightVisible = if (showPreview) {
            trafficLightAllowed && previewTrafficLight
        } else {
            trafficLightAllowed && trafficLights.isNotEmpty() && !trafficLightHiddenByMap
        }
        navContainer?.visibility = if (navAllowed && navVisible) View.VISIBLE else View.GONE
        laneGuidanceContainer?.visibility = if (laneGuidanceVisible || laneGuidanceTransparentFillVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        mapContainer?.visibility = if (mapVisible || mapTransparentFillVisible) View.VISIBLE else View.GONE
        arrowContainer?.visibility = if (arrowAllowed && arrowVisible) View.VISIBLE else View.GONE
        speedLimitView?.visibility = if (speedAllowed && speedVisible) View.VISIBLE else View.GONE
        hudSpeedContainer?.visibility = if (hudSpeedAllowed && hudSpeedVisible) View.VISIBLE else View.GONE
        speedometer?.visibility = if (speedometerAllowed && speedometerVisible) View.VISIBLE else View.GONE
        clock?.visibility = if (clockAllowed && clockVisible) View.VISIBLE else View.GONE

        // Hide main container if nothing is visible
        val customVisible = applyCustomBlockStates()
        val anyVisible = (navAllowed && navVisible) ||
            laneGuidanceVisible ||
            laneGuidanceTransparentFillVisible ||
            mapVisible ||
            mapTransparentFillVisible ||
            (arrowAllowed && arrowVisible) ||
            (speedAllowed && speedVisible) ||
            (hudSpeedAllowed && hudSpeedVisible) ||
            roadCameraVisible ||
            trafficLightVisible ||
            (speedometerAllowed && speedometerVisible) ||
            turnSignalsVisible ||
            (clockAllowed && clockVisible) ||
            customVisible
        container?.visibility = if (showPreview || anyVisible) View.VISIBLE else View.GONE
        laneGuidanceHadVisibleContent = laneGuidanceVisible
        mapHadVisibleContent = mapVisible

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
        if (updatedAt <= 0L) {
            cancelHudSpeedHide()
            return false
        }
        if (distance >= HUDSPEED_HIDE_DISTANCE_METERS) {
            val timeoutMs = resolveHudSpeedTimeoutMs(distance) ?: run {
                cancelHudSpeedHide()
                return false
            }
            val elapsed = System.currentTimeMillis() - updatedAt
            val remaining = timeoutMs - elapsed
            if (remaining <= 0L) {
                cancelHudSpeedHide()
                return true
            }
            scheduleHudSpeedHide(remaining)
            return false
        }
        val timeoutMs = resolveHudSpeedTimeoutMs(distance) ?: run {
            cancelHudSpeedHide()
            return false
        }
        val elapsed = System.currentTimeMillis() - updatedAt
        val remaining = timeoutMs - elapsed
        if (remaining <= 0L) {
            cancelHudSpeedHide()
            return true
        }
        scheduleHudSpeedHide(remaining)
        return false
    }

    private fun shouldHideBlockWhenMapVisible(
        showPreview: Boolean,
        mapVisible: Boolean,
        hideWhenMapActive: Boolean,
    ): Boolean {
        return !showPreview && mapVisible && hideWhenMapActive
    }

    private fun resolveHudSpeedTimeoutMs(distanceMeters: Int): Long? {
        val seconds = if (distanceMeters < HUDSPEED_HIDE_DISTANCE_METERS) {
            OverlayPrefs.cameraTimeoutNear(context)
        } else {
            OverlayPrefs.cameraTimeoutFar(context)
        }
        if (seconds <= 0) {
            return null
        }
        return seconds.toLong() * 1000L
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

    private fun updateLaneGuidance(
        maneuver: MapLaneManeuver?,
        preview: Boolean,
        fillTransparentBackground: Boolean
    ) {
        val container = laneGuidanceContainer ?: return
        val image = laneGuidanceImageView ?: return
        val placeholder = laneGuidancePlaceholderView ?: return
        val distance = laneGuidanceDistanceView ?: return
        val showDistance = OverlayPrefs.laneGuidanceShowDistance(context)
        container.setBackgroundColor(Color.TRANSPARENT)
        if (fillTransparentBackground) {
            val hadImage = image.visibility == View.VISIBLE && image.drawable != null
            val hadPlaceholder = placeholder.visibility == View.VISIBLE
            val hadDistance = distance.visibility == View.VISIBLE && distance.text.isNotBlank()
            image.visibility = if (hadImage) View.INVISIBLE else View.GONE
            placeholder.visibility = if (hadPlaceholder) View.INVISIBLE else View.GONE
            distance.visibility = if (hadDistance) View.INVISIBLE else View.GONE
            container.invalidate()
            container.postInvalidateOnAnimation()
            return
        }
        val bitmap = maneuver?.bitmap?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        if (preview && bitmap != null) {
            image.setImageBitmap(resolveLaneGuidanceHudBitmap(maneuver))
            image.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            distance.text = formatLaneGuidanceDistance(maneuver.distanceMeters)
            distance.visibility = if (showDistance) View.VISIBLE else View.GONE
            container.background = null
            return
        }
        if (preview) {
            laneGuidanceHudBitmapSourceToken = Int.MIN_VALUE
            laneGuidanceHudBitmapSourceGenId = -1
            laneGuidanceHudBitmapSourceWidth = 0
            laneGuidanceHudBitmapSourceHeight = 0
            laneGuidanceHudBitmap = null
            image.setImageDrawable(null)
            image.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            distance.text = context.getString(R.string.preview_hudspeed_distance)
            distance.visibility = if (showDistance) View.VISIBLE else View.GONE
            container.background = null
            return
        }
        if (bitmap != null) {
            image.setImageBitmap(resolveLaneGuidanceHudBitmap(maneuver))
            image.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            distance.text = formatLaneGuidanceDistance(maneuver.distanceMeters)
            distance.visibility = if (showDistance) View.VISIBLE else View.GONE
            container.background = null
            return
        }
        laneGuidanceHudBitmapSourceToken = Int.MIN_VALUE
        laneGuidanceHudBitmapSourceGenId = -1
        laneGuidanceHudBitmapSourceWidth = 0
        laneGuidanceHudBitmapSourceHeight = 0
        laneGuidanceHudBitmap = null
        image.setImageDrawable(null)
        image.visibility = View.GONE
        placeholder.visibility = if (preview) View.VISIBLE else View.GONE
        distance.text = context.getString(R.string.preview_hudspeed_distance)
        distance.visibility = if (preview && showDistance) View.VISIBLE else View.GONE
        container.background = null
    }

    private fun formatLaneGuidanceDistance(distanceMeters: Int): String {
        return LaneGuidanceHudRenderHelper.formatDistance(distanceMeters)
    }

    private fun resolveLaneGuidanceHudBitmap(maneuver: MapLaneManeuver): Bitmap {
        val source = maneuver.bitmap
        val token = maneuver.token
        val generationId = source.generationId
        val width = source.width
        val height = source.height
        if (
            laneGuidanceHudBitmap != null &&
            laneGuidanceHudBitmapSourceToken == token &&
            laneGuidanceHudBitmapSourceGenId == generationId &&
            laneGuidanceHudBitmapSourceWidth == width &&
            laneGuidanceHudBitmapSourceHeight == height
        ) {
            return laneGuidanceHudBitmap ?: source
        }
        val prepared = LaneGuidanceHudRenderHelper.prepareBitmap(source)
        laneGuidanceHudBitmapSourceToken = token
        laneGuidanceHudBitmapSourceGenId = generationId
        laneGuidanceHudBitmapSourceWidth = width
        laneGuidanceHudBitmapSourceHeight = height
        laneGuidanceHudBitmap = prepared
        return prepared
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

    private fun updateTurnSignals(
        allowed: Boolean,
        preview: Boolean,
        leftActive: Boolean,
        rightActive: Boolean,
        fillTransparentBackground: Boolean
    ) {
        val container = turnSignalsContainer ?: return
        turnSignalsPreviewMode = preview
        turnSignalLeftActive = leftActive
        turnSignalRightActive = rightActive
        container.setBackgroundColor(Color.TRANSPARENT)
        if (!allowed) {
            stopTurnSignalBlinking()
            container.visibility = View.GONE
            return
        }
        val hasActive = leftActive || rightActive
        if (!preview && !hasActive && !fillTransparentBackground) {
            stopTurnSignalBlinking()
            container.visibility = View.GONE
            return
        }
        if (preview) {
            stopTurnSignalBlinking()
        } else if (hasActive) {
            startTurnSignalBlinking()
        } else {
            stopTurnSignalBlinking()
        }
        applyTurnSignalVisibility()
        container.visibility = View.VISIBLE
        if (fillTransparentBackground) {
            container.invalidate()
            container.postInvalidateOnAnimation()
        }
    }

    private fun applyTurnSignalVisibility() {
        val left = turnSignalLeftView ?: return
        val right = turnSignalRightView ?: return
        val onPhase = turnSignalsPreviewMode || turnSignalBlinkVisible
        left.visibility = if (turnSignalLeftActive && onPhase) View.VISIBLE else View.INVISIBLE
        right.visibility = if (turnSignalRightActive && onPhase) View.VISIBLE else View.INVISIBLE
    }

    private fun applyTurnSignalsIconStyle() {
        TurnSignalIcons.applyPair(context, turnSignalLeftView, turnSignalRightView, turnSignalsIconStyle)
    }

    private fun startTurnSignalBlinking() {
        if (turnSignalBlinkRunnable != null) {
            return
        }
        turnSignalBlinkVisible = true
        val runnable = object : Runnable {
            override fun run() {
                turnSignalBlinkVisible = !turnSignalBlinkVisible
                applyTurnSignalVisibility()
                handler.postDelayed(this, TURN_SIGNAL_BLINK_INTERVAL_MS)
            }
        }
        turnSignalBlinkRunnable = runnable
        handler.postDelayed(runnable, TURN_SIGNAL_BLINK_INTERVAL_MS)
    }

    private fun stopTurnSignalBlinking() {
        turnSignalBlinkRunnable?.let { handler.removeCallbacks(it) }
        turnSignalBlinkRunnable = null
        turnSignalBlinkVisible = true
    }

    private fun setTextOrHide(view: TextView, text: String) {
        if (text.isBlank()) {
            view.visibility = View.GONE
        } else {
            if (view.text.toString() != text) {
                view.text = text
            }
            view.visibility = View.VISIBLE
        }
    }

    private fun setSpeedometerTextOrHide(view: TextView, text: String) {
        if (text.isBlank()) {
            view.visibility = View.GONE
            return
        }
        val speedText = text.substringBefore('\n')
        val unitText = text.substringAfter('\n', "")
        view.text = if (unitText.isBlank()) {
            speedText
        } else {
            val fullText = "$speedText\n$unitText"
            val unitStart = speedText.length + 1
            SpannableStringBuilder(fullText).apply {
                setSpan(
                    RelativeSizeSpan(SPEEDOMETER_UNIT_RELATIVE_SIZE),
                    unitStart,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        view.visibility = View.VISIBLE
    }

    private fun formatSpeedometerText(speedText: String): String {
        if (speedText.isBlank()) {
            return ""
        }
        if (!speedometerShowUnitText) {
            return speedText
        }
        return "$speedText\n${context.getString(R.string.speedometer_unit_text)}"
    }

    private fun buildTimeLine(state: NavigationHudState): String {
        val distance = if (OverlayPrefs.navShowDistance(context)) {
            state.rawDistance.ifBlank { state.distance }
        } else {
            ""
        }
        return NavTimeLine.format(state.time, distance, resolveArrivalText(state))
    }

    private fun resolveArrivalText(state: NavigationHudState): String {
        val explicitArrival = state.arrival.trim()
        if (explicitArrival.isNotBlank()) {
            return normalizeTo24Hour(explicitArrival) ?: explicitArrival
        }
        val etaSeconds = parseEtaSeconds(state.time.trim()) ?: return ""
        if (etaSeconds <= 0) {
            return ""
        }
        val arrivalAtMillis = System.currentTimeMillis() + etaSeconds.toLong() * 1000L
        return clockFormatter.format(Date(arrivalAtMillis))
    }

    private fun normalizeTo24Hour(text: String): String? {
        val normalized = text.trim()
        val amPmMatch = Regex(
            "(?i)(\\d{1,2})[:.](\\d{2})\\s*([ap])\\.?\\s*m\\.?"
        ).find(normalized)
        if (amPmMatch != null) {
            val hourRaw = amPmMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = amPmMatch.groupValues[2].toIntOrNull() ?: return null
            val meridiem = amPmMatch.groupValues[3].lowercase(Locale.getDefault())
            if (minute !in 0..59 || hourRaw !in 1..12) {
                return null
            }
            val hour24 = when {
                meridiem == "a" && hourRaw == 12 -> 0
                meridiem == "p" && hourRaw != 12 -> hourRaw + 12
                else -> hourRaw
            }
            return String.format(Locale.getDefault(), "%02d:%02d", hour24, minute)
        }

        val twentyFourMatch = Regex("(?<!\\d)([01]?\\d|2[0-3])[:.](\\d{2})(?!\\d)").find(normalized)
        if (twentyFourMatch != null) {
            val hour = twentyFourMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = twentyFourMatch.groupValues[2].toIntOrNull() ?: return null
            if (minute !in 0..59) {
                return null
            }
            return String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        }
        return null
    }

    private fun parseEtaSeconds(text: String): Int? {
        val normalized = text.lowercase(Locale.getDefault())
        val days = Regex("(\\d+)\\s*(?:дн\\.?|день|дня|дней|д|day|days)(?!\\p{L})")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 0
        if (":" in normalized) {
            val parts = normalized.split(":").map { it.trim() }
            if (parts.size == 2) {
                val first = parts[0].toIntOrNull() ?: return null
                val second = parts[1].toIntOrNull() ?: return null
                val base = if (first >= 1) {
                    first * 3600 + second * 60
                } else {
                    first * 60 + second
                }
                return days * 86400 + base
            }
            if (parts.size == 3) {
                val hours = parts[0].toIntOrNull() ?: return null
                val minutes = parts[1].toIntOrNull() ?: return null
                val seconds = parts[2].toIntOrNull() ?: return null
                return days * 86400 + hours * 3600 + minutes * 60 + seconds
            }
        }
        val hours = Regex("(\\d+)\\s*ч").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*мин").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(\\d+)\\s*сек").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            val fallback = Regex("(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            return fallback?.let { it * 60 }
        }
        return days * 86400 + hours * 3600 + minutes * 60 + seconds
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
        val baseSizeSp = if (overspeed) {
            SPEED_LIMIT_ALERT_TEXT_SIZE_SP
        } else {
            SPEED_LIMIT_TEXT_SIZE_SP
        }
        val previewScale = if (preview) PREVIEW_SPEED_LIMIT_TEXT_SCALE else 1f
        val scaledSizeSp =
            baseSizeSp * speedTextScale.coerceIn(SPEED_TEXT_SCALE_MIN, SPEED_TEXT_SCALE_MAX) * previewScale
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSizeSp)
        view.strokeEnabled = false
        val background = if (overspeed) {
            R.drawable.bg_speed_limit_alert
        } else {
            R.drawable.bg_speed_limit
        }
        view.setBackgroundResource(background)
        view.setTextColor(Color.BLACK)
        view.visibility = View.VISIBLE
    }

    private fun updateHudSpeed(
        camType: Int?,
        directionIcon: Int?,
        distanceText: String,
        limitText: String,
        showLimit: Boolean,
        limitTextScale: Float,
        showGpsStatus: Boolean,
        hasGps: Boolean,
        fillTransparentBackground: Boolean
    ) {
        val gpsStatusMode = showGpsStatus
        val transparentFillMode = fillTransparentBackground
        val distanceVisible = distanceText.isNotBlank()
        strelkaImageView?.visibility = View.GONE
        if (!distanceVisible && !gpsStatusMode && !transparentFillMode) {
            hudSpeedContainer?.visibility = View.GONE
            return
        }
        hudSpeedContainer?.visibility = View.VISIBLE
        val useFullLayout = showLimit
        val fullLayout = hudSpeedFullLayout
        val compactLayout = hudSpeedCompactLayout
        if (fullLayout == null || compactLayout == null) {
            return
        }
        fullLayout.visibility = if (useFullLayout) View.VISIBLE else View.GONE
        compactLayout.visibility = if (useFullLayout) View.GONE else View.VISIBLE
        hudSpeedActiveLayout = if (useFullLayout) fullLayout else compactLayout
        hudSpeedContent?.setBackgroundColor(Color.TRANSPARENT)
        if (useFullLayout) {
            val icon = hudSpeedFullIcon ?: return
            val rightColumn = hudSpeedFullRight
            val direction = hudSpeedFullDirection ?: return
            val distance = hudSpeedFullDistance ?: return
            val limit = hudSpeedFullLimit ?: return
            val gpsBadge = hudSpeedFullGpsBadge
            if (gpsStatusMode || transparentFillMode) {
                if (gpsStatusMode) {
                    icon.setImageDrawable(null)
                    icon.clearColorFilter()
                    icon.visibility = View.INVISIBLE
                    gpsBadge?.setImageResource(resolveHudSpeedGpsIcon(hasGps))
                    gpsBadge?.setColorFilter(resolveHudSpeedGpsColor(hasGps))
                    gpsBadge?.visibility = View.VISIBLE
                } else {
                    gpsBadge?.setImageDrawable(null)
                    gpsBadge?.clearColorFilter()
                    gpsBadge?.visibility = View.GONE
                    icon.setImageDrawable(null)
                    icon.clearColorFilter()
                    icon.visibility = View.INVISIBLE
                }
                val clearOnly = transparentFillMode
                rightColumn?.visibility = if (clearOnly) View.INVISIBLE else View.GONE
                limit.visibility = if (clearOnly) View.INVISIBLE else View.GONE
                distance.visibility = if (clearOnly) View.INVISIBLE else View.GONE
                direction.setImageDrawable(null)
                direction.visibility = if (clearOnly) View.INVISIBLE else View.GONE
                syncHudSpeedMeasuredSize()
                return
            }
            gpsBadge?.setImageDrawable(null)
            gpsBadge?.clearColorFilter()
            gpsBadge?.visibility = View.GONE
            icon.visibility = View.VISIBLE
            icon.clearColorFilter()
            icon.setImageResource(resolveHudSpeedCamIcon(camType))
            rightColumn?.visibility = View.VISIBLE
            val limitVisible = limitText.isNotBlank()
            if (limitVisible) {
                val scaledSizeSp =
                    SPEED_LIMIT_TEXT_SIZE_SP *
                        speedTextScale.coerceIn(SPEED_TEXT_SCALE_MIN, SPEED_TEXT_SCALE_MAX) *
                        limitTextScale
                limit.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSizeSp)
                limit.text = limitText
                limit.setBackgroundResource(R.drawable.bg_hudspeed_limit)
                limit.visibility = View.VISIBLE
            } else {
                limit.visibility = View.GONE
            }
            distance.text = distanceText
            distance.visibility = View.VISIBLE
            if (directionIcon == null) {
                direction.setImageDrawable(null)
                direction.visibility = View.GONE
            } else {
                direction.setImageResource(directionIcon)
                direction.visibility = View.VISIBLE
            }
            syncHudSpeedMeasuredSize()
            return
        }
        val icon = hudSpeedCompactIcon ?: return
        val direction = hudSpeedCompactDirection ?: return
        val distance = hudSpeedCompactDistance ?: return
        val leftColumn = hudSpeedCompactLeft
        val rightColumn = hudSpeedCompactRight
        val gpsBadge = hudSpeedCompactGpsBadge
        if (gpsStatusMode || transparentFillMode) {
            if (gpsStatusMode) {
                icon.setImageDrawable(null)
                icon.clearColorFilter()
                icon.visibility = View.INVISIBLE
                gpsBadge?.setImageResource(resolveHudSpeedGpsIcon(hasGps))
                gpsBadge?.setColorFilter(resolveHudSpeedGpsColor(hasGps))
                gpsBadge?.visibility = View.VISIBLE
            } else {
                gpsBadge?.setImageDrawable(null)
                gpsBadge?.clearColorFilter()
                gpsBadge?.visibility = View.GONE
                icon.setImageDrawable(null)
                icon.clearColorFilter()
                icon.visibility = View.INVISIBLE
            }
            val clearOnly = transparentFillMode
            direction.setImageDrawable(null)
            direction.visibility = if (clearOnly) View.INVISIBLE else View.GONE
            distance.visibility = if (clearOnly) View.INVISIBLE else View.GONE
            rightColumn?.visibility = if (clearOnly) View.INVISIBLE else View.GONE
            syncHudSpeedMeasuredSize()
            return
        }
        gpsBadge?.setImageDrawable(null)
        gpsBadge?.clearColorFilter()
        gpsBadge?.visibility = View.GONE
        icon.visibility = View.VISIBLE
        icon.clearColorFilter()
        icon.setImageResource(resolveHudSpeedCamIcon(camType))
        distance.text = distanceText
        distance.visibility = View.VISIBLE
        val directionVisible = directionIcon != null
        if (directionVisible) {
            direction.setImageResource(directionIcon)
            direction.visibility = View.VISIBLE
        } else {
            direction.setImageDrawable(null)
            direction.visibility = View.GONE
        }
        val targetColumn = if (directionVisible) rightColumn else leftColumn
        if (targetColumn != null && distance.parent != targetColumn) {
            (distance.parent as? ViewGroup)?.removeView(distance)
            targetColumn.addView(distance)
        }
        if (rightColumn != null) {
            rightColumn.visibility = if (directionVisible) View.VISIBLE else View.GONE
        }
        syncHudSpeedMeasuredSize()
    }

    private fun updateStrelka(bitmap: Bitmap?) {
        val imageView = strelkaImageView ?: return
        hudSpeedFullLayout?.visibility = View.GONE
        hudSpeedCompactLayout?.visibility = View.GONE
        hudSpeedActiveLayout = imageView
        if (bitmap == null) {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            hudSpeedContainer?.visibility = View.GONE
            return
        }
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        hudSpeedContainer?.visibility = View.VISIBLE
        syncHudSpeedMeasuredSize()
    }

    private fun syncHudSpeedMeasuredSize() {
        val container = hudSpeedContainer ?: return
        val content = hudSpeedContent ?: return
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredWidth = content.measuredWidth.coerceAtLeast(1)
        val measuredHeight = content.measuredHeight.coerceAtLeast(1)
        val contentParams = (content.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(measuredWidth, measuredHeight)
        if (contentParams.width != measuredWidth || contentParams.height != measuredHeight) {
            content.layoutParams = contentParams.apply {
                width = measuredWidth
                height = measuredHeight
            }
        }
        val containerParams = (container.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(measuredWidth, measuredHeight)
        if (containerParams.width != measuredWidth || containerParams.height != measuredHeight) {
            container.layoutParams = containerParams.apply {
                width = measuredWidth
                height = measuredHeight
            }
        }
    }

    private fun updateHudSpeedOverspeed(overspeed: Boolean) {
        val content = hudSpeedActiveLayout ?: hudSpeedContent ?: return
        val background = hudSpeedOverspeedView ?: return
        if (!overspeed) {
            background.visibility = View.GONE
            return
        }
        val density = content.resources.displayMetrics.density
        val paddingPx = (HUDSPEED_OVERSPEED_PADDING_DP * density).roundToInt().coerceAtLeast(0)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredWidth = content.measuredWidth
        val measuredHeight = content.measuredHeight
        if (measuredWidth <= 0 || measuredHeight <= 0) {
            background.visibility = View.GONE
            return
        }
        val width = measuredWidth + paddingPx * 2
        val height = measuredHeight + paddingPx * 2
        val params = (background.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        params.width = width
        params.height = height
        params.leftMargin = -paddingPx
        params.topMargin = -paddingPx
        params.rightMargin = -paddingPx
        params.bottomMargin = -paddingPx
        background.layoutParams = params
        background.visibility = View.VISIBLE
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

    private fun resolveTrafficLightPreviewArrow(): Bitmap? {
        trafficLightPreviewArrow?.let { return it }
        val drawable = ContextCompat.getDrawable(context, R.drawable.context_lane_straightahead_small_24)
            ?: return null
        val size = context.resources.getDimensionPixelSize(R.dimen.traffic_light_arrow_size_expanded)
            .coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        trafficLightPreviewArrow = bitmap
        return bitmap
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

            val arrowRes = if (light.arrowBitmap == null) resolveTrafficLightArrowRes(light.arrowDirection) else null
            val useExpanded = light.arrowBitmap != null || arrowRes != null
            if (useExpanded) {
                compactView.visibility = View.GONE
                expandedView.visibility = View.VISIBLE
                expandedCircle.setBackgroundResource(backgroundRes)
                expandedText.text = countdownText
                expandedText.visibility = if (countdownText.isBlank()) View.INVISIBLE else View.VISIBLE
                if (light.arrowBitmap != null) {
                    expandedIcon.setImageBitmap(light.arrowBitmap)
                } else if (arrowRes != null) {
                    expandedIcon.setImageResource(arrowRes)
                }
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

    private fun resolveTrafficLightArrowRes(direction: String): Int? {
        // Windshield RouteDirectionArrow names.
        return when (direction.trim().uppercase(Locale.US)) {
            "FORWARD" -> R.drawable.context_lane_straightahead_small_24
            "LEFT" -> R.drawable.context_lane_left90_small_24
            "RIGHT" -> R.drawable.context_lane_right90_small_24
            "UTURN_LEFT" -> R.drawable.context_lane_left180_small_24
            else -> null
        }
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

    private fun resolveHudSpeedGpsIcon(hasGps: Boolean): Int {
        return if (hasGps) {
            R.drawable.location_on_24dp_e8eaed_fill0_wght400_grad0_opsz24
        } else {
            R.drawable.location_off_24dp_e8eaed_fill0_wght400_grad0_opsz24
        }
    }

    private fun resolveHudSpeedGpsColor(hasGps: Boolean): Int {
        return ContextCompat.getColor(
            context,
            if (hasGps) R.color.traffic_light_green_primary else R.color.traffic_light_red_primary
        )
    }

    private fun formatHudSpeedDistance(distanceMeters: Int?): String {
        if (distanceMeters == null || distanceMeters < 0) {
            return ""
        }
        return "${distanceMeters}м"
    }

    private fun shouldHideManeuverByDistance(state: NavigationHudState, showPreview: Boolean): Boolean {
        if (showPreview) {
            dynamicHideTurnSpeedBucket = null
            return false
        }
        if (!OverlayPrefs.hideTurnWhenFarEnabled(context)) {
            dynamicHideTurnSpeedBucket = null
            return false
        }
        val distanceMeters = resolveManeuverDistanceMeters(state) ?: run {
            dynamicHideTurnSpeedBucket = null
            return false
        }
        val resolution = OverlayPrefs.resolveHideTurnThreshold(
            context = context,
            speedKmh = state.speedKmh,
            currentBucket = dynamicHideTurnSpeedBucket
        )
        dynamicHideTurnSpeedBucket = resolution.bucket
        val thresholdMeters = resolution.thresholdMeters
        return distanceMeters > thresholdMeters
    }

    private fun resolveMapHideByManeuver(showPreview: Boolean, hideNavigationByDistance: Boolean): Boolean {
        if (showPreview || !hideNavigationByDistance) {
            cancelMapHideByManeuverDelay()
            return false
        }
        val shouldHide = OverlayPrefs.hideTurnDynamicEnabled(context) &&
            OverlayPrefs.hideTurnDynamicHideMapBlock(context)
        if (!shouldHide) {
            cancelMapHideByManeuverDelay()
            return false
        }
        if (hideMapByManeuverActive) {
            return true
        }
        scheduleMapHideByManeuverDelay()
        return false
    }

    private fun scheduleMapHideByManeuverDelay() {
        if (pendingMapHideByManeuverRunnable != null) {
            return
        }
        val runnable = Runnable {
            pendingMapHideByManeuverRunnable = null
            hideMapByManeuverActive = true
            applyState(lastState)
        }
        pendingMapHideByManeuverRunnable = runnable
        handler.postDelayed(runnable, MAP_HIDE_BY_MANEUVER_DELAY_MS)
    }

    private fun cancelMapHideByManeuverDelay() {
        pendingMapHideByManeuverRunnable?.let { handler.removeCallbacks(it) }
        pendingMapHideByManeuverRunnable = null
        hideMapByManeuverActive = false
    }

    private fun resolveManeuverDistanceMeters(state: NavigationHudState): Int? {
        val rawNextTextWithUnit = appendUnitIfMissing(state.rawNextText, state.distanceUnit)
        parseDistanceMeters(rawNextTextWithUnit)?.let { return it }
        parseDistanceMeters(state.primaryText)?.let { return it }
        return null
    }

    private fun parseDistanceMeters(text: String): Int? {
        val normalized = text.lowercase(Locale.getDefault()).replace(',', '.')
        val kilometerMatch = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(км|km)\\b").find(normalized)
        if (kilometerMatch != null) {
            val km = kilometerMatch.groupValues[1].toDoubleOrNull() ?: return null
            return (km * 1000.0).toInt().coerceAtLeast(0)
        }
        val meterMatch = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(м|m)\\b").find(normalized)
        if (meterMatch != null) {
            val meters = meterMatch.groupValues[1].toDoubleOrNull() ?: return null
            return meters.toInt().coerceAtLeast(0)
        }
        return null
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
        val display = HudDisplayUtils.resolveDisplay(context, displayId, allowFallback = false) ?: return
        val displayContext = context.createDisplayContext(display)
        val metrics = displayContext.resources.displayMetrics
        val (containerWidthPx, containerHeightPx) = resolveContainerSizePx(metrics)
        updateNavLayoutWidth(metrics.density)
        updateContainerLayout(metrics, containerWidthPx, containerHeightPx)
        val containerWidth = containerWidthPx.toFloat()
        val containerHeight = containerHeightPx.toFloat()
        val activeHudAlertSource = resolveActiveHudAlertSource()
        navContainer?.let {
            if (isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_NAV)) {
                it.background = ContextCompat.getDrawable(it.context, R.drawable.bg_nav_block_outline)
            } else {
                it.background = null
            }
            positionView(it, navPositionDp, navScale, navAlpha, metrics.density, containerWidth, containerHeight)
        }
        laneGuidanceContainer?.let {
            positionView(
                it,
                laneGuidancePositionDp,
                laneGuidanceScale,
                laneGuidanceAlpha,
                metrics.density,
                containerWidth,
                containerHeight
            )
        }
        arrowContainer?.let {
            positionView(it, arrowPositionDp, arrowScale, arrowAlpha, metrics.density, containerWidth, containerHeight)
        }
        speedLimitView?.let {
            positionView(it, speedPositionDp, speedScale, speedAlpha, metrics.density, containerWidth, containerHeight)
        }
        hudSpeedContainer?.let {
            if (isSharedAlertPreviewTarget()) {
                it.background = ContextCompat.getDrawable(it.context, R.drawable.bg_nav_block_outline)
            } else {
                it.background = null
            }
            val position = when (activeHudAlertSource) {
                OverlayPrefs.HudAlertSource.HUDSPEED -> hudSpeedPositionDp
                OverlayPrefs.HudAlertSource.STRELKA -> strelkaPositionDp
            }
            val scale = when (activeHudAlertSource) {
                OverlayPrefs.HudAlertSource.HUDSPEED -> hudSpeedScale
                OverlayPrefs.HudAlertSource.STRELKA -> strelkaScale
            }
            val alpha = when (activeHudAlertSource) {
                OverlayPrefs.HudAlertSource.HUDSPEED -> hudSpeedAlpha
                OverlayPrefs.HudAlertSource.STRELKA -> strelkaAlpha
            }
            positionView(it, position, scale, alpha, metrics.density, containerWidth, containerHeight)
        }
        roadCameraContainer?.let {
            positionView(it, roadCameraPositionDp, roadCameraScale, roadCameraAlpha, metrics.density, containerWidth, containerHeight)
        }
        trafficLightContainer?.let {
            positionView(it, trafficLightPositionDp, trafficLightScale, trafficLightAlpha, metrics.density, containerWidth, containerHeight)
        }
        speedometerView?.let {
            if (isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER)) {
                it.background = ContextCompat.getDrawable(it.context, R.drawable.bg_nav_block_outline)
            } else {
                it.background = null
            }
            positionView(it, speedometerPositionDp, speedometerScale, speedometerAlpha, metrics.density, containerWidth, containerHeight)
        }
        turnSignalsContainer?.let {
            applyTurnSignalsSpacing(metrics.density)
            if (isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_TURN_SIGNALS)) {
                it.background = ContextCompat.getDrawable(it.context, R.drawable.bg_nav_block_outline)
            } else {
                it.background = null
            }
            positionView(
                it,
                turnSignalsPositionDp,
                turnSignalsScale,
                turnSignalsAlpha,
                metrics.density,
                containerWidth,
                containerHeight,
                anchorXFraction = 0.5f
            )
        }
        clockView?.let {
            positionView(it, clockPositionDp, clockScale, clockAlpha, metrics.density, containerWidth, containerHeight)
        }
        customBlocksDocument.blocks.forEach { block ->
            customBlockViewHolders[block.id]?.root?.let { view ->
                positionView(
                    view,
                    PointF(block.xDp, block.yDp),
                    block.scale,
                    block.alpha,
                    metrics.density,
                    containerWidth,
                    containerHeight
                )
            }
        }
        updateMapView(displayContext, containerWidthPx, containerHeightPx)
    }

    private fun syncCustomBlockViews() {
        val layer = customBlocksLayer ?: return
        val enabledIds = customBlocksDocument.blocks.filter { it.enabled }.mapTo(linkedSetOf()) { it.id }
        customBlockViewHolders.keys.filterNot(enabledIds::contains).forEach { blockId ->
            customBlockViewHolders.remove(blockId)?.root?.let(layer::removeView)
        }
        customBlocksDocument.blocks.filter { it.enabled }.forEach { block ->
            if (customBlockViewHolders[block.id] == null) {
                customBlockViewHolders[block.id] = CustomBlockViewFactory.create(layer.context, layer)
            }
        }
    }

    private fun applyCustomBlockStates(): Boolean {
        val layer = customBlocksLayer ?: return false
        var anyVisible = false
        customBlocksDocument.blocks.forEach { block ->
            val holder = customBlockViewHolders[block.id] ?: return@forEach
            val state = customBlockRenderStates[block.id]
                ?: CustomBlockRenderState(blockId = block.id, visible = false)
            holder.bind(block, state)
            if (customBlocksDocument.enabled && block.enabled && state.visible && state.error == null) {
                anyVisible = true
            }
        }
        layer.visibility = if (customBlocksDocument.enabled && anyVisible) View.VISIBLE else View.GONE
        return customBlocksDocument.enabled && anyVisible
    }

    private fun applyTurnSignalsSpacing(density: Float) {
        val left = turnSignalLeftView ?: return
        val params = left.layoutParams as? LinearLayout.LayoutParams ?: return
        val marginPx = resolveTurnSignalsGapPx(density)
        if (params.marginEnd == marginPx) {
            return
        }
        left.layoutParams = params.apply {
            marginEnd = marginPx
        }
    }

    private fun resolveTurnSignalsGapPx(density: Float): Int {
        val gapDp = (turnSignalsSpacingDp - OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP).coerceAtLeast(0f)
        return (gapDp * density).roundToInt()
    }

    private fun resolveTurnSignalsMaxSpacingDp(): Float {
        val safeScale = turnSignalsScale.coerceAtLeast(0.01f)
        return ((containerWidthDp / safeScale) - OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP)
            .coerceAtLeast(OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP)
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
        applyInfoMirrorMode(view, containerWidthPx, containerHeightPx)
        updateContainerOutlineAlpha(view)
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: ошибка обновления: ${e.message}")
        }
    }

    private fun applyInfoMirrorMode(container: FrameLayout, containerWidthPx: Int, containerHeightPx: Int) {
        container.pivotX = containerWidthPx / 2f
        container.pivotY = containerHeightPx / 2f
        container.scaleX = if (infoMirrorGalaxyEnabled) -1f else 1f
        container.scaleY = if (infoMirrorStarsheep7Enabled) -1f else 1f
    }

    private fun updateContainerOutlineAlpha(container: FrameLayout) {
        if (isPreviewTarget(OverlayBroadcasts.PREVIEW_TARGET_CONTAINER)) {
            if (container.background == null || container.background is ColorDrawable) {
                container.background = ContextCompat.getDrawable(container.context, R.drawable.bg_hud_container_outline)
            }
            val effectiveAlpha = max(containerAlpha, CONTAINER_OUTLINE_PREVIEW_MIN_ALPHA)
            val alphaValue = (effectiveAlpha * 255).roundToInt().coerceIn(0, 255)
            container.background?.alpha = alphaValue
        } else {
            container.background = null
        }
    }

    private fun updateMapView(displayContext: Context, containerWidthPx: Int, containerHeightPx: Int) {
        val previewMap = shouldPreviewBlock(OverlayBroadcasts.PREVIEW_TARGET_MAP, mapEnabled)
        val routeSnapshot = MapRouteTelemetryStore.current()
        val hasMapRoute = routeSnapshot.hasRoute
        val runtimeMapVisible = !previewMode && mapEnabled && hasMapRoute && !hideMapByManeuverActive
        logMapState(
            stage = "update",
            previewMap = previewMap,
            hasMapRoute = hasMapRoute,
            mapContainerReady = mapContainerView != null,
            mapContentReady = mapContentView != null,
        )
        val mapContainer = mapContainerView ?: return
        val mapContent = mapContentView ?: return
        val mapTripStatus = mapTripStatusView ?: return
        val placeholder = mapPlaceholderView ?: return
        val widthPx = (mapWidthDp * displayContext.resources.displayMetrics.density)
            .roundToInt()
            .coerceIn(1, containerWidthPx.coerceAtLeast(1))
        val heightPx = (mapHeightDp * displayContext.resources.displayMetrics.density)
            .roundToInt()
            .coerceIn(1, containerHeightPx.coerceAtLeast(1))
        val mapContainerParams = (mapContainer.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(widthPx, heightPx)
        if (mapContainerParams.width != widthPx || mapContainerParams.height != heightPx) {
            mapContainer.layoutParams = mapContainerParams.apply {
                width = widthPx
                height = heightPx
            }
        }
        positionView(
            mapContainer,
            mapPositionDp,
            1f,
            mapAlpha,
            displayContext.resources.displayMetrics.density,
            containerWidthPx.toFloat(),
            containerHeightPx.toFloat()
        )
        if (!previewMap && !runtimeMapVisible) {
            releaseMapController()
            if (mapTransparentFillPending) {
                clearMapBlock(mapContainer, mapContent, mapTripStatus, placeholder)
            } else {
                hideMapBlock()
            }
            return
        }
        mapContainer.visibility = View.VISIBLE
        mapContainer.background = null
        mapContainer.setBackgroundColor(Color.TRANSPARENT)
        val tripStatusReservedHeightPx = updateMapTripStatus(
            view = mapTripStatus,
            state = lastState,
            mapHeightPx = heightPx,
            preview = previewMap
        )
        if (previewMap) {
            hudMapController?.setVisible(false)
            mapContent.visibility = View.GONE
            placeholder.background = if (previewTarget == OverlayBroadcasts.PREVIEW_TARGET_MAP) {
                ContextCompat.getDrawable(displayContext, R.drawable.bg_nav_block_outline)
            } else {
                null
            }
            mapPlaceholderLabelView?.text = displayContext.getString(R.string.position_map_block_label)
            mapPlaceholderIconView?.let { icon ->
                val iconSizePx = MapRenderSettingsStore.current().roadEventIconSizePx
                    .coerceIn(ROAD_EVENT_ICON_SIZE_MIN_PX, ROAD_EVENT_ICON_SIZE_MAX_PX)
                icon.layoutParams = (icon.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = iconSizePx
                    height = iconSizePx
                    topMargin = 4
                } ?: LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    topMargin = 4
                }
            }
            placeholder.visibility = View.VISIBLE
        } else {
            mapContent.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            ensureLocalMapController(displayContext, mapContent).apply {
                attachTo(mapContent)
                setTripStatusReservedHeightPx(tripStatusReservedHeightPx)
                setVisible(true)
            }
        }
    }

    private fun releaseMapController() {
        hudMapController?.release()
        hudMapController = null
    }

    private fun hideMapBlock() {
        mapContainerView?.visibility = View.GONE
        mapContentView?.visibility = View.GONE
        mapTripStatusView?.visibility = View.GONE
        mapPlaceholderView?.visibility = View.GONE
    }

    private fun clearMapBlock(
        mapContainer: FrameLayout,
        mapContent: FrameLayout,
        mapTripStatus: MapTripStatusView,
        placeholder: FrameLayout
    ) {
        mapContainer.background = null
        mapContainer.setBackgroundColor(Color.TRANSPARENT)
        mapContent.setBackgroundColor(Color.TRANSPARENT)
        mapContent.visibility = View.GONE
        mapTripStatus.visibility = View.GONE
        placeholder.visibility = View.GONE
        mapContainer.visibility = View.VISIBLE
        mapContainer.invalidate()
        mapContainer.postInvalidateOnAnimation()
    }

    private fun logMapState(
        stage: String,
        previewMap: Boolean,
        hasMapRoute: Boolean,
        mapContainerReady: Boolean,
        mapContentReady: Boolean,
    ) {
        val key = "$stage|$mapEnabled|$hasMapRoute|$previewMap|$mapContainerReady|$mapContentReady"
        if (key == lastMapDebugKey) return
        lastMapDebugKey = key
        Log.d(
            HUD_OVERLAY_TAG,
            "map state: stage=$stage enabled=$mapEnabled hasRoute=$hasMapRoute preview=$previewMap " +
                "container=$mapContainerReady content=$mapContentReady"
        )
    }

    private fun removeMapView() {
        releaseMapController()
        hideMapBlock()
    }

    private fun updateMapTripStatus(
        view: MapTripStatusView,
        state: NavigationHudState,
        mapHeightPx: Int,
        preview: Boolean,
    ): Int {
        val bitmap = if (preview) {
            null
        } else {
            state.tripStatusBitmap?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        }
        val distance = if (preview) {
            context.getString(R.string.preview_distance_text)
        } else {
            state.distance.trim()
        }
        val arrival = if (preview) {
            context.getString(R.string.preview_trip_status_arrival_text)
        } else {
            resolveArrivalText(state)
        }
        val time = if (preview) {
            context.getString(R.string.preview_trip_status_eta_text)
        } else {
            state.time.trim()
        }
        val hasContent = bitmap != null || distance.isNotBlank() || arrival.isNotBlank() || time.isNotBlank()
        val targetHeight = resolveMapTripStatusHeightPx(mapHeightPx, bitmap != null)
        val targetVisible = MapRenderSettingsStore.current().tripStatusEnabled && (preview || hasContent)
        val params = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                targetHeight,
                Gravity.BOTTOM
            )
        if (
            params.width != FrameLayout.LayoutParams.MATCH_PARENT ||
            params.height != targetHeight ||
            params.gravity != Gravity.BOTTOM
        ) {
            view.layoutParams = params.apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
                height = targetHeight
                gravity = Gravity.BOTTOM
            }
        }
        view.updateContent(distance = distance, arrival = arrival, time = time, bitmap = bitmap)
        val targetVisibility = if (targetVisible) View.VISIBLE else View.GONE
        if (view.visibility != targetVisibility) {
            view.visibility = targetVisibility
        }
        return if (targetVisible) targetHeight else 0
    }

    private fun ensureLocalMapController(displayContext: Context, mapContent: FrameLayout): HudMapController {
        val existing = hudMapController
        if (existing != null) {
            return existing
        }
        return HudMapController(displayContext).also { controller ->
            hudMapController = controller
            controller.attachTo(mapContent)
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

    private fun resolveScaledLayoutWidthPx(widthDp: Float, scale: Float, density: Float): Int {
        val safeScale = scale.coerceAtLeast(0.01f)
        val visibleWidthPx = (widthDp * density).coerceAtLeast(1f)
        return (visibleWidthPx / safeScale).roundToInt().coerceAtLeast(1)
    }

    private fun updateNavLayoutWidth(density: Float): Int {
        val navWidthPx = resolveScaledLayoutWidthPx(navWidthDp, navScale, density)
        navContainer?.layoutParams = (navContainer?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = navWidthPx
        } ?: FrameLayout.LayoutParams(navWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        return navWidthPx
    }

    private fun positionView(
        view: View,
        positionDp: PointF,
        scale: Float,
        alpha: Float,
        density: Float,
        containerWidthPx: Float,
        containerHeightPx: Float,
        anchorXFraction: Float = 0f,
        anchorYFraction: Float = 0f
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
        // Keep anchoring consistent: scaling should expand from top-left,
        // otherwise centered pivot creates a visual gap near container edges.
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = alpha
        val xPx = positionDp.x * density
        val yPx = positionDp.y * density
        view.x = OverlayPositionMath.runtimeStartPx(
            positionPx = xPx,
            containerPx = containerWidthPx,
            contentPx = scaledWidth,
            anchorFraction = anchorXFraction
        )
        view.y = OverlayPositionMath.runtimeStartPx(
            positionPx = yPx,
            containerPx = containerHeightPx,
            contentPx = scaledHeight,
            anchorFraction = anchorYFraction
        )
    }

    private fun resolveViewSize(view: View, maxWidthPx: Float, exactWidth: Boolean): Pair<Float, Float> {
        val params = view.layoutParams
        val exactWidthPx = if (exactWidth && params != null && params.width > 0) {
            params.width.toFloat()
        } else {
            null
        }
        val widthSpec = if ((exactWidthPx ?: maxWidthPx) > 0f) {
            val width = (exactWidthPx ?: maxWidthPx).roundToInt()
            val mode = if (exactWidth) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            View.MeasureSpec.makeMeasureSpec(width, mode)
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
        return params.width == ViewGroup.LayoutParams.MATCH_PARENT || params.width > 0
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
