package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import android.util.Log
import java.util.Locale

private const val MIN_ARROW_NATIVE_UPDATE_INTERVAL_MS = 3000L

class NavigationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        touchNavigatorIntentTimeout(context, action)
        if (action.startsWith("com.yandex.") && shouldSuppressDuplicateYandexIntent(intent, action)) {
            return
        }
        if (action.startsWith("com.yandex.")) {
            Log.d(TAG, "Yandex intent: $action extras=${formatExtras(intent)}")
        } else {
            Log.d(TAG, "not Yandex intent: $action extras=${formatExtras(intent)}")
        }
        when (action) {
            ACTION_NAV_UPDATE, ACTION_NAV_UPDATE_DEBUG -> {
                Log.d(
                    TAG,
                    "route_active extra present=${intent.hasExtra(EXTRA_ROUTE_ACTIVE)} value=" +
                        "${intent.getBooleanExtra(EXTRA_ROUTE_ACTIVE, false)}"
                )
                val update = NavigationUpdate(
                    title = normalizeText(intent.getStringExtra(EXTRA_TITLE).orEmpty()),
                    text = normalizeText(intent.getStringExtra(EXTRA_TEXT).orEmpty()),
                    subtext = normalizeText(intent.getStringExtra(EXTRA_SUBTEXT).orEmpty()),
                    speedLimit = normalizeText(intent.getStringExtra(EXTRA_SPEED_LIMIT).orEmpty()),
                    routeActive = intent.getBooleanExtra(EXTRA_ROUTE_ACTIVE, false),
                    source = normalizeText(intent.getStringExtra(EXTRA_SOURCE).orEmpty()),
                    timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()),
                    hasImage = intent.getBooleanExtra(EXTRA_HAS_IMAGE, false)
                )
                Log.d(TAG, "Navigation update: $update")
                UiLogStore.append(
                    LogCategory.NAVIGATION,
                    "обновление title=\"${update.title}\" text=\"${update.text}\" subtext=\"${update.subtext}\" " +
                        "speedLimit=\"${update.speedLimit}\" active=${update.routeActive} source=\"${update.source}\""
                )
                if (intent.hasExtra(EXTRA_ROUTE_ACTIVE) && !update.routeActive) {
                    UiLogStore.append(LogCategory.NAVIGATION, "маршрут завершен (route_active=false)")
                    cancelNavigatorIntentTimeout()
                    dynamicHideTurnSpeedBucket = null
                    NavigationHudStore.reset(
                        intent.action.orEmpty(),
                        update.timestamp,
                        preserveSpeedLimit = true,
                        preserveRoadCamera = true,
                        preserveHudSpeed = true,
                        preserveStrelka = true
                    )
                    return
                }
                val primary = update.title.ifBlank { update.text }
                val secondary = listOf(update.text, update.subtext)
                    .filter { it.isNotBlank() && it != primary }
                    .joinToString(" • ")
                NavigationHudStore.update { state ->
                    state.copy(
                        primaryText = primary,
                        secondaryText = secondary,
                        speedLimit = update.speedLimit,
                        source = update.source.ifBlank { state.source },
                        routeActive = update.routeActive,
                        lastUpdated = update.timestamp,
                    lastAction = action,
                        rawTitle = update.title,
                        rawText = update.text,
                        rawSubtext = update.subtext,
                        rawSpeedLimit = update.speedLimit
                    )
                }
            }
            ACTION_YANDEX_MANEUVER -> {
                val bitmap = getBitmapExtra(intent, EXTRA_MANEUVER_BITMAP)
                val size = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "none"
                val maneuverTypeFromExtra = normalizeText(intent.getStringExtra(EXTRA_MANEUVER_TYPE).orEmpty())
                val useExtraType = maneuverTypeFromExtra.isNotBlank()
                val maneuverTypePredicted = if (useExtraType) "skipped" else bitmap?.let {
                    predictManeuverType(context, it)
                }.orEmpty()
                val maneuverType = if (useExtraType) maneuverTypeFromExtra else maneuverTypePredicted
                val now = System.currentTimeMillis()
                Log.d(TAG, "Yandex maneuver bitmap: $size typeFromExtra=\"$maneuverTypeFromExtra\" typePredicted=\"$maneuverTypePredicted\" final=\"$maneuverType\"")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс маневр bitmap=$size typeExtra=\"$maneuverTypeFromExtra\" typePredicted=\"$maneuverTypePredicted\" final=\"$maneuverType\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val maneuverChanged = hasManeuverChanged(
                        previousState = state,
                        incomingBitmap = bitmap,
                        incomingManeuverType = maneuverType
                    )
                    val next = state.copy(
                        maneuverBitmap = bitmap ?: state.maneuverBitmap,
                        maneuverType = maneuverType.ifBlank { state.maneuverType },
                        source = SOURCE_YANDEX,
                        lastUpdated = now,
                        lastAction = action,
                        secondaryText = if (maneuverChanged && state.secondaryText == state.rawNextStreet) {
                            ""
                        } else {
                            state.secondaryText
                        },
                        rawNextStreet = if (maneuverChanged) "" else state.rawNextStreet
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.MANEUVER) }
            }
            ACTION_YANDEX_NEXT_TEXT -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_NEXT_TEXT).orEmpty())
                Log.d(TAG, "Yandex next text: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс next_text=\"$raw\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val unit = extractTrailingUnit(raw)
                    val next = state.copy(
                        primaryText = raw.takeIf { it.isNotBlank() } ?: state.primaryText,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawNextText = raw,
                        distanceUnit = unit.ifBlank { state.distanceUnit }
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.DISTANCE) }
            }
            ACTION_YANDEX_NEXT_STREET -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_NEXT_STREET).orEmpty())
                Log.d(TAG, "Yandex next street: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс next_street=\"$raw\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val next = state.copy(
                        secondaryText = raw.takeIf { it.isNotBlank() } ?: state.secondaryText,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawNextStreet = raw
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.STREET) }
            }
            ACTION_YANDEX_SPEEDLIMIT -> {
                if (OverlayPrefs.speedLimitFromHudSpeed(context)) {
                    Log.d(TAG, "Yandex speedlimit ignored: HUD Speed preferred")
                    return
                }
                val raw = normalizeText(intent.getStringExtra(EXTRA_SPEEDLIMIT_TEXT).orEmpty())
                Log.d(TAG, "Yandex speedlimit: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс speedlimit=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        speedLimit = raw.takeIf { it.isNotBlank() } ?: state.speedLimit,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawSpeedLimit = raw
                    )
                }
            }
            ACTION_YANDEX_ARRIVAL -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_ARRIVAL_TEXT).orEmpty())
                Log.d(TAG, "Yandex arrival: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс прибытие: $raw")
                NavigationHudStore.update { state ->
                    state.copy(
                        arrival = raw,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawArrival = raw
                    )
                }
                // Don't end navigation here - only end when notification is removed
            }
            ACTION_YANDEX_DISTANCE -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_DISTANCE_TEXT).orEmpty())
                Log.d(TAG, "Yandex distance: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс distance=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        distance = raw.takeIf { it.isNotBlank() } ?: state.distance,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawDistance = raw
                    )
                }
            }
            ACTION_YANDEX_TIME -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_TIME_TEXT).orEmpty())
                Log.d(TAG, "Yandex time: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс time=\"$raw\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val next = state.copy(
                        time = raw.takeIf { it.isNotBlank() } ?: state.time,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawTime = raw
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.TIME) }
            }
            ACTION_YANDEX_TRIP_STATUS_BITMAP -> {
                val bitmap = getBitmapExtra(intent, EXTRA_TRIP_STATUS_BITMAP)
                    ?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
                val size = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "none"
                Log.d(TAG, "Yandex trip status bitmap: $size")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс trip status bitmap=$size")
                NavigationHudStore.update { state ->
                    state.copy(
                        tripStatusBitmap = bitmap,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action
                    )
                }
            }
            ACTION_YANDEX_NAV_ACTIVE -> {
                val isActive = intent.getBooleanExtra(EXTRA_NAV_IS_ACTIVE, false)
                Log.d(TAG, "Yandex navigation state: isActive=$isActive")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс состояние навигации: ${if (isActive) "активна" else "завершена"}")
                // NAV_ACTIVE is ignored for navigation state; keep for diagnostics only.
            }
            ACTION_YANDEX_ROADCAMERA -> {
                val cameraId = normalizeText(intent.getStringExtra(EXTRA_CAMERA_ID).orEmpty())
                val distance = normalizeText(intent.getStringExtra(EXTRA_CAMERA_DISTANCE).orEmpty())
                val icon = getBitmapExtra(intent, EXTRA_CAMERA_ICON)
                val iconSize = if (icon != null) "${icon.width}x${icon.height}" else "none"

                if (cameraId.isBlank()) {
                    Log.d(TAG, "Yandex road camera: hidden")
                    UiLogStore.append(LogCategory.NAVIGATION, "яндекс дорожная камера: скрыта")
                    cancelRoadCameraHide()
                    clearRoadCamera(context, action)
                } else {
                    Log.d(TAG, "Yandex road camera: id=\"$cameraId\" distance=\"$distance\" icon=$iconSize")
                    UiLogStore.append(LogCategory.NAVIGATION, "яндекс дорожная камера: id=\"$cameraId\" distance=\"$distance\" icon=$iconSize")
                    roadCameraContext = context.applicationContext
                    NavigationHudStore.update { state ->
                        state.copy(
                            roadCameraId = cameraId,
                            roadCameraDistance = distance,
                            roadCameraIcon = icon,
                            source = SOURCE_YANDEX,
                            lastUpdated = System.currentTimeMillis(),
                            lastAction = action
                        )
                    }
                    scheduleRoadCameraHide(context)
                }
            }
            ACTION_WINDSHIELD_TRAFFIC_LIGHT -> {
                val color = normalizeText(intent.getStringExtra(EXTRA_TL_COLOR).orEmpty()).uppercase(Locale.US)
                val countdown = normalizeText(intent.getStringExtra(EXTRA_TL_COUNTDOWN).orEmpty())
                val arrow = normalizeText(intent.getStringExtra(EXTRA_TL_ARROW).orEmpty()).uppercase(Locale.US)
                val id = readStringOrIntExtra(intent, EXTRA_TL_ID)
                val position = intent.getIntExtra(EXTRA_TL_POSITION, 0)
                val source = intent.getStringExtra(EXTRA_TL_SOURCE).orEmpty()
                Log.d(
                    TAG,
                    "Windshield traffic light: color=\"$color\" countdown=\"$countdown\" arrow=\"$arrow\" " +
                        "id=\"$id\" position=$position source=\"$source\""
                )
                handleWindshieldTrafficLight(
                    context = context,
                    action = action,
                    color = color,
                    countdown = countdown,
                    arrow = arrow,
                    id = id,
                    position = position
                )
            }
            ACTION_YANDEX_ROUTE_POLYLINE -> {
                val routeActive = intent.getBooleanExtra(EXTRA_ROUTE_ACTIVE_FLAG, false)
                val routeId = normalizeText(intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty())
                val lats = intent.getDoubleArrayExtra(EXTRA_POLYLINE_LATS)
                val lons = intent.getDoubleArrayExtra(EXTRA_POLYLINE_LONS)
                val count = intent.getIntExtra(EXTRA_POLYLINE_COUNT, 0)

                val latsInfo = if (lats != null) "size=${lats.size} first=${lats.firstOrNull()}" else "null"
                val lonsInfo = if (lons != null) "size=${lons.size} first=${lons.firstOrNull()}" else "null"

                Log.d(TAG, "Yandex route polyline: active=$routeActive id=\"$routeId\" count=$count lats=[$latsInfo] lons=[$lonsInfo]")
                UiLogStore.append(
                    LogCategory.NAVIGATION,
                    "яндекс полилиния маршрута: active=$routeActive id=\"$routeId\" points=$count"
                )

                val safeCount = if (lats != null && lons != null) {
                    val arrayCount = minOf(lats.size, lons.size)
                    if (count > 0) minOf(count, arrayCount) else arrayCount
                } else {
                    0
                }
                if (routeActive && lats != null && lons != null && safeCount >= 2) {
                    val points = buildList {
                        for (index in 0 until safeCount) {
                            val lat = lats[index]
                            val lon = lons[index]
                            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                                add(LatLng(lat, lon))
                            }
                        }
                    }
                    Log.d(TAG, "Route polyline: ${points.size} valid points received")
                    UiLogStore.append(LogCategory.NAVIGATION, "получена полилиния: ${points.size} точек")
                    MapRouteTelemetryStore.replaceRoutePolyline(context.applicationContext, routeId, points)
                } else if (!routeActive) {
                    Log.d(TAG, "Route polyline: inactive update, clearing current route")
                    UiLogStore.append(LogCategory.NAVIGATION, "полилиния inactive: очищаем текущий маршрут")
                    MapRouteTelemetryStore.clearRoutePolyline(context.applicationContext)
                } else {
                    Log.w(
                        TAG,
                        "Route polyline ignored: invalid payload active=$routeActive safeCount=$safeCount"
                    )
                }
            }
            ACTION_NATIVE_NAV_STOP -> {
                endNavigation(context, action, "штатная навигация: стоп")
            }
            ACTION_HUDSPEED_UPDATE -> {
                val hasCamera = intent.getBooleanExtra(HUDSPEED_HAS_CAMERA, false)
                val hasGps = intent.getBooleanExtra(HUDSPEED_HAS_GPS, false)

                val distance = intent.getIntExtra(HUDSPEED_DISTANCE, -1)
                val limit1 = intent.getIntExtra(HUDSPEED_LIMIT_1, -1)
                val limit2 = intent.getIntExtra(HUDSPEED_LIMIT_2, -1)
                val camType = intent.getIntExtra(HUDSPEED_CAM_TYPE, -1)
                val camFlag = intent.getIntExtra(HUDSPEED_CAM_FLAG, -1)

                Log.d(
                    TAG,
                    "HUDSPEED_UPDATE: hasCamera=$hasCamera hasGps=$hasGps distance=$distance limit1=$limit1 limit2=$limit2 camType=$camType camFlag=$camFlag extras=${formatExtras(intent)}"
                )

                val resolvedDistance = distance.takeIf { hasCamera && it >= 0 }
                val resolvedCamType = camType.takeIf { hasCamera && it >= 0 }
                val resolvedCamFlag = camFlag.takeIf { hasCamera && it >= 0 }
                val resolvedLimit1 = limit1.takeIf { hasCamera && it > 0 }
                NavigationHudStore.update { state ->
                    val now = System.currentTimeMillis()
                    val hudSpeedDataChanged = hasCamera != state.hudSpeedHasCamera ||
                        hasGps != state.hudSpeedHasGps ||
                        resolvedDistance != state.hudSpeedDistanceMeters ||
                        resolvedCamType != state.hudSpeedCamType ||
                        resolvedCamFlag != state.hudSpeedCamFlag ||
                        resolvedLimit1 != state.hudSpeedLimit1
                    val hudSpeedUpdatedAt = when {
                        !hasCamera -> 0L
                        hudSpeedDataChanged || state.hudSpeedUpdatedAt <= 0L -> now
                        else -> state.hudSpeedUpdatedAt
                    }
                    val preferHudSpeedLimit = OverlayPrefs.speedLimitFromHudSpeed(context)
                    val useHudSpeedLimit = preferHudSpeedLimit && resolvedLimit1 != null
                    val clearHudSpeedLimit = preferHudSpeedLimit && !hasCamera
                    state.copy(
                        hudSpeedHasCamera = hasCamera,
                        hudSpeedHasGps = hasGps,
                        hudSpeedDistanceMeters = resolvedDistance,
                        hudSpeedCamType = resolvedCamType,
                        hudSpeedCamFlag = resolvedCamFlag,
                        hudSpeedLimit1 = resolvedLimit1,
                        hudSpeedUpdatedAt = hudSpeedUpdatedAt,
                        speedLimit = when {
                            useHudSpeedLimit -> resolvedLimit1.toString()
                            clearHudSpeedLimit -> ""
                            else -> state.speedLimit
                        },
                        rawSpeedLimit = when {
                            useHudSpeedLimit -> resolvedLimit1.toString()
                            clearHudSpeedLimit -> ""
                            else -> state.rawSpeedLimit
                        },
                        source = SOURCE_HUDSPEED,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action
                    )
                }
            }
            ACTION_STRELKA_EVENT_START -> {
                val now = System.currentTimeMillis()
                Log.d(TAG, "Strelka event started")
                UiLogStore.append(LogCategory.NAVIGATION, "strelka event: start")
                NavigationHudStore.update { state ->
                    state.copy(
                        strelkaActive = true,
                        strelkaBitmap = null,
                        strelkaUpdatedAt = now,
                        source = SOURCE_STRELKA,
                        lastUpdated = now,
                        lastAction = action
                    )
                }
            }
            ACTION_STRELKA_EVENT_END -> {
                val now = System.currentTimeMillis()
                Log.d(TAG, "Strelka event ended")
                UiLogStore.append(LogCategory.NAVIGATION, "strelka event: end")
                NavigationHudStore.update { state ->
                    state.copy(
                        strelkaActive = false,
                        strelkaBitmap = null,
                        strelkaUpdatedAt = now,
                        source = SOURCE_STRELKA,
                        lastUpdated = now,
                        lastAction = action
                    )
                }
            }
            ACTION_STRELKA_OVERLAY_BITMAP -> {
                val ready = intent.getBooleanExtra(EXTRA_STRELKA_BITMAP_READY, false)
                val bitmapBytes = intent.getByteArrayExtra(EXTRA_STRELKA_BITMAP_PNG)
                val bitmap = decodeStrelkaBitmap(bitmapBytes)
                val size = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "none"
                Log.d(TAG, "Strelka overlay bitmap: ready=$ready bytes=${bitmapBytes?.size ?: 0} bitmap=$size")
                if (!ready) {
                    UiLogStore.append(LogCategory.NAVIGATION, "strelka bitmap ignored: ready=false")
                    return
                }
                if (bitmap == null) {
                    UiLogStore.append(LogCategory.NAVIGATION, "strelka bitmap ignored: decode failed")
                    return
                }
                UiLogStore.append(LogCategory.NAVIGATION, "strelka bitmap=$size")
                val now = System.currentTimeMillis()
                NavigationHudStore.update { state ->
                    state.copy(
                        strelkaActive = true,
                        strelkaBitmap = bitmap,
                        strelkaUpdatedAt = now,
                        source = SOURCE_STRELKA,
                        lastUpdated = now,
                        lastAction = action
                    )
                }
            }
        }
    }

    data class NavigationUpdate(
        val title: String,
        val text: String,
        val subtext: String,
        val speedLimit: String,
        val routeActive: Boolean,
        val source: String,
        val timestamp: Long,
        val hasImage: Boolean
    )

    companion object {
        private const val TAG = "NavigationReceiver"
        private const val NATIVE_NAV_DEBOUNCE_MS = 100L
        private const val YANDEX_DUPLICATE_SUPPRESSION_MS = 400L

        private val nativeNavHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingNativeNavUpdate: Runnable? = null
        private val trafficLightHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingTrafficLightCleanup: Runnable? = null
        private val activeTrafficLights = LinkedHashMap<String, TrafficLightInfo>()
        private val windshieldBatcher = WindshieldTrafficLightBatcher()
        private var pendingWindshieldCommit: Runnable? = null
        private val roadCameraHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingRoadCameraHide: Runnable? = null
        private val navigatorIntentTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingNavigatorIntentTimeout: Runnable? = null
        private var navigatorIntentTimeoutContext: Context? = null
        private var roadCameraContext: Context? = null
        private var trafficLightContext: Context? = null

        @Volatile
        private var lastArrowNativeUpdateAt: Long = 0L
        @Volatile
        private var lastNavigatorIntentAt: Long = 0L
        @Volatile
        private var lastNativeNavPayload: NativeNavPayload? = null
        private var dynamicHideTurnSpeedBucket: OverlayPrefs.DynamicHideTurnSpeedBucket? = null
        private val recentYandexPayloads = LinkedHashMap<String, Long>()

        const val ACTION_NAV_UPDATE = "plus.monjaro.NAVIGATION_UPDATE"
        const val ACTION_NAV_UPDATE_DEBUG = "debug.monjaro.NAVIGATION_UPDATE"

        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SUBTEXT = "subtext"
        const val EXTRA_SPEED_LIMIT = "speedlimit"
        const val EXTRA_ROUTE_ACTIVE = "route_active"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_HAS_IMAGE = "has_image"

        const val ACTION_YANDEX_MANEUVER = "com.yandex.MANEUVER"
        const val ACTION_YANDEX_NEXT_TEXT = "com.yandex.NIXT"
        const val ACTION_YANDEX_NEXT_STREET = "com.yandex.NEXTSTREET"
        const val ACTION_YANDEX_SPEEDLIMIT = "com.yandex.SPEEDLIMIT"
        const val ACTION_YANDEX_ARRIVAL = "com.yandex.ARRIVAL"
        const val ACTION_YANDEX_DISTANCE = "com.yandex.DISTANCE"
        const val ACTION_YANDEX_TIME = "com.yandex.TIME"
        const val ACTION_YANDEX_TRIP_STATUS_BITMAP = "com.yandex.TRIP_STATUS_BITMAP"
        const val ACTION_YANDEX_NAV_ACTIVE = "com.yandex.NAV_ACTIVE"
        const val ACTION_YANDEX_ROADCAMERA = "com.yandex.ROADCAMERA"
        const val ACTION_YANDEX_ROUTE_POLYLINE = "com.yandex.ROUTE_POLYLINE"
        const val ACTION_WINDSHIELD_TRAFFIC_LIGHT = "plus.monjaro.TRAFFIC_LIGHT_UPDATE"
        const val ACTION_NATIVE_NAV_STOP = "com.g992.anhud.NATIVE_NAV_STOP"
        const val ACTION_HUDSPEED_UPDATE = "air.strelkasd.CAMERA_INFO_CHANGED"
        const val ACTION_STRELKA_EVENT_START = "com.aleksan.button.STRELKA_EVENT_START"
        const val ACTION_STRELKA_EVENT_END = "com.aleksan.button.STRELKA_EVENT_END"
        const val ACTION_STRELKA_OVERLAY_BITMAP = "com.aleksan.button.STRELKA_OVERLAY_BITMAP"
        private const val ACTION_NAV_UPDATES_TIMEOUT = "nav_updates_timeout"

        const val EXTRA_MANEUVER_BITMAP = "maneuver_bitmap"
        const val EXTRA_MANEUVER_TYPE = "maneuver_type"
        const val EXTRA_NEXT_TEXT = "next_text"
        const val EXTRA_NEXT_STREET = "next_street"
        const val EXTRA_SPEEDLIMIT_TEXT = "speedlimit_text"
        const val EXTRA_ARRIVAL_TEXT = "Arrival_text"
        const val EXTRA_DISTANCE_TEXT = "Distance_text"
        const val EXTRA_TIME_TEXT = "Time_text"
        const val EXTRA_TRIP_STATUS_BITMAP = "trip_status_bitmap"
        const val EXTRA_NAV_IS_ACTIVE = "is_active"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_CAMERA_DISTANCE = "distance_text"
        const val EXTRA_CAMERA_ICON = "camera_icon"
        const val EXTRA_TL_COLOR = "tl_color"
        const val EXTRA_TL_COUNTDOWN = "tl_countdown"
        const val EXTRA_TL_ARROW = "tl_arrow"
        const val EXTRA_TL_ID = "tl_id"
        const val EXTRA_TL_POSITION = "tl_position"
        const val EXTRA_TL_SOURCE = "tl_source"
        const val EXTRA_ROUTE_ACTIVE_FLAG = "route_active"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_POLYLINE_LATS = "polyline_lats"
        const val EXTRA_POLYLINE_LONS = "polyline_lons"
        const val EXTRA_POLYLINE_COUNT = "polyline_count"
        const val HUDSPEED_HAS_CAMERA = "hasCamera"
        const val HUDSPEED_HAS_GPS = "hasGps"
        const val HUDSPEED_DISTANCE = "distance"
        const val HUDSPEED_LIMIT_1 = "limit1"
        const val HUDSPEED_LIMIT_2 = "limit2"
        const val HUDSPEED_CAM_TYPE = "camType"
        const val HUDSPEED_CAM_FLAG = "camFlag"
        const val EXTRA_STRELKA_BITMAP_PNG = "bitmap_png"
        const val EXTRA_STRELKA_BITMAP_READY = "bitmap_ready"

        private const val SOURCE_YANDEX = "yandex"
        private const val SOURCE_HUDSPEED = "hudspeed"
        private const val SOURCE_STRELKA = "strelka"
        const val DEFAULT_NATIVE_TURN_ID = 101
        private const val MAX_SUPPORTED_TURN_ID = 150

        fun onNavigationStartedFromNotification(context: Context) {
            val appContext = context.applicationContext
            navigatorIntentTimeoutContext = appContext
            lastNavigatorIntentAt = System.currentTimeMillis()
            scheduleNavigatorIntentTimeout(appContext)
        }

        fun clearNavigatorIntentTimeout() {
            cancelNavigatorIntentTimeout()
        }

        private fun touchNavigatorIntentTimeout(context: Context, action: String) {
            if (!isNavigatorIntentAction(action)) {
                return
            }
            val appContext = context.applicationContext
            navigatorIntentTimeoutContext = appContext
            lastNavigatorIntentAt = System.currentTimeMillis()
            scheduleNavigatorIntentTimeout(appContext)
        }

        private fun isNavigatorIntentAction(action: String): Boolean {
            return action == ACTION_NAV_UPDATE ||
                action == ACTION_NAV_UPDATE_DEBUG ||
                action == ACTION_YANDEX_MANEUVER ||
                action == ACTION_YANDEX_NEXT_TEXT ||
                action == ACTION_YANDEX_NEXT_STREET ||
                action == ACTION_YANDEX_SPEEDLIMIT ||
                action == ACTION_YANDEX_ARRIVAL ||
                action == ACTION_YANDEX_DISTANCE ||
                action == ACTION_YANDEX_TIME ||
                action == ACTION_YANDEX_TRIP_STATUS_BITMAP ||
                action == ACTION_YANDEX_NAV_ACTIVE ||
                action == ACTION_YANDEX_ROADCAMERA ||
                action == ACTION_WINDSHIELD_TRAFFIC_LIGHT ||
                action == ACTION_YANDEX_ROUTE_POLYLINE
        }

        private fun scheduleNavigatorIntentTimeout(context: Context) {
            pendingNavigatorIntentTimeout?.let { navigatorIntentTimeoutHandler.removeCallbacks(it) }
            pendingNavigatorIntentTimeout = null
            val timeoutSeconds = OverlayPrefs.navUpdatesEndTimeout(context)
            if (timeoutSeconds <= 0) {
                return
            }
            val delayMs = timeoutSeconds.toLong() * 1000L
            val runnable = Runnable {
                pendingNavigatorIntentTimeout = null
                handleNavigatorIntentTimeout()
            }
            pendingNavigatorIntentTimeout = runnable
            navigatorIntentTimeoutHandler.postDelayed(runnable, delayMs)
        }

        private fun handleNavigatorIntentTimeout() {
            val context = navigatorIntentTimeoutContext ?: return
            val timeoutSeconds = OverlayPrefs.navUpdatesEndTimeout(context)
            if (timeoutSeconds <= 0) {
                cancelNavigatorIntentTimeout()
                return
            }
            val timeoutMs = timeoutSeconds.toLong() * 1000L
            val lastAt = lastNavigatorIntentAt
            if (lastAt <= 0L) {
                return
            }
            val elapsed = System.currentTimeMillis() - lastAt
            if (elapsed < timeoutMs) {
                scheduleNavigatorIntentTimeout(context)
                return
            }
            if (!isNavigationActiveForTimeout()) {
                return
            }
            endNavigation(
                context,
                ACTION_NAV_UPDATES_TIMEOUT,
                "таймаут обновлений навигатора: стоп"
            )
        }

        private fun isNavigationActiveForTimeout(): Boolean {
            val state = NavigationHudStore.snapshot()
            return state.routeActive == true ||
                state.rawTitle.isNotBlank() ||
                state.rawText.isNotBlank() ||
                state.rawSubtext.isNotBlank() ||
                state.rawNextText.isNotBlank() ||
                state.rawNextStreet.isNotBlank() ||
                state.rawDistance.isNotBlank() ||
                state.rawArrival.isNotBlank() ||
                state.rawTime.isNotBlank() ||
                state.maneuverBitmap != null ||
                state.tripStatusBitmap != null ||
                state.maneuverType.isNotBlank()
        }

        private fun cancelNavigatorIntentTimeout() {
            pendingNavigatorIntentTimeout?.let { navigatorIntentTimeoutHandler.removeCallbacks(it) }
            pendingNavigatorIntentTimeout = null
            lastNavigatorIntentAt = 0L
            navigatorIntentTimeoutContext = null
        }

        private fun normalizeText(value: String): String {
            if (value.isBlank()) {
                return value
            }
            val normalized = value
                .replace('\u00A0', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            return normalized
        }

        private fun shouldSuppressDuplicateYandexIntent(intent: Intent, action: String): Boolean {
            val signature = buildYandexDuplicateSignature(intent, action) ?: return false
            val now = System.currentTimeMillis()
            pruneRecentYandexPayloads(now)
            val previousAt = recentYandexPayloads.put(signature, now)
            return previousAt != null && now - previousAt < YANDEX_DUPLICATE_SUPPRESSION_MS
        }

        private fun buildYandexDuplicateSignature(intent: Intent, action: String): String? {
            return when (action) {
                ACTION_YANDEX_MANEUVER -> {
                    val bitmap = getBitmapExtra(intent, EXTRA_MANEUVER_BITMAP)
                    val bitmapKey = if (bitmap != null) {
                        "${bitmap.width}x${bitmap.height}:${bitmap.config}"
                    } else {
                        "none"
                    }
                    val maneuverType = normalizeText(intent.getStringExtra(EXTRA_MANEUVER_TYPE).orEmpty())
                    "$action:$maneuverType:$bitmapKey"
                }
                ACTION_YANDEX_NEXT_TEXT -> {
                    "$action:${normalizeText(intent.getStringExtra(EXTRA_NEXT_TEXT).orEmpty())}"
                }
                ACTION_YANDEX_NEXT_STREET -> {
                    "$action:${normalizeText(intent.getStringExtra(EXTRA_NEXT_STREET).orEmpty())}"
                }
                ACTION_YANDEX_SPEEDLIMIT -> {
                    "$action:${normalizeText(intent.getStringExtra(EXTRA_SPEEDLIMIT_TEXT).orEmpty())}"
                }
                ACTION_YANDEX_ARRIVAL -> {
                    "$action:${normalizeText(intent.getStringExtra(EXTRA_ARRIVAL_TEXT).orEmpty())}"
                }
                ACTION_YANDEX_DISTANCE -> {
                    "$action:${normalizeText(intent.getStringExtra(EXTRA_DISTANCE_TEXT).orEmpty())}"
                }
                ACTION_YANDEX_TIME -> {
                    "$action:${normalizeText(intent.getStringExtra(EXTRA_TIME_TEXT).orEmpty())}"
                }
                ACTION_YANDEX_TRIP_STATUS_BITMAP -> {
                    val bitmap = getBitmapExtra(intent, EXTRA_TRIP_STATUS_BITMAP)
                    val bitmapKey = if (bitmap != null) {
                        "${bitmap.width}x${bitmap.height}:${bitmap.generationId}"
                    } else {
                        "none"
                    }
                    "$action:$bitmapKey"
                }
                ACTION_YANDEX_NAV_ACTIVE -> {
                    "$action:${intent.getBooleanExtra(EXTRA_NAV_IS_ACTIVE, false)}"
                }
                else -> null
            }
        }

        private fun pruneRecentYandexPayloads(now: Long) {
            if (recentYandexPayloads.isEmpty()) {
                return
            }
            val cutoff = now - YANDEX_DUPLICATE_SUPPRESSION_MS
            recentYandexPayloads.entries.removeIf { (_, updatedAt) -> updatedAt < cutoff }
        }

        private fun extractTrailingUnit(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                return ""
            }
            val match = Regex("[^0-9.,\\s]+\\s*$").find(trimmed)
            return match?.value?.trim().orEmpty()
        }

        private fun getBitmapExtra(intent: Intent, key: String): Bitmap? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key)
            }
        }

        private fun formatExtras(intent: Intent): String {
            val extras = intent.extras ?: return "{}"
            val entries = extras.keySet().sorted().map { key ->
                @Suppress("DEPRECATION")
                val value = extras.get(key)
                val formatted = describeExtraValue(value)
                "$key=$formatted"
            }
            return "{${entries.joinToString(", ")}}"
        }

        private fun describeExtraValue(value: Any?): String {
            return when (value) {
                null -> "null"
                is Bitmap -> "Bitmap(${value.width}x${value.height}, ${value.config})"
                is Bundle -> "Bundle(keys=${value.keySet().sorted().joinToString(",")}, size=${value.size()})"
                is ByteArray -> "ByteArray(size=${value.size})"
                is IntArray -> "IntArray(size=${value.size})"
                is LongArray -> "LongArray(size=${value.size})"
                is FloatArray -> "FloatArray(size=${value.size})"
                is DoubleArray -> "DoubleArray(size=${value.size})"
                is BooleanArray -> "BooleanArray(size=${value.size})"
                is Array<*> -> {
                    val type = value.firstOrNull()?.javaClass?.simpleName ?: "Unknown"
                    "Array<$type>(size=${value.size})"
                }
                else -> value.toString().take(200)
            }
        }

        private fun decodeStrelkaBitmap(bytes: ByteArray?): Bitmap? {
            if (bytes == null || bytes.isEmpty()) {
                return null
            }
            return runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
            }.getOrNull()
        }

        /**
         * Schedule native navigation update with debouncing.
         * Waits 100ms for more broadcasts to arrive, then sends everything as a batch.
         */
        private fun maybeUpdateNativeNavigation(
            context: Context,
            state: NavigationHudState,
            trigger: NativeNavUpdateTrigger
        ) {
            if (!OverlayPrefs.nativeNavEnabled(context)) {
                return
            }
            if (trigger == NativeNavUpdateTrigger.MANEUVER && !canSendArrowUpdate()) {
                return
            }

            // Cancel any pending update
            pendingNativeNavUpdate?.let { nativeNavHandler.removeCallbacks(it) }

            // Schedule new update after debounce delay
            val runnable = Runnable {
                pendingNativeNavUpdate = null
                sendNativeNavUpdate(context)
            }
            pendingNativeNavUpdate = runnable
            nativeNavHandler.postDelayed(runnable, NATIVE_NAV_DEBOUNCE_MS)
        }

        private fun sendNativeNavUpdate(context: Context) {
            val state = NavigationHudStore.snapshot()
            if (!OverlayPrefs.nativeNavEnabled(context)) {
                Log.d(TAG, "Native nav disabled in settings, skipping start/update")
                return
            }
            val maneuverType = state.maneuverType.trim()
            val street = state.rawNextStreet.trim()
            val maneuverDistanceMeters = resolveManeuverDistanceMeters(state)
            if (shouldHideNativeNavigationByDistance(context, maneuverDistanceMeters)) {
                val thresholdMeters = resolveDynamicHideTurnThresholdMeters(context, state.speedKmh)
                if (NativeNavigationController.isActive()) {
                    Log.d(
                        TAG,
                        "Stopping native navigation: maneuver distance $maneuverDistanceMeters m exceeds threshold $thresholdMeters m"
                    )
                    NativeNavigationController.stopNavigation(context)
                }
                lastNativeNavPayload = null
                return
            }
            val destinationDistanceText = state.rawDistance.trim()
            val timeText = state.rawTime.trim()
            val distanceMeters = maneuverDistanceMeters ?: 0
            val destinationDistanceMeters = parseDistanceMeters(destinationDistanceText) ?: 0
            val etaSeconds = parseEtaSeconds(timeText) ?: 0
            val turnId = resolveTurnId(context, maneuverType)
            val payload = NativeNavPayload(
                turnId = turnId,
                street = street,
                distanceToManeuverMeters = distanceMeters,
                distanceToDestinationMeters = destinationDistanceMeters,
                totalDistanceToDestinationMeters = destinationDistanceMeters,
                etaSeconds = etaSeconds
            )

            // Only start native navigation if we have actual navigation data
            val hasNavigationData = turnId != DEFAULT_NATIVE_TURN_ID ||
                distanceMeters > 0 ||
                destinationDistanceMeters > 0
            if (!hasNavigationData) {
                Log.d(TAG, "No navigation data yet, skipping native nav update")
                return
            }
            if (!NativeNavigationController.ensureInitialized(context)) {
                Log.w(TAG, "Native nav update skipped: DIM interaction not available")
                Log.d(
                    TAG,
                    "Native nav update (would send): turnId=$turnId street=$street dist=$distanceMeters " +
                        "dest=$destinationDistanceMeters eta=$etaSeconds"
                )
                return
            }

            if (NativeNavigationController.isActive() && lastNativeNavPayload == payload) {
                Log.d(TAG, "Native nav update skipped (no changes)")
                return
            }

            NavigationHudStore.update { current ->
                if (current.nativeTurnId == turnId) {
                    current
                } else {
                    current.copy(nativeTurnId = turnId)
                }
            }
            if (!NativeNavigationController.isActive()) {
                Log.d(TAG, "Starting native navigation with data: turnId=$turnId dist=$distanceMeters")
                NativeNavigationController.startNavigation(context)
            }
            NativeNavigationController.updateNavigation(
                context = context,
                turnId = turnId,
                streetName = street,
                distanceToManeuverMeters = distanceMeters,
                distanceToDestinationMeters = destinationDistanceMeters,
                totalDistanceToDestinationMeters = destinationDistanceMeters,
                etaSeconds = etaSeconds
            )
            lastNativeNavPayload = payload
            Log.d(
                TAG,
                "Native nav update sent: turnId=$turnId street=$street dist=$distanceMeters dest=$destinationDistanceMeters eta=$etaSeconds"
            )
        }

        private fun shouldHideNativeNavigationByDistance(
            context: Context,
            maneuverDistanceMeters: Int?
        ): Boolean {
            if (!OverlayPrefs.hideTurnWhenFarEnabled(context)) {
                dynamicHideTurnSpeedBucket = null
                return false
            }
            val distanceMeters = maneuverDistanceMeters ?: return false
            val speedKmh = NavigationHudStore.snapshot().speedKmh
            val thresholdMeters = resolveDynamicHideTurnThresholdMeters(context, speedKmh)
            return distanceMeters > thresholdMeters
        }

        private fun resolveDynamicHideTurnThresholdMeters(context: Context, speedKmh: Int?): Int {
            val resolution = OverlayPrefs.resolveHideTurnThreshold(
                context = context,
                speedKmh = speedKmh,
                currentBucket = dynamicHideTurnSpeedBucket
            )
            dynamicHideTurnSpeedBucket = resolution.bucket
            return resolution.thresholdMeters
        }

        private fun resolveManeuverDistanceMeters(state: NavigationHudState): Int? {
            val rawNextTextWithUnit = appendUnitIfMissing(state.rawNextText, state.distanceUnit)
            parseDistanceMeters(rawNextTextWithUnit)?.let { return it }
            parseDistanceMeters(state.primaryText)?.let { return it }
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

        private fun canSendArrowUpdate(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastArrowNativeUpdateAt < MIN_ARROW_NATIVE_UPDATE_INTERVAL_MS) {
                return false
            }
            lastArrowNativeUpdateAt = now
            return true
        }

        private enum class NativeNavUpdateTrigger {
            MANEUVER,
            DISTANCE,
            STREET,
            TIME
        }

        private fun endNavigation(context: Context, action: String, reason: String) {
            Log.d(TAG, "Navigation ended: $reason")
            UiLogStore.append(LogCategory.NAVIGATION, reason)
            cancelNavigatorIntentTimeout()
            recentYandexPayloads.clear()
            // Only stop native navigation if it was enabled
            if (OverlayPrefs.nativeNavEnabled(context)) {
                NativeNavigationController.stopNavigation(context)
            }
            lastNativeNavPayload = null
            dynamicHideTurnSpeedBucket = null
            NavigationHudStore.reset(
                action,
                preserveSpeedLimit = true,
                preserveRoadCamera = true,
                preserveHudSpeed = true,
                preserveStrelka = true
            )
        }

        private fun hasManeuverChanged(
            previousState: NavigationHudState,
            incomingBitmap: Bitmap?,
            incomingManeuverType: String
        ): Boolean {
            val previousSignature = maneuverSignature(
                bitmap = previousState.maneuverBitmap,
                maneuverType = previousState.maneuverType
            ) ?: return false
            val nextSignature = maneuverSignature(
                bitmap = incomingBitmap ?: previousState.maneuverBitmap,
                maneuverType = incomingManeuverType.ifBlank { previousState.maneuverType }
            ) ?: return false
            return previousSignature != nextSignature
        }

        private fun maneuverSignature(bitmap: Bitmap?, maneuverType: String): String? {
            val normalizedType = maneuverType.trim()
            val bitmapKey = if (bitmap != null) {
                "${bitmap.width}x${bitmap.height}:${bitmap.config}"
            } else {
                "none"
            }
            if (normalizedType.isBlank() && bitmap == null) {
                return null
            }
            return "$normalizedType:$bitmapKey"
        }

        private fun handleWindshieldTrafficLight(
            context: Context,
            action: String,
            color: String,
            countdown: String,
            arrow: String,
            id: String,
            position: Int
        ) {
            val now = System.currentTimeMillis()
            trafficLightContext = context.applicationContext
            if (WindshieldTrafficLightBatcher.isClearSignal(color, id, countdown, arrow)) {
                cancelWindshieldCommit()
                windshieldBatcher.reset()
                commitWindshieldBatch(context, action, emptyList(), now)
                return
            }
            if (color.isBlank()) {
                return
            }
            val light = WindshieldTrafficLight(
                key = WindshieldTrafficLight.keyFor(id, position),
                position = position,
                color = color,
                countdown = countdown,
                arrow = arrow
            )
            windshieldBatcher.accept(light, now)?.let { previous ->
                commitWindshieldBatch(context, action, previous, now)
            }
            cancelWindshieldCommit()
            val appContext = context.applicationContext
            val runnable = Runnable {
                pendingWindshieldCommit = null
                val batch = windshieldBatcher.takeBatch() ?: return@Runnable
                commitWindshieldBatch(appContext, action, batch, System.currentTimeMillis())
            }
            pendingWindshieldCommit = runnable
            trafficLightHandler.postDelayed(runnable, WindshieldTrafficLightBatcher.COMMIT_DELAY_MS)
        }

        private fun commitWindshieldBatch(
            context: Context,
            action: String,
            batch: List<WindshieldTrafficLight>,
            now: Long
        ) {
            val merged = WindshieldTrafficLightBatcher.merge(activeTrafficLights, batch, now)
            activeTrafficLights.clear()
            activeTrafficLights.putAll(merged)
            updateTrafficLightState(context, action, now)
        }

        private fun cancelWindshieldCommit() {
            pendingWindshieldCommit?.let { trafficLightHandler.removeCallbacks(it) }
            pendingWindshieldCommit = null
        }

        fun clearTrafficLightCache() {
            trafficLightHandler.post {
                cancelWindshieldCommit()
                windshieldBatcher.reset()
                activeTrafficLights.clear()
                pendingTrafficLightCleanup?.let { trafficLightHandler.removeCallbacks(it) }
                pendingTrafficLightCleanup = null
            }
        }

        private fun readStringOrIntExtra(intent: Intent, key: String): String {
            @Suppress("DEPRECATION")
            return when (val value = intent.extras?.get(key)) {
                null -> ""
                is String -> normalizeText(value)
                else -> value.toString()
            }
        }

        private fun updateTrafficLightState(context: Context, action: String, now: Long) {
            val maxActive = OverlayPrefs.trafficLightMaxActive(context).coerceAtLeast(1)
            val resolved = activeTrafficLights.values
                .sortedWith(compareBy<TrafficLightInfo>({ it.position }, { it.id }))
                .take(maxActive)
            NavigationHudStore.update { state ->
                state.copy(
                    trafficLights = resolved,
                    source = SOURCE_YANDEX,
                    lastUpdated = now,
                    lastAction = action
                )
            }
            scheduleTrafficLightCleanup()
        }

        private fun scheduleRoadCameraHide(context: Context) {
            cancelRoadCameraHide()
            val seconds = OverlayPrefs.roadCameraTimeout(context)
            if (seconds <= 0) {
                return
            }
            val delayMs = seconds.toLong() * 1000L
            val runnable = Runnable {
                pendingRoadCameraHide = null
                val ctx = roadCameraContext ?: context.applicationContext
                clearRoadCamera(ctx, "road_camera_timeout")
            }
            pendingRoadCameraHide = runnable
            roadCameraHandler.postDelayed(runnable, delayMs)
        }

        private fun cancelRoadCameraHide() {
            pendingRoadCameraHide?.let { roadCameraHandler.removeCallbacks(it) }
            pendingRoadCameraHide = null
        }

        private fun clearRoadCamera(context: Context, action: String) {
            NavigationHudStore.update { state ->
                state.copy(
                    roadCameraId = null,
                    roadCameraDistance = null,
                    roadCameraIcon = null,
                    lastUpdated = System.currentTimeMillis(),
                    lastAction = action
                )
            }
        }

        private fun scheduleTrafficLightCleanup() {
            pendingTrafficLightCleanup?.let { trafficLightHandler.removeCallbacks(it) }
            if (activeTrafficLights.isEmpty()) {
                pendingTrafficLightCleanup = null
                return
            }
            val now = System.currentTimeMillis()
            val nextExpiry = activeTrafficLights.values
                .filter { it.expiresAt < Long.MAX_VALUE }
                .minOfOrNull { it.expiresAt }
                ?: return
            val delayMs = (nextExpiry - now).coerceAtLeast(0L)
            val runnable = Runnable {
                pendingTrafficLightCleanup = null
                purgeExpiredTrafficLights()
            }
            pendingTrafficLightCleanup = runnable
            trafficLightHandler.postDelayed(runnable, delayMs)
        }

        private fun purgeExpiredTrafficLights() {
            val now = System.currentTimeMillis()
            val iterator = activeTrafficLights.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.expiresAt < Long.MAX_VALUE && entry.value.expiresAt <= now) {
                    iterator.remove()
                }
            }
            val context = trafficLightContext ?: return
            updateTrafficLightState(context = context, action = "windshield_traffic_light_timeout", now = now)
        }

        private data class NativeNavPayload(
            val turnId: Int,
            val street: String,
            val distanceToManeuverMeters: Int,
            val distanceToDestinationMeters: Int,
            val totalDistanceToDestinationMeters: Int,
            val etaSeconds: Int
        )

        private fun resolveTurnId(context: Context, maneuverType: String): Int {
            val prefs = context.getSharedPreferences(
                "maneuver_match_prefs",
                Context.MODE_PRIVATE
            )
            val key = "mapping_$maneuverType"
            val raw = prefs.getString(key, null)
            val match = Regex("^\\d+").find(raw.orEmpty())
            val parsed = match?.value?.toIntOrNull()
            val resolved = if (parsed == null || parsed > MAX_SUPPORTED_TURN_ID) {
                DEFAULT_NATIVE_TURN_ID
            } else {
                parsed
            }
            val normalized = resolved.toString()
            if (!raw.isNullOrBlank() && raw != normalized) {
                prefs.edit().putString(key, normalized).apply()
            }
            return resolved
        }

        private fun parseDistanceMeters(text: String): Int? {
            val normalized = text.lowercase(Locale.getDefault())
                .replace(',', '.')
            val numberMatch = Regex("([0-9]+(?:\\.[0-9]+)?)").find(normalized) ?: return null
            val value = numberMatch.groupValues[1].toDoubleOrNull() ?: return null
            val meters = when {
                normalized.contains("км") || normalized.contains("km") -> value * 1000.0
                normalized.contains("м") || normalized.contains("m") -> value
                else -> value
            }
            return meters.toInt().coerceAtLeast(0)
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

        private fun predictManeuverType(context: Context, bitmap: Bitmap): String {
            val result = ManeuverRecognition.analyze(context, bitmap)
            if (result.top.isNotEmpty()) {
                val top = result.top.joinToString { "${it.name}=${it.distance}" }
                Log.d(TAG, "Maneuver candidates: $top")
            }
            return result.bestName
        }
    }
}
