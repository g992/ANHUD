package com.g992.anhud

import android.content.Context
import android.content.SharedPreferences
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Polygon
import kotlin.math.asinh
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

data class OfflineRegionBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

private data class OfflinePolygonPoint(
    val lon: Double,
    val lat: Double,
)

const val OFFLINE_REGION_MAX_ZOOM = 16.0

data class OfflineSizeEstimate(
    val lowMb: Double,
    val highMb: Double,
) {
    fun lowBytes(): Long = (lowMb * 1024.0 * 1024.0).toLong().coerceAtLeast(0L)

    fun highBytes(): Long = (highMb * 1024.0 * 1024.0).toLong().coerceAtLeast(0L)

    fun displayText(context: Context): String {
        return when {
            highMb <= 0.0 -> context.getString(R.string.map_settings_offline_size_unknown)
            highMb - lowMb < 8.0 -> context.getString(
                R.string.map_settings_offline_size_single,
                highMb
            )
            else -> context.getString(
                R.string.map_settings_offline_size_range,
                lowMb,
                highMb
            )
        }
    }
}

object OfflineRegionSizeEstimator {
    private const val PREFS_NAME = "offline_region_size_estimator"
    private const val KEY_BYTES_PER_TILE = "bytes_per_tile"
    private const val DEFAULT_LOW_BYTES_PER_TILE = 12_000.0
    private const val DEFAULT_HIGH_BYTES_PER_TILE = 26_000.0
    private const val STYLE_OVERHEAD_MB = 3.0
    private const val ESTIMATE_MAX_ZOOM_CAP = OFFLINE_REGION_MAX_ZOOM.toInt()
    private const val VECTOR_SOURCE_ESTIMATE_ZOOM_OFFSET = 2
    private const val MIN_REASONABLE_BYTES_PER_TILE = 4_000.0
    private const val MAX_REASONABLE_BYTES_PER_TILE = 64_000.0
    private const val MIN_CALIBRATION_TILE_COUNT = 100L

    fun estimate(context: Context, bounds: OfflineRegionBounds, maxZoom: Double): OfflineSizeEstimate {
        return estimate(context, estimateTileCount(bounds, maxZoom))
    }

    fun estimate(context: Context, tileCount: Long): OfflineSizeEstimate {
        val totalTiles = tileCount.coerceAtLeast(1L)
        val calibratedBytesPerTile = calibratedBytesPerTile(context)
        val lowBytesPerTile = calibratedBytesPerTile?.let { it * 0.75 } ?: DEFAULT_LOW_BYTES_PER_TILE
        val highBytesPerTile = calibratedBytesPerTile?.let { it * 1.25 } ?: DEFAULT_HIGH_BYTES_PER_TILE
        return OfflineSizeEstimate(
            lowMb = STYLE_OVERHEAD_MB + (totalTiles.toDouble() * lowBytesPerTile / (1024.0 * 1024.0)),
            highMb = STYLE_OVERHEAD_MB + (totalTiles.toDouble() * highBytesPerTile / (1024.0 * 1024.0)),
        )
    }

    fun estimateTileCount(
        geometry: Geometry,
        bounds: OfflineRegionBounds,
        maxZoom: Double,
    ): Long {
        val effectiveZoom = effectiveEstimateZoom(maxZoom)
        val bboxTiles = estimateTileCountAtZoom(bounds, effectiveZoom)
        val polygons = geometry.toPolygonRings() ?: return bboxTiles
        val fillRatio = polygonFillRatio(polygons, bounds)
        if (fillRatio <= 0.0) return bboxTiles
        return (bboxTiles.toDouble() * fillRatio).toLong().coerceAtLeast(1L)
    }

    fun estimateTileCount(bounds: OfflineRegionBounds, maxZoom: Double): Long {
        return estimateTileCountAtZoom(bounds, effectiveEstimateZoom(maxZoom)).coerceAtLeast(1L)
    }

    fun calibrate(context: Context, completedTileBytes: Long, completedTileCount: Long) {
        if (completedTileBytes <= 0L || completedTileCount < MIN_CALIBRATION_TILE_COUNT) return
        val measured = completedTileBytes.toDouble() / completedTileCount.toDouble()
        if (measured !in MIN_REASONABLE_BYTES_PER_TILE..MAX_REASONABLE_BYTES_PER_TILE) {
            prefs(context).edit().remove(KEY_BYTES_PER_TILE).apply()
            return
        }
        val prefs = prefs(context)
        val previous = calibratedBytesPerTile(context)
        val smoothed = if (previous == null) {
            measured
        } else {
            previous * 0.65 + measured * 0.35
        }
        prefs.edit().putFloat(KEY_BYTES_PER_TILE, smoothed.toFloat()).apply()
    }

    private fun calibratedBytesPerTile(context: Context): Double? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_BYTES_PER_TILE)) return null
        val value = prefs.getFloat(KEY_BYTES_PER_TILE, 0f).toDouble()
        if (value !in MIN_REASONABLE_BYTES_PER_TILE..MAX_REASONABLE_BYTES_PER_TILE) {
            prefs.edit().remove(KEY_BYTES_PER_TILE).apply()
            return null
        }
        return value
    }

    fun clearCalibration(context: Context) {
        prefs(context).edit().remove(KEY_BYTES_PER_TILE).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun estimateTileCountAtZoom(bounds: OfflineRegionBounds, zoom: Int): Long {
        val westX = lonToTileX(bounds.west, zoom)
        val eastX = lonToTileX(bounds.east, zoom)
        val northY = latToTileY(bounds.north, zoom)
        val southY = latToTileY(bounds.south, zoom)
        val xTiles = (eastX - westX + 1).coerceAtLeast(1)
        val yTiles = (southY - northY + 1).coerceAtLeast(1)
        return xTiles.toLong() * yTiles.toLong()
    }

    private fun effectiveEstimateZoom(maxZoom: Double): Int {
        val requestedZoom = maxZoom.roundToInt()
            .coerceIn(MAP_ZOOM_MIN.roundToInt(), ESTIMATE_MAX_ZOOM_CAP)
        val sourceZoom = (MAP_STYLE_SOURCE_MAX_ZOOM - VECTOR_SOURCE_ESTIMATE_ZOOM_OFFSET)
            .coerceIn(MAP_ZOOM_MIN.roundToInt(), ESTIMATE_MAX_ZOOM_CAP)
        return minOf(requestedZoom, sourceZoom)
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val normalized = ((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0) * n.toDouble()
        return normalized.toInt().coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val clipped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clipped)
        val n = 1 shl zoom
        val mercator = (1.0 - asinh(tan(latRad)) / Math.PI) / 2.0
        return (mercator * n.toDouble()).toInt().coerceIn(0, n - 1)
    }

    private fun Geometry.toPolygonRings(): List<List<List<OfflinePolygonPoint>>>? {
        return when (this) {
            is Polygon -> listOf(
                coordinates().map { ring ->
                    ring.map { point -> OfflinePolygonPoint(point.longitude(), point.latitude()) }
                }
            )
            is MultiPolygon -> coordinates().map { polygon ->
                polygon.map { ring ->
                    ring.map { point -> OfflinePolygonPoint(point.longitude(), point.latitude()) }
                }
            }
            else -> null
        }
    }

    private fun polygonFillRatio(
        polygons: List<List<List<OfflinePolygonPoint>>>,
        bounds: OfflineRegionBounds,
    ): Double {
        val bboxArea = projectedBoxArea(bounds)
        if (bboxArea <= 0.0) return 1.0
        val polygonArea = polygons.sumOf { polygonProjectedArea(it) }
        if (polygonArea <= 0.0) return 1.0
        return (polygonArea / bboxArea).coerceIn(0.01, 1.0)
    }

    private fun projectedBoxArea(bounds: OfflineRegionBounds): Double {
        val west = projectLon(bounds.west)
        val east = projectLon(bounds.east)
        val north = projectLat(bounds.north)
        val south = projectLat(bounds.south)
        return abs((east - west) * (north - south))
    }

    private fun polygonProjectedArea(polygon: List<List<OfflinePolygonPoint>>): Double {
        if (polygon.isEmpty()) return 0.0
        val outer = ringProjectedArea(polygon.first())
        val holes = polygon.drop(1).sumOf { ringProjectedArea(it) }
        return (outer - holes).coerceAtLeast(0.0)
    }

    private fun ringProjectedArea(ring: List<OfflinePolygonPoint>): Double {
        if (ring.size < 3) return 0.0
        var area = 0.0
        var previous = ring.last()
        ring.forEach { current ->
            val x1 = projectLon(previous.lon)
            val y1 = projectLat(previous.lat)
            val x2 = projectLon(current.lon)
            val y2 = projectLat(current.lat)
            area += (x1 * y2) - (x2 * y1)
            previous = current
        }
        return abs(area) / 2.0
    }

    private fun projectLon(lon: Double): Double {
        return (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0
    }

    private fun projectLat(lat: Double): Double {
        val clipped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(clipped)
        return (1.0 - asinh(tan(latRad)) / Math.PI) / 2.0
    }
}

fun OfflineRegionEntry.boundsPreview(): OfflineRegionBounds =
    OfflineRegionBounds(west = west, south = south, east = east, north = north)

fun ManualOfflineBounds.boundsPreview(): OfflineRegionBounds =
    OfflineRegionBounds(west = west, south = south, east = east, north = north)
