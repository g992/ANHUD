package com.g992.anhud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val ROUTE_PREFS_NAME = "map_route_telemetry"
private const val ROUTE_TELEMETRY_TAG = "MapRouteTelemetry"
private const val PREF_STATE = "state"
private const val PREF_START = "start"
private const val PREF_END = "end"
private const val PREF_SAMPLED = "sampled"
private const val PREF_ROUTE_ID = "route_id"
private const val PREF_PRE_MANEUVER = "pre_maneuver"
private const val PREF_WAYPOINTS = "waypoints"
private const val PREF_JAMS = "jams"
private const val PREF_ROUTE_ALERTS = "route_alerts"
private const val PREF_ROUTE_ALERTS_ROUTE_ID = "route_alerts_route_id"
private const val PREF_ROUTE_LANE_POINTS = "route_lane_points"
private const val EXTERNAL_ROUTE_SPEED_MPS = 10.0
private const val LANE_GUIDANCE_BIND_MAX_DISTANCE_METERS = 2_000.0
private const val LANE_GUIDANCE_DISPLAY_MAX_DISTANCE_METERS = 1_000.0
private const val LANE_GUIDANCE_PASS_SEGMENT_HYSTERESIS = 1
private const val LANE_GUIDANCE_CANDIDATE_MAX = 6
const val ROUTE_TELEMETRY_LOCATION_PROVIDER = "route-telemetry"

data class MapRouteTelemetrySnapshot(
    val state: String? = null,
    val hasExternalTelemetry: Boolean = false,
    val routeToken: String? = null,
    val routeJamsToken: String? = null,
    val routePoints: List<LatLng> = emptyList(),
    val routeJams: List<String> = emptyList(),
    val routeAlerts: List<MapRouteAlert> = emptyList(),
    val routeAlertsToken: String? = null,
    val laneManeuver: MapLaneManeuver? = null,
    val startLocation: Location? = null,
) {
    val hasRoute: Boolean
        get() = routePoints.size >= 2

    val routeBuilt: Boolean
        get() = hasRoute || state == MAP_ROUTE_STATE_BUILT
}

data class MapRouteAlert(
    val type: String,
    val point: LatLng,
)

data class MapLaneManeuver(
    val bitmap: Bitmap,
    val point: LatLng,
    val iconAnchor: PointF,
    val token: Int,
)

private data class ResolvedLaneBitmapAnchor(
    val anchor: PointF,
    val source: String,
)

private data class MapLanePoint(
    val routeId: String,
    val segmentIndex: Int,
    val point: LatLng,
    val laneSig: String,
)

private data class LaneGuidanceCandidate(
    val routeId: String,
    val segmentIndex: Int,
    val laneSig: String,
    val bitmap: Bitmap,
    val explicitPoint: LatLng?,
    val iconAnchor: PointF,
    val token: Int,
    val receivedAtUptimeMs: Long,
)

private data class ResolvedLaneGuidanceCandidate(
    val candidate: LaneGuidanceCandidate,
    val point: LatLng,
    val progressMeters: Double,
    val directDistanceMeters: Double?,
)

class MapRouteTelemetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MapRouteTelemetryStore.handleIntent(context.applicationContext, intent)
    }
}

object MapRouteTelemetryStore {
    private val listeners = CopyOnWriteArraySet<(MapRouteTelemetrySnapshot) -> Unit>()

    @Volatile
    private var initialized = false
    @Volatile
    private var currentSnapshot = MapRouteTelemetrySnapshot()
    private var parsedSampledRouteCache: ParsedSampledRoute? = null
    private var lastSnapshotLogKey: String? = null
    private var laneManeuverToken: Int = 0
    private var appContext: Context? = null
    private var routeLocation: Location? = null
    private var routeLocationListener: LocationListener? = null
    private val laneGuidanceCandidates = linkedMapOf<String, LaneGuidanceCandidate>()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            ensureRouteLocationTracking(context.applicationContext)
            currentSnapshot = buildSnapshot(context.applicationContext)
            initialized = true
        }
    }

    fun current(): MapRouteTelemetrySnapshot = currentSnapshot

    fun addListener(listener: (MapRouteTelemetrySnapshot) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (MapRouteTelemetrySnapshot) -> Unit) {
        listeners -= listener
    }

    fun handleIntent(context: Context, intent: Intent) {
        initialize(context)
        ensureRouteLocationTracking(context.applicationContext)
        val prefs = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE)
        when (intent.action) {
            MAP_ROUTE_TELEMETRY_ACTION -> {
                val sampled = intent.getStringExtra(MAP_EXTRA_ROUTE_SAMPLED)
                val jams = intent.getStringExtra(MAP_EXTRA_ROUTE_JAMS)
                val changed = prefs.editIfChanged {
                    putStringIfChanged(PREF_START, intent.getStringExtra(MAP_EXTRA_ROUTE_START))
                    putStringIfChanged(PREF_END, intent.getStringExtra(MAP_EXTRA_ROUTE_END))
                    putStringIfChanged(PREF_SAMPLED, sampled)
                    putStringIfChanged(PREF_PRE_MANEUVER, intent.getStringExtra(MAP_EXTRA_ROUTE_PRE_MANEUVER))
                    putStringIfChanged(PREF_WAYPOINTS, intent.getStringExtra(MAP_EXTRA_ROUTE_WAYPOINTS))
                    putStringIfChanged(PREF_JAMS, jams)
                    putStringIfChanged(PREF_ROUTE_LANE_POINTS, intent.getStringExtra(MAP_EXTRA_ROUTE_LANE_POINTS))
                }
                if (!changed) {
                    return
                }
            }

            MAP_ROUTE_STATE_ACTION -> {
                val state = intent.getStringExtra(MAP_EXTRA_ROUTE_STATE)
                Log.d(ROUTE_TELEMETRY_TAG, "route state: $state")
                val changed = prefs.editIfChanged {
                    putStringIfChanged(PREF_STATE, state)
                    if (state == MAP_ROUTE_STATE_ARRIVED || state == MAP_ROUTE_STATE_CANCELLED) {
                        removeIfPresent(PREF_START)
                        removeIfPresent(PREF_END)
                        removeIfPresent(PREF_SAMPLED)
                        removeIfPresent(PREF_ROUTE_ID)
                        removeIfPresent(PREF_PRE_MANEUVER)
                        removeIfPresent(PREF_WAYPOINTS)
                        removeIfPresent(PREF_JAMS)
                        removeIfPresent(PREF_ROUTE_ALERTS)
                        removeIfPresent(PREF_ROUTE_ALERTS_ROUTE_ID)
                        removeIfPresent(PREF_ROUTE_LANE_POINTS)
                        laneGuidanceCandidates.clear()
                    }
                }
                if (!changed) {
                    return
                }
            }

            MAP_ROUTE_ALERTS_ACTION,
            MAP_ROUTE_ALERTS_ALT_ACTION -> {
                val alerts = intent.getStringExtra(MAP_EXTRA_ROUTE_ALERTS)
                val routeId = intent.getStringExtra(MAP_EXTRA_ROUTE_ID)
                val changed = prefs.editIfChanged {
                    putStringIfChanged(PREF_ROUTE_ALERTS, alerts)
                    putStringIfChanged(PREF_ROUTE_ALERTS_ROUTE_ID, routeId)
                }
                if (!changed) {
                    return
                }
            }

            MAP_MANEUVER_BLOCK_ACTION,
            MAP_MANEUVER_BLOCK_ALT_ACTION -> {
                handleManeuverBlockIntent(context, intent)
                publishSnapshotIfChanged(context, buildSnapshot(context))
                return
            }
        }
        publishSnapshotIfChanged(context, buildSnapshot(context))
    }

    private fun handleManeuverBlockIntent(context: Context, intent: Intent) {
        if (intent.getStringExtra(MAP_EXTRA_MANEUVER_BLOCK_SOURCE_TYPE) != MAP_MANEUVER_SOURCE_LANE_AND_MANEUVER) {
            return
        }
        val bitmap = readBitmapExtra(intent, MAP_EXTRA_MANEUVER_BLOCK_BITMAP)
            ?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
            ?: return
        val routeId = intent.getStringExtra(MAP_EXTRA_LANE_ROUTE_ID).orEmpty().trim()
        val laneSig = intent.getStringExtra(MAP_EXTRA_LANE_SIG).orEmpty().trim()
        val laneSeg = intent.getIntExtra(MAP_EXTRA_LANE_SEG, Int.MIN_VALUE)
        if (laneSeg < 0 || laneSig.isBlank()) return
        laneManeuverToken += 1
        val iconAnchor = resolveLaneBitmapAnchor(intent, bitmap)
        val explicitPoint = resolveLanePositionFromIntent(intent)
        val candidate = LaneGuidanceCandidate(
            routeId = routeId,
            segmentIndex = laneSeg,
            laneSig = laneSig,
            bitmap = bitmap,
            explicitPoint = explicitPoint,
            iconAnchor = iconAnchor.anchor,
            token = laneManeuverToken,
            receivedAtUptimeMs = SystemClock.uptimeMillis()
        )
        laneGuidanceCandidates[buildLaneGuidanceKey(routeId, laneSeg, laneSig)] = candidate
        pruneLaneGuidanceCandidates()
        Log.d(
            ROUTE_TELEMETRY_TAG,
            "lane maneuver anchor: source=${iconAnchor.source} x=${iconAnchor.anchor.x.format(3)} " +
                "y=${iconAnchor.anchor.y.format(3)} " +
                "bitmap=${bitmap.width}x${bitmap.height} routeId=${routeId.ifBlank { "-" }} " +
                "laneSig=${laneSig.ifBlank { "-" }} laneSeg=$laneSeg " +
                "explicitPoint=${explicitPoint?.let { "${it.latitude},${it.longitude}" } ?: "-"} " +
                "intentAnchorValid=${intent.getBooleanExtra(MAP_EXTRA_LANE_BITMAP_ANCHOR_VALID, false)} " +
                "intentAnchorX=${intent.getFloatExtra(MAP_EXTRA_LANE_BITMAP_ANCHOR_X, Float.NaN)} " +
                "intentAnchorY=${intent.getFloatExtra(MAP_EXTRA_LANE_BITMAP_ANCHOR_Y, Float.NaN)}"
        )
    }

    private fun readBitmapExtra(intent: Intent, key: String): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key) as? Bitmap
            }
        }.getOrNull()
    }

    private fun resolveLanePositionFromIntent(intent: Intent): LatLng? {
        if (!intent.getBooleanExtra(MAP_EXTRA_LANE_POS_VALID, false)) return null
        val lat = intent.getFloatExtra(MAP_EXTRA_LANE_POS_LAT, Float.NaN)
        val lon = intent.getFloatExtra(MAP_EXTRA_LANE_POS_LON, Float.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return null
        return LatLng(lat.toDouble(), lon.toDouble())
    }

    private fun resolveLaneBitmapAnchor(intent: Intent, bitmap: Bitmap): ResolvedLaneBitmapAnchor {
        detectLaneBitmapAnchor(bitmap)?.let { return it }
        val fallbackAnchor = if (bitmap.width >= (bitmap.height * 1.3f)) {
            PointF(0.12f, 0.16f)
        } else {
            PointF(0.12f, 0.88f)
        }
        return ResolvedLaneBitmapAnchor(
            anchor = fallbackAnchor,
            source = "fallback_corner"
        )
    }

    private fun detectLaneBitmapAnchor(bitmap: Bitmap): ResolvedLaneBitmapAnchor? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 2 || height <= 2) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val alphaThreshold = 12
        val left = IntArray(height) { width }
        val right = IntArray(height) { -1 }
        val bottom = IntArray(width) { -1 }

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val alpha = pixels[rowOffset + x] ushr 24
                if (alpha <= alphaThreshold) continue
                if (y > bottom[x]) bottom[x] = y
                if (x < left[y]) left[y] = x
                if (x > right[y]) right[y] = x
            }
        }

        val validLeft = left.filter { it < width }
        val validRight = right.filter { it >= 0 }
        val validBottom = bottom.filter { it >= 0 }
        if (validLeft.isEmpty() || validRight.isEmpty() || validBottom.isEmpty()) {
            return null
        }

        val sideThresholdPx = maxOf(6f, width * 0.03f)
        val bottomThresholdPx = maxOf(6f, height * 0.05f)
        val leftBody = median(validLeft)
        val rightBody = median(validRight)
        val bottomBody = median(validBottom)

        detectHorizontalEdgeCluster(
            extents = left,
            bodyEdge = leftBody,
            side = HorizontalSide.LEFT,
            thresholdPx = sideThresholdPx
        )?.let { return it.toResolvedAnchor(width, height) }

        detectHorizontalEdgeCluster(
            extents = right,
            bodyEdge = rightBody,
            side = HorizontalSide.RIGHT,
            thresholdPx = sideThresholdPx
        )?.let { return it.toResolvedAnchor(width, height) }

        detectBottomEdgeCluster(
            extents = bottom,
            bodyEdge = bottomBody,
            thresholdPx = bottomThresholdPx
        )?.let { return it.toResolvedAnchor(width, height) }

        return null
    }

    private fun detectHorizontalEdgeCluster(
        extents: IntArray,
        bodyEdge: Float,
        side: HorizontalSide,
        thresholdPx: Float,
    ): DetectedAnchor? {
        val ranges = mutableListOf<IntRange>()
        var rangeStart = -1
        for (index in extents.indices) {
            val extent = extents[index]
            val protrusion = when {
                extent < 0 -> 0f
                side == HorizontalSide.LEFT -> bodyEdge - extent
                else -> extent - bodyEdge
            }
            if (protrusion >= thresholdPx) {
                if (rangeStart < 0) rangeStart = index
            } else if (rangeStart >= 0) {
                ranges += rangeStart until index
                rangeStart = -1
            }
        }
        if (rangeStart >= 0) {
            ranges += rangeStart until extents.size
        }
        val bestRange = ranges
            .filter { !it.isEmpty() && (it.last - it.first + 1) <= 48 }
            .maxByOrNull { range ->
                val peak = range.maxOf { rowIndex ->
                    if (side == HorizontalSide.LEFT) bodyEdge - extents[rowIndex] else extents[rowIndex] - bodyEdge
                }
                peak * (range.last - range.first + 1)
            } ?: return null

        val extreme = when (side) {
            HorizontalSide.LEFT -> bestRange.minOf { extents[it] }
            HorizontalSide.RIGHT -> bestRange.maxOf { extents[it] }
        }
        val rows = bestRange.filter { rowIndex ->
            when (side) {
                HorizontalSide.LEFT -> extents[rowIndex] <= extreme + 1
                HorizontalSide.RIGHT -> extents[rowIndex] >= extreme - 1
            }
        }
        if (rows.isEmpty()) return null
        val y = rows.average().toFloat()
        val x = extreme.toFloat()
        val source = if (side == HorizontalSide.LEFT) "detected_left" else "detected_right"
        return DetectedAnchor(x = x, y = y, score = 1f, source = source)
    }

    private fun detectBottomEdgeCluster(
        extents: IntArray,
        bodyEdge: Float,
        thresholdPx: Float,
    ): DetectedAnchor? {
        val ranges = mutableListOf<IntRange>()
        var rangeStart = -1
        for (index in extents.indices) {
            val extent = extents[index]
            val protrusion = if (extent < 0) 0f else extent - bodyEdge
            if (protrusion >= thresholdPx) {
                if (rangeStart < 0) rangeStart = index
            } else if (rangeStart >= 0) {
                ranges += rangeStart until index
                rangeStart = -1
            }
        }
        if (rangeStart >= 0) {
            ranges += rangeStart until extents.size
        }
        val bestRange = ranges
            .filter { !it.isEmpty() && (it.last - it.first + 1) <= 64 }
            .maxByOrNull { range ->
                val peak = range.maxOf { columnIndex -> extents[columnIndex] - bodyEdge }
                peak * (range.last - range.first + 1)
            } ?: return null

        val extreme = bestRange.maxOf { extents[it] }
        val columns = bestRange.filter { extents[it] >= extreme - 1 }
        if (columns.isEmpty()) return null
        return DetectedAnchor(
            x = columns.average().toFloat(),
            y = extreme.toFloat(),
            score = 1f,
            source = "detected_bottom"
        )
    }

    private fun DetectedAnchor.toResolvedAnchor(width: Int, height: Int): ResolvedLaneBitmapAnchor {
        return ResolvedLaneBitmapAnchor(
            anchor = PointF(
                (x / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                (y / (height - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            ),
            source = source
        )
    }

    private fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle].toFloat()
        }
    }

    private data class DetectedAnchor(
        val x: Float,
        val y: Float,
        val score: Float,
        val source: String,
    )

    private fun IntRange.isEmpty(): Boolean = first > last

    private enum class HorizontalSide {
        LEFT,
        RIGHT,
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

    private fun ensureRouteLocationTracking(context: Context) {
        if (routeLocationListener != null || !hasLocationPermission(context)) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { location ->
            routeLocation = Location(location)
            publishSnapshotIfChanged(context, buildSnapshot(context))
        }
        routeLocationListener = listener
        preferredLocationProviders(manager).forEach { provider ->
            latestKnownLocation(manager, provider)?.let { known ->
                routeLocation = Location(known)
            }
            runCatching {
                manager.requestLocationUpdates(provider, 1000L, 3f, listener, Looper.getMainLooper())
            }.onFailure {
                Log.w(ROUTE_TELEMETRY_TAG, "route location subscribe failed for $provider: ${it.message}")
            }
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pruneLaneGuidanceCandidates(currentSegmentIndex: Int = Int.MIN_VALUE) {
        val iterator = laneGuidanceCandidates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val candidate = entry.value
            val invalidBitmap = candidate.bitmap.isRecycled || candidate.bitmap.width <= 0 || candidate.bitmap.height <= 0
            val behindRoute = currentSegmentIndex >= 0 &&
                candidate.segmentIndex < (currentSegmentIndex - LANE_GUIDANCE_PASS_SEGMENT_HYSTERESIS)
            if (invalidBitmap || behindRoute) {
                iterator.remove()
            }
        }
        while (laneGuidanceCandidates.size > LANE_GUIDANCE_CANDIDATE_MAX) {
            val oldestKey = laneGuidanceCandidates.entries.minByOrNull { it.value.receivedAtUptimeMs }?.key ?: break
            laneGuidanceCandidates.remove(oldestKey)
        }
    }

    private fun resolveActiveLaneGuidance(
        prefs: android.content.SharedPreferences,
        routePoints: List<LatLng>,
        routeId: String?,
    ): MapLaneManeuver? {
        if (laneGuidanceCandidates.isEmpty()) return null
        val location = routeLocation
        val currentProjection = location?.let { findClosestRouteProjectionOnRoute(routePoints, it) }
        val currentSegmentIndex = currentProjection?.segmentIndex ?: -1
        val currentProgressMeters = currentProjection?.let { routeProgressMetersAtProjection(routePoints, it) }
        pruneLaneGuidanceCandidates(currentSegmentIndex)
        val lanePointsRaw = prefs.getString(PREF_ROUTE_LANE_POINTS, null)
        val currentAnchor = location?.let { LatLng(it.latitude, it.longitude) }

        val resolvedCandidates = laneGuidanceCandidates.values.mapNotNull { candidate ->
            val point = candidate.explicitPoint
                ?: findLanePoint(
                    raw = lanePointsRaw,
                    routeId = candidate.routeId.ifBlank { routeId.orEmpty() },
                    segmentIndex = candidate.segmentIndex,
                    laneSig = candidate.laneSig
                )
                ?: fallbackRoutePoint(routePoints, candidate.segmentIndex)
                ?: return@mapNotNull null
            if (
                currentSegmentIndex >= 0 &&
                candidate.segmentIndex < (currentSegmentIndex - LANE_GUIDANCE_PASS_SEGMENT_HYSTERESIS)
            ) {
                return@mapNotNull null
            }
            val directDistance = currentAnchor?.let { distanceMetersBetween(it, point) }
            if (directDistance != null && directDistance > LANE_GUIDANCE_BIND_MAX_DISTANCE_METERS) {
                return@mapNotNull null
            }
            val progressMeters = if (routePoints.size >= 2) {
                findClosestRouteProjectionOnRoute(routePoints, point.latitude, point.longitude)
                    ?.let { routeProgressMetersAtProjection(routePoints, it) }
                    ?: candidate.segmentIndex.toDouble()
            } else {
                candidate.segmentIndex.toDouble()
            }
            ResolvedLaneGuidanceCandidate(
                candidate = candidate,
                point = point,
                progressMeters = progressMeters,
                directDistanceMeters = directDistance
            )
        }
        if (resolvedCandidates.isEmpty()) return null

        var selected: ResolvedLaneGuidanceCandidate? = null
        var bestProgressDelta = Double.MAX_VALUE
        var bestDistance = Double.MAX_VALUE
        var bestSegment = Int.MAX_VALUE
        var bestToken = Int.MIN_VALUE
        for (candidate in resolvedCandidates) {
            val progressDelta = if (currentProgressMeters != null) {
                val delta = candidate.progressMeters - currentProgressMeters
                if (delta >= 0.0) delta else Double.MAX_VALUE / 4
            } else {
                candidate.candidate.segmentIndex.toDouble()
            }
            val distance = candidate.directDistanceMeters ?: Double.MAX_VALUE
            val segment = candidate.candidate.segmentIndex
            val token = candidate.candidate.token
            val better = progressDelta < bestProgressDelta ||
                (progressDelta == bestProgressDelta && distance < bestDistance) ||
                (progressDelta == bestProgressDelta && distance == bestDistance && segment < bestSegment) ||
                (progressDelta == bestProgressDelta &&
                    distance == bestDistance &&
                    segment == bestSegment &&
                    token > bestToken)
            if (better) {
                selected = candidate
                bestProgressDelta = progressDelta
                bestDistance = distance
                bestSegment = segment
                bestToken = token
            }
        }
        selected ?: return null
        val remainingDistanceMeters = when {
            currentProgressMeters != null -> selected.progressMeters - currentProgressMeters
            selected.directDistanceMeters != null -> selected.directDistanceMeters
            else -> null
        } ?: return null
        if (
            remainingDistanceMeters < 0.0 ||
            remainingDistanceMeters > LANE_GUIDANCE_DISPLAY_MAX_DISTANCE_METERS
        ) {
            return null
        }

        return MapLaneManeuver(
            bitmap = selected.candidate.bitmap,
            point = selected.point,
            iconAnchor = selected.candidate.iconAnchor,
            token = selected.candidate.token
        )
    }

    private fun fallbackRoutePoint(routePoints: List<LatLng>, segmentIndex: Int): LatLng? {
        if (segmentIndex < 0) return null
        return routePoints.getOrNull(segmentIndex.coerceAtMost(routePoints.lastIndex))
    }

    private fun buildLaneGuidanceKey(routeId: String, segmentIndex: Int, laneSig: String): String {
        return routeId.trim() + "|" + segmentIndex + "|" + laneSig.trim()
    }

    private fun publishSnapshotIfChanged(context: Context, snapshot: MapRouteTelemetrySnapshot) {
        if (snapshot.sameRouteContentAs(currentSnapshot)) {
            currentSnapshot = snapshot
            return
        }
        currentSnapshot = snapshot
        logSnapshot(currentSnapshot)
        listeners.forEach { it(currentSnapshot) }
        notifyRouteStatusChanged(context, currentSnapshot)
    }

    private fun logSnapshot(snapshot: MapRouteTelemetrySnapshot) {
        val debugKey = "${snapshot.state}|${snapshot.routeToken.orEmpty()}|${snapshot.hasRoute}|" +
            "${snapshot.routePoints.size}|${snapshot.routeJams.size}"
        if (debugKey == lastSnapshotLogKey) return
        lastSnapshotLogKey = debugKey
        Log.d(
            ROUTE_TELEMETRY_TAG,
            "snapshot: state=${snapshot.state} hasRoute=${snapshot.hasRoute} " +
                "built=${snapshot.routeBuilt} points=${snapshot.routePoints.size} " +
                "routeToken=${snapshot.routeToken.orEmpty()}"
        )
    }

    fun replaceRoutePolyline(context: Context, routeId: String?, points: List<LatLng>) {
        if (points.size < 2) return
        initialize(context)
        val routeToken = points.routePointsToken(routeId)
        if (currentSnapshot.state == MAP_ROUTE_STATE_BUILT &&
            currentSnapshot.routeToken == routeToken &&
            currentSnapshot.routeJams.isEmpty()
        ) {
            return
        }
        val sampled = serializePoints(points)
        context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE).editIfChanged {
            putStringIfChanged(PREF_STATE, MAP_ROUTE_STATE_BUILT)
            putStringIfChanged(PREF_ROUTE_ID, routeId)
            putStringIfChanged(PREF_SAMPLED, sampled)
            removeIfPresent(PREF_JAMS)
        }
        laneGuidanceCandidates.clear()
        publishSnapshotIfChanged(context, buildSnapshot(context))
        Log.d(ROUTE_TELEMETRY_TAG, "route polyline stored: id=${routeId.orEmpty()} points=${points.size}")
    }

    fun clearRoutePolyline(context: Context) {
        initialize(context)
        val changed = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE).editIfChanged {
            putStringIfChanged(PREF_STATE, MAP_ROUTE_STATE_CANCELLED)
            removeIfPresent(PREF_START)
            removeIfPresent(PREF_END)
            removeIfPresent(PREF_SAMPLED)
            removeIfPresent(PREF_ROUTE_ID)
            removeIfPresent(PREF_PRE_MANEUVER)
            removeIfPresent(PREF_WAYPOINTS)
            removeIfPresent(PREF_JAMS)
        }
        if (!changed) {
            return
        }
        laneGuidanceCandidates.clear()
        publishSnapshotIfChanged(context, buildSnapshot(context))
        Log.d(ROUTE_TELEMETRY_TAG, "route polyline cleared")
    }

    private fun notifyRouteStatusChanged(context: Context, snapshot: MapRouteTelemetrySnapshot) {
        context.sendBroadcast(
            Intent(MAP_RENDER_ROUTE_STATUS_ACTION).apply {
                putExtra(MAP_EXTRA_HAS_ROUTE, snapshot.hasRoute)
                putExtra(MAP_EXTRA_ROUTE_BUILT, snapshot.routeBuilt)
                putExtra(MAP_EXTRA_ROUTE_STATE, snapshot.state)
            }
        )
    }

    private fun buildSnapshot(context: Context): MapRouteTelemetrySnapshot {
        val prefs = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE)
        val sampledRaw = prefs.getString(PREF_SAMPLED, null)
        val startRaw = prefs.getString(PREF_START, null)
        val routeId = prefs.getString(PREF_ROUTE_ID, null)
        val jamsRaw = prefs.getString(PREF_JAMS, null)
        val alertsRaw = prefs.getString(PREF_ROUTE_ALERTS, null)
        val alertsRouteId = prefs.getString(PREF_ROUTE_ALERTS_ROUTE_ID, null)
        val parsedSampled = parseSampledRoute(sampledRaw, routeId)
        val points = parsedSampled?.points ?: listOfNotNull(
            parsePoint(startRaw),
            parsePoint(prefs.getString(PREF_END, null))
        )
        val routeToken = parsedSampled?.token ?: listOfNotNull(parsePoint(startRaw), parsePoint(prefs.getString(PREF_END, null)))
            .takeIf { it.size >= 2 }
            ?.routePointsToken(routeId)
        val jams = normalizeJams(jamsRaw, points.size - 1)
        return MapRouteTelemetrySnapshot(
            state = prefs.getString(PREF_STATE, null),
            hasExternalTelemetry = sampledRaw != null || prefs.getString(PREF_STATE, null) != null,
            routeToken = routeToken,
            routeJamsToken = jamsRaw.routeRawToken(),
            routePoints = points,
            routeJams = jams,
            routeAlerts = parseRouteAlerts(alertsRaw),
            routeAlertsToken = alertsRaw.routeRawToken(alertsRouteId),
            laneManeuver = resolveActiveLaneGuidance(prefs, points, routeId),
            startLocation = parsedSampled?.startLocation ?: points.startLocation(),
        )
    }

    private fun parseSampledRoute(raw: String?, routeId: String?): ParsedSampledRoute? {
        if (raw.isNullOrBlank()) return null
        parsedSampledRouteCache?.takeIf { it.raw == raw && it.routeId == routeId }?.let { return it }
        val points = parsePoints(raw)
        val parsed = ParsedSampledRoute(
            raw = raw,
            routeId = routeId,
            points = points,
            startLocation = points.startLocation(),
            token = points.routePointsToken(routeId),
        )
        parsedSampledRouteCache = parsed
        return parsed
    }

    private fun parsePoints(raw: String?): List<LatLng> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull(::parsePoint)
    }

    private fun parsePoint(raw: String?): LatLng? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return LatLng(lat, lon)
    }

    private fun parseRouteAlerts(raw: String?): List<MapRouteAlert> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { chunk ->
            val separator = chunk.indexOf('@')
            if (separator <= 0 || separator >= chunk.lastIndex) return@mapNotNull null
            val type = chunk.substring(0, separator).trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val point = parsePoint(chunk.substring(separator + 1)) ?: return@mapNotNull null
            MapRouteAlert(type, point)
        }
    }

    private fun parseLanePoints(raw: String?): List<MapLanePoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull(::parseLanePoint)
    }

    private fun parseLanePoint(raw: String): MapLanePoint? {
        val parts = raw.split('|')
        if (parts.size >= 4 && ',' in parts[1]) {
            val seg = parts[0].trim().toIntOrNull() ?: return null
            val point = parsePoint(parts[1]) ?: return null
            val sig = parts[2].trim()
            val routeId = parts[3].trim()
            if (sig.isBlank()) return null
            return MapLanePoint(routeId, seg, point, sig)
        }
        if (parts.size >= 5) {
            val seg = parts[0].trim().toIntOrNull() ?: return null
            val lat = parts[1].trim().toDoubleOrNull() ?: return null
            val lon = parts[2].trim().toDoubleOrNull() ?: return null
            val sig = parts[3].trim()
            val routeId = parts[4].trim()
            if (sig.isBlank()) return null
            return MapLanePoint(routeId, seg, LatLng(lat, lon), sig)
        }
        return null
    }

    private fun findLanePoint(
        raw: String?,
        routeId: String,
        segmentIndex: Int,
        laneSig: String,
    ): LatLng? {
        if (segmentIndex == Int.MIN_VALUE || laneSig.isBlank()) return null
        val candidates = parseLanePoints(raw).filter { point ->
            point.segmentIndex == segmentIndex &&
                point.laneSig == laneSig &&
                (routeId.isBlank() || point.routeId.isBlank() || point.routeId == routeId)
        }
        return candidates.firstOrNull()?.point
    }

    private fun serializePoints(points: List<LatLng>): String {
        return points.joinToString(";") { point ->
            "${point.latitude},${point.longitude}"
        }
    }

    private fun normalizeJams(raw: String?, size: Int): List<String> {
        if (size <= 0) return emptyList()
        val base = raw.orEmpty()
            .split(Regex("[,;]"))
            .mapNotNull { jam ->
                when (jam.trim().uppercase()) {
                    "FREE" -> "low"
                    "LIGHT" -> "moderate"
                    "HARD" -> "heavy"
                    "VERY_HARD", "VERYHARD" -> "severe"
                    "BLOCKED" -> "blocked"
                    else -> null
                }
            }
            .toMutableList()
        while (base.size < size) {
            base += base.lastOrNull() ?: "low"
        }
        return base.take(size)
    }

    private data class ParsedSampledRoute(
        val raw: String,
        val routeId: String?,
        val points: List<LatLng>,
        val startLocation: Location?,
        val token: String,
    )

    private class PrefsChangeBuilder(
        private val prefs: android.content.SharedPreferences,
        private val editor: android.content.SharedPreferences.Editor,
    ) {
        var changed: Boolean = false
            private set

        fun putStringIfChanged(key: String, value: String?) {
            if (prefs.getString(key, null) == value) return
            editor.putString(key, value)
            changed = true
        }

        fun removeIfPresent(key: String) {
            if (!prefs.contains(key)) return
            editor.remove(key)
            changed = true
        }
    }

    private fun android.content.SharedPreferences.editIfChanged(block: PrefsChangeBuilder.() -> Unit): Boolean {
        val editor = edit()
        val builder = PrefsChangeBuilder(this, editor)
        builder.block()
        if (builder.changed) {
            editor.apply()
        }
        return builder.changed
    }

    private fun MapRouteTelemetrySnapshot.sameRouteContentAs(other: MapRouteTelemetrySnapshot): Boolean {
        return state == other.state &&
            hasExternalTelemetry == other.hasExternalTelemetry &&
            routeToken == other.routeToken &&
            routeJamsToken == other.routeJamsToken &&
            routeAlertsToken == other.routeAlertsToken &&
            laneManeuver?.token == other.laneManeuver?.token &&
            laneManeuver?.point == other.laneManeuver?.point &&
            routePoints.size == other.routePoints.size &&
            routeJams == other.routeJams &&
            routeAlerts == other.routeAlerts
    }

    private fun String?.routeRawToken(extra: String? = null): String? {
        if (this.isNullOrBlank()) return null
        return buildString {
            append(this@routeRawToken.length)
            append(':')
            append(stableHash64(this@routeRawToken).toString(16))
            if (!extra.isNullOrBlank()) {
                append(':')
                append(stableHash64(extra).toString(16))
            }
        }
    }

    private fun List<LatLng>.routePointsToken(routeId: String?): String {
        var hash = 1125899906842597L
        forEach { point ->
            hash = (hash * 31L) + point.latitude.toBits()
            hash = (hash * 31L) + point.longitude.toBits()
        }
        if (!routeId.isNullOrBlank()) {
            hash = (hash * 31L) + stableHash64(routeId)
        }
        return "$size:${hash.toString(16)}"
    }

    private fun stableHash64(value: String): Long {
        var hash = -3750763034362895579L
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 1099511628211L
        }
        return hash
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
        return runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }

    private fun List<LatLng>.startLocation(): Location? {
        if (size < 2) return null
        val start = first()
        val next = this[1]
        return Location(ROUTE_TELEMETRY_LOCATION_PROVIDER).apply {
            latitude = start.latitude
            longitude = start.longitude
            altitude = start.altitude
            bearing = bearingBetween(start, next).toFloat()
            speed = EXTERNAL_ROUTE_SPEED_MPS.toFloat()
            accuracy = 3f
            time = System.currentTimeMillis()
        }
    }

    private fun bearingBetween(start: LatLng, end: LatLng): Double {
        val startLat = Math.toRadians(start.latitude)
        val endLat = Math.toRadians(end.latitude)
        val deltaLon = Math.toRadians(end.longitude - start.longitude)
        val y = sin(deltaLon) * cos(endLat)
        val x = cos(startLat) * sin(endLat) -
            sin(startLat) * cos(endLat) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

const val MAP_ROUTE_TELEMETRY_ACTION = "com.yandex.ROUTE_TELEMETRY"
const val MAP_ROUTE_STATE_ACTION = "com.yandex.ROUTE_STATE"
const val MAP_ROUTE_ALERTS_ACTION = "com.yandex.ROUTE_ALERTS"
const val MAP_ROUTE_ALERTS_ALT_ACTION = "ru.yandex.yandexmaps.ROUTE_ALERTS"
const val MAP_MANEUVER_BLOCK_ACTION = "com.yandex.MANEUVER_BLOCK_BITMAP"
const val MAP_MANEUVER_BLOCK_ALT_ACTION = "ru.yandex.yandexmaps.MANEUVER_BLOCK_BITMAP"
const val MAP_EXTRA_ROUTE_STATE = "route_state"
const val MAP_EXTRA_ROUTE_ID = "route_id"
const val MAP_EXTRA_ROUTE_START = "route_start"
const val MAP_EXTRA_ROUTE_END = "route_end"
const val MAP_EXTRA_ROUTE_SAMPLED = "route_sampled"
const val MAP_EXTRA_ROUTE_PRE_MANEUVER = "route_pre_maneuver"
const val MAP_EXTRA_ROUTE_WAYPOINTS = "route_waypoints"
const val MAP_EXTRA_ROUTE_JAMS = "route_jams"
const val MAP_EXTRA_ROUTE_LANE_POINTS = "route_lane_points"
const val MAP_EXTRA_ROUTE_ALERTS = "route_alerts"
const val MAP_EXTRA_MANEUVER_BLOCK_BITMAP = "maneuver_block_bitmap"
const val MAP_EXTRA_MANEUVER_BLOCK_SOURCE_TYPE = "source_type"
const val MAP_MANEUVER_SOURCE_LANE_AND_MANEUVER = "lane_and_maneuver"
const val MAP_EXTRA_LANE_SIG = "lane_sig"
const val MAP_EXTRA_LANE_SEG = "lane_seg"
const val MAP_EXTRA_LANE_ROUTE_ID = "lane_route_id"
const val MAP_EXTRA_LANE_POS_VALID = "lane_pos_valid"
const val MAP_EXTRA_LANE_POS_LAT = "lane_pos_lat"
const val MAP_EXTRA_LANE_POS_LON = "lane_pos_lon"
const val MAP_EXTRA_LANE_BITMAP_ANCHOR_VALID = "lane_bitmap_anchor_valid"
const val MAP_EXTRA_LANE_BITMAP_ANCHOR_X = "lane_bitmap_anchor_x"
const val MAP_EXTRA_LANE_BITMAP_ANCHOR_Y = "lane_bitmap_anchor_y"
const val MAP_EXTRA_HAS_ROUTE = "has_route"
const val MAP_EXTRA_ROUTE_BUILT = "route_built"
const val MAP_ROUTE_STATE_BUILT = "BUILT"
const val MAP_ROUTE_STATE_ARRIVED = "ARRIVED"
const val MAP_ROUTE_STATE_CANCELLED = "CANCELLED"
const val MAP_RENDER_ROUTE_STATUS_ACTION = "com.g992.mapformer.ROUTE_STATUS_CHANGED"
