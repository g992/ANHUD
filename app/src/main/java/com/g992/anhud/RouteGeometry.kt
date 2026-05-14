package com.g992.anhud

import android.location.Location
import kotlin.math.cos
import kotlin.math.hypot

internal data class SharedRouteProjection(
    val segmentIndex: Int,
    val projectedPoint: LatLng,
    val distanceMeters: Double,
)

internal data class SharedSegmentProjection(
    val projectedPoint: LatLng,
    val distanceMeters: Double,
)

internal data class RouteTrimStart(
    val segmentIndex: Int,
    val point: LatLng,
)

internal fun findClosestRouteProjectionOnRoute(
    points: List<LatLng>,
    location: Location,
): SharedRouteProjection? {
    return findClosestRouteProjectionOnRoute(points, location.latitude, location.longitude)
}

internal fun findClosestRouteProjectionOnRoute(
    points: List<LatLng>,
    targetLat: Double,
    targetLon: Double,
): SharedRouteProjection? {
    if (points.size < 2) return null
    var best: SharedRouteProjection? = null
    for (index in 0 until points.lastIndex) {
        val projection = projectPointOntoRouteSegment(targetLat, targetLon, points[index], points[index + 1])
        if (best == null || projection.distanceMeters < best.distanceMeters) {
            best = SharedRouteProjection(index, projection.projectedPoint, projection.distanceMeters)
        }
    }
    return best
}

internal fun routeProgressMetersAtProjection(points: List<LatLng>, projection: SharedRouteProjection): Double {
    if (points.size < 2) return 0.0
    var distance = 0.0
    for (index in 0 until projection.segmentIndex.coerceAtMost(points.lastIndex - 1)) {
        distance += distanceMetersBetween(points[index], points[index + 1])
    }
    val segmentStart = points.getOrNull(projection.segmentIndex) ?: return distance
    distance += distanceMetersBetween(segmentStart, projection.projectedPoint)
    return distance
}

internal fun findNearestRouteSegmentIndexForLocation(points: List<LatLng>, location: Location): Int {
    return findClosestRouteProjectionOnRoute(points, location)?.segmentIndex ?: -1
}

internal fun resolveRouteTrimStart(
    points: List<LatLng>,
    location: Location?,
    backtrackMeters: Double,
    maxProjectionDistanceMeters: Double,
): RouteTrimStart {
    return if (location == null) {
        resolveRouteTrimStart(
            points = points,
            targetLat = null,
            targetLon = null,
            backtrackMeters = backtrackMeters,
            maxProjectionDistanceMeters = maxProjectionDistanceMeters
        )
    } else {
        resolveRouteTrimStart(
            points = points,
            targetLat = location.latitude,
            targetLon = location.longitude,
            backtrackMeters = backtrackMeters,
            maxProjectionDistanceMeters = maxProjectionDistanceMeters
        )
    }
}

internal fun resolveRouteTrimStart(
    points: List<LatLng>,
    targetLat: Double?,
    targetLon: Double?,
    backtrackMeters: Double,
    maxProjectionDistanceMeters: Double,
): RouteTrimStart {
    if (points.size < 2) {
        val start = points.firstOrNull() ?: LatLng(0.0, 0.0)
        return RouteTrimStart(segmentIndex = 0, point = start)
    }
    val projection = if (targetLat == null || targetLon == null) {
        null
    } else {
        findClosestRouteProjectionOnRoute(points, targetLat, targetLon)
            ?.takeIf { it.distanceMeters <= maxProjectionDistanceMeters }
    }
    val trimStart = projection?.let { backtrackRouteProjection(points, it, backtrackMeters) }
    return if (trimStart == null) {
        RouteTrimStart(segmentIndex = 0, point = points.first())
    } else {
        RouteTrimStart(segmentIndex = trimStart.segmentIndex, point = trimStart.projectedPoint)
    }
}

internal fun distanceMetersBetween(start: LatLng, end: LatLng): Double {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
    val dx = (end.longitude - start.longitude) * longitudeScale
    val dy = (end.latitude - start.latitude) * latitudeScale
    return hypot(dx, dy)
}

internal fun interpolateRouteLatLng(start: LatLng, end: LatLng, fraction: Double): LatLng {
    val safeFraction = fraction.coerceIn(0.0, 1.0)
    return LatLng(
        start.latitude + ((end.latitude - start.latitude) * safeFraction),
        start.longitude + ((end.longitude - start.longitude) * safeFraction)
    )
}

internal fun backtrackRouteProjection(
    points: List<LatLng>,
    projection: SharedRouteProjection,
    backtrackMeters: Double,
): SharedRouteProjection {
    if (backtrackMeters <= 0.0 || projection.segmentIndex !in 0 until points.lastIndex) {
        return projection
    }
    var remaining = backtrackMeters
    val currentSegmentStart = points[projection.segmentIndex]
    val distanceFromSegmentStart = distanceMetersBetween(currentSegmentStart, projection.projectedPoint)
    if (distanceFromSegmentStart >= remaining) {
        return projection.copy(
            projectedPoint = interpolateRouteLatLng(
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
        val segmentLength = distanceMetersBetween(segmentStart, segmentEnd)
        if (segmentLength >= remaining) {
            return projection.copy(
                segmentIndex = segmentIndex,
                projectedPoint = interpolateRouteLatLng(
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

private fun projectPointOntoRouteSegment(
    targetLat: Double,
    targetLon: Double,
    start: LatLng,
    end: LatLng,
): SharedSegmentProjection {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
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
    return SharedSegmentProjection(
        projectedPoint = projectedPoint,
        distanceMeters = hypot(px - closestX, py - closestY)
    )
}
