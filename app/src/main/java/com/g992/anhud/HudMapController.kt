package com.g992.anhud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAP_ROUTE_SOURCE_ID = "anhud-map-route-source"
private const val MAP_ROUTE_OUTLINE_LAYER_ID = "anhud-map-route-outline-layer"
private const val MAP_ROUTE_LAYER_ID = "anhud-map-route-layer"
private const val MAP_ROUTE_COLOR_PROP = "routeColor"
private const val MAP_ROUTE_ALERT_SOURCE_ID = "anhud-map-route-alert-source"
private const val MAP_ROUTE_ALERT_LAYER_ID = "anhud-map-route-alert-layer"
private const val MAP_ROUTE_ALERT_ICON_PROP = "routeAlertIcon"
private const val MAP_ROUTE_ALERT_ICON_PREFIX = "anhud-route-alert-"
private const val MAP_LANE_MANEUVER_SOURCE_ID = "anhud-map-lane-maneuver-source"
private const val MAP_LANE_MANEUVER_LAYER_ID = "anhud-map-lane-maneuver-layer"
private const val MAP_LANE_MANEUVER_IMAGE_ID = "anhud-map-lane-maneuver-image"
private const val MAP_MAX_FPS = 15
private const val MAP_MAX_LAST_KNOWN_LOCATION_AGE_MS = 10_000L
private const val MAP_TRACKING_TOP_PADDING_RATIO = 0.97f
private const val MAP_TRACKING_BOTTOM_PADDING_DP = 18
private const val MAP_ROUTE_TRIM_BACKTRACK_METERS = 6.0
private const val MAP_ROUTE_TRIM_MAX_DISTANCE_METERS = 35.0
private const val MAP_ROUTE_ALERT_PASSED_TOLERANCE_METERS = 12.0
private const val MAP_ROUTE_PROGRESS_RENDER_GRANULARITY_METERS = 25.0
private const val MAP_ROUTE_TRIM_RENDER_GRANULARITY_METERS = 5.0
private const val MAP_LOCATION_TELEPORT_AGE_MS = 4_000L
private const val MAP_LOCATION_TELEPORT_DISTANCE_METERS = 80f
private const val MAP_HEADING_FALLBACK_MIN_DISTANCE_METERS = 2.0
private const val MAP_HEADING_STUCK_EPSILON_DEGREES = 2f
private const val MAP_HEADING_FALLBACK_DIVERGENCE_DEGREES = 5f
private const val MAP_HEADING_FALLBACK_SMOOTHING = 0.35
private const val MAP_ARROW_BASE_SIZE_DP = 180
private const val HUD_MAP_TAG = "HudMapController"

class HudMapController(
    private val context: Context,
) {
    private val mapView = MapView(
        context,
        MapLibreMapOptions()
            .textureMode(true)
            .translucentTextureSurface(true)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .zoomGesturesEnabled(false)
            .doubleTapGesturesEnabled(false)
            .quickZoomGesturesEnabled(false)
            .scrollGesturesEnabled(false)
            .horizontalScrollGesturesEnabled(false)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)
    )
    private val mapVignetteView = MapVignetteView(context)
    private val renderHandler = Handler(Looper.getMainLooper())
    private var mapLibreMap: MapLibreMap? = null
    private var locationComponent: LocationComponent? = null
    private var deviceLocationListener: LocationListener? = null
    private var released = false
    private var pendingTelemetryRender: Runnable? = null
    private var lastTelemetryRenderUptimeMs: Long = 0L
    private var currentSettings = MapRenderSettingsStore.current()
    private var currentSnapshot = MapRouteTelemetryStore.current()
    private var currentLocation: Location? = currentSnapshot.startLocation
    private var currentDisplayLocation: Location? = currentLocation
    private var currentSpeedBucketKmh: Int = initialSpeedBucket(NavigationHudStore.snapshot().speedKmh)
    private var lastRouteRenderDebugKey: String? = null
    private var lastRouteAlertRenderDebugKey: String? = null
    private var lastLaneManeuverRenderDebugKey: String? = null
    private var lastRouteSnapshotDebugKey: String? = null
    private var routeFeatureCache: RouteFeatureCache? = null
    private var routeAlertFeatureCache: RouteAlertFeatureCache? = null
    private var appliedRoadEventIconSizePx: Int? = null
    private var laneManeuverFeatureCache: LaneManeuverFeatureCache? = null
    private var appliedLaneManeuverImageKey: String? = null
    private var lastRawDeviceLocation: Location? = null
    private var lastResolvedDeviceBearing: Float? = null

    private val settingsListener: (MapRenderSettings) -> Unit = { settings ->
        val tileProviderChanged = currentSettings.tileProviderId != settings.tileProviderId
        val mapStyleChanged = currentSettings.styleModeId != settings.styleModeId ||
            currentSettings.customStyleJson != settings.customStyleJson
        val vignetteChanged = currentSettings.mapVignetteEnabled != settings.mapVignetteEnabled
        val locationStyleChanged = currentSettings.arrowScalePercent != settings.arrowScalePercent
        val roadEventSettingsChanged = currentSettings.roadEventsEnabled != settings.roadEventsEnabled ||
            currentSettings.roadEventIconSizePx != settings.roadEventIconSizePx ||
            currentSettings.hiddenRoadEventTypes != settings.hiddenRoadEventTypes
        val laneManeuverSettingsChanged = currentSettings.laneGuidanceEnabled != settings.laneGuidanceEnabled ||
            currentSettings.laneGuidanceWidthPx != settings.laneGuidanceWidthPx
        currentSettings = settings
        if (locationStyleChanged) {
            applyLocationStyle()
        }
        if (tileProviderChanged || mapStyleChanged) {
            mapLibreMap?.let(::loadMapStyle)
        }
        if (vignetteChanged) {
            applyVignetteVisibility()
        }
        if (roadEventSettingsChanged) {
            routeAlertFeatureCache = null
            mapLibreMap?.style?.let { style ->
                appliedRoadEventIconSizePx = null
                applyRoadEventIcons(style)
            }
            requestTelemetryRender()
        }
        if (laneManeuverSettingsChanged) {
            laneManeuverFeatureCache = null
            appliedLaneManeuverImageKey = null
            requestTelemetryRender()
        }
        applyTrackingConfig()
    }

    private val routeListener: (MapRouteTelemetrySnapshot) -> Unit = { snapshot ->
        logRouteSnapshot(snapshot)
        currentSnapshot = snapshot
        if (currentLocation == null) {
            currentLocation = snapshot.startLocation
        }
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
    private val styleTemplateListener: () -> Unit = {
        renderHandler.post {
            if (released) return@post
            mapLibreMap?.let(::loadMapStyle)
        }
    }

    init {
        MapRenderSettingsStore.addListener(settingsListener)
        MapRouteTelemetryStore.addListener(routeListener)
        NavigationHudStore.registerListener(navStateListener)
        MapStyleTemplateStore.addListener(styleTemplateListener)
        mapView.setBackgroundColor(Color.BLACK)
        mapView.setMaximumFps(MAP_MAX_FPS)
        mapView.setOnTouchListener { _, _: MotionEvent -> true }
        mapView.onCreate(null)
        mapView.getMapAsync(::onMapReady)
        mapView.onStart()
        mapView.onResume()
        mapVignetteView.visibility = View.GONE
    }

    fun attachTo(container: FrameLayout) {
        if (released) return
        attachChildToContainer(container, mapView, 0)
        attachChildToContainer(container, mapVignetteView, 1)
        mapView.visibility = View.VISIBLE
        applyVignetteVisibility()
        applyTrackingConfig()
        requestTelemetryRender()
    }

    fun setVisible(visible: Boolean) {
        if (released) return
        mapView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        mapVignetteView.visibility = if (visible) {
            if (currentSettings.mapVignetteEnabled) View.VISIBLE else View.GONE
        } else {
            View.INVISIBLE
        }
    }

    fun release() {
        if (released) return
        released = true
        pendingTelemetryRender?.let(renderHandler::removeCallbacks)
        pendingTelemetryRender = null
        MapRenderSettingsStore.removeListener(settingsListener)
        MapRouteTelemetryStore.removeListener(routeListener)
        NavigationHudStore.unregisterListener(navStateListener)
        MapStyleTemplateStore.removeListener(styleTemplateListener)
        stopDeviceLocationTracking()
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        (mapVignetteView.parent as? ViewGroup)?.removeView(mapVignetteView)
    }

    private fun attachChildToContainer(container: FrameLayout, child: View, index: Int) {
        val currentParent = child.parent as? ViewGroup
        if (currentParent !== container) {
            currentParent?.removeView(child)
            container.addView(
                child,
                index,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun applyVignetteVisibility() {
        mapVignetteView.visibility = if (currentSettings.mapVignetteEnabled) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun onMapReady(map: MapLibreMap) {
        mapLibreMap = map
        map.uiSettings.setAllGesturesEnabled(false)
        map.uiSettings.setRotateGesturesEnabled(false)
        map.uiSettings.setTiltGesturesEnabled(false)
        map.uiSettings.setZoomGesturesEnabled(false)
        map.uiSettings.setDoubleTapGesturesEnabled(false)
        map.uiSettings.setQuickZoomGesturesEnabled(false)
        map.uiSettings.setScrollGesturesEnabled(false)
        map.uiSettings.setHorizontalScrollGesturesEnabled(false)
        map.uiSettings.setCompassEnabled(false)
        map.uiSettings.setLogoEnabled(false)
        map.uiSettings.setAttributionEnabled(false)
        loadMapStyle(map)
    }

    private fun loadMapStyle(map: MapLibreMap) {
        resetStyleCaches()
        map.setStyle(buildHudMapStyle(context)) { style ->
            Log.d(HUD_MAP_TAG, "map style loaded provider=${currentMapTileProvider(context).id}")
            ensureRouteLayer(style)
            ensureRouteAlertLayer(style)
            ensureLaneManeuverLayer(style)
            appliedRoadEventIconSizePx = null
            applyRoadEventIcons(style)
            enableLocationComponent(map, style)
            startDeviceLocationTracking()
            requestTelemetryRender()
            applyTrackingConfig()
        }
    }

    private fun resetStyleCaches() {
        routeFeatureCache = null
        routeAlertFeatureCache = null
        laneManeuverFeatureCache = null
        appliedRoadEventIconSizePx = null
        appliedLaneManeuverImageKey = null
    }

    private fun enableLocationComponent(map: MapLibreMap, style: Style) {
        val component = map.locationComponent
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(buildLocationOptions(context, currentSettings))
                .useDefaultLocationEngine(false)
                .build()
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.GPS
        component.setCameraMode(
            CameraMode.TRACKING_GPS,
            object : OnLocationCameraTransitionListener {
                override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                    applyTrackingConfig()
                }

                override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
            }
        )
        locationComponent = component
        applyLocationStyle()
        updateDisplayLocation()
    }

    private fun ensureRouteLayer(style: Style) {
        if (style.getSource(MAP_ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MAP_ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        }
        if (style.getLayer(MAP_ROUTE_OUTLINE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(MAP_ROUTE_OUTLINE_LAYER_ID, MAP_ROUTE_SOURCE_ID).withProperties(
                    lineColor("#000000"),
                    lineWidth(9.5f),
                    lineOpacity(0.62f),
                    lineCap("round"),
                    lineJoin("round")
                )
            )
        }
        if (style.getLayer(MAP_ROUTE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(MAP_ROUTE_LAYER_ID, MAP_ROUTE_SOURCE_ID).withProperties(
                    lineColor(Expression.get(MAP_ROUTE_COLOR_PROP)),
                    lineWidth(6.5f),
                    lineOpacity(0.98f),
                    lineCap("round"),
                    lineJoin("round")
                )
            )
        }
    }

    private fun ensureRouteAlertLayer(style: Style) {
        if (style.getSource(MAP_ROUTE_ALERT_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MAP_ROUTE_ALERT_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        }
        if (style.getLayer(MAP_ROUTE_ALERT_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(MAP_ROUTE_ALERT_LAYER_ID, MAP_ROUTE_ALERT_SOURCE_ID).withProperties(
                    iconImage(Expression.get(MAP_ROUTE_ALERT_ICON_PROP)),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconAnchor(Property.ICON_ANCHOR_BOTTOM)
                )
            )
        }
    }

    private fun ensureLaneManeuverLayer(style: Style) {
        if (style.getSource(MAP_LANE_MANEUVER_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MAP_LANE_MANEUVER_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        }
        if (style.getLayer(MAP_LANE_MANEUVER_LAYER_ID) == null) {
            style.addLayer(
                SymbolLayer(MAP_LANE_MANEUVER_LAYER_ID, MAP_LANE_MANEUVER_SOURCE_ID).withProperties(
                    iconImage(MAP_LANE_MANEUVER_IMAGE_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconAnchor(Property.ICON_ANCHOR_CENTER)
                )
            )
        }
    }

    private fun applyRoadEventIcons(style: Style) {
        val iconSizePx = currentSettings.roadEventIconSizePx
            .coerceIn(ROAD_EVENT_ICON_SIZE_MIN_PX, ROAD_EVENT_ICON_SIZE_MAX_PX)
        if (appliedRoadEventIconSizePx == iconSizePx) return
        RoadEventOptions.forEach { option ->
            renderResourceToBitmap(option.iconRes, iconSizePx)?.let { bitmap ->
                style.addImage(routeAlertIconId(option.typeKey), bitmap)
            }
        }
        appliedRoadEventIconSizePx = iconSizePx
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
        updateDisplayLocation()
        renderRoute()
        renderRouteAlerts()
        renderLaneManeuver()
    }

    private fun renderRoute() {
        val map = mapLibreMap ?: run {
            Log.d(HUD_MAP_TAG, "route render skipped: map is not ready, points=${currentSnapshot.routePoints.size}")
            return
        }
        val style = map.style ?: run {
            Log.d(HUD_MAP_TAG, "route render skipped: style is not ready, points=${currentSnapshot.routePoints.size}")
            return
        }
        ensureRouteLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(MAP_ROUTE_SOURCE_ID) ?: run {
            Log.w(HUD_MAP_TAG, "route render skipped: source is missing")
            return
        }
        val points = currentSnapshot.routePoints
        val renderCacheKey = routeRenderCacheKey(currentSnapshot)
        val cached = routeFeatureCache?.takeIf { it.key == renderCacheKey }
        if (cached != null) {
            logRouteRender(points.size, cached.visibleRunCount)
            return
        }
        val featureCollection = if (points.size >= 2) {
            buildRouteFeatureCollection(points, currentSnapshot.routeJams, currentDisplayLocation)
        } else {
            RouteFeatureCollection(FeatureCollection.fromFeatures(emptyArray()), visibleRunCount = 0)
        }
        routeFeatureCache = RouteFeatureCache(
            key = renderCacheKey,
            featureCollection = featureCollection.collection,
            visibleRunCount = featureCollection.visibleRunCount
        )
        source.setGeoJson(featureCollection.collection)
        logRouteRender(points.size, featureCollection.visibleRunCount)
    }

    private fun buildRouteFeatureCollection(
        points: List<LatLng>,
        jams: List<String>,
        displayLocation: Location?,
    ): RouteFeatureCollection {
        if (points.size < 2) {
            return RouteFeatureCollection(FeatureCollection.fromFeatures(emptyArray()), visibleRunCount = 0)
        }
        val features = buildRouteRuns(
            trimPassedRouteSegments(points, jams, displayLocation)
        ).map { run ->
            Feature.fromGeometry(
                LineString.fromLngLats(
                    run.points.map { point ->
                        Point.fromLngLat(point.longitude, point.latitude)
                    }
                )
            ).apply {
                addStringProperty(MAP_ROUTE_COLOR_PROP, routeJamColor(run.jam))
            }
        }
        return RouteFeatureCollection(
            collection = FeatureCollection.fromFeatures(features),
            visibleRunCount = features.size
        )
    }

    private fun renderRouteAlerts() {
        val style = mapLibreMap?.style ?: run {
            Log.d(HUD_MAP_TAG, "route alerts render skipped: style is not ready, alerts=${currentSnapshot.routeAlerts.size}")
            return
        }
        ensureRouteAlertLayer(style)
        applyRoadEventIcons(style)
        val source = style.getSourceAs<GeoJsonSource>(MAP_ROUTE_ALERT_SOURCE_ID) ?: run {
            Log.w(HUD_MAP_TAG, "route alerts render skipped: source is missing")
            return
        }
        val renderCacheKey = routeAlertRenderCacheKey(currentSnapshot, currentSettings, currentDisplayLocation)
        val cached = routeAlertFeatureCache?.takeIf { it.key == renderCacheKey }
        if (cached != null) {
            logRouteAlertsRender(currentSnapshot.routeAlerts.size, cached.visibleAlertCount)
            return
        }
        val featureCollection = buildRouteAlertFeatureCollection(
            alerts = currentSnapshot.routeAlerts,
            routePoints = currentSnapshot.routePoints,
            displayLocation = currentDisplayLocation
        )
        routeAlertFeatureCache = RouteAlertFeatureCache(
            key = renderCacheKey,
            featureCollection = featureCollection.collection,
            visibleAlertCount = featureCollection.visibleAlertCount
        )
        source.setGeoJson(featureCollection.collection)
        logRouteAlertsRender(currentSnapshot.routeAlerts.size, featureCollection.visibleAlertCount)
    }

    private fun buildRouteAlertFeatureCollection(
        alerts: List<MapRouteAlert>,
        routePoints: List<LatLng>,
        displayLocation: Location?,
    ): RouteAlertFeatureCollection {
        if (!currentSettings.roadEventsEnabled || alerts.isEmpty()) {
            return RouteAlertFeatureCollection(FeatureCollection.fromFeatures(emptyArray()), visibleAlertCount = 0)
        }
        val hiddenTypes = currentSettings.hiddenRoadEventTypes
        val visibleAlerts = filterUpcomingRouteAlerts(alerts, routePoints, displayLocation)
            .filter { alert -> resolveRoadEventToggleKey(alert.type) !in hiddenTypes }
        val features = visibleAlerts.map { alert ->
            val typeKey = resolveRoadEventToggleKey(alert.type)
            Feature.fromGeometry(
                Point.fromLngLat(alert.point.longitude, alert.point.latitude)
            ).apply {
                addStringProperty(MAP_ROUTE_ALERT_ICON_PROP, routeAlertIconId(typeKey))
            }
        }
        return RouteAlertFeatureCollection(
            collection = FeatureCollection.fromFeatures(features),
            visibleAlertCount = features.size
        )
    }

    private fun renderLaneManeuver() {
        val style = mapLibreMap?.style ?: run {
            Log.d(HUD_MAP_TAG, "lane maneuver render skipped: style is not ready")
            return
        }
        ensureLaneManeuverLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(MAP_LANE_MANEUVER_SOURCE_ID) ?: run {
            Log.w(HUD_MAP_TAG, "lane maneuver render skipped: source is missing")
            return
        }
        val maneuver = currentSnapshot.laneManeuver
        val renderCacheKey = laneManeuverRenderCacheKey(maneuver, currentSettings)
        val cached = laneManeuverFeatureCache?.takeIf { it.key == renderCacheKey }
        if (cached != null) {
            logLaneManeuverRender(cached.visible)
            return
        }
        val featureCollection = buildLaneManeuverFeatureCollection(style, maneuver)
        laneManeuverFeatureCache = LaneManeuverFeatureCache(
            key = renderCacheKey,
            featureCollection = featureCollection.collection,
            visible = featureCollection.visible
        )
        source.setGeoJson(featureCollection.collection)
        logLaneManeuverRender(featureCollection.visible)
    }

    private fun buildLaneManeuverFeatureCollection(
        style: Style,
        maneuver: MapLaneManeuver?,
    ): LaneManeuverFeatureCollection {
        if (!currentSettings.laneGuidanceEnabled ||
            maneuver == null ||
            maneuver.bitmap.isRecycled ||
            maneuver.bitmap.width <= 0 ||
            maneuver.bitmap.height <= 0
        ) {
            return LaneManeuverFeatureCollection(FeatureCollection.fromFeatures(emptyArray()), visible = false)
        }
        val image = applyLaneManeuverImage(style, maneuver)
            ?: return LaneManeuverFeatureCollection(FeatureCollection.fromFeatures(emptyArray()), visible = false)
        style.getLayerAs<SymbolLayer>(MAP_LANE_MANEUVER_LAYER_ID)?.setProperties(
            iconOffset(arrayOf(image.offsetX, image.offsetY))
        )
        val feature = Feature.fromGeometry(
            Point.fromLngLat(maneuver.point.longitude, maneuver.point.latitude)
        )
        return LaneManeuverFeatureCollection(
            collection = FeatureCollection.fromFeature(feature),
            visible = true
        )
    }

    private fun applyLaneManeuverImage(style: Style, maneuver: MapLaneManeuver): LaneManeuverImage {
        val targetWidthPx = currentSettings.laneGuidanceWidthPx
            .coerceIn(LANE_GUIDANCE_WIDTH_MIN_PX, LANE_GUIDANCE_WIDTH_MAX_PX)
        val source = maneuver.bitmap
        val scale = targetWidthPx.toFloat() / source.width.coerceAtLeast(1).toFloat()
        val targetHeightPx = (source.height * scale).roundToInt().coerceAtLeast(1)
        val imageKey = "${maneuver.token}|${source.generationId}|${source.width}x${source.height}|$targetWidthPx"
        if (appliedLaneManeuverImageKey != imageKey) {
            val image = if (source.width == targetWidthPx && source.height == targetHeightPx) {
                source
            } else {
                Bitmap.createScaledBitmap(source, targetWidthPx, targetHeightPx, true)
            }
            style.addImage(MAP_LANE_MANEUVER_IMAGE_ID, image)
            appliedLaneManeuverImageKey = imageKey
        }
        val safeAnchorX = maneuver.iconAnchor.x.coerceIn(0f, 1f)
        val safeAnchorY = maneuver.iconAnchor.y.coerceIn(0f, 1f)
        return LaneManeuverImage(
            offsetX = (0.5f - safeAnchorX) * targetWidthPx,
            offsetY = (0.5f - safeAnchorY) * targetHeightPx
        )
    }

    private fun routeRenderCacheKey(snapshot: MapRouteTelemetrySnapshot): String {
        return buildString {
            append(snapshot.routeToken ?: routeGeometryToken(snapshot.routePoints))
            append('|')
            append(snapshot.routeJamsToken ?: snapshot.routeJams.joinToString(separator = ","))
            append('|')
            append(snapshot.state.orEmpty())
            append('|')
            append(snapshot.routePoints.size)
            append('|')
            append(currentDisplayLocation.routeProgressRenderKey(MAP_ROUTE_TRIM_RENDER_GRANULARITY_METERS))
        }
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

    private fun logRouteRender(pointCount: Int, visibleRunCount: Int) {
        val debugKey = "${currentSnapshot.routeToken.orEmpty()}|${currentSnapshot.state}|$pointCount|$visibleRunCount"
        if (debugKey == lastRouteRenderDebugKey) return
        lastRouteRenderDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route rendered: state=${currentSnapshot.state} points=$pointCount runs=$visibleRunCount " +
                "hasLocation=${currentLocation != null} hasDisplayLocation=${currentDisplayLocation != null}"
        )
    }

    private fun logRouteAlertsRender(totalAlertCount: Int, visibleAlertCount: Int) {
        val debugKey = "${currentSnapshot.routeAlertsToken.orEmpty()}|$totalAlertCount|$visibleAlertCount|" +
            "${currentSettings.roadEventsEnabled}|${currentSettings.roadEventIconSizePx}|" +
            currentSettings.hiddenRoadEventTypes.toList().sorted().joinToString(separator = ",")
        if (debugKey == lastRouteAlertRenderDebugKey) return
        lastRouteAlertRenderDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route alerts rendered: total=$totalAlertCount visible=$visibleAlertCount " +
                "enabled=${currentSettings.roadEventsEnabled}"
        )
    }

    private fun logLaneManeuverRender(visible: Boolean) {
        val maneuver = currentSnapshot.laneManeuver
        val debugKey = "${maneuver?.token ?: -1}|${maneuver?.point}|$visible|" +
            "${currentSettings.laneGuidanceEnabled}|${currentSettings.laneGuidanceWidthPx}"
        if (debugKey == lastLaneManeuverRenderDebugKey) return
        lastLaneManeuverRenderDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "lane maneuver rendered: visible=$visible token=${maneuver?.token ?: -1} " +
                "hasBitmap=${maneuver?.bitmap?.isRecycled == false}"
        )
    }

    private fun applyLocationStyle() {
        locationComponent?.applyStyle(buildLocationOptions(context, currentSettings))
    }

    private fun applyTrackingConfig() {
        val component = locationComponent ?: return
        component.paddingWhileTracking(trackingPaddingValues())
        component.zoomWhileTracking(resolveTargetZoom(currentSettings, currentSpeedBucketKmh))
        component.tiltWhileTracking(currentSettings.tilt)
        trackingPadding().also { padding ->
            mapLibreMap?.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
    }

    private fun trackingPadding(): IntArray {
        val side = dp(18)
        val baseBottom = if (mapView.height > 0) dp(MAP_TRACKING_BOTTOM_PADDING_DP) else 0
        val baseTop = (mapView.height.coerceAtLeast(1) * MAP_TRACKING_TOP_PADDING_RATIO).roundToInt()
        val arrowScreenShiftPx = resolveArrowScreenShiftPx(currentSettings)
        val top = (baseTop - arrowScreenShiftPx).coerceAtLeast(0)
        val bottom = baseBottom + arrowScreenShiftPx
        return intArrayOf(side, top, side, bottom)
    }

    private fun trackingPaddingValues(): DoubleArray {
        val padding = trackingPadding()
        return doubleArrayOf(
            padding[0].toDouble(),
            padding[1].toDouble(),
            padding[2].toDouble(),
            padding[3].toDouble()
        )
    }

    private fun startDeviceLocationTracking() {
        if (deviceLocationListener != null || !hasLocationPermission()) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { location ->
            currentLocation = prepareDeviceLocation(location)
            requestTelemetryRender()
        }
        deviceLocationListener = listener
        preferredLocationProviders(manager).forEach { provider ->
            latestKnownLocation(manager, provider)?.let {
                currentLocation = prepareDeviceLocation(it)
            }
            runCatching {
                manager.requestLocationUpdates(provider, 1000L, 3f, listener, Looper.getMainLooper())
            }.onFailure {
                Log.w(HUD_MAP_TAG, "requestLocationUpdates failed for $provider: ${it.message}")
            }
        }
        requestTelemetryRender()
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

    private fun updateDisplayLocation() {
        val rawLocation = currentLocation ?: currentSnapshot.startLocation ?: return
        currentDisplayLocation = rawLocation
        locationComponent?.forceLocationUpdate(rawLocation)
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

    private fun resolveArrowScreenShiftPx(settings: MapRenderSettings): Int {
        val arrowScale = (settings.arrowScalePercent / 100f)
            .coerceIn(
                MAP_ARROW_SCALE_MIN_PERCENT / 100f,
                MAP_ARROW_SCALE_MAX_PERCENT / 100f
            )
        val baseArrowHeightPx = ContextCompat.getDrawable(context, R.drawable.ic_nav_arrow)
            ?.intrinsicHeight
            ?.takeIf { it > 0 }
            ?: dp(MAP_ARROW_BASE_SIZE_DP)
        return (baseArrowHeightPx * arrowScale / 2f).roundToInt()
    }

    private fun renderResourceToBitmap(resourceId: Int, sizePx: Int): Bitmap? {
        val drawable: Drawable = ContextCompat.getDrawable(context, resourceId) ?: return null
        val safeSize = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        return bitmap
    }
}

private fun buildLocationOptions(
    context: Context,
    settings: MapRenderSettings,
): LocationComponentOptions {
    val arrowScale = (settings.arrowScalePercent / 100f)
        .coerceIn(
            MAP_ARROW_SCALE_MIN_PERCENT / 100f,
            MAP_ARROW_SCALE_MAX_PERCENT / 100f
        )
    return LocationComponentOptions.builder(context)
        .gpsDrawable(R.drawable.ic_nav_arrow)
        .foregroundDrawable(R.drawable.ic_nav_arrow)
        .foregroundDrawableStale(R.drawable.ic_nav_arrow)
        .backgroundDrawable(R.drawable.ic_transparent_puck)
        .backgroundDrawableStale(R.drawable.ic_transparent_puck)
        .bearingDrawable(R.drawable.ic_transparent_puck)
        .maxZoomIconScale(arrowScale)
        .minZoomIconScale(arrowScale)
        .build()
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

private fun routeJamColor(jam: String?): String {
    return when (jam?.lowercase()) {
        "low" -> "#82EA0E"
        "moderate" -> "#FFFF41"
        "heavy" -> "#FF5413"
        "severe" -> "#932100"
        "blocked" -> "#1A1A1A"
        "unknown" -> "#A0A0A0"
        else -> "#78C8FF"
    }
}

private data class RouteRun(
    val jam: String?,
    val points: List<LatLng>,
)

private data class RouteFeatureCache(
    val key: String,
    val featureCollection: FeatureCollection,
    val visibleRunCount: Int,
)

private data class RouteFeatureCollection(
    val collection: FeatureCollection,
    val visibleRunCount: Int,
)

private data class RouteAlertFeatureCache(
    val key: String,
    val featureCollection: FeatureCollection,
    val visibleAlertCount: Int,
)

private data class RouteAlertFeatureCollection(
    val collection: FeatureCollection,
    val visibleAlertCount: Int,
)

private data class LaneManeuverFeatureCache(
    val key: String,
    val featureCollection: FeatureCollection,
    val visible: Boolean,
)

private data class LaneManeuverFeatureCollection(
    val collection: FeatureCollection,
    val visible: Boolean,
)

private data class LaneManeuverImage(
    val offsetX: Float,
    val offsetY: Float,
)

private data class VisibleRouteSegment(
    val start: LatLng,
    val end: LatLng,
    val jam: String?,
)

private fun trimPassedRouteSegments(
    points: List<LatLng>,
    jams: List<String>,
    location: Location?,
): List<VisibleRouteSegment> {
    if (points.size < 2) return emptyList()
    val trimStart = resolveRouteTrimStart(
        points = points,
        location = location,
        backtrackMeters = MAP_ROUTE_TRIM_BACKTRACK_METERS,
        maxProjectionDistanceMeters = MAP_ROUTE_TRIM_MAX_DISTANCE_METERS
    )
    val visibleSegments = mutableListOf<VisibleRouteSegment>()
    val firstEnd = points[trimStart.segmentIndex + 1]
    if (distanceMetersBetween(trimStart.point, firstEnd) > 1.0) {
        visibleSegments += VisibleRouteSegment(
            start = trimStart.point,
            end = firstEnd,
            jam = jams.getOrNull(trimStart.segmentIndex)
        )
    }
    for (index in (trimStart.segmentIndex + 1) until points.lastIndex) {
        visibleSegments += VisibleRouteSegment(
            start = points[index],
            end = points[index + 1],
            jam = jams.getOrNull(index)
        )
    }
    return visibleSegments
}

private fun buildRouteRuns(segments: List<VisibleRouteSegment>): List<RouteRun> {
    if (segments.isEmpty()) return emptyList()
    val runs = mutableListOf<RouteRun>()
    var currentJam = segments.first().jam
    val currentPoints = mutableListOf(segments.first().start, segments.first().end)
    for (index in 1 until segments.size) {
        val segment = segments[index]
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

private fun laneManeuverRenderCacheKey(
    maneuver: MapLaneManeuver?,
    settings: MapRenderSettings,
): String {
    if (maneuver == null) {
        return "empty|${settings.laneGuidanceEnabled}|${settings.laneGuidanceWidthPx}"
    }
    val bitmap = maneuver.bitmap
    return buildString {
        append(maneuver.token)
        append('|')
        append(maneuver.point.latitude)
        append(',')
        append(maneuver.point.longitude)
        append('|')
        append(maneuver.iconAnchor.x)
        append(',')
        append(maneuver.iconAnchor.y)
        append('|')
        append(bitmap.generationId)
        append('|')
        append(bitmap.width)
        append('x')
        append(bitmap.height)
        append('|')
        append(settings.laneGuidanceEnabled)
        append('|')
        append(settings.laneGuidanceWidthPx)
    }
}

private fun routeAlertRenderCacheKey(
    snapshot: MapRouteTelemetrySnapshot,
    settings: MapRenderSettings,
    displayLocation: Location?,
): String {
    return buildString {
        append(snapshot.routeAlertsToken ?: routeAlertsGeometryToken(snapshot.routeAlerts))
        append('|')
        append(snapshot.routeToken ?: routeGeometryToken(snapshot.routePoints))
        append('|')
        append(settings.roadEventsEnabled)
        append('|')
        append(settings.roadEventIconSizePx)
        append('|')
        settings.hiddenRoadEventTypes.toList().sorted().forEach { type ->
            append(type)
            append(',')
        }
        append('|')
        append(displayLocation.routeProgressRenderKey())
    }
}

private fun filterUpcomingRouteAlerts(
    alerts: List<MapRouteAlert>,
    routePoints: List<LatLng>,
    location: Location?,
): List<MapRouteAlert> {
    if (alerts.isEmpty() || routePoints.size < 2 || location == null) return alerts
    val currentProjection = findClosestRouteProjectionOnRoute(routePoints, location) ?: return alerts
    val currentProgressMeters = routeProgressMetersAtProjection(routePoints, currentProjection)
    return alerts.filter { alert ->
        val alertProjection = findClosestRouteProjectionOnRoute(
            points = routePoints,
            targetLat = alert.point.latitude,
            targetLon = alert.point.longitude
        ) ?: return@filter true
        val alertProgressMeters = routeProgressMetersAtProjection(routePoints, alertProjection)
        alertProgressMeters + MAP_ROUTE_ALERT_PASSED_TOLERANCE_METERS >= currentProgressMeters
    }
}

private fun routeAlertIconId(typeKey: String): String {
    return MAP_ROUTE_ALERT_ICON_PREFIX + resolveRoadEventToggleKey(typeKey)
}

private fun routeGeometryToken(points: List<LatLng>): String {
    if (points.isEmpty()) return "empty"
    return buildString {
        append(points.size)
        points.forEach { point ->
            append('|')
            append((point.latitude * 100_000.0).roundToInt())
            append(',')
            append((point.longitude * 100_000.0).roundToInt())
        }
    }
}

private fun routeAlertsGeometryToken(alerts: List<MapRouteAlert>): String {
    if (alerts.isEmpty()) return "empty"
    return buildString {
        append(alerts.size)
        alerts.forEach { alert ->
            append('|')
            append(resolveRoadEventToggleKey(alert.type))
            append('@')
            append((alert.point.latitude * 100_000.0).roundToInt())
            append(',')
            append((alert.point.longitude * 100_000.0).roundToInt())
        }
    }
}

private fun Location?.routeProgressRenderKey(granularityMeters: Double = MAP_ROUTE_PROGRESS_RENDER_GRANULARITY_METERS): String {
    if (this == null) return "no-location"
    val safeGranularity = granularityMeters.coerceAtLeast(1.0)
    val latBucket = (latitude * 111_320.0 / safeGranularity).roundToInt()
    val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(latitude))
    val lonBucket = if (abs(lonScale) <= 0.0001) {
        0
    } else {
        (longitude * lonScale / safeGranularity).roundToInt()
    }
    return "$latBucket|$lonBucket"
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
    val distance = distanceMetersBetween(start, end)
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
    return abs((((to - from + 540f) % 360f) - 180f))
}

private fun interpolateBearing(from: Float, to: Float, fraction: Double): Float {
    val delta = ((((to - from + 540f) % 360f) - 180f) * fraction).toFloat()
    return (from + delta + 360f) % 360f
}

private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f
