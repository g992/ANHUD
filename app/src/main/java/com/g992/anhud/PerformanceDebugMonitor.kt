package com.g992.anhud

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

object PerformanceDebugMonitor {
    private const val SAMPLE_INTERVAL_MS = 5_000L
    private const val HISTORY_MINUTES = 5
    private const val MAX_SAMPLES = HISTORY_MINUTES * 60 / (SAMPLE_INTERVAL_MS / 1000L).toInt()
    private const val MB = 1024f * 1024f
    private const val ADB_COMMAND_TIMEOUT_MS = 1_500L
    private const val ADB_HOST = "localhost"
    private const val ADB_PORT = 5555

    data class Sample(
        val timestampMs: Long,
        val systemCpuPercent: Float,
        val appCpuPercent: Float,
        val ramUsedPercent: Float,
        val ramUsedMb: Float,
        val ramTotalMb: Float,
        val appPssMb: Float,
        val appJavaHeapMb: Float,
        val appNativeHeapMb: Float
    )

    data class TopAppCpu(
        val processName: String,
        val pid: Int,
        val cpuPercent: Float
    )

    data class Snapshot(
        val samples: List<Sample>,
        val topApps: List<TopAppCpu>,
        val topAppsNote: String?,
        val systemCpuNote: String?
    )

    interface Listener {
        fun onSnapshotUpdated(snapshot: Snapshot)
    }

    private val lock = Any()
    private val listeners = mutableSetOf<Listener>()
    private val samples = ArrayDeque<Sample>()
    private var topApps = emptyList<TopAppCpu>()
    private var topAppsNote: String? = null
    private var systemCpuNote: String? = null

    private var started = false
    private var appContext: Context? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var lastTotalCpuJiffies: Long? = null
    private var lastIdleCpuJiffies: Long? = null
    private var lastProcessCpuMs: Long? = null
    private var lastSampleElapsedMs: Long? = null
    private var previousProcessTicks = HashMap<Int, Long>()
    private val adbShellClient = LocalAdbShellClient()

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleOnce()
            workerHandler?.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            appContext = context.applicationContext
            workerThread = HandlerThread("PerformanceDebugMonitor").also { it.start() }
            workerHandler = Handler(workerThread!!.looper)
            workerHandler?.post(sampleRunnable)
        }
    }

    fun registerListener(listener: Listener) {
        synchronized(lock) {
            listeners.add(listener)
        }
        listener.onSnapshotUpdated(currentSnapshot())
    }

    fun unregisterListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun currentSnapshot(): Snapshot {
        synchronized(lock) {
            return Snapshot(
                samples = samples.toList(),
                topApps = topApps.toList(),
                topAppsNote = topAppsNote,
                systemCpuNote = systemCpuNote
            )
        }
    }

    private fun sampleOnce() {
        val context = appContext ?: return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val elapsedMs = SystemClock.elapsedRealtime()

        val (cpuStat, cpuStatReadNote) = readSystemCpuStat()
        val totalCpuJiffies = cpuStat?.totalJiffies
        val idleCpuJiffies = cpuStat?.idleJiffiesWithIoWait
        val (systemCpuPercent, systemCpuSampleNote) = if (
            cpuStat != null &&
            lastTotalCpuJiffies != null &&
            lastIdleCpuJiffies != null
        ) {
            val totalDelta = cpuStat.totalJiffies - (lastTotalCpuJiffies ?: cpuStat.totalJiffies)
            val idleDelta = cpuStat.idleJiffiesWithIoWait - (lastIdleCpuJiffies ?: cpuStat.idleJiffiesWithIoWait)
            if (totalDelta > 0L) {
                ((totalDelta - idleDelta).toFloat() * 100f / totalDelta.toFloat()).coerceIn(0f, 100f) to null
            } else {
                0f to "CPU диагностика: нулевая дельта счетчиков, ждём следующий интервал"
            }
        } else if (cpuStat == null) {
            0f to (cpuStatReadNote ?: "CPU диагностика: /proc/stat недоступен")
        } else {
            0f to "CPU диагностика: ожидание второй выборки для расчета"
        }

        val processCpuMs = Process.getElapsedCpuTime()
        val appCpuPercent = if (lastProcessCpuMs != null && lastSampleElapsedMs != null) {
            val cpuDeltaMs = processCpuMs - (lastProcessCpuMs ?: processCpuMs)
            val wallDeltaMs = elapsedMs - (lastSampleElapsedMs ?: elapsedMs)
            val cores = max(1, Runtime.getRuntime().availableProcessors())
            if (cpuDeltaMs > 0L && wallDeltaMs > 0L) {
                (cpuDeltaMs.toFloat() * 100f / (wallDeltaMs.toFloat() * cores.toFloat())).coerceIn(0f, 100f)
            } else {
                0f
            }
        } else {
            0f
        }

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val usedRamBytes = (memInfo.totalMem - memInfo.availMem).coerceAtLeast(0L)
        val ramUsedPercent = if (memInfo.totalMem > 0L) {
            (usedRamBytes.toFloat() * 100f / memInfo.totalMem.toFloat()).coerceIn(0f, 100f)
        } else {
            0f
        }

        val processMemoryInfo = runCatching {
            activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        }.getOrNull()
        val appPssMb = ((processMemoryInfo?.totalPss ?: 0) / 1024f).coerceAtLeast(0f)
        val runtime = Runtime.getRuntime()
        val appJavaHeapMb = ((runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L) / MB)
        val appNativeHeapMb = (Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L) / MB)

        val sample = Sample(
            timestampMs = elapsedMs,
            systemCpuPercent = systemCpuPercent,
            appCpuPercent = appCpuPercent,
            ramUsedPercent = ramUsedPercent,
            ramUsedMb = usedRamBytes / MB,
            ramTotalMb = memInfo.totalMem / MB,
            appPssMb = appPssMb,
            appJavaHeapMb = appJavaHeapMb,
            appNativeHeapMb = appNativeHeapMb
        )

        val (topAppsSample, topAppsSampleNote) = if (
            cpuStat != null &&
            totalCpuJiffies != null &&
            lastTotalCpuJiffies != null
        ) {
            val totalDelta = totalCpuJiffies - (lastTotalCpuJiffies ?: totalCpuJiffies)
            if (totalDelta > 0L) {
                collectTopCpuApps(activityManager, totalDelta)
            } else {
                emptyList<TopAppCpu>() to "Недостаточно данных для расчета CPU по приложениям"
            }
        } else {
            emptyList<TopAppCpu>() to "Ожидание первой пары замеров CPU"
        }

        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) {
                samples.removeFirst()
            }
            topApps = topAppsSample
            topAppsNote = topAppsSampleNote
            systemCpuNote = systemCpuSampleNote
        }
        notifyListeners()

        if (totalCpuJiffies != null && idleCpuJiffies != null) {
            lastTotalCpuJiffies = totalCpuJiffies
            lastIdleCpuJiffies = idleCpuJiffies
        }
        lastProcessCpuMs = processCpuMs
        lastSampleElapsedMs = elapsedMs
    }

    private data class SystemCpuStat(
        val totalJiffies: Long,
        val idleJiffiesWithIoWait: Long
    )

    private data class ProcessCpuStat(
        val processName: String,
        val ticks: Long
    )

    private fun readSystemCpuStat(): Pair<SystemCpuStat?, String?> {
        val localLine = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrElse { error ->
            val errorName = error::class.java.simpleName.ifBlank { "error" }
            return fallbackSystemCpuStatViaAdb("CPU диагностика: /proc/stat недоступен ($errorName)")
        } ?: return fallbackSystemCpuStatViaAdb("CPU диагностика: /proc/stat пуст")

        return parseSystemCpuStat(localLine)
    }

    private fun parseSystemCpuStat(line: String): Pair<SystemCpuStat?, String?> {
        if (!line.startsWith("cpu ")) {
            return null to "CPU диагностика: /proc/stat не содержит строку cpu"
        }
        val parts = line.trim().split(WHITESPACE_REGEX)
        if (parts.size < 6) {
            return null to "CPU диагностика: недостаточно полей в /proc/stat (${parts.size})"
        }
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) {
            return null to "CPU диагностика: не удалось распарсить счетчики CPU"
        }
        val total = values.sum()
        val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
        return SystemCpuStat(totalJiffies = total, idleJiffiesWithIoWait = idle) to null
    }

    private fun fallbackSystemCpuStatViaAdb(localReadNote: String): Pair<SystemCpuStat?, String?> {
        val output = adbShellClient.execute("cat /proc/stat", ADB_COMMAND_TIMEOUT_MS)
        if (output.error != null) {
            return null to "$localReadNote; adb socket fallback недоступен (${output.error})"
        }

        val line = output.stdout
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("cpu ") }
            ?: return null to "$localReadNote; adb socket fallback вернул пустой /proc/stat"

        val (stat, parseNote) = parseSystemCpuStat(line)
        return if (stat != null) {
            stat to "CPU диагностика: /proc/stat недоступен, используем adb socket fallback"
        } else {
            null to (parseNote ?: "$localReadNote; adb socket fallback: ошибка парсинга")
        }
    }

    private fun collectTopCpuApps(
        activityManager: ActivityManager,
        totalCpuDeltaJiffies: Long
    ): Pair<List<TopAppCpu>, String?> {
        val runningNames = activityManager.runningAppProcesses
            ?.associate { it.pid to it.processName }
            .orEmpty()

        val currentTicks = HashMap<Int, Long>()
        val currentNames = HashMap<Int, String>()
        var readableCount = 0
        var deniedCount = 0

        val procDirs = runCatching {
            File("/proc").listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }.orEmpty()
        }.getOrNull().orEmpty()

        for (dir in procDirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val stat = readProcessCpuStat(pid)
            if (stat == null) {
                deniedCount += 1
                continue
            }
            readableCount += 1
            currentTicks[pid] = stat.ticks
            currentNames[pid] = sanitizeProcessName(runningNames[pid] ?: stat.processName, pid)
        }

        if (readableCount == 0) {
            previousProcessTicks = currentTicks
            return collectTopCpuAppsViaAdb(activityManager)
        }

        val result = ArrayList<TopAppCpu>()
        for ((pid, ticks) in currentTicks) {
            val previousTicks = previousProcessTicks[pid] ?: continue
            val processDelta = ticks - previousTicks
            if (processDelta <= 0L) continue
            val cpuPercent = processDelta.toFloat() * 100f / totalCpuDeltaJiffies.toFloat()
            if (cpuPercent <= 0f) continue
            result.add(
                TopAppCpu(
                    processName = currentNames[pid].orEmpty(),
                    pid = pid,
                    cpuPercent = cpuPercent
                )
            )
        }

        previousProcessTicks = currentTicks

        val note = when {
            readableCount == 0 -> "ОС ограничила доступ к /proc (CPU других приложений недоступен)"
            result.isEmpty() -> "Нет процессов с заметной CPU-активностью за интервал"
            deniedCount > readableCount -> "Часть процессов скрыта системой, TOP-10 может быть неполным"
            else -> null
        }

        return result
            .sortedByDescending { it.cpuPercent }
            .take(10) to note
    }

    private fun collectTopCpuAppsViaAdb(activityManager: ActivityManager): Pair<List<TopAppCpu>, String?> {
        val output = adbShellClient.execute("dumpsys cpuinfo", ADB_COMMAND_TIMEOUT_MS)
        if (output.error != null) {
            return emptyList<TopAppCpu>() to "ОС ограничила доступ к /proc, adb socket fallback недоступен (${output.error})"
        }

        val runningByPid = activityManager.runningAppProcesses
            ?.associateBy { it.pid }
            .orEmpty()
        val top = HashMap<Int, TopAppCpu>()
        for (rawLine in output.stdout.lineSequence()) {
            val match = DUMPSYS_CPUINFO_LINE_REGEX.find(rawLine) ?: continue
            val cpuPercent = match.groupValues[1].toFloatOrNull() ?: continue
            if (cpuPercent <= 0f) continue
            val subject = match.groupValues[2].trim().substringBefore(": ").trim()
            if (subject.equals("TOTAL", ignoreCase = true)) continue

            val slash = subject.indexOf('/')
            if (slash <= 0) continue
            val pid = subject.substring(0, slash).toIntOrNull() ?: continue
            if (pid <= 0) continue
            val rawName = subject.substring(slash + 1).trim()
            if (rawName.isBlank()) continue

            val resolvedName = runningByPid[pid]?.processName ?: rawName
            val name = sanitizeProcessName(resolvedName, pid)
            val existing = top[pid]
            if (existing == null || cpuPercent > existing.cpuPercent) {
                top[pid] = TopAppCpu(
                    processName = name,
                    pid = pid,
                    cpuPercent = cpuPercent
                )
            }
        }

        if (top.isEmpty()) {
            return emptyList<TopAppCpu>() to "ОС ограничила доступ к /proc, adb socket fallback не вернул процессы CPU"
        }
        return top.values
            .sortedByDescending { it.cpuPercent }
            .take(10) to "ОС ограничила доступ к /proc, TOP-10 получен через adb socket fallback"
    }

    private fun readProcessCpuStat(pid: Int): ProcessCpuStat? {
        val line = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val openBracket = line.indexOf('(')
        val closeBracket = line.lastIndexOf(')')
        if (openBracket < 0 || closeBracket <= openBracket) return null

        val processName = line.substring(openBracket + 1, closeBracket)
        val tail = line.substring(closeBracket + 2).trim().split(WHITESPACE_REGEX)
        if (tail.size < 13) return null

        val utime = tail[11].toLongOrNull() ?: return null
        val stime = tail[12].toLongOrNull() ?: return null
        return ProcessCpuStat(
            processName = processName,
            ticks = utime + stime
        )
    }

    private fun sanitizeProcessName(name: String, pid: Int): String {
        val cleaned = name
            .replace('\u0000', ' ')
            .trim()
            .ifEmpty { "pid:$pid" }
        return if (cleaned.length <= 48) cleaned else cleaned.take(45) + "..."
    }

    private data class AdbShellOutput(
        val stdout: String,
        val error: String?
    )

    private class LocalAdbShellClient {
        private val lock = Any()
        private val adbBase64 = AdbBase64 { bytes -> Base64.getEncoder().encodeToString(bytes) }

        private var connection: AdbConnection? = null
        private var socket: Socket? = null
        private var crypto: AdbCrypto? = null

        fun execute(command: String, timeoutMs: Long): AdbShellOutput {
            synchronized(lock) {
                var lastError = "неизвестная ошибка"
                repeat(2) {
                    val connectError = ensureConnected(timeoutMs)
                    if (connectError != null) {
                        lastError = connectError
                        return@repeat
                    }
                    val activeConnection = connection
                    if (activeConnection == null) {
                        lastError = "соединение не установлено"
                        return@repeat
                    }

                    try {
                        val stream = activeConnection.open("shell:$command")
                        try {
                            return AdbShellOutput(stdout = readStream(stream), error = null)
                        } finally {
                            runCatching { stream.close() }
                        }
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return AdbShellOutput(stdout = "", error = "прервано")
                    } catch (error: SocketTimeoutException) {
                        closeConnectionLocked()
                        lastError = "таймаут команды"
                    } catch (error: IOException) {
                        closeConnectionLocked()
                        val errorName = error::class.java.simpleName.ifBlank { "IOException" }
                        lastError = "ошибка ввода-вывода ($errorName)"
                    } catch (error: Exception) {
                        closeConnectionLocked()
                        lastError = error::class.java.simpleName.ifBlank { "error" }
                    }
                }
                return AdbShellOutput(stdout = "", error = lastError)
            }
        }

        private fun readStream(stream: com.tananaev.adblib.AdbStream): String {
            val output = StringBuilder()
            while (true) {
                val chunk = try {
                    stream.read()
                } catch (error: IOException) {
                    val message = error.message.orEmpty().lowercase(Locale.ROOT)
                    if (message.contains("closed")) break
                    throw error
                }
                if (chunk.isNotEmpty()) {
                    output.append(String(chunk, Charsets.UTF_8))
                }
            }
            return output.toString()
        }

        private fun ensureConnected(timeoutMs: Long): String? {
            if (connection != null) return null
            val timeout = timeoutMs.coerceAtLeast(500L).toInt()

            return try {
                val freshSocket = Socket().apply {
                    soTimeout = timeout
                    connect(InetSocketAddress(ADB_HOST, ADB_PORT), timeout)
                }
                val adbCrypto = crypto ?: AdbCrypto.generateAdbKeyPair(adbBase64).also { crypto = it }
                val freshConnection = AdbConnection.create(freshSocket, adbCrypto)
                val connected = freshConnection.connect(timeoutMs, TimeUnit.MILLISECONDS, false)
                if (!connected) {
                    runCatching { freshConnection.close() }
                    runCatching { freshSocket.close() }
                    "таймаут подключения"
                } else {
                    socket = freshSocket
                    connection = freshConnection
                    null
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                "прервано"
            } catch (error: Exception) {
                closeConnectionLocked()
                error::class.java.simpleName.ifBlank { "error" }
            }
        }

        private fun closeConnectionLocked() {
            runCatching { connection?.close() }
            runCatching { socket?.close() }
            connection = null
            socket = null
        }
    }

    private fun notifyListeners() {
        val snapshot = currentSnapshot()
        val listenersSnapshot = synchronized(lock) { listeners.toList() }
        listenersSnapshot.forEach { listener ->
            runCatching { listener.onSnapshotUpdated(snapshot) }
        }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val DUMPSYS_CPUINFO_LINE_REGEX = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+(.+)$")

    fun formatPercent(value: Float): String = String.format(Locale.getDefault(), "%.1f%%", value)
    fun formatMb(value: Float): String = String.format(Locale.getDefault(), "%.1f MB", value)
}
