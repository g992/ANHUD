package com.g992.anhud.hudbridge

import android.content.Context
import android.util.Log
import com.g992.anhud.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Makes sure ghudbridgelited runs on QNX. A running copy may belong to another app,
 * so it is never stopped or overwritten; otherwise the binary from assets is copied to /tmp
 * (through the NFS shared folder, or typed in over telnet where there is none) and started.
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
    private const val TELNET_LINE_MAX = 200
    private const val TELNET_BATCH_LINES = 40
    private const val TELNET_BATCH_TIMEOUT_MS = 60_000L

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

        if (!hasBundledBuild(qnx)) {
            progress("Копирую $NAME на QNX…")
            val bundled = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val sharedProblem = checkSharedDirWritable()
            if (sharedProblem == null) {
                copyViaShared(qnx, bundled)
            } else {
                Log.w(TAG, "shared folder unusable ($sharedProblem), copying over telnet")
                progress("NFS-мост недоступен, передаю $NAME через telnet…")
                copyViaTelnet(qnx, bundled)
            }
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

    /** True when /tmp already holds the daemon this APK carries, so there is nothing to copy. */
    private fun hasBundledBuild(qnx: QnxShell): Boolean {
        val bundledId = BuildConfig.HUD_BRIDGE_BUILD_ID
        if (bundledId.isBlank()) {
            return false
        }
        return buildOf(qnx.exec("$QNX_PATH --version 2>&1").output) == bundledId
    }

    private fun copyViaShared(qnx: QnxShell, bytes: ByteArray) {
        val bridgeName = NAME + "-" + java.lang.Long.toHexString(System.nanoTime())
        val shared = File(SHARED_ANDROID, bridgeName)
        FileOutputStream(shared).use { it.write(bytes) }
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
    }

    /**
     * For head units without /data/vendor/nfs/shared: the file is typed into the shell as
     * `print -n '…' >> file` lines (octal escapes for everything but plain characters).
     * QNX has no printf/base64, but ksh's print understands \0nnn. Size is checked at the end.
     */
    private fun copyViaTelnet(qnx: QnxShell, bytes: ByteArray) {
        val probe = "$QNX_PATH.probe"
        val probeBytes = byteArrayOf('A'.code.toByte(), 0, 0xFF.toByte(), '\\'.code.toByte(), '\''.code.toByte())
        qnx.execBatch(listOf(": > $probe") + printLines(probeBytes, probe))
        val probeSize = qnx.exec("wc -c < $probe; rm -f $probe").output.trim().toIntOrNull()
        if (probeSize != probeBytes.size) {
            throw IOException("NFS-мост недоступен, а передача через telnet не работает (проба: $probeSize)")
        }

        must(qnx.exec(": > $QNX_PATH"), "создание $QNX_PATH")
        printLines(bytes, QNX_PATH).chunked(TELNET_BATCH_LINES).forEach { batch ->
            qnx.execBatch(batch, TELNET_BATCH_TIMEOUT_MS)
        }
        val size = qnx.exec("wc -c < $QNX_PATH").output.trim().toIntOrNull()
        if (size != bytes.size) {
            qnx.exec("rm -f $QNX_PATH")
            throw IOException("передача $NAME через telnet не удалась: $size из ${bytes.size} байт")
        }
        must(qnx.exec("chmod 755 $QNX_PATH"), "chmod")
    }

    /** `print -n '…' >> [target]` lines that recreate [bytes], each at most [TELNET_LINE_MAX] chars. */
    internal fun printLines(bytes: ByteArray, target: String): List<String> {
        val prefix = "print -n '"
        val suffix = "' >> $target"
        val budget = TELNET_LINE_MAX - prefix.length - suffix.length
        val lines = ArrayList<String>()
        val body = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val piece = if (isPlain(c)) c.toChar().toString() else "\\0" + Integer.toOctalString(c).padStart(3, '0')
            if (body.length + piece.length > budget) {
                lines.add(prefix + body + suffix)
                body.setLength(0)
            }
            body.append(piece)
        }
        if (body.isNotEmpty()) {
            lines.add(prefix + body + suffix)
        }
        return lines
    }

    private fun isPlain(c: Int): Boolean {
        return c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '.'.code || c == '_'.code || c == '-'.code || c == '/'.code || c == ':'.code
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
