package com.g992.anhud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class YandexVisualSnapshot(
    val minimap: Bitmap? = null,
    val jams: JamsBar? = null,
    val lanes: Bitmap? = null,
    val laneDistance: String = "",
    val laneQueue: String = "",
    val hasRoute: Boolean = false,
)

/** Images supplied by the installed Yandex Navigator build; old route telemetry is not used. */
object YandexVisualStore {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val decoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "YandexMinimapDecoder").apply { isDaemon = true }
    }
    private var sourceEnabled = true
    private var value = YandexVisualSnapshot()
    private var frameVersion = 0L
    private var pendingFrame: Pair<Long, ByteArray>? = null
    private var decoderRunning = false

    @Synchronized fun snapshot(): YandexVisualSnapshot = value

    fun setSourceEnabled(enabled: Boolean) {
        val changed = synchronized(this) {
            if (enabled == sourceEnabled) false else {
                sourceEnabled = enabled
                ++frameVersion
                pendingFrame = null
                value = YandexVisualSnapshot()
                true
            }
        }
        if (changed) listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun change(update: (YandexVisualSnapshot) -> YandexVisualSnapshot) {
        synchronized(this) { if (!sourceEnabled) return; value = update(value) }
        listeners.forEach { it() }
    }

    fun acceptMinimap(jpeg: ByteArray?, hasRoute: Boolean) {
        val version = synchronized(this) { if (!sourceEnabled) return; ++frameVersion }
        if (jpeg == null || jpeg.isEmpty() || !hasRoute) {
            val accepted = synchronized(this) {
                if (!sourceEnabled || version != frameVersion) false else {
                    pendingFrame = null
                    value = value.copy(minimap = null, jams = if (hasRoute) value.jams else null, hasRoute = hasRoute)
                    true
                }
            }
            if (accepted) listeners.forEach { it() }
            return
        }
        if (jpeg.size > MAX_JPEG_BYTES) return
        val shouldStart = synchronized(this) {
            if (!sourceEnabled || version != frameVersion) return
            pendingFrame = version to jpeg
            if (decoderRunning) false else { decoderRunning = true; true }
        }
        if (shouldStart) decoder.execute { decodePendingFrames() }
    }

    private fun decodePendingFrames() {
        while (true) {
            val (version, jpeg) = synchronized(this) {
                val next = pendingFrame
                pendingFrame = null
                if (next == null) {
                    decoderRunning = false
                    return
                }
                next
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION) continue
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
            val accepted = synchronized(this) {
                if (!sourceEnabled || version != frameVersion) false
                else { value = value.copy(minimap = bitmap, hasRoute = true); true }
            }
            if (accepted) listeners.forEach { it() }
        }
    }

    fun acceptJams(bitmap: Bitmap?) {
        change { it.copy(jams = bitmap?.safeCopy()?.let(JamsBarParser::parse)) }
    }

    fun acceptLanes(bitmap: Bitmap?) {
        change { it.copy(lanes = bitmap?.safeCopy()) }
    }

    fun acceptLaneDistance(text: String) {
        change { it.copy(laneDistance = text) }
    }

    fun acceptLaneQueue(queue: String) {
        change { it.copy(
            laneQueue = queue,
            lanes = if (queue.isBlank()) null else it.lanes,
            laneDistance = if (queue.isBlank()) "" else it.laneDistance
        ) }
    }

    fun clearLanes() {
        change { it.copy(lanes = null, laneDistance = "", laneQueue = "") }
    }

    fun endRoute() {
        synchronized(this) { ++frameVersion; pendingFrame = null }
        change { YandexVisualSnapshot() }
    }

    private fun Bitmap.safeCopy(): Bitmap? = try {
        if (isRecycled || width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) null
        else copy(Bitmap.Config.ARGB_8888, false)
    } catch (_: RuntimeException) { null }

    private const val MAX_JPEG_BYTES = 900_000
    private const val MAX_DIMENSION = 1920
}
