package com.g992.anhud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class OfflineRegionEntry(
    val id: String,
    val countryIso3: String,
    val countryRu: String,
    val countryName: String,
    val level: String,
    val name: String,
    val shapeISO: String?,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    val displayLabel: String = "$name · $countryRu"
    val secondaryLabel: String = listOfNotNull(countryRu, level, shapeISO).joinToString(" · ")
    val searchKey: String = listOf(name, countryRu, countryIso3, level, shapeISO.orEmpty())
        .joinToString(" ")
        .lowercase()
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
        val array = when {
            json.trimStart().startsWith("{") -> JSONObject(json).optJSONArray("regions") ?: JSONArray()
            else -> JSONArray(json)
        }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    OfflineRegionEntry(
                        id = item.getString("id"),
                        countryIso3 = item.getString("countryIso3"),
                        countryRu = item.getString("countryRu"),
                        countryName = item.optString("countryName", item.getString("countryIso3")),
                        level = item.optString("level", "ADM1"),
                        name = item.optString("name", item.optString("regionRu", "Unknown")),
                        shapeISO = item.optString("shapeISO").takeIf { it.isNotBlank() },
                        west = item.optJSONArray("bbox")?.optDouble(0) ?: 0.0,
                        south = item.optJSONArray("bbox")?.optDouble(1) ?: 0.0,
                        east = item.optJSONArray("bbox")?.optDouble(2) ?: 0.0,
                        north = item.optJSONArray("bbox")?.optDouble(3) ?: 0.0,
                    )
                )
            }
        }.sortedWith(compareBy({ it.countryRu }, { it.level }, { it.name }))
    }
}
