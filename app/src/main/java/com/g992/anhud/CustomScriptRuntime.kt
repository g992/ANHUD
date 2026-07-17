package com.g992.anhud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class CustomScriptRuntime(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextRequestId = AtomicInteger(1)
    private val pending = linkedMapOf<Int, PendingRequest>()
    private val outgoingQueue = ArrayDeque<OutgoingRequest>()
    private var service: Messenger? = null
    private var bound = false
    private var closed = false

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != CustomScriptSandboxProtocol.MSG_RESULT) return@Handler false
        val request = pending.remove(message.arg1) ?: return@Handler true
        mainHandler.removeCallbacks(request.timeout)
        val error = message.data.getString(CustomScriptSandboxProtocol.KEY_ERROR)
        if (error != null) {
            request.callback(Result.failure(IllegalArgumentException(error)))
        } else {
            val result = runCatching {
                CustomScriptEvaluation.parse(
                    message.data.getString(CustomScriptSandboxProtocol.KEY_RESULT).orEmpty()
                )
            }
            request.callback(result)
        }
        flushQueue()
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let(::Messenger)
            flushQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            failPending("Превышен лимит выполнения скрипта")
            if (!closed) bind()
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            bound = false
            failPending("Превышен лимит выполнения скрипта")
            if (!closed) bind()
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            bound = false
            failPending("Не удалось запустить изолированный JS-runtime")
        }
    }

    fun evaluate(
        script: String,
        environment: CustomScriptEnvironment,
        callback: (Result<CustomScriptEvaluation>) -> Unit
    ) {
        val program = runCatching {
            CustomScriptPolicy.dependencies(script)
            CustomScriptDsl.buildProgram(script, environment)
        }.getOrElse { error ->
            mainHandler.post { callback(Result.failure(error)) }
            return
        }
        mainHandler.post {
            if (closed) {
                callback(Result.failure(IllegalStateException("JS-runtime закрыт")))
                return@post
            }
            val requestId = nextRequestId.getAndIncrement()
            outgoingQueue.addLast(OutgoingRequest(requestId, program, callback))
            flushQueue()
        }
    }

    override fun close() {
        mainHandler.post {
            if (closed) return@post
            closed = true
            outgoingQueue.forEach { request ->
                request.callback(Result.failure(IllegalStateException("JS-runtime закрыт")))
            }
            outgoingQueue.clear()
            failPending("JS-runtime закрыт")
            if (bound) runCatching { appContext.unbindService(connection) }
            bound = false
            service = null
        }
    }

    private fun bind() {
        if (bound || closed) return
        bound = appContext.bindService(
            Intent(appContext, CustomScriptSandboxService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        if (!bound) failQueued("Не удалось подключить изолированный JS-runtime")
    }

    private fun flushQueue() {
        if (pending.isNotEmpty()) return
        val target = service ?: run {
            bind()
            return
        }
        if (outgoingQueue.isNotEmpty()) {
            val outgoing = outgoingQueue.removeFirst()
            val timeout = Runnable {
                pending.remove(outgoing.requestId)?.callback?.invoke(
                    Result.failure(IllegalStateException("Превышен лимит выполнения скрипта"))
                )
            }
            pending[outgoing.requestId] = PendingRequest(outgoing.callback, timeout)
            mainHandler.postDelayed(timeout, CLIENT_TIMEOUT_MS)
            val message = Message.obtain(null, CustomScriptSandboxProtocol.MSG_EVALUATE).apply {
                arg1 = outgoing.requestId
                replyTo = replies
                data = Bundle().apply {
                    putString(CustomScriptSandboxProtocol.KEY_PROGRAM, outgoing.program)
                }
            }
            runCatching { target.send(message) }.onFailure {
                pending.remove(outgoing.requestId)
                mainHandler.removeCallbacks(timeout)
                outgoing.callback(Result.failure(IllegalStateException("JS-runtime недоступен")))
                service = null
                bound = false
                bind()
            }
        }
    }

    private fun failPending(message: String) {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { request ->
            mainHandler.removeCallbacks(request.timeout)
            request.callback(Result.failure(IllegalStateException(message)))
        }
    }

    private fun failQueued(message: String) {
        while (outgoingQueue.isNotEmpty()) {
            outgoingQueue.removeFirst().callback(Result.failure(IllegalStateException(message)))
        }
    }

    private data class OutgoingRequest(
        val requestId: Int,
        val program: String,
        val callback: (Result<CustomScriptEvaluation>) -> Unit
    )

    private data class PendingRequest(
        val callback: (Result<CustomScriptEvaluation>) -> Unit,
        val timeout: Runnable
    )

    companion object {
        const val MAX_RESULT_JSON_LENGTH = 16 * 1024
        private const val CLIENT_TIMEOUT_MS = 1_500L
    }
}
