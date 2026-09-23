package com.g992.anhud.hudbridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Makes sure ghudbridgelited runs on QNX. A running copy may belong to another app,
 * so it is never stopped or overwritten; otherwise the binary from assets is copied
 * through the shared folder to /tmp and started in the background.
 */
object DaemonInstaller {
    private const val TAG = "HudBridgeInstaller"

    const val NAME = "ghudbridgelited"
    const val ASSET_NAME = NAME
    const val QNX_PATH = "/tmp/$NAME"
    const val QNX_LOG = "/tmp/$NAME.log"
    const val PORT = 49210
    const val SHARED_ANDROID = "/data/vendor/nfs/shared"
    const val SHARED_QNX = "/shared"

    /** [reused]: the daemon was already running; [buildId]: its build (e.g. "ghbl-…") when known. */
    data class Result(val reused: Boolean, val buildId: String?)

    @Throws(IOException::class)
    fun ensureRunning(context: Context, qnx: QnxShell, progress: (String) -> Unit): Result {
        progress("Проверяю, запущен ли $NAME…")
        if (isRunning(qnx.exec("pidin ar").output)) {
            progress("$NAME уже запущен — использую его")
            val build = try {
                buildOf(qnx.exec("$QNX_PATH --version 2>&1").output)
            } catch (e: IOException) {
                Log.w(TAG, "version of running daemon unavailable", e)
                null
            }
            return Result(reused = true, buildId = build)
        }

        progress("Копирую $NAME на QNX…")
        val bridgeName = NAME + "-" + java.lang.Long.toHexString(System.nanoTime())
        val shared = File(SHARED_ANDROID, bridgeName)
        context.assets.open(ASSET_NAME).use { src ->
            FileOutputStream(shared).use { dst -> src.copyTo(dst, 64 * 1024) }
        }
        try {
            must(qnx.exec("cp $SHARED_QNX/$bridgeName $QNX_PATH"), "cp")
            must(qnx.exec("chmod 755 $QNX_PATH"), "chmod")
        } finally {
            try {
                qnx.exec("rm -f $SHARED_QNX/$bridgeName")
            } catch (e: IOException) {
                Log.w(TAG, "shared copy cleanup failed", e)
            }
            shared.delete()
        }

        val version = qnx.exec("$QNX_PATH --version 2>&1").output
        if (!version.contains("build=ghbl-")) {
            throw IOException("на QNX оказался не $NAME: $version")
        }
        val build = buildOf(version)
        progress("Запускаю $NAME (${build ?: "?"})…")
        must(qnx.exec("$QNX_PATH --port $PORT > $QNX_LOG 2>&1 &"), "start")
        return Result(reused = false, buildId = build)
    }

    /** Null when Android can write into the shared folder, else the reason in Russian. */
    fun checkSharedDirWritable(): String? {
        val dir = File(SHARED_ANDROID)
        if (!dir.isDirectory) {
            return "нет папки $SHARED_ANDROID"
        }
        val probe = File(dir, ".anhud-probe-" + java.lang.Long.toHexString(System.nanoTime()))
        return try {
            FileOutputStream(probe).use { it.write(0) }
            null
        } catch (e: IOException) {
            "нет доступа на запись в $SHARED_ANDROID: ${e.message}"
        } catch (e: SecurityException) {
            "нет доступа на запись в $SHARED_ANDROID: ${e.message}"
        } finally {
            probe.delete()
        }
    }

    /** Exact executable name match in a `pidin ar` listing ("pid Arguments" per line). */
    internal fun isRunning(pidinAr: String): Boolean {
        for (line in pidinAr.split("\n")) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 2 || !cols[0].matches(Regex("\\d+"))) {
                continue
            }
            val exe = cols[1].substring(cols[1].lastIndexOf('/') + 1)
            if (exe == NAME) {
                return true
            }
        }
        return false
    }

    internal fun buildOf(version: String): String? {
        val i = version.indexOf("build=")
        if (i < 0) {
            return null
        }
        return version.substring(i + 6).split(Regex("\\s"))[0].ifEmpty { null }
    }

    private fun must(r: QnxShell.Result, what: String) {
        if (!r.ok) {
            throw IOException("$what на QNX не удался (код ${r.exitCode}): ${r.output}")
        }
    }
}
