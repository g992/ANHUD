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

/**
 * Private 800x480 VirtualDisplay whose frames are read back as RGBA and handed to [sender]
 * (only the changed rectangle, at most [MAX_FPS] per second). Transparent pixels stay
 * transparent on the HUD.
 */
class HudBridgeFrameSink(
    context: Context,
    dpi: Int,
    private val sender: (ByteArray, Gcp.Rect) -> Unit,
    private val onSendFailed: (Throwable) -> Unit
) : AutoCloseable {

    private val thread = HandlerThread("hud-bridge-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(Gcp.WIDTH, Gcp.HEIGHT, PixelFormat.RGBA_8888, 3)
    private val virtualDisplay: VirtualDisplay

    val display: Display
        get() = virtualDisplay.display

    private var latest: ByteArray? = null
    private var previous: ByteArray? = null
    private var lastSendAt = 0L
    private var sendScheduled = false
    private var failed = false

    @Volatile
    private var closed = false

    private val sendLatest = Runnable {
        sendScheduled = false
        sendNow()
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
        if (closed || failed) {
            return
        }
        val rgba = try {
            reader.acquireLatestImage()?.use { copyOut(it) } ?: return
        } catch (_: IllegalStateException) {
            return
        }
        latest = rgba
        if (sendScheduled) {
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
        val rect = Gcp.changedRect(previous, rgba, Gcp.WIDTH, Gcp.HEIGHT) ?: return
        lastSendAt = SystemClock.uptimeMillis()
        try {
            sender(rgba, rect)
            previous = rgba
        } catch (e: Throwable) {
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
            val last = previous
            previous = null
            if (latest == null) {
                latest = last
            }
            sendNow()
        }
    }

    override fun close() {
        closed = true
        handler.removeCallbacks(sendLatest)
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
    }
}
