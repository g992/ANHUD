package com.g992.anhud

import android.content.Context
import org.json.JSONArray

data class OfflineRegionEntry(
    val id: String,
    val countryIso3: String,
    val countryRu: String,
    val regionRu: String,
) {
    val displayLabel: String = "$regionRu · $countryRu"
    val searchKey: String = "$regionRu $countryRu".lowercase()
}

object OfflineRegionCatalog {
    private const val ASSET_PATH = "offline_regions_ru.json"

    @Volatile
    private var cached: List<OfflineRegionEntry>? = null

    fun all(context: Context): List<OfflineRegionEntry> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }
    }

    fun findById(context: Context, id: String?): OfflineRegionEntry? {
        if (id.isNullOrBlank()) return null
        return all(context).firstOrNull { it.id == id }
    }

    fun search(context: Context, query: String): List<OfflineRegionEntry> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return all(context)
        return all(context).filter { it.searchKey.contains(normalized) }
    }

    private fun load(context: Context): List<OfflineRegionEntry> {
        val json = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    OfflineRegionEntry(
                        id = item.getString("id"),
                        countryIso3 = item.getString("countryIso3"),
                        countryRu = item.getString("countryRu"),
                        regionRu = item.getString("regionRu"),
                    )
                )
            }
        }.sortedWith(compareBy({ it.regionRu }, { it.countryRu }))
    }
}
