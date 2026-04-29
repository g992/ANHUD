package com.g992.anhud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.logo.Alignment
import com.yandex.mapkit.logo.HorizontalAlignment
import com.yandex.mapkit.logo.Padding
import com.yandex.mapkit.logo.VerticalAlignment
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.MapType
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.RotationType
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAP_MAX_FPS = 15
private const val MAP_MAX_LAST_KNOWN_LOCATION_AGE_MS = 10_000L
private const val MAP_TRACKING_TOP_PADDING_RATIO = 0.97f
private const val MAP_ROUTE_TRIM_BACKTRACK_METERS = 3.0
private const val MAP_ROUTE_ALERT_PASSED_TOLERANCE_METERS = 12.0
private const val MAP_ROUTE_PROGRESS_RENDER_GRANULARITY_METERS = 25.0
private const val MAP_ROUTE_OFF_ROUTE_CLEAR_METERS = 35.0
private const val MAP_CAMERA_ANIMATION_SECONDS = 0.35f
private const val MAP_LOCATION_ANIMATION_MIN_MS = 250L
private const val MAP_LOCATION_ANIMATION_MAX_MS = 1_100L
private const val MAP_LOCATION_ANIMATION_TARGET_MS = 900L
private const val MAP_LOCATION_TELEPORT_DISTANCE_METERS = 80f
private const val MAP_LOCATION_TELEPORT_AGE_MS = 4_000L
private const val MAP_HEADING_FALLBACK_MIN_DISTANCE_METERS = 2.0
private const val MAP_HEADING_STUCK_EPSILON_DEGREES = 2f
private const val MAP_HEADING_FALLBACK_DIVERGENCE_DEGREES = 5f
private const val MAP_HEADING_FALLBACK_SMOOTHING = 0.35
private const val HUD_MAP_TAG = "HudMapController"

class HudMapController(
    private val context: Context,
) {
    private val mapView = MapView(context)
    private val renderHandler = Handler(Looper.getMainLooper())
    private var routeCollection: MapObjectCollection? = null
    private var routeAlertCollection: MapObjectCollection? = null
    private var laneManeuverCollection: MapObjectCollection? = null
    private var laneManeuverPlacemark: PlacemarkMapObject? = null
    private var locationCollection: MapObjectCollection? = null
    private var locationPlacemark: PlacemarkMapObject? = null
    private val locationArrowProvider by lazy { ImageProvider.fromBitmap(createLocationArrowBitmap()) }
    private var deviceLocationListener: LocationListener? = null
    private var released = false
    private var mapKitStarted = false
    private var pendingTelemetryRender: Runnable? = null
    private var lastTelemetryRenderUptimeMs: Long = 0L
    private var currentSettings = MapRenderSettingsStore.current()
    private var currentSnapshot = MapRouteTelemetryStore.current()
    private var currentLocation: Location? = null
    private var currentDisplayLocation: Location? = null
    private var locationAnimation: LocationAnimation? = null
    private var lastRawDeviceLocation: Location? = null
    private var lastResolvedDeviceBearing: Float? = null
    private var currentSpeedBucketKmh: Int = initialSpeedBucket(NavigationHudStore.snapshot().speedKmh)
    private var lastRouteRenderDebugKey: String? = null
    private var lastRouteSnapshotDebugKey: String? = null
    private var lastRouteAlertRenderKey: String? = null
    private var lastLaneManeuverRenderKey: String? = null
    private var lastCameraKey: String? = null
    private var appliedMapStyleId: String? = null

    private val settingsListener: (MapRenderSettings) -> Unit = { settings ->
        currentSettings = settings
        applyMapStyle(force = true)
        updateLocationStyle()
        applyTrackingConfig()
        requestTelemetryRender()
    }

    private val routeListener: (MapRouteTelemetrySnapshot) -> Unit = { snapshot ->
        logRouteSnapshot(snapshot)
        currentSnapshot = snapshot
        requestTelemetryRender()
    }

    private val navStateListener = object : NavigationHudStore.Listener {
        override fun onStateUpdated(state: NavigationHudState) {
            val newBucket = applySpeedBucketHysteresis(currentSpeedBucketKmh, state.speedKmh)
            if (newBucket == currentSpeedBucketKmh) return
            currentSpeedBucketKmh = newBucket
            if (currentSettings.autoZoomEnabled) {
                applyTrackingConfig()
            }
        }
    }

    init {
        MapRenderSettingsStore.addListener(settingsListener)
        MapRouteTelemetryStore.addListener(routeListener)
        NavigationHudStore.registerListener(navStateListener)
        mapView.setBackgroundColor(Color.BLACK)
        mapView.setNoninteractive(true)
        mapView.setOnTouchListener { _, _: MotionEvent -> true }
        configureMap()
        startMapKit()
        mapView.onStart()
        startDeviceLocationTracking()
        requestTelemetryRender()
    }

    fun attachTo(container: FrameLayout) {
        if (released) return
        val currentParent = mapView.parent as? ViewGroup
        if (currentParent !== container) {
            currentParent?.removeView(mapView)
            container.addView(
                mapView,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        mapView.visibility = View.VISIBLE
        applyTrackingConfig()
        requestTelemetryRender()
    }

    fun setVisible(visible: Boolean) {
        if (released) return
        mapView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun release() {
        if (released) return
        released = true
        pendingTelemetryRender?.let(renderHandler::removeCallbacks)
        pendingTelemetryRender = null
        locationAnimation = null
        MapRenderSettingsStore.removeListener(settingsListener)
        MapRouteTelemetryStore.removeListener(routeListener)
        NavigationHudStore.unregisterListener(navStateListener)
        stopDeviceLocationTracking()
        mapView.onStop()
        stopMapKit()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    private fun configureMap() {
        val map = mapView.mapWindow.map
        map.setMapType(MapType.VECTOR_MAP)
        map.isNightModeEnabled = true
        map.isZoomGesturesEnabled = false
        map.isScrollGesturesEnabled = false
        map.isTiltGesturesEnabled = false
        map.isRotateGesturesEnabled = false
        map.isFastTapEnabled = false
        map.set2DMode(false)
        map.logo.setAlignment(Alignment(HorizontalAlignment.LEFT, VerticalAlignment.TOP))
        map.logo.setPadding(Padding(dp(8), dp(8)))
        mapView.mapWindow.setMaxFps(MAP_MAX_FPS)
        routeCollection = map.mapObjects.addCollection()
        routeAlertCollection = map.mapObjects.addCollection()
        laneManeuverCollection = map.mapObjects.addCollection()
        locationCollection = map.mapObjects.addCollection()
        applyMapStyle(force = true)
    }

    private fun applyMapStyle(force: Boolean = false) {
        val style = resolveMapStyleOption(currentSettings.mapStyleId)
        if (!force && appliedMapStyleId == style.id) return
        val styleJson = runCatching {
            context.assets.open(style.assetPath).bufferedReader().use { it.readText() }
        }.onFailure {
            Log.w(HUD_MAP_TAG, "map style load failed: ${style.assetPath}: ${it.message}")
        }.getOrNull()
        if (styleJson.isNullOrBlank()) return
        runCatching {
            mapView.mapWindow.map.setMapStyle(styleJson)
        }.onSuccess { applied ->
            if (applied) {
                appliedMapStyleId = style.id
            } else {
                Log.w(HUD_MAP_TAG, "map style rejected: ${style.id}")
            }
        }.onFailure {
            Log.w(HUD_MAP_TAG, "map style apply failed: ${style.id}: ${it.message}")
        }
    }

    private fun startMapKit() {
        if (mapKitStarted) return
        mapKitStarted = true
        runCatching { MapKitFactory.getInstance().onStart() }
            .onFailure { Log.w(HUD_MAP_TAG, "MapKit onStart failed: ${it.message}") }
    }

    private fun stopMapKit() {
        if (!mapKitStarted) return
        mapKitStarted = false
        runCatching { MapKitFactory.getInstance().onStop() }
            .onFailure { Log.w(HUD_MAP_TAG, "MapKit onStop failed: ${it.message}") }
    }

    private fun logRouteSnapshot(snapshot: MapRouteTelemetrySnapshot) {
        val debugKey = "${snapshot.state}|${snapshot.routeToken.orEmpty()}|${snapshot.hasRoute}|" +
            "${snapshot.routePoints.size}|${snapshot.routeJams.size}"
        if (debugKey == lastRouteSnapshotDebugKey) return
        lastRouteSnapshotDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route snapshot received: state=${snapshot.state} hasRoute=${snapshot.hasRoute} " +
                "points=${snapshot.routePoints.size} jams=${snapshot.routeJams.size} " +
                "routeToken=${snapshot.routeToken.orEmpty()}"
        )
    }

    private fun requestTelemetryRender() {
        if (released) return
        val now = SystemClock.uptimeMillis()
        val minIntervalMs = (1000L / MAP_MAX_FPS).coerceAtLeast(1L)
        val elapsed = now - lastTelemetryRenderUptimeMs
        if (elapsed >= minIntervalMs) {
            pendingTelemetryRender?.let(renderHandler::removeCallbacks)
            pendingTelemetryRender = null
            lastTelemetryRenderUptimeMs = now
            applyTelemetrySnapshot()
            return
        }
        if (pendingTelemetryRender != null) return
        val runnable = Runnable {
            pendingTelemetryRender = null
            lastTelemetryRenderUptimeMs = SystemClock.uptimeMillis()
            applyTelemetrySnapshot()
        }
        pendingTelemetryRender = runnable
        renderHandler.postDelayed(runnable, minIntervalMs - elapsed)
    }

    private fun applyTelemetrySnapshot() {
        lastTelemetryRenderUptimeMs = SystemClock.uptimeMillis()
        val displayLocation = resolveDisplayLocation(lastTelemetryRenderUptimeMs)
        currentDisplayLocation = displayLocation
        renderLocation(resolveLocationMarkerLocation(displayLocation))
        renderRoute(displayLocation)
        renderRouteAlerts(displayLocation)
        renderLaneManeuver()
        moveCamera(displayLocation)
        if (locationAnimation != null) {
            requestTelemetryRender()
        }
    }

    private fun renderRoute(displayLocation: Location?) {
        val collection = routeCollection ?: return
        val points = currentSnapshot.routePoints
        if (points.size < 2) {
            if (currentSnapshot.state == MAP_ROUTE_STATE_ARRIVED ||
                currentSnapshot.state == MAP_ROUTE_STATE_CANCELLED
            ) {
                collection.clear()
                lastRouteRenderDebugKey = null
            }
            return
        }
        val renderCacheKey = routeRenderCacheKey(
            routeToken = currentSnapshot.routeToken,
            jamsToken = currentSnapshot.routeJamsToken,
            state = currentSnapshot.state,
            pointCount = points.size,
            displayLocation = displayLocation
        )
        if (renderCacheKey == lastRouteRenderDebugKey) return
        val trimmedRoute = trimRouteSegments(points, currentSnapshot.routeJams, displayLocation)
        if (trimmedRoute.shouldHideRoute) {
            if (lastRouteRenderDebugKey != null) {
                collection.clear()
                lastRouteRenderDebugKey = null
            }
            Log.w(
                HUD_MAP_TAG,
                "route hidden: stale geometry likely, distanceToRouteMeters=" +
                    "${"%.1f".format(trimmedRoute.distanceToRouteMeters ?: 0.0)} points=${points.size}"
            )
            return
        }
        val visibleSegments = trimmedRoute.segments
        if (visibleSegments.isEmpty()) {
            Log.w(
                HUD_MAP_TAG,
                "route render skipped: no visible segments, clearing current route; points=${points.size}"
            )
            if (lastRouteRenderDebugKey != null) {
                collection.clear()
                lastRouteRenderDebugKey = null
            }
            return
        }
        collection.clear()
        val routeRuns = buildRouteRuns(visibleSegments)
        routeRuns.forEach { run ->
            collection.addPolyline(
                Polyline(
                    run.points.map { point -> Point(point.latitude, point.longitude) }
                )
            ).apply {
                setStrokeWidth(6.5f)
                setOutlineWidth(1.5f)
                setOutlineColor(Color.argb(160, 0, 0, 0))
                setStrokeColor(routeJamColor(run.jam))
                zIndex = 10f
            }
        }
        lastRouteRenderDebugKey = renderCacheKey
        Log.d(
            HUD_MAP_TAG,
            "route rendered: state=${currentSnapshot.state} points=${points.size} " +
                "visibleSegments=${visibleSegments.size} runs=${routeRuns.size} " +
                "hasDisplayLocation=${displayLocation != null}"
        )
    }

    private fun renderLocation(location: Location?) {
        if (location == null) return
        val point = Point(location.latitude, location.longitude)
        val placemark = locationPlacemark ?: locationCollection?.addEmptyPlacemark(point)?.also {
            locationPlacemark = it
            it.setIcon(locationArrowProvider, buildLocationIconStyle())
            it.zIndex = 20f
        } ?: return
        placemark.geometry = point
        placemark.direction = resolveBearing(location)
        updateLocationStyle()
    }

    private fun renderRouteAlerts(displayLocation: Location?) {
        val collection = routeAlertCollection ?: return
        val alerts = filterUpcomingRouteAlerts(
            alerts = currentSnapshot.routeAlerts,
            routePoints = currentSnapshot.routePoints,
            location = displayLocation
        )
        val hiddenTypes = currentSettings.hiddenRoadEventTypes
        val iconSizePx = currentSettings.roadEventIconSizePx
            .coerceIn(ROAD_EVENT_ICON_SIZE_MIN_PX, ROAD_EVENT_ICON_SIZE_MAX_PX)
        if (!currentSettings.roadEventsEnabled || alerts.isEmpty()) {
            if (lastRouteAlertRenderKey != null) {
                collection.clear()
                lastRouteAlertRenderKey = null
            }
            return
        }
        val renderKey = buildString {
            append(currentSnapshot.routeAlertsToken.orEmpty())
            append('|')
            append(iconSizePx)
            append('|')
            alerts.forEach { alert ->
                append(resolveRoadEventToggleKey(alert.type))
                append('@')
                append((alert.point.latitude * 100_000.0).roundToInt())
                append(',')
                append((alert.point.longitude * 100_000.0).roundToInt())
                append(';')
            }
            append('|')
            hiddenTypes.toList().sorted().forEach { type ->
                append(type)
                append(',')
            }
        }
        if (renderKey == lastRouteAlertRenderKey) return
        collection.clear()
        alerts.forEach { alert ->
            val typeKey = resolveRoadEventToggleKey(alert.type)
            if (typeKey in hiddenTypes) return@forEach
            val bitmap = renderResourceToBitmap(resolveRoadEventIconRes(alert.type)) ?: return@forEach
            collection.addPlacemark(Point(alert.point.latitude, alert.point.longitude)).apply {
                setIcon(
                    ImageProvider.fromBitmap(bitmap),
                    IconStyle()
                        .setAnchor(PointF(0.5f, 1f))
                        .setFlat(false)
                        .setScale(iconSizePx / ROAD_EVENT_ICON_SIZE_MAX_PX.toFloat())
                        .setZIndex(18f)
                )
                zIndex = 18f
            }
        }
        lastRouteAlertRenderKey = renderKey
    }

    private fun renderLaneManeuver() {
        val maneuver = currentSnapshot.laneManeuver
        val collection = laneManeuverCollection ?: return
        if (!currentSettings.laneGuidanceEnabled ||
            maneuver == null ||
            maneuver.bitmap.isRecycled ||
            maneuver.bitmap.width <= 0 ||
            maneuver.bitmap.height <= 0
        ) {
            if (lastLaneManeuverRenderKey != null || laneManeuverPlacemark != null) {
                laneManeuverPlacemark?.let { runCatching { collection.remove(it) } }
                laneManeuverPlacemark = null
                lastLaneManeuverRenderKey = null
            }
            return
        }
        val renderKey = buildString {
            append(maneuver.token)
            append('|')
            append(maneuver.point.latitude)
            append('|')
            append(maneuver.point.longitude)
            append('|')
            append(maneuver.iconAnchor.x)
            append('|')
            append(maneuver.iconAnchor.y)
            append('|')
            append(maneuver.bitmap.width)
            append('x')
            append(maneuver.bitmap.height)
            append('|')
            append(currentSettings.laneGuidanceWidthPx)
        }
        if (renderKey == lastLaneManeuverRenderKey && laneManeuverPlacemark != null) return
        val point = Point(maneuver.point.latitude, maneuver.point.longitude)
        val placemark = laneManeuverPlacemark ?: collection.addPlacemark(point).also {
            laneManeuverPlacemark = it
        }
        val maxWidthPx = currentSettings.laneGuidanceWidthPx
            .coerceIn(LANE_GUIDANCE_WIDTH_MIN_PX, LANE_GUIDANCE_WIDTH_MAX_PX)
        val scale = maxWidthPx.toFloat() / maneuver.bitmap.width.coerceAtLeast(1).toFloat()
        placemark.geometry = point
        placemark.setIcon(
            ImageProvider.fromBitmap(maneuver.bitmap),
            IconStyle()
                .setAnchor(maneuver.iconAnchor)
                .setFlat(false)
                .setScale(scale.coerceAtMost(1f))
                .setZIndex(19f)
        )
        placemark.zIndex = 19f
        lastLaneManeuverRenderKey = renderKey
    }

    private fun renderResourceToBitmap(resourceId: Int): Bitmap? {
        val drawable: Drawable = ContextCompat.getDrawable(context, resourceId) ?: return null
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun updateLocationStyle() {
        val placemark = locationPlacemark ?: return
        placemark.setIconStyle(buildLocationIconStyle())
    }

    private fun buildLocationIconStyle(): IconStyle {
        val scale = (currentSettings.arrowScalePercent.toFloat() / MAP_ARROW_SCALE_MAX_PERCENT.toFloat())
            .coerceIn(
                MAP_ARROW_SCALE_MIN_PERCENT.toFloat() / MAP_ARROW_SCALE_MAX_PERCENT.toFloat(),
                1.2f
            )
        return IconStyle()
            .setAnchor(android.graphics.PointF(0.5f, 0.5f))
            .setRotationType(RotationType.ROTATE)
            .setFlat(true)
            .setScale(scale)
            .setZIndex(20f)
    }

    private fun applyTrackingConfig() {
        updateFocusPoint()
        moveCamera(currentDisplayLocation ?: resolveDisplayLocation(SystemClock.uptimeMillis()), force = true)
    }

    private fun updateFocusPoint() {
        val height = mapView.height.coerceAtLeast(1)
        val width = mapView.width.coerceAtLeast(1)
        mapView.mapWindow.focusPoint = ScreenPoint(
            width / 2f,
            height * MAP_TRACKING_TOP_PADDING_RATIO
        )
    }

    private fun moveCamera(location: Location?, force: Boolean = false) {
        location ?: return
        updateFocusPoint()
        val zoom = resolveTargetZoom(currentSettings, currentSpeedBucketKmh).toFloat()
        val tilt = currentSettings.tilt.toFloat()
        val azimuth = resolveBearing(location)
        val key = "${location.renderKey()}|${(zoom * 10f).roundToInt()}|" +
            "${tilt.roundToInt()}|${azimuth.roundToInt()}"
        if (!force && key == lastCameraKey) return
        lastCameraKey = key
        val cameraPosition = CameraPosition(
            Point(location.latitude, location.longitude),
            zoom,
            azimuth,
            tilt
        )
        if (force) {
            mapView.mapWindow.map.move(cameraPosition)
        } else {
            mapView.mapWindow.map.move(
                cameraPosition,
                Animation(Animation.Type.SMOOTH, MAP_CAMERA_ANIMATION_SECONDS)
            )
        }
    }

    private fun resolveDisplayLocation(nowUptimeMs: Long): Location? {
        return resolveAnimatedLocation(nowUptimeMs) ?: currentLocation ?: currentDisplayLocation ?: currentSnapshot.startLocation
    }

    private fun resolveLocationMarkerLocation(displayLocation: Location?): Location? {
        return displayLocation?.takeUnless {
            it.provider == ROUTE_TELEMETRY_LOCATION_PROVIDER
        }
    }

    private fun resolveAnimatedLocation(nowUptimeMs: Long): Location? {
        val animation = locationAnimation ?: return currentDisplayLocation
        val elapsed = nowUptimeMs - animation.startedAtUptimeMs
        if (elapsed >= animation.durationMs) {
            locationAnimation = null
            currentDisplayLocation = animation.to
            return animation.to
        }
        val fraction = (elapsed.toDouble() / animation.durationMs.toDouble()).coerceIn(0.0, 1.0)
        val easedFraction = smoothStep(fraction)
        return interpolateLocation(animation.from, animation.to, easedFraction).also {
            currentDisplayLocation = it
        }
    }

    private fun resolveBearing(location: Location): Float {
        return if (location.hasBearing()) {
            location.bearing
        } else {
            mapView.mapWindow.map.cameraPosition.azimuth
        }
    }

    private fun routeRenderCacheKey(
        routeToken: String?,
        jamsToken: String?,
        state: String?,
        pointCount: Int,
        displayLocation: Location?,
    ): String {
        return buildString {
            append(routeToken.orEmpty())
            append('|')
            append(jamsToken.orEmpty())
            append('|')
            append(state.orEmpty())
            append('|')
            append(pointCount)
            append('|')
            append(displayLocation.routeProgressRenderKey())
        }
    }

    private fun startDeviceLocationTracking() {
        if (deviceLocationListener != null || !hasLocationPermission()) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { location ->
            updateLocationTarget(prepareDeviceLocation(location))
            requestTelemetryRender()
        }
        deviceLocationListener = listener
        preferredLocationProviders(manager).forEach { provider ->
            latestKnownLocation(manager, provider)?.let {
                setLocationImmediately(prepareDeviceLocation(it))
            }
            runCatching {
                manager.requestLocationUpdates(provider, 1000L, 3f, listener, Looper.getMainLooper())
            }.onFailure {
                Log.w(HUD_MAP_TAG, "requestLocationUpdates failed for $provider: ${it.message}")
            }
        }
        requestTelemetryRender()
    }

    private fun updateLocationTarget(location: Location) {
        val now = SystemClock.uptimeMillis()
        val previousDisplay = resolveAnimatedLocation(now)
        val previousTarget = currentLocation
        currentLocation = Location(location)
        if (previousDisplay == null || previousTarget == null) {
            setLocationImmediately(location)
            return
        }
        val distanceMeters = previousDisplay.distanceTo(location)
        val ageMs = if (previousTarget.time > 0L && location.time > previousTarget.time) {
            location.time - previousTarget.time
        } else {
            MAP_LOCATION_ANIMATION_TARGET_MS
        }
        if (distanceMeters >= MAP_LOCATION_TELEPORT_DISTANCE_METERS || ageMs >= MAP_LOCATION_TELEPORT_AGE_MS) {
            setLocationImmediately(location)
            return
        }
        locationAnimation = LocationAnimation(
            from = previousDisplay,
            to = Location(location),
            startedAtUptimeMs = now,
            durationMs = ageMs.coerceIn(MAP_LOCATION_ANIMATION_MIN_MS, MAP_LOCATION_ANIMATION_MAX_MS)
        )
    }

    private fun setLocationImmediately(location: Location) {
        val copy = Location(location)
        currentLocation = copy
        currentDisplayLocation = copy
        locationAnimation = null
    }

    private fun stopDeviceLocationTracking() {
        val listener = deviceLocationListener ?: return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { manager.removeUpdates(listener) }
        deviceLocationListener = null
        lastRawDeviceLocation = null
        lastResolvedDeviceBearing = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun prepareDeviceLocation(location: Location): Location {
        val rawLocation = Location(location)
        val previousRawLocation = lastRawDeviceLocation
        val derivedBearing = previousRawLocation?.let { previous ->
            derivedBearingBetween(previous, rawLocation)
        }
        val resolvedBearing = resolveHeadingBearing(
            previousResolvedBearing = lastResolvedDeviceBearing,
            previousReportedBearing = previousRawLocation?.takeIf { it.hasBearing() }?.bearing,
            reportedBearing = rawLocation.takeIf { it.hasBearing() }?.bearing,
            derivedBearing = derivedBearing
        )
        if (resolvedBearing != null) {
            rawLocation.bearing = resolvedBearing
        }
        lastRawDeviceLocation = Location(location)
        lastResolvedDeviceBearing = resolvedBearing
        return rawLocation
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

private fun resolveTargetZoom(settings: MapRenderSettings, speedBucketKmh: Int): Double {
    if (!settings.autoZoomEnabled) {
        return settings.zoom
    }
    val speed = speedBucketKmh.coerceAtLeast(0).toDouble()
    val zoom = when {
        speed <= 60.0 -> interpolateLinear(
            x0 = 0.0,
            y0 = settings.autoZoomAt0Kmh,
            x1 = 60.0,
            y1 = settings.autoZoomAt60Kmh,
            x = speed
        )
        else -> interpolateLinear(
            x0 = 60.0,
            y0 = settings.autoZoomAt60Kmh,
            x1 = 90.0,
            y1 = settings.autoZoomAt90Kmh,
            x = speed
        )
    }
    return zoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
}

private fun interpolateLinear(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x: Double,
): Double {
    if (abs(x1 - x0) < 0.0001) return y0
    val t = (x - x0) / (x1 - x0)
    return y0 + ((y1 - y0) * t)
}

private fun initialSpeedBucket(speedKmh: Int?): Int {
    val safeSpeed = (speedKmh ?: 0).coerceAtLeast(0)
    return ((safeSpeed + 5) / 10) * 10
}

private fun applySpeedBucketHysteresis(currentBucketKmh: Int, speedKmh: Int?): Int {
    val safeSpeed = (speedKmh ?: 0).coerceAtLeast(0)
    var bucket = currentBucketKmh.coerceAtLeast(0)
    while (safeSpeed > bucket + 5) {
        bucket += 10
    }
    while (safeSpeed < bucket - 5) {
        bucket = (bucket - 10).coerceAtLeast(0)
    }
    return bucket
}

private fun routeJamColor(jam: String?): Int {
    return when (jam?.lowercase()) {
        "low" -> Color.argb(235, 118, 189, 51)
        "moderate" -> Color.argb(235, 145, 210, 85)
        "heavy" -> Color.argb(235, 234, 117, 0)
        "severe" -> Color.argb(255, 159, 0, 0)
        "blocked" -> Color.argb(235, 193, 0, 32)
        else -> Color.argb(230, 120, 200, 255)
    }
}

private fun createLocationArrowBitmap(): Bitmap {
    val size = 180
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        style = Paint.Style.FILL
    }
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC845")
        style = Paint.Style.FILL
    }
    val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.FILL
    }
    val shadow = Path().apply {
        moveTo(90f, 12f)
        lineTo(155f, 150f)
        lineTo(90f, 121f)
        lineTo(25f, 150f)
        close()
    }
    val arrow = Path().apply {
        moveTo(90f, 30f)
        lineTo(136f, 126f)
        lineTo(90f, 104f)
        lineTo(44f, 126f)
        close()
    }
    val highlight = Path().apply {
        moveTo(90f, 42f)
        lineTo(104f, 92f)
        lineTo(90f, 86f)
        lineTo(76f, 92f)
        close()
    }
    canvas.drawPath(shadow, shadowPaint)
    canvas.drawPath(arrow, arrowPaint)
    canvas.drawPath(highlight, highlightPaint)
    return bitmap
}

private fun Location?.renderKey(): String {
    if (this == null) return "no-location"
    return "${(latitude * 100_000.0).roundToInt()}|${(longitude * 100_000.0).roundToInt()}"
}

private fun Location?.routeProgressRenderKey(): String {
    if (this == null) return "no-location"
    val latBucket = (latitude * 111_320.0 / MAP_ROUTE_PROGRESS_RENDER_GRANULARITY_METERS).roundToInt()
    val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(latitude))
    val lonBucket = if (abs(lonScale) <= 0.0001) {
        0
    } else {
        (longitude * lonScale / MAP_ROUTE_PROGRESS_RENDER_GRANULARITY_METERS).roundToInt()
    }
    return "$latBucket|$lonBucket"
}

private data class VisibleRouteSegment(
    val start: LatLng,
    val end: LatLng,
    val jam: String?,
)

private data class TrimmedRouteSegmentsResult(
    val segments: List<VisibleRouteSegment>,
    val distanceToRouteMeters: Double? = null,
    val shouldHideRoute: Boolean = false,
)

private data class RouteRun(
    val jam: String?,
    val points: List<LatLng>,
)

private data class LocationAnimation(
    val from: Location,
    val to: Location,
    val startedAtUptimeMs: Long,
    val durationMs: Long,
)

private fun interpolateLocation(from: Location, to: Location, fraction: Double): Location {
    val result = Location(to)
    result.latitude = from.latitude + ((to.latitude - from.latitude) * fraction)
    result.longitude = from.longitude + ((to.longitude - from.longitude) * fraction)
    if (from.hasAltitude() && to.hasAltitude()) {
        result.altitude = from.altitude + ((to.altitude - from.altitude) * fraction)
    }
    if (from.hasBearing() && to.hasBearing()) {
        result.bearing = interpolateBearing(from.bearing, to.bearing, fraction)
    }
    if (from.hasSpeed() && to.hasSpeed()) {
        result.speed = (from.speed + ((to.speed - from.speed) * fraction)).toFloat()
    }
    if (from.hasAccuracy() && to.hasAccuracy()) {
        result.accuracy = (from.accuracy + ((to.accuracy - from.accuracy) * fraction)).toFloat()
    }
    return result
}

private fun interpolateBearing(from: Float, to: Float, fraction: Double): Float {
    val delta = ((((to - from + 540f) % 360f) - 180f) * fraction).toFloat()
    return (from + delta + 360f) % 360f
}

internal fun resolveHeadingBearing(
    previousResolvedBearing: Float?,
    previousReportedBearing: Float?,
    reportedBearing: Float?,
    derivedBearing: Float?,
): Float? {
    val targetBearing = when {
        derivedBearing == null -> reportedBearing ?: previousResolvedBearing
        reportedBearing == null -> derivedBearing
        shouldPreferDerivedBearing(previousReportedBearing, reportedBearing, derivedBearing) -> derivedBearing
        else -> reportedBearing
    } ?: return null
    return if (previousResolvedBearing == null) {
        normalizeBearing(targetBearing)
    } else {
        interpolateBearing(previousResolvedBearing, targetBearing, MAP_HEADING_FALLBACK_SMOOTHING)
    }
}

internal fun shouldPreferDerivedBearing(
    previousReportedBearing: Float?,
    reportedBearing: Float,
    derivedBearing: Float,
): Boolean {
    if (previousReportedBearing == null) return false
    if (angularDistanceDegrees(previousReportedBearing, reportedBearing) > MAP_HEADING_STUCK_EPSILON_DEGREES) {
        return false
    }
    return angularDistanceDegrees(reportedBearing, derivedBearing) >= MAP_HEADING_FALLBACK_DIVERGENCE_DEGREES
}

private fun derivedBearingBetween(from: Location, to: Location): Float? {
    if (from.time > 0L && to.time > from.time && (to.time - from.time) >= MAP_LOCATION_TELEPORT_AGE_MS) {
        return null
    }
    val start = LatLng(from.latitude, from.longitude)
    val end = LatLng(to.latitude, to.longitude)
    val distance = distanceMeters(start, end)
    if (distance < MAP_HEADING_FALLBACK_MIN_DISTANCE_METERS ||
        distance >= MAP_LOCATION_TELEPORT_DISTANCE_METERS.toDouble()
    ) {
        return null
    }
    return bearingBetweenPoints(start, end)
}

internal fun bearingBetweenPoints(start: LatLng, end: LatLng): Float {
    val startLat = Math.toRadians(start.latitude)
    val endLat = Math.toRadians(end.latitude)
    val deltaLon = Math.toRadians(end.longitude - start.longitude)
    val y = kotlin.math.sin(deltaLon) * kotlin.math.cos(endLat)
    val x = kotlin.math.cos(startLat) * kotlin.math.sin(endLat) -
        kotlin.math.sin(startLat) * kotlin.math.cos(endLat) * kotlin.math.cos(deltaLon)
    return normalizeBearing(Math.toDegrees(kotlin.math.atan2(y, x)).toFloat())
}

internal fun angularDistanceDegrees(from: Float, to: Float): Float {
    return kotlin.math.abs((((to - from + 540f) % 360f) - 180f))
}

private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f

private fun smoothStep(fraction: Double): Double {
    val t = fraction.coerceIn(0.0, 1.0)
    return t * t * (3.0 - (2.0 * t))
}

private fun buildRouteRuns(segments: List<VisibleRouteSegment>): List<RouteRun> {
    if (segments.isEmpty()) return emptyList()
    val runs = mutableListOf<RouteRun>()
    var currentJam = segments.first().jam
    val currentPoints = mutableListOf(segments.first().start, segments.first().end)
    segments.drop(1).forEach { segment ->
        if (segment.jam == currentJam) {
            currentPoints += segment.end
        } else {
            runs += RouteRun(currentJam, currentPoints.toList())
            currentJam = segment.jam
            currentPoints.clear()
            currentPoints += segment.start
            currentPoints += segment.end
        }
    }
    runs += RouteRun(currentJam, currentPoints.toList())
    return runs
}

private fun filterUpcomingRouteAlerts(
    alerts: List<MapRouteAlert>,
    routePoints: List<LatLng>,
    location: Location?,
): List<MapRouteAlert> {
    if (alerts.isEmpty() || routePoints.size < 2 || location == null) return alerts
    val currentProjection = findClosestRouteProjection(routePoints, location) ?: return alerts
    val currentProgressMeters = routeProgressMeters(routePoints, currentProjection)
    return alerts.filter { alert ->
        val alertProjection = findClosestRouteProjection(
            points = routePoints,
            targetLat = alert.point.latitude,
            targetLon = alert.point.longitude
        ) ?: return@filter true
        val alertProgressMeters = routeProgressMeters(routePoints, alertProjection)
        alertProgressMeters + MAP_ROUTE_ALERT_PASSED_TOLERANCE_METERS >= currentProgressMeters
    }
}

private fun trimRouteSegments(
    points: List<LatLng>,
    jams: List<String>,
    location: Location?,
): TrimmedRouteSegmentsResult {
    if (points.size < 2) return TrimmedRouteSegmentsResult(emptyList())
    if (location == null) {
        return TrimmedRouteSegmentsResult(
            segments = points.zipWithNext().mapIndexed { index, (start, end) ->
                VisibleRouteSegment(start, end, jams.getOrNull(index))
            }
        )
    }
    val closest = findClosestRouteProjection(points, location) ?: return TrimmedRouteSegmentsResult(
        segments = points.zipWithNext().mapIndexed { index, (start, end) ->
            VisibleRouteSegment(start, end, jams.getOrNull(index))
        }
    )
    if (closest.distanceMeters > MAP_ROUTE_OFF_ROUTE_CLEAR_METERS) {
        return TrimmedRouteSegmentsResult(
            segments = emptyList(),
            distanceToRouteMeters = closest.distanceMeters,
            shouldHideRoute = true,
        )
    }
    val trimStart = backtrackRouteProjection(points, closest, MAP_ROUTE_TRIM_BACKTRACK_METERS)
    val result = mutableListOf<VisibleRouteSegment>()
    val projectedStart = trimStart.projectedPoint
    val firstEnd = points[trimStart.segmentIndex + 1]
    if (distanceMeters(projectedStart, firstEnd) > 1.0) {
        result += VisibleRouteSegment(projectedStart, firstEnd, jams.getOrNull(trimStart.segmentIndex))
    }
    for (index in (trimStart.segmentIndex + 1) until points.lastIndex) {
        result += VisibleRouteSegment(points[index], points[index + 1], jams.getOrNull(index))
    }
    return TrimmedRouteSegmentsResult(
        segments = result,
        distanceToRouteMeters = closest.distanceMeters,
    )
}

private data class RouteProjection(
    val segmentIndex: Int,
    val projectedPoint: LatLng,
    val distanceMeters: Double,
)

private fun findClosestRouteProjection(points: List<LatLng>, location: Location): RouteProjection? {
    return findClosestRouteProjection(points, location.latitude, location.longitude)
}

private fun findClosestRouteProjection(
    points: List<LatLng>,
    targetLat: Double,
    targetLon: Double,
): RouteProjection? {
    if (points.size < 2) return null
    var best: RouteProjection? = null
    for (index in 0 until points.lastIndex) {
        val projection = projectPointOntoSegment(targetLat, targetLon, points[index], points[index + 1])
        if (best == null || projection.distanceMeters < best.distanceMeters) {
            best = RouteProjection(index, projection.projectedPoint, projection.distanceMeters)
        }
    }
    return best
}

private fun routeProgressMeters(points: List<LatLng>, projection: RouteProjection): Double {
    if (points.size < 2) return 0.0
    var distance = 0.0
    for (index in 0 until projection.segmentIndex.coerceAtMost(points.lastIndex - 1)) {
        distance += distanceMeters(points[index], points[index + 1])
    }
    val segmentStart = points.getOrNull(projection.segmentIndex) ?: return distance
    distance += distanceMeters(segmentStart, projection.projectedPoint)
    return distance
}

private fun backtrackRouteProjection(
    points: List<LatLng>,
    projection: RouteProjection,
    backtrackMeters: Double,
): RouteProjection {
    if (backtrackMeters <= 0.0 || projection.segmentIndex !in 0 until points.lastIndex) {
        return projection
    }
    var remaining = backtrackMeters
    val currentSegmentStart = points[projection.segmentIndex]
    val distanceFromSegmentStart = distanceMeters(currentSegmentStart, projection.projectedPoint)
    if (distanceFromSegmentStart >= remaining) {
        return projection.copy(
            projectedPoint = interpolateLatLng(
                start = projection.projectedPoint,
                end = currentSegmentStart,
                fraction = remaining / distanceFromSegmentStart
            )
        )
    }

    remaining -= distanceFromSegmentStart
    var segmentIndex = projection.segmentIndex - 1
    while (segmentIndex >= 0) {
        val segmentStart = points[segmentIndex]
        val segmentEnd = points[segmentIndex + 1]
        val segmentLength = distanceMeters(segmentStart, segmentEnd)
        if (segmentLength >= remaining) {
            return projection.copy(
                segmentIndex = segmentIndex,
                projectedPoint = interpolateLatLng(
                    start = segmentEnd,
                    end = segmentStart,
                    fraction = remaining / segmentLength
                )
            )
        }
        remaining -= segmentLength
        segmentIndex -= 1
    }
    return projection.copy(segmentIndex = 0, projectedPoint = points.first())
}

private fun interpolateLatLng(start: LatLng, end: LatLng, fraction: Double): LatLng {
    val safeFraction = fraction.coerceIn(0.0, 1.0)
    return LatLng(
        start.latitude + ((end.latitude - start.latitude) * safeFraction),
        start.longitude + ((end.longitude - start.longitude) * safeFraction)
    )
}

private data class SegmentProjection(
    val projectedPoint: LatLng,
    val distanceMeters: Double,
)

private fun projectPointOntoSegment(
    targetLat: Double,
    targetLon: Double,
    start: LatLng,
    end: LatLng,
): SegmentProjection {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
    val ax = start.longitude * longitudeScale
    val ay = start.latitude * latitudeScale
    val bx = end.longitude * longitudeScale
    val by = end.latitude * latitudeScale
    val px = targetLon * longitudeScale
    val py = targetLat * latitudeScale
    val abx = bx - ax
    val aby = by - ay
    val abLengthSquared = abx * abx + aby * aby
    val t = if (abLengthSquared <= 0.0001) 0.0 else (((px - ax) * abx) + ((py - ay) * aby)) / abLengthSquared
    val clampedT = t.coerceIn(0.0, 1.0)
    val closestX = ax + abx * clampedT
    val closestY = ay + aby * clampedT
    val projectedPoint = LatLng(
        closestY / latitudeScale,
        if (longitudeScale == 0.0) start.longitude else closestX / longitudeScale
    )
    return SegmentProjection(
        projectedPoint = projectedPoint,
        distanceMeters = kotlin.math.hypot(px - closestX, py - closestY)
    )
}

private fun distanceMeters(start: LatLng, end: LatLng): Double {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
    val dx = (end.longitude - start.longitude) * longitudeScale
    val dy = (end.latitude - start.latitude) * latitudeScale
    return kotlin.math.hypot(dx, dy)
}

private fun preferredLocationProviders(manager: LocationManager): List<String> {
    return buildList {
        if (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.GPS_PROVIDER)
        }
        if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
        if (runCatching { manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.PASSIVE_PROVIDER)
        }
    }.ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
}

private fun latestKnownLocation(manager: LocationManager, provider: String): Location? {
    return runCatching { manager.getLastKnownLocation(provider) }
        .getOrNull()
        ?.takeIf { System.currentTimeMillis() - it.time <= MAP_MAX_LAST_KNOWN_LOCATION_AGE_MS }
}
