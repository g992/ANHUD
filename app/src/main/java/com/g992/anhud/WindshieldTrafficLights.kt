package com.g992.anhud

/**
 * Мега-ЯН (ru.TLListener) шлёт `plus.monjaro.TRAFFIC_LIGHT_UPDATE` пачкой: по интенту на каждый
 * светофор из Windshield, позиции растут, маркера конца пачки нет. Пустой список приходит одним
 * интентом со всеми полями "".
 */
data class WindshieldTrafficLight(
    val key: String,
    val position: Int,
    val color: String,
    val countdown: String,
    val arrow: String
) {
    companion object {
        const val KEY_PREFIX = "ws:"

        fun keyFor(id: String, position: Int): String {
            return if (id.isNotBlank()) "$KEY_PREFIX$id" else "${KEY_PREFIX}#pos$position"
        }
    }
}

/**
 * Collects one burst. A burst ends when the position stops growing or after [batchGapMs] of
 * silence. [takeBatch] may be committed while the burst is still going: it always holds the whole
 * burst so far, so a late tail extends the committed set instead of replacing it.
 */
class WindshieldTrafficLightBatcher(
    private val batchGapMs: Long = DEFAULT_BATCH_GAP_MS
) {
    private val current = LinkedHashMap<String, WindshieldTrafficLight>()
    private var dirty = false
    private var lastPosition = Int.MIN_VALUE
    private var lastAt = 0L

    /** Returns the previous burst if [light] starts a new one before the previous was committed. */
    fun accept(light: WindshieldTrafficLight, now: Long): List<WindshieldTrafficLight>? {
        val startsNewBatch = current.isNotEmpty() &&
            (light.position <= lastPosition || now - lastAt > batchGapMs)
        val previous = if (startsNewBatch && dirty) current.values.toList() else null
        if (startsNewBatch) {
            current.clear()
        }
        current[light.key] = light
        dirty = true
        lastPosition = light.position
        lastAt = now
        return previous
    }

    /** The current burst if it changed since the last call, else null. */
    fun takeBatch(): List<WindshieldTrafficLight>? {
        if (!dirty) {
            return null
        }
        dirty = false
        return current.values.toList()
    }

    fun reset() {
        current.clear()
        dirty = false
        lastPosition = Int.MIN_VALUE
        lastAt = 0L
    }

    companion object {
        const val DEFAULT_BATCH_GAP_MS = 150L
        const val COMMIT_DELAY_MS = 80L
        const val SAFETY_TTL_MS = 60_000L

        fun isClearSignal(color: String, id: String, countdown: String, arrow: String): Boolean {
            return color.isBlank() && id.isBlank() && countdown.isBlank() && arrow.isBlank()
        }

        /** Replaces the current batch, retaining a countdown when its light keeps the same colour. */
        fun merge(
            current: Map<String, TrafficLightInfo>,
            batch: List<WindshieldTrafficLight>,
            now: Long,
            ttlMs: Long = SAFETY_TTL_MS
        ): LinkedHashMap<String, TrafficLightInfo> {
            val result = LinkedHashMap<String, TrafficLightInfo>()
            batch.forEach { light ->
                val existing = current[light.key]
                val keepCountdown = existing != null &&
                    existing.countdownText.isNotBlank() &&
                    light.countdown.isBlank() &&
                    existing.color.equals(light.color, ignoreCase = true)
                result[light.key] = TrafficLightInfo(
                    id = light.key.hashCode(),
                    color = light.color,
                    countdownText = if (keepCountdown) existing?.countdownText.orEmpty() else light.countdown,
                    arrowDirection = light.arrow,
                    lastUpdated = now,
                    expiresAt = now + ttlMs,
                    position = light.position
                )
            }
            return result
        }
    }
}
