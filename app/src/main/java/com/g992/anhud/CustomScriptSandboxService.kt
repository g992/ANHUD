package com.g992.anhud

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.Executors

/**
 * The third-party wrapper exposes QuickJS memory/stack limits but no interrupt handler. Running
 * each evaluation in an isolated process lets this service enforce a hard wall-clock limit by
 * terminating only the sandbox process; the HUD process remains alive and reconnects on demand.
 */
class CustomScriptSandboxService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "custom-block-quickjs")
    }
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CustomScriptSandboxProtocol.MSG_EVALUATE) {
            evaluate(message)
            true
        } else {
            false
        }
    })

    override fun onCreate() {
        super.onCreate()
        QuickJSLoader.init()
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun evaluate(request: Message) {
        val program = request.data.getString(CustomScriptSandboxProtocol.KEY_PROGRAM).orEmpty()
        val replyTo = request.replyTo ?: return
        val requestId = request.arg1
        executor.execute {
            val watchdog = Runnable { Process.killProcess(Process.myPid()) }
            mainHandler.postDelayed(watchdog, HARD_TIMEOUT_MS)
            val result = runCatching {
                require(program.length <= MAX_PROGRAM_LENGTH) { "JS-программа слишком большая" }
                QuickJSContext.create().use { quickJs ->
                    quickJs.setMemoryLimit(MEMORY_LIMIT_BYTES)
                    quickJs.setMaxStackSize(STACK_LIMIT_BYTES)
                    val value = quickJs.evaluate(program, "custom-block.js")
                    require(value is String) { "Скрипт вернул неподдерживаемый результат" }
                    require(value.length <= CustomScriptRuntime.MAX_RESULT_JSON_LENGTH) {
                        "Результат скрипта слишком большой"
                    }
                    value
                }
            }
            mainHandler.post {
                mainHandler.removeCallbacks(watchdog)
                val response = Message.obtain(null, CustomScriptSandboxProtocol.MSG_RESULT).apply {
                    arg1 = requestId
                    data = Bundle().apply {
                        result.onSuccess { putString(CustomScriptSandboxProtocol.KEY_RESULT, it) }
                        result.onFailure {
                            putString(
                                CustomScriptSandboxProtocol.KEY_ERROR,
                                it.message ?: it::class.java.simpleName
                            )
                        }
                    }
                }
                runCatching { replyTo.send(response) }
            }
        }
    }

    private companion object {
        const val HARD_TIMEOUT_MS = 200L
        const val MEMORY_LIMIT_BYTES = 4 * 1024 * 1024
        const val STACK_LIMIT_BYTES = 256 * 1024
        const val MAX_PROGRAM_LENGTH = 32 * 1024
    }
}
