package com.g992.anhud

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

internal object CarPlayPatchPrefs {
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("carplay_patch_prefs", Context.MODE_PRIVATE)

    fun options(context: Context) = CarPlayPatchOptions(
        restartPending = prefs(context).getBoolean("restart_pending", false)
    )

    fun setRestartPending(context: Context, pending: Boolean) {
        check(prefs(context).edit().putBoolean("restart_pending", pending).commit()) {
            "Не удалось сохранить состояние активации CarPlay."
        }
    }
}

/** Serializes temporary-file writes across provider restarts; listeners run on the main thread. */
internal object CarPlayPatchState {
    data class State(val busy: Boolean = false, val message: String = "", val failed: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val patchLock = Any()
    @Volatile var state = State()
        private set

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun apply(context: Context, options: CarPlayPatchOptions, isActive: () -> Boolean) = synchronized(patchLock) {
        if (!isActive()) return@synchronized false
        update(State(busy = true, message = "Подготовка CarPlay…"))
        try {
            val result = CarPlayConfigPatcher(CarPlayLocalAdb(), isActive = isActive,
                progress = { update(State(busy = true, message = it)) },
                setRestartPending = { CarPlayPatchPrefs.setRestartPending(context, it) })
                .apply(options.copy(restartPending = CarPlayPatchPrefs.options(context).restartPending))
            update(State(message = result))
            false
        } catch (e: Exception) {
            update(State(message = e.message ?: "Не удалось активировать данные CarPlay", failed = true))
            isActive() && generateSequence<Throwable>(e) { it.cause }.any {
                it is java.io.IOException || it is CarPlayAdbUnavailableException
            }
        }
    }

    fun awaitingRetry() {
        update(state.copy(message = "${state.message} Повтор через 10 секунд…"))
    }

    private fun update(value: State) {
        state = value
        UiLogStore.append(LogCategory.NAVIGATION, "CarPlay: ${value.message}")
        main.post { listeners.forEach { it() } }
    }
}
