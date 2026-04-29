package com.g992.anhud

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

const val OFFLINE_STATUS_IDLE = 0
const val OFFLINE_STATUS_RESOLVING = 1
const val OFFLINE_STATUS_DOWNLOADING = 2

data class MapCacheSnapshot(
    val configuredBytes: Long = 1024L * 1024L * 1024L,
    val usedBytes: Long = 0L,
    val downloadRouteEnabled: Boolean = false,
    val routeDownloadActive: Boolean = false,
    val routeDownloadStartedChunks: Int = 0,
    val routeDownloadCompletedChunks: Int = 0,
    val routeDownloadTotalChunks: Int = 0,
    val routeDownloadPercent: Int = 0,
    val lastError: String? = null,
    val offlineSelectionLabel: String? = null,
    val offlineDownloadedLabel: String? = null,
    val offlineDownloadedRegionId: String? = null,
    val offlineDownloadedRegionIds: Set<String> = emptySet(),
    val offlineDownloadedRegionSizesBytes: Map<String, Long> = emptyMap(),
    val offlineDownloadStatus: Int = OFFLINE_STATUS_IDLE,
    val offlineDownloadPercent: Int = 0,
    val offlineDownloadCompletedResources: Long = 0L,
    val offlineDownloadRequiredResources: Long = 0L,
    val offlineDownloadCompletedBytes: Long = 0L,
    val offlineRegionReady: Boolean = false,
    val offlineLastError: String? = null,
)

object MapCacheController {
    private val listeners = CopyOnWriteArraySet<(MapCacheSnapshot) -> Unit>()

    @Volatile
    private var snapshot = MapCacheSnapshot()

    fun initialize(context: Context) {
        MapRenderSettingsStore.initialize(context.applicationContext)
        syncFromSettings(MapRenderSettingsStore.current())
        MapRenderSettingsStore.addListener(::syncFromSettings)
    }

    fun current(): MapCacheSnapshot = snapshot

    fun addListener(listener: (MapCacheSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeListener(listener: (MapCacheSnapshot) -> Unit) {
        listeners -= listener
    }

    fun clearRouteDownloadCache() = Unit

    fun clearCache() = Unit

    fun startOfflineRegionDownload(entry: OfflineRegionEntry) {
        publish(
            snapshot.copy(
                offlineDownloadedLabel = entry.displayLabel,
                offlineDownloadedRegionId = entry.id,
                offlineLastError = "Оффлайн-загрузка старого тайлового кэша отключена после перехода на MapKit"
            )
        )
    }

    fun deleteOfflineRegion(regionId: String) {
        if (snapshot.offlineDownloadedRegionId == regionId) {
            publish(
                snapshot.copy(
                    offlineDownloadedLabel = null,
                    offlineDownloadedRegionId = null,
                    offlineLastError = null
                )
            )
        }
    }

    private fun syncFromSettings(settings: MapRenderSettings) {
        publish(
            snapshot.copy(
                configuredBytes = settings.cacheSizeBytes(),
                downloadRouteEnabled = false,
                routeDownloadActive = false,
                routeDownloadStartedChunks = 0,
                routeDownloadCompletedChunks = 0,
                routeDownloadTotalChunks = 0,
                routeDownloadPercent = 0,
                offlineSelectionLabel = settings.offlineRegionId
            )
        )
    }

    private fun publish(updated: MapCacheSnapshot) {
        snapshot = updated
        listeners.forEach { it(updated) }
    }
}
