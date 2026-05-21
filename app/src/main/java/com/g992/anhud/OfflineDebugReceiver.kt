package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.maplibre.geojson.Feature
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val OFFLINE_DEBUG_TAG = "OfflineDebugReceiver"
private const val OFFLINE_DEBUG_DUMP_FILE = "offline_debug_last.txt"

class OfflineDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_OFFLINE) return
        val pendingResult = goAsync()
        worker.execute {
            val output = runCatching {
                handleCommand(context.applicationContext, intent)
            }.getOrElse { error ->
                "offline_debug_error: ${error.message ?: error.javaClass.simpleName}"
            }
            Log.i(OFFLINE_DEBUG_TAG, output)
            writeLastDump(context.applicationContext, output)
            pendingResult.finish()
        }
    }

    private fun handleCommand(context: Context, intent: Intent): String {
        return when (intent.getStringExtra(EXTRA_COMMAND)?.trim()?.lowercase().orEmpty()) {
            "", "help" -> helpText()
            "search" -> handleSearch(context, intent)
            "estimate" -> handleEstimate(context, intent)
            "download" -> handleDownload(context, intent)
            "delete" -> handleDelete(context, intent)
            "dump" -> awaitAsyncResult { callback -> MapCacheController.debugDumpState(callback) }
            "refresh" -> {
                MapCacheController.debugRefreshOfflineRegions()
                "offline_debug_refresh_requested"
            }
            "cleanup" -> awaitAsyncResult { callback -> MapCacheController.debugPruneDuplicateRegions(callback) }
            "reset_estimator" -> {
                OfflineRegionSizeEstimator.clearCalibration(context)
                "offline_debug_estimator_reset"
            }
            else -> helpText()
        }
    }

    private fun handleSearch(context: Context, intent: Intent): String {
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        require(query.isNotEmpty()) { "search requires --es query <text>" }
        val limit = intent.getIntExtra(EXTRA_LIMIT, 10).coerceIn(1, 20)
        val matches = OfflineRegionCatalog.searchAllSources(context, query).take(limit)
        return buildString {
            appendLine("offline_debug_search query=\"$query\" count=${matches.size}")
            matches.forEach { entry ->
                appendLine("${entry.id} | ${entry.displayLabel} | ${entry.level} | ${entry.source}")
            }
        }.trim()
    }

    private fun handleEstimate(context: Context, intent: Intent): String {
        val entry = resolveSingleEntry(context, intent)
        val resolved = GeoBoundariesRegionResolver.resolveCatalogEntry(context, entry)
        val geometry = Feature.fromJson(resolved.geometryJson).geometry()
            ?: error("No geometry for ${entry.displayLabel}")
        val maxZoom = currentOfflineDebugMaxZoom()
        val exactTileCount = OfflineRegionSizeEstimator.estimateTileCount(geometry, resolved.bounds, maxZoom)
        val bboxTileCount = OfflineRegionSizeEstimator.estimateTileCount(resolved.bounds, maxZoom)
        val estimate = OfflineRegionSizeEstimator.estimate(context, exactTileCount)
        return buildString {
            appendLine("offline_debug_estimate regionId=${entry.id}")
            appendLine("label=${entry.displayLabel}")
            appendLine("source=${resolved.sourceHint}")
            appendLine("maxZoom=$maxZoom")
            appendLine("bbox=${resolved.bounds.west},${resolved.bounds.south},${resolved.bounds.east},${resolved.bounds.north}")
            appendLine("bboxTiles=$bboxTileCount")
            appendLine("exactTiles=$exactTileCount")
            appendLine("estimateBytes=${estimate.lowBytes()}-${estimate.highBytes()}")
            append("estimateDisplay=${estimate.displayText(context)}")
        }
    }

    private fun handleDownload(context: Context, intent: Intent): String {
        val entry = resolveSingleEntry(context, intent)
        MapCacheController.startOfflineRegionDownload(entry)
        return "offline_debug_download_started regionId=${entry.id} label=${entry.displayLabel}"
    }

    private fun handleDelete(context: Context, intent: Intent): String {
        val entry = resolveSingleEntry(context, intent)
        MapCacheController.deleteOfflineRegion(entry.id)
        return "offline_debug_delete_requested regionId=${entry.id} label=${entry.displayLabel}"
    }

    private fun resolveSingleEntry(context: Context, intent: Intent): OfflineRegionEntry {
        val regionId = intent.getStringExtra(EXTRA_REGION_ID)?.trim()
        if (!regionId.isNullOrEmpty()) {
            return OfflineRegionCatalog.findById(context, regionId)
                ?: error("Region not found: $regionId")
        }
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        require(query.isNotEmpty()) { "command requires --es region_id <id> or --es query <text>" }
        val matches = OfflineRegionCatalog.searchAllSources(context, query).take(5)
        require(matches.isNotEmpty()) { "No matches for \"$query\"" }
        require(matches.size == 1) {
            buildString {
                appendLine("Ambiguous query \"$query\". Use region_id.")
                matches.forEach { appendLine("${it.id} | ${it.displayLabel}") }
            }.trim()
        }
        return matches.first()
    }

    private fun currentOfflineDebugMaxZoom(): Double {
        val settings = MapRenderSettingsStore.current()
        return if (settings.autoZoomEnabled) {
            maxOf(settings.autoZoomAt0Kmh, settings.autoZoomAt60Kmh, settings.autoZoomAt90Kmh)
        } else {
            settings.zoom
        }.coerceIn(MAP_ZOOM_MIN, OFFLINE_REGION_MAX_ZOOM)
    }

    private fun awaitAsyncResult(request: ((String) -> Unit) -> Unit): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("offline_debug_timeout")
        request { text ->
            result.set(text)
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result.get()
    }

    private fun writeLastDump(context: Context, output: String) {
        runCatching {
            File(context.filesDir, OFFLINE_DEBUG_DUMP_FILE).writeText(output, Charsets.UTF_8)
        }
    }

    private fun helpText(): String {
        return """
            offline_debug_help
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd search --es query leningrad
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd estimate --es region_id <id>
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd download --es region_id <id>
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd delete --es region_id <id>
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd dump
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd cleanup
            adb shell am broadcast -a $ACTION_DEBUG_OFFLINE --es cmd reset_estimator
            adb shell run-as com.g992.anhud cat files/$OFFLINE_DEBUG_DUMP_FILE
        """.trimIndent()
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()

        const val ACTION_DEBUG_OFFLINE = "com.g992.anhud.DEBUG_OFFLINE"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_QUERY = "query"
        const val EXTRA_REGION_ID = "region_id"
        const val EXTRA_LIMIT = "limit"
    }
}
