package com.g992.anhud

/** CP-SRV patterns recovered from GeeGeek's t1/i0. No log value can start a route. */
internal data class CarPlayLogDistance(
    val timestamp: Long,
    val maneuver: Int,
    val distance: String,
    val units: Int,
    val destination: String
) {
    fun matches(route: CarPlayRouteData, since: Long, now: Long): Boolean =
        route.hasRoute && maneuver == route.maneuver && timestamp >= since &&
            now - timestamp in 0..MAX_AGE_MS &&
            (destination.isBlank() || route.road.isBlank() ||
                ProviderNavFormat.normalize(destination) == ProviderNavFormat.normalize(route.road))

    companion object {
        const val MAX_AGE_MS = 5000L
        private val epoch = Regex("^\\s*([0-9]+\\.[0-9]+)\\s")
        private val tag = Regex("\\bCP-SRV\\s*:")
        private val distance = Regex("DistanceRemainingToNextManeuver(?:Display)?Str(?:ing)?\\s*=\\s*([0-9.,]+)")
        private val units = Regex("DistanceRemainingToNextManeuver(?:Display)?Units\\s*=\\s*(\\d+)")
        private val maneuver = Regex("(?:mManeuverType|ManeuverType)\\s*=\\s*(\\d+)")
        private val destination = Regex("(?:mDestinationName|DestinationName)\\s*=\\s*([^,}]+)")

        /** Bounded logcat tail, ordered by timestamp; discard everything predating this turn. */
        fun parse(logs: String, since: Long, now: Long): CarPlayLogDistance? {
            val tracker = Tracker()
            logs.lineSequence().forEach { tracker.feed(it, since, now) }
            return tracker.sample()?.takeIf { now - it.timestamp in 0..MAX_AGE_MS }
        }
    }

    /** Incremental parse() state so a streamed line costs O(line), not O(buffer). */
    internal class Tracker {
        private var type: Int? = null
        private var unit: Int? = null
        private var road = ""
        private var value: String? = null
        private var distanceAt = 0L

        fun reset() {
            type = null; unit = null; road = ""; value = null; distanceAt = 0L
        }

        fun feed(line: String, since: Long, now: Long = Long.MAX_VALUE) {
            if (!tag.containsMatchIn(line)) return
            val time = epoch.find(line)?.groupValues?.get(1)?.toBigDecimalOrNull()
                ?.movePointRight(3)?.toLong() ?: return
            if (time < since || time > now) return
            maneuver.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it != type) { value = null; unit = null; road = "" }
                type = it
            }
            destination.find(line)?.groupValues?.get(1)?.trim()?.let {
                if (road.isNotBlank() && road != it) value = null
                road = it
            }
            units.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                unit = it.takeIf { value -> value in 0..4 }
            }
            distance.find(line)?.groupValues?.get(1)?.trimEnd(',')?.let { raw ->
                val number = raw.replace(',', '.').toDoubleOrNull()
                value = raw.takeIf { number != null && number.isFinite() && number in 0.0..10_000_000.0 }
                distanceAt = time
            }
        }

        fun sample(): CarPlayLogDistance? {
            val type = type ?: return null
            val unit = unit ?: return null
            val value = value ?: return null
            return CarPlayLogDistance(distanceAt, type, value, unit, road)
        }
    }
}
