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
    private const val TELNET_MIN_BATCH_LINES = 5
    private const val TELNET_MAX_RETRIES = 8
    private const val TELNET_MAX_DROPS = 5
    private const val TELNET_RECONNECT_TRIES = 5
    private const val TELNET_RECONNECT_DELAY_MS = 3_000L
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
     * QNX has no printf/base64, but ksh's print understands \0nnn. Each batch is verified.
     */
    internal fun copyViaTelnet(qnx: QnxSession, bytes: ByteArray, reconnectDelayMs: Long = TELNET_RECONNECT_DELAY_MS) {
        val probe = "$QNX_PATH.probe"
        val probeBytes = byteArrayOf('A'.code.toByte(), 0, 0xFF.toByte(), '\\'.code.toByte(), '\''.code.toByte())
        qnx.execBatch(listOf(": > $probe") + printLines(probeBytes, probe))
        val probeSize = qnx.exec("wc -c < $probe").output.trim().toIntOrNull()
        // cksum also catches a lost octal digit, which keeps the size; not every QNX has it.
        val probeCksum = qnx.exec("cksum < $probe; rm -f $probe").output.trim().split(Regex("\\s+"))
        val hasCksum = probeCksum.take(2).joinToString(" ") == cksumOf(probeBytes)
        if (probeSize != probeBytes.size) {
            throw IOException("NFS-мост недоступен, а передача через telnet не работает (проба: $probeSize)")
        }

        must(qnx.exec(": > $QNX_PATH"), "создание $QNX_PATH")
        // The pty drops input when a big batch arrives at once, so every batch lands in a
        // part file first and is appended only when its cksum (or size) matches; a bad one is
        // resent in smaller batches.
        val part = "$QNX_PATH.part"
        val pieces = printPieces(bytes, part)
        var batchLines = TELNET_BATCH_LINES
        var retries = 0
        var drops = 0
        var lost = false
        var resync = false
        var i = 0
        var from = 0
        while (i < pieces.size) {
            val batch = pieces.subList(i, minOf(i + batchLines, pieces.size))
            val chunk = bytes.copyOfRange(from, from + batch.sumOf { it.second })
            if (lost) {
                if (++drops > TELNET_MAX_DROPS) {
                    throw IOException("передача $NAME через telnet не удалась: связь с QNX рвалась $TELNET_MAX_DROPS раз подряд")
                }
                reconnect(qnx, reconnectDelayMs)
                lost = false
                resync = true
            }
            val check = if (hasCksum) {
                "set -- \$(cksum < $part); [ \"\$1 \$2\" = \"${cksumOf(chunk)}\" ]"
            } else {
                "[ \$(wc -c < $part) -eq ${chunk.size} ]"
            }
            val sent = try {
                if (resync) {
                    // The batch may have been appended right before the drop; anything else restarts.
                    when (qnx.exec("wc -c < $QNX_PATH").output.trim().toIntOrNull()) {
                        from -> Unit
                        from + chunk.size -> {
                            i += batch.size
                            from += chunk.size
                        }
                        else -> {
                            Log.w(TAG, "partial $QNX_PATH unusable after reconnect, starting over")
                            must(qnx.exec(": > $QNX_PATH"), "создание $QNX_PATH")
                            i = 0
                            from = 0
                        }
                    }
                    resync = false
                    continue
                }
                qnx.execBatch(listOf(": > $part") + batch.map { it.first }, TELNET_BATCH_TIMEOUT_MS)
                qnx.exec("$check && cat $part >> $QNX_PATH").ok
            } catch (e: QnxConnectionLostException) {
                // Nothing is resent until the file on QNX is checked, or a batch could land twice.
                Log.w(TAG, "telnet dropped at line $i, reconnecting", e)
                lost = true
                continue
            } catch (e: IOException) {
                // A lost quote leaves ksh waiting for more input: interrupt it and resend.
                Log.w(TAG, "telnet batch at line $i stuck", e)
                try {
                    qnx.interrupt()
                } catch (dropped: QnxConnectionLostException) {
                    lost = true
                    continue
                }
                false
            }
            if (sent) {
                i += batch.size
                from += chunk.size
                retries = 0
                drops = 0
                continue
            }
            if (++retries > TELNET_MAX_RETRIES) {
                qnx.exec("rm -f $part $QNX_PATH")
                throw IOException("передача $NAME через telnet не удалась: пакет со строки $i не дошёл после $TELNET_MAX_RETRIES повторов")
            }
            batchLines = maxOf(TELNET_MIN_BATCH_LINES, batchLines / 2)
            Log.w(TAG, "telnet batch at line $i came short, resending by $batchLines lines")
        }
        qnx.exec("rm -f $part")
        val size = qnx.exec("wc -c < $QNX_PATH").output.trim().toIntOrNull()
        if (size != bytes.size) {
            qnx.exec("rm -f $QNX_PATH")
            throw IOException("передача $NAME через telnet не удалась: $size из ${bytes.size} байт")
        }
        must(qnx.exec("chmod 755 $QNX_PATH"), "chmod")
    }

    /** Logs in again after a dropped telnet, waiting [delayMs] between failed tries. */
    private fun reconnect(qnx: QnxSession, delayMs: Long) {
        var last: IOException? = null
        repeat(TELNET_RECONNECT_TRIES) { attempt ->
            if (attempt > 0) {
                Thread.sleep(delayMs)
            }
            try {
                qnx.reconnect()
                return
            } catch (e: QnxAuthException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "telnet reconnect ${attempt + 1}/$TELNET_RECONNECT_TRIES failed", e)
                last = e
            }
        }
        throw IOException("связь с QNX оборвалась при передаче $NAME и не восстановилась: ${last?.message}", last)
    }

    /** `print -n '…' >> [target]` lines that recreate [bytes], each at most [TELNET_LINE_MAX] chars. */
    internal fun printLines(bytes: ByteArray, target: String): List<String> {
        return printPieces(bytes, target).map { it.first }
    }

    /** [printLines] with the number of bytes each line writes. */
    private fun printPieces(bytes: ByteArray, target: String): List<Pair<String, Int>> {
        val prefix = "print -n '"
        val suffix = "' >> $target"
        val budget = TELNET_LINE_MAX - prefix.length - suffix.length
        val lines = ArrayList<Pair<String, Int>>()
        val body = StringBuilder()
        var count = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val piece = if (isPlain(c)) c.toChar().toString() else "\\0" + Integer.toOctalString(c).padStart(3, '0')
            if (body.length + piece.length > budget) {
                lines.add(prefix + body + suffix to count)
                body.setLength(0)
                count = 0
            }
            body.append(piece)
            count++
        }
        if (body.isNotEmpty()) {
            lines.add(prefix + body + suffix to count)
        }
        return lines
    }

    /** POSIX `cksum` of [bytes] as "crc size", the start of what QNX prints for a file. */
    internal fun cksumOf(bytes: ByteArray): String {
        var crc = 0
        fun update(b: Int) {
            crc = crc xor (b shl 24)
            repeat(8) {
                crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        for (b in bytes) {
            update(b.toInt() and 0xFF)
        }
        var n = bytes.size.toLong()
        while (n != 0L) {
            update((n and 0xFF).toInt())
            n = n shr 8
        }
        return "${crc.inv().toLong() and 0xFFFFFFFFL} ${bytes.size}"
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
