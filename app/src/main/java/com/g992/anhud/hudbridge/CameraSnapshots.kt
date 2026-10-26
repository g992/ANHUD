package com.g992.anhud.hudbridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Frames for the view editor. First choice: a live frame — the daemon writes one (CAMSNAP) to
 * /tmp/ghbl_snap_<input>.yuv, fetched over the QNX shell as base64 (~3 s) and deleted there; never
 * cached, so every editor open shows the camera as it is now. Fallback, when the bridge is down, the
 * daemon predates CAMSNAP or the snapshot fails: /calibration/cap_{rear,left,right}.yuv, the stills
 * taken at the AVM calibration, cached in the app's cache dir per file (its `ls -ln` line).
 * Both are NV12 1280x800.
 */
object CameraSnapshots {
    private const val TAG = "CameraSnapshots"
    private const val DIR = "camera_caps"
    private const val FETCH_TIMEOUT_MS = 30_000L
    private const val LS_TIMEOUT_MS = 3_000L
    /** The daemon gives up after 2 s; the rest is the round trip. */
    private const val LIVE_TIMEOUT_MS = 3_000L

    /** [live]: a current frame; otherwise the factory still, [liveProblem] says why (Russian). */
    class Snapshot(val nv12: ByteArray, val live: Boolean, val liveProblem: String?)

    fun capName(role: Int): String = when (role) {
        Gcp.CAM_ROLE_LEFT -> "left"
        Gcp.CAM_ROLE_RIGHT -> "right"
        Gcp.CAM_ROLE_FRONT -> "front"
        else -> "rear"
    }

    fun capPath(role: Int): String = "/calibration/cap_${capName(role)}.yuv"

    /** [role]'s picture, live when possible; IOException with a Russian message when there is none. */
    @Throws(IOException::class)
    fun load(context: Context, role: Int, progress: (String) -> Unit): Snapshot {
        progress("Подключаюсь к приборке…")
        HudBridgeManager.openQnxShell().use { shell ->
            val problem = try {
                return Snapshot(loadLive(shell, role, progress), live = true, liveProblem = null)
            } catch (e: IOException) {
                Log.w(TAG, "live snapshot unavailable: ${e.message}")
                e.message ?: e.javaClass.simpleName
            }
            val factory = try {
                loadFactory(context, shell, role, progress)
            } catch (e: IOException) {
                throw IOException("$problem; ${e.message}", e)
            }
            return Snapshot(factory, live = false, liveProblem = problem)
        }
    }

    @Throws(IOException::class)
    private fun loadLive(shell: QnxShell, role: Int, progress: (String) -> Unit): ByteArray {
        if (!HudBridgeManager.isLinkUp()) {
            throw IOException("фоновая отрисовка не запущена")
        }
        if (!HudBridgeManager.supportsCameraSnapshot()) {
            throw IOException("работает старый демон HUD, новый запустится после перезагрузки приборки")
        }
        progress("Снимаю кадр с камеры…")
        val input = RearCameraLogic.inputOf(role)
        val done = HudBridgeManager.requestCameraSnapshot(input, LIVE_TIMEOUT_MS)
            ?: throw IOException("нет связи с демоном HUD")
        if (!done.ok) {
            throw IOException(describeStatus(done.status))
        }
        if (done.bytes != CameraViewRenderer.NV12_BYTES) {
            throw IOException("кадр камеры необычного размера (${done.bytes} байт)")
        }
        val path = Gcp.camSnapPath(input)
        progress("Загружаю кадр с приборки…")
        try {
            val text = shell.exec("openssl base64 < $path", FETCH_TIMEOUT_MS).output
            return CameraCalibrationCheck.decodeBase64(text, CameraViewRenderer.NV12_BYTES)
                ?: throw IOException("кадр камеры не прочитался")
        } finally {
            // 1.5 MB of QNX RAM; the next request writes a fresh one anyway.
            try {
                shell.exec("rm -f $path", LS_TIMEOUT_MS)
            } catch (e: IOException) {
                Log.d(TAG, "snapshot cleanup failed: ${e.message}")
            }
        }
    }

    @Throws(IOException::class)
    private fun loadFactory(context: Context, shell: QnxShell, role: Int, progress: (String) -> Unit): ByteArray {
        val name = capName(role)
        val path = capPath(role)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val line = shell.exec("ls -ln $path 2>/dev/null", LS_TIMEOUT_MS).output.lines()
            .map { it.trim() }
            .firstOrNull { it.endsWith(" $path") }
            ?: throw IOException("на приборке нет снимка этой камеры")
        val size = line.split(Regex("\\s+")).getOrNull(4)?.toLongOrNull()
        if (size != CameraViewRenderer.NV12_BYTES.toLong()) {
            throw IOException("снимок камеры необычного размера ($size байт)")
        }
        val cached = File(dir, "cap_${name}_${Integer.toHexString(line.hashCode())}.yuv")
        if (cached.length() == size) {
            return cached.readBytes()
        }
        progress("Загружаю заводской снимок камеры с приборки…")
        val text = shell.exec("openssl base64 < $path", FETCH_TIMEOUT_MS).output
        val bytes = CameraCalibrationCheck.decodeBase64(text, CameraViewRenderer.NV12_BYTES)
            ?: throw IOException("снимок камеры не прочитался")
        dir.listFiles { f -> f.name.startsWith("cap_${name}_") }?.forEach { it.delete() }
        try {
            cached.writeBytes(bytes)
        } catch (_: IOException) {
            cached.delete()
        }
        return bytes
    }

    private fun describeStatus(status: Int): String = when (status) {
        Gcp.ERR_BUSY -> "камера занята"
        Gcp.ERR_TIMEOUT -> "камера не отдала кадр"
        Gcp.ERR_OOM -> "демону HUD не хватило памяти"
        Gcp.ERR_IO -> "демон HUD не смог записать кадр"
        else -> "демон HUD отказал (код $status)"
    }
}
