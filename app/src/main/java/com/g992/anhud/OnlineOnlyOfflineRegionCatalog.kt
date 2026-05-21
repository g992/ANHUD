package com.g992.anhud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.zip.GZIPInputStream

private const val ONLINE_ONLY_OFFLINE_REGIONS_ASSET_PATH = "offline_regions_online_search.json.gz"
private const val ONLINE_ONLY_SEARCH_LIMIT = 120
private val SEARCH_LOCALE_RU = Locale("ru")
private const val ONLINE_ONLY_OFFLINE_REGIONS_TAG = "OfflineRegionCatalog"

object OnlineOnlyOfflineRegionCatalog {
    @Volatile
    private var cached: List<OfflineRegionEntry>? = null

    fun search(context: Context, query: String): List<OfflineRegionEntry> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return all(context)
            .asSequence()
            .filter { it.searchKey.contains(normalized) }
            .sortedWith(
                compareByDescending<OfflineRegionEntry> { it.name.lowercase().startsWith(normalized) }
                    .thenBy { it.name.length }
                    .thenBy { it.countryRu }
                    .thenBy { it.level }
                    .thenBy { it.name }
            )
            .take(ONLINE_ONLY_SEARCH_LIMIT)
            .toList()
    }

    private fun all(context: Context): List<OfflineRegionEntry> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching { load(context.applicationContext) }
                .onFailure { error ->
                    Log.w(
                        ONLINE_ONLY_OFFLINE_REGIONS_TAG,
                        "Online-only offline-region catalog is unavailable, falling back to bundled-only search",
                        error
                    )
                }
                .getOrDefault(emptyList())
                .also { cached = it }
        }
    }

    private fun load(context: Context): List<OfflineRegionEntry> {
        val json = GZIPInputStream(context.assets.open(ONLINE_ONLY_OFFLINE_REGIONS_ASSET_PATH))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val array = when {
            json.trimStart().startsWith("{") -> JSONObject(json).optJSONArray("regions") ?: JSONArray()
            else -> JSONArray(json)
        }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val regionId = item.optString("id").trim()
                if (regionId.isEmpty()) continue
                val rawCountryCode = item.optString("countryCode").trim()
                val countryIso3 = resolveCountryIso3(rawCountryCode) ?: continue
                val countryLabel = resolveCountryLabel(rawCountryCode, countryIso3)
                add(
                    OfflineRegionEntry(
                        id = regionId,
                        countryIso3 = countryIso3,
                        countryRu = countryLabel,
                        countryName = countryLabel,
                        level = item.optString("level", "ADM1"),
                        name = item.optString("name", "Unknown"),
                        shapeISO = item.optString("shapeISO").takeIf { it.isNotBlank() },
                        west = item.optJSONArray("bbox")?.optDouble(0) ?: 0.0,
                        south = item.optJSONArray("bbox")?.optDouble(1) ?: 0.0,
                        east = item.optJSONArray("bbox")?.optDouble(2) ?: 0.0,
                        north = item.optJSONArray("bbox")?.optDouble(3) ?: 0.0,
                        source = OfflineRegionSource.ONLINE_ONLY,
                        bundledGeometryAvailable = false,
                    )
                )
            }
        }
    }

    private fun resolveCountryIso3(rawCountryCode: String): String? {
        val baseCode = rawCountryCode.substringBefore('-').trim().uppercase()
        return when (baseCode.length) {
            2 -> runCatching { Locale("", baseCode).isO3Country.uppercase() }.getOrNull()
            3 -> baseCode
            else -> null
        }
    }

    private fun resolveCountryLabel(rawCountryCode: String, countryIso3: String): String {
        val baseCode = rawCountryCode.substringBefore('-').trim().uppercase()
        val iso2Code = when (baseCode.length) {
            2 -> baseCode
            3 -> ISO3_TO_ISO2[baseCode]
            else -> null
        }
        val label = iso2Code?.let { Locale("", it).getDisplayCountry(SEARCH_LOCALE_RU) }.orEmpty()
        return label.ifBlank { countryIso3 }
    }
}

private val ISO3_TO_ISO2: Map<String, String> by lazy {
    Locale.getISOCountries().mapNotNull { iso2Code ->
        val iso3Code = runCatching { Locale("", iso2Code).isO3Country.uppercase() }.getOrNull()
        iso3Code?.let { it to iso2Code }
    }.toMap()
}
