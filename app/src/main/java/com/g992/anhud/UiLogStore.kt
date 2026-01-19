package com.g992.anhud

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap

enum class LogCategory(val title: String) {
    NAVIGATION("Навигация"),
    SENSORS("Датчики"),
    SYSTEM("Система")
}

object UiLogStore {
    private const val MAX_LINES = 200
    private val lock = Any()
    private val logs = EnumMap<LogCategory, MutableList<String>>(LogCategory::class.java)
    private val listeners = mutableSetOf<Listener>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        for (category in LogCategory.values()) {
            logs[category] = mutableListOf()
        }
    }

    interface Listener {
        fun onLogsUpdated(category: LogCategory, lines: List<String>)
    }

    fun append(category: LogCategory, message: String) {
        val line = "${timestamp()} $message"
        val snapshot: List<String>
        synchronized(lock) {
            val list = logs.getValue(category)
            list.add(line)
            if (list.size > MAX_LINES) {
                list.removeAt(0)
            }
            snapshot = list.toList()
        }
        notifyListeners(category, snapshot)
    }

    fun registerListener(listener: Listener) {
        synchronized(lock) {
            listeners.add(listener)
        }
        for (category in LogCategory.values()) {
            listener.onLogsUpdated(category, snapshot(category))
        }
    }

    fun unregisterListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun totalCount(): Int {
        synchronized(lock) {
            return logs.values.sumOf { it.size }
        }
    }

    private fun snapshot(category: LogCategory): List<String> {
        synchronized(lock) {
            return logs.getValue(category).toList()
        }
    }

    private fun notifyListeners(category: LogCategory, lines: List<String>) {
        val listenersSnapshot: List<Listener>
        synchronized(lock) {
            listenersSnapshot = listeners.toList()
        }
        for (listener in listenersSnapshot) {
            listener.onLogsUpdated(category, lines)
        }
    }

    private fun timestamp(): String {
        return LocalTime.now().format(timeFormat)
    }
}
