package com.g992.anhud

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.asinh
import kotlin.math.roundToInt
import kotlin.math.tan

data class OfflineRegionBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

data class OfflineSizeEstimate(
    val lowMb: Double,
    val highMb: Double,
) {
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
    private const val ESTIMATE_MAX_ZOOM_CAP = 18

    fun estimate(context: Context, bounds: OfflineRegionBounds, maxZoom: Double): OfflineSizeEstimate {
        val roundedMaxZoom = maxZoom.roundToInt()
            .coerceIn(MAP_ZOOM_MIN.roundToInt(), ESTIMATE_MAX_ZOOM_CAP)
        val totalTiles = estimateTileCount(bounds, roundedMaxZoom.toDouble()).coerceAtLeast(1L)
        val calibratedBytesPerTile = calibratedBytesPerTile(context)
        val lowBytesPerTile = calibratedBytesPerTile?.let { it * 0.75 } ?: DEFAULT_LOW_BYTES_PER_TILE
        val highBytesPerTile = calibratedBytesPerTile?.let { it * 1.25 } ?: DEFAULT_HIGH_BYTES_PER_TILE
        return OfflineSizeEstimate(
            lowMb = STYLE_OVERHEAD_MB + (totalTiles.toDouble() * lowBytesPerTile / (1024.0 * 1024.0)),
            highMb = STYLE_OVERHEAD_MB + (totalTiles.toDouble() * highBytesPerTile / (1024.0 * 1024.0)),
        )
    }

    fun estimateTileCount(bounds: OfflineRegionBounds, maxZoom: Double): Long {
        val roundedMaxZoom = maxZoom.roundToInt()
            .coerceIn(MAP_ZOOM_MIN.roundToInt(), ESTIMATE_MAX_ZOOM_CAP)
        var totalTiles = 0L
        for (zoom in MAP_ZOOM_MIN.roundToInt()..roundedMaxZoom) {
            totalTiles += estimateTileCountAtZoom(bounds, zoom)
        }
        return totalTiles.coerceAtLeast(1L)
    }

    fun calibrate(context: Context, completedBytes: Long, estimatedTileCount: Long) {
        if (completedBytes <= 0L || estimatedTileCount <= 0L) return
        val measured = completedBytes.toDouble() / estimatedTileCount.toDouble()
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
        return prefs.getFloat(KEY_BYTES_PER_TILE, 0f).toDouble().takeIf { it > 0.0 }
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
}

fun OfflineRegionEntry.boundsPreview(): OfflineRegionBounds =
    OfflineRegionBounds(west = west, south = south, east = east, north = north)

fun ManualOfflineBounds.boundsPreview(): OfflineRegionBounds =
    OfflineRegionBounds(west = west, south = south, east = east, north = north)
