package com.g992.anhud.hudbridge

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Private 800x480 VirtualDisplay whose frames are read back as RGBA and handed to [sender]
 * (only the changed rectangle, at most [MAX_FPS] per second). Transparent pixels stay
 * transparent on the HUD.
 *
 * While [setOverRects] holds rectangles and [overLayerAvailable] says the daemon has the
 * over-stock layer, each frame is split by [HudLayerSplitter]: those rectangles go to the layer
 * above the stock HUD (on black), the rest to the usual one. [sender] gets the FRAME parts and
 * whether to remove the over layer after them.
 */
class HudBridgeFrameSink(
    context: Context,
    dpi: Int,
    private val sender: (List<Gcp.FramePart>, Boolean) -> Unit,
    private val onSendFailed: (Throwable) -> Unit,
    private val overLayerAvailable: () -> Boolean = { false }
) : AutoCloseable {

    private val thread = HandlerThread("hud-bridge-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(Gcp.WIDTH, Gcp.HEIGHT, PixelFormat.RGBA_8888, 3)
    private val virtualDisplay: VirtualDisplay

    val display: Display
        get() = virtualDisplay.display

    private var latest: ByteArray? = null
    /** The newest frame that went to [sendNow], kept to split again when only the rectangles change. */
    private var lastRaw: ByteArray? = null
    private val splitter = HudLayerSplitter(Gcp.WIDTH, Gcp.HEIGHT)
    private var lastSendAt = 0L
    private var sendScheduled = false
    private var failed = false

    @Volatile
    private var closed = false

    /** Container rectangles of the blocks drawn over the stock HUD; null = feature off. */
    @Volatile
    private var overRects: List<Gcp.Rect>? = null

    /** The daemon's over-stock window holds our content (read after [stopSending]). */
    val overLayerActive: Boolean
        get() = splitter.overActive

    private val sendLatest = Runnable {
        sendScheduled = false
        sendNow()
    }

    /** Rectangles changed with no new frame (static picture): split the last one again. */
    private val resplitLast = Runnable {
        if (latest == null) {
            latest = lastRaw
        }
        requestSend()
    }

    init {
        reader.setOnImageAvailableListener({ onFrame() }, handler)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        try {
            virtualDisplay = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                Gcp.WIDTH,
                Gcp.HEIGHT,
                dpi,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: throw IllegalStateException("createVirtualDisplay returned null")
        } catch (e: RuntimeException) {
            reader.close()
            thread.quitSafely()
            throw e
        }
    }

    private fun onFrame() {
        if (closed) {
            return
        }
        val rgba = try {
            reader.acquireLatestImage()?.use { copyOut(it) } ?: return
        } catch (_: IllegalStateException) {
            return
        }
        // Kept while the link is down too: restart() must push what the overlay shows now, not the
        // last frame that got through (that left e.g. an editor preview on the HUD after a reconnect).
        latest = rgba
        // This frame is split with the current rectangles anyway.
        handler.removeCallbacks(resplitLast)
        requestSend()
    }

    /**
     * Container rectangles (display pixels) of the blocks to draw over the stock HUD, null when
     * none are selected. Called on every overlay draw; a change normally comes with a new frame,
     * otherwise the last frame is split again shortly.
     */
    fun setOverRects(rects: List<Gcp.Rect>?) {
        if (rects == overRects) {
            return
        }
        overRects = rects
        handler.removeCallbacks(resplitLast)
        handler.postDelayed(resplitLast, RESPLIT_DELAY_MS)
    }

    private fun requestSend() {
        if (closed || failed || sendScheduled) {
            return
        }
        val wait = lastSendAt + MIN_SEND_INTERVAL_MS - SystemClock.uptimeMillis()
        if (lastSendAt == 0L || wait <= 0) {
            sendNow()
        } else {
            sendScheduled = true
            handler.postDelayed(sendLatest, wait)
        }
    }

    private fun sendNow() {
        if (closed || failed) {
            return
        }
        val rgba = latest ?: return
        latest = null
        lastRaw = rgba
        val rects = if (overLayerAvailable()) overRects else null
        val batch = splitter.split(rgba, rects) ?: return
        lastSendAt = SystemClock.uptimeMillis()
        try {
            sender(batch.parts, batch.dropOver)
            splitter.commit(batch)
        } catch (e: Throwable) {
            if (latest == null) {
                latest = rgba
            }
            if (!closed && !failed) {
                failed = true
                Log.w(TAG, "frame send failed", e)
                onSendFailed(e)
            }
        }
    }

    /** A fresh daemon window is empty: resume sending and push the whole last frame again. */
    fun restart() {
        handler.post {
            failed = false
            splitter.reset()
            if (latest == null) {
                latest = lastRaw
            }
            sendNow()
        }
    }

    /**
     * Stops sending for good and waits until a send already in progress has finished, so nothing
     * can reach the daemon after the caller's final frame. The display keeps running until [close].
     */
    fun stopSending(timeoutMs: Long = STOP_WAIT_MS) {
        closed = true
        val idle = CountDownLatch(1)
        if (handler.post { idle.countDown() }) {
            idle.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        closed = true
        handler.removeCallbacks(sendLatest)
        handler.removeCallbacks(resplitLast)
        virtualDisplay.release()
        reader.close()
        thread.quitSafely()
    }

    private fun copyOut(image: Image): ByteArray {
        val plane = image.planes[0]
        val src = plane.buffer
        val rowStride = plane.rowStride
        val rowBytes = Gcp.WIDTH * 4
        val out = ByteArray(rowBytes * Gcp.HEIGHT)
        for (y in 0 until Gcp.HEIGHT) {
            src.position(y * rowStride)
            src.get(out, y * rowBytes, rowBytes)
        }
        return out
    }

    companion object {
        private const val TAG = "HudBridgeFrameSink"
        const val VIRTUAL_DISPLAY_NAME = "anhud-hud-bridge"
        const val MAX_FPS = 20
        private const val MIN_SEND_INTERVAL_MS = 1000L / MAX_FPS
        private const val STOP_WAIT_MS = 500L
        private const val RESPLIT_DELAY_MS = 120L
    }
}
