package com.g992.anhud.hudbridge

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Another app is drawing on the HUD; [holder] is its name as the daemon reports it. */
class BusyException(val holder: String) : IOException("HUD занят приложением $holder")

/** The daemon answered HELLO with ERROR (anything but BUSY). */
class DaemonRejectedException(val code: Int, val daemonMessage: String) :
    IOException("демон отказал: $daemonMessage (код $code)")

/**
 * One gcp/1 session with ghudbridgelited: HELLO, frames, PING twice a second, BYE.
 * The daemon removes our window as soon as the connection goes away.
 */
class HudLink private constructor(
    private val socket: Socket,
    appName: String,
    private val listener: Listener
) : AutoCloseable {

    fun interface Listener {
        /** The session ended on the daemon's side; not called after [close]. */
        fun onLinkLost(reason: String)
    }

    private val output: OutputStream = socket.getOutputStream()
    private val input = DataInputStream(socket.getInputStream())
    private val reader: Thread
    private val pinger: Thread
    private var seq = 0

    @Volatile
    private var closing = false

    @Volatile
    private var cameraMessageSent = false

    /** The daemon answered a camera message with BAD_TYPE: it predates CAMCFG/VEHSTATE. */
    @Volatile
    var cameraRejected = false
        private set

    /** HELLO_ACK's server id, e.g. "ghudbridgelited/0.4.0". */
    val serverId: String

    /** The daemon answers CAMSNAP (0.4.0 and later); older ones would end the session on it. */
    val supportsCameraSnapshot: Boolean
        get() = Gcp.supportsCamSnap(serverId)

    /** HELLO_ACK's caps: with [Gcp.CAP_LAYER_OVER] the daemon has the over-stock layer (0.5.0+). */
    val caps: Int

    val supportsOverLayer: Boolean
        get() = (caps and Gcp.CAP_LAYER_OVER) != 0

    private val snapRequestIds = AtomicInteger()
    private val snapWaiters = ConcurrentHashMap<Int, CompletableFuture<Gcp.CamSnapDone>>()

    init {
        send(Gcp.T_HELLO, 0, Gcp.hello(appName))
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val (type, answer) = readMessage()
        if (type == Gcp.T_ERROR) {
            val e = Gcp.Error(answer)
            if (e.code == Gcp.ERR_BUSY) {
                throw BusyException(e.busyHolder)
            }
            throw DaemonRejectedException(e.code, e.message)
        }
        if (type != Gcp.T_HELLO_ACK) {
            throw IOException("неожиданный ответ на HELLO: 0x" + Integer.toHexString(type))
        }
        serverId = Gcp.helloAckServerId(answer)
        caps = Gcp.helloAckCaps(answer)
        socket.soTimeout = 0
        reader = Thread(::readLoop, "hud-link-read")
        pinger = Thread(::pingLoop, "hud-link-ping")
        reader.start()
        pinger.start()
    }

    /** Sends [rect] of the whole 800x480 RGBA window [rgba]. */
    @Throws(IOException::class)
    fun sendFrame(rgba: ByteArray, rect: Gcp.Rect) {
        send(Gcp.T_FRAME, Gcp.FRAME_LAST, Gcp.frame(rgba, Gcp.WIDTH, rect))
    }

    /**
     * Sends [parts] as one frame: FRAME_LAST only on the last one, so the daemon posts every
     * layer's window together. Layer-1 parts only when [supportsOverLayer].
     */
    @Throws(IOException::class)
    fun sendFrames(parts: List<Gcp.FramePart>) {
        parts.forEachIndexed { i, part ->
            val flags = if (i == parts.lastIndex) Gcp.FRAME_LAST else 0
            send(Gcp.T_FRAME, flags, Gcp.frame(part.rgba, Gcp.WIDTH, part.rect, part.layer))
        }
    }

    /** LAYER_OFF: the daemon clears and removes the over-stock window. */
    @Throws(IOException::class)
    fun sendOverLayerOff() {
        send(Gcp.T_LAYER_OFF, 0, Gcp.layerOff())
    }

    /** CAMCFG: camera views (rear, side) with window placement and undistortion parameters. */
    @Throws(IOException::class)
    fun sendCameraConfig(config: Gcp.CameraConfig) {
        cameraMessageSent = true
        send(Gcp.T_CAMCFG, 0, Gcp.camCfg(config))
    }

    /** VEHSTATE: current gear, speed and turn signals for the daemon's camera selection. */
    @Throws(IOException::class)
    fun sendVehicleState(gear: Int, speedKmh: Float, flags: Int) {
        cameraMessageSent = true
        send(Gcp.T_VEHSTATE, 0, Gcp.vehState(gear, speedKmh, flags))
    }

    /**
     * CAMSNAP: one live frame of camera [inputId] into [Gcp.camSnapPath], waited for up to
     * [timeoutMs]. Only for a daemon with [supportsCameraSnapshot]. IOException when no answer comes
     * in time or the link goes away meanwhile; a daemon-side failure is the answer's status.
     */
    @Throws(IOException::class)
    fun requestCameraSnapshot(inputId: Int, timeoutMs: Long): Gcp.CamSnapDone {
        val id = snapRequestIds.incrementAndGet()
        val answer = CompletableFuture<Gcp.CamSnapDone>()
        snapWaiters[id] = answer
        try {
            send(Gcp.T_CAMSNAP, 0, Gcp.camSnap(inputId, id))
            return answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IOException("демон не прислал кадр вовремя")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause?.message ?: "связь потеряна")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("прервано").apply { initCause(e) }
        } finally {
            snapWaiters.remove(id)
        }
    }

    override fun close() {
        closing = true
        failSnapWaiters("связь закрыта")
        try {
            send(Gcp.T_BYE, 0, ByteArray(0))
        } catch (_: IOException) {
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
        pinger.interrupt()
    }

    @Synchronized
    private fun send(type: Int, flags: Int, payload: ByteArray) {
        output.write(Gcp.message(type, flags, ++seq, payload))
        output.flush()
    }

    private fun readMessage(): Pair<Int, ByteArray> {
        val h = ByteArray(Gcp.HEADER_SIZE)
        input.readFully(h)
        val header = Gcp.Header(h)
        val payload = ByteArray(header.length)
        input.readFully(payload)
        return header.type to payload
    }

    private fun readLoop() {
        var why = "соединение закрыто"
        try {
            while (true) {
                val (type, payload) = readMessage()
                if (type == Gcp.T_CAMSNAP_DONE) {
                    Gcp.CamSnapDone.parse(payload)?.let { snapWaiters[it.requestId]?.complete(it) }
                    continue
                }
                if (type == Gcp.T_ERROR) {
                    val error = Gcp.Error(payload)
                    if (error.code == Gcp.ERR_BAD_TYPE && cameraMessageSent) {
                        cameraRejected = true
                    }
                    why = "ошибка демона: " + error.message
                    break
                }
                if (type == Gcp.T_BYE) {
                    why = "демон завершил сессию"
                    break
                }
            }
        } catch (_: SocketTimeoutException) {
            why = "демон не отвечает"
        } catch (e: IOException) {
            why = "связь потеряна: ${e.message}"
        } catch (e: IllegalArgumentException) {
            why = "связь потеряна: ${e.message}"
        }
        failSnapWaiters(why)
        if (!closing) {
            Log.w(TAG, "link lost: $why")
            listener.onLinkLost(why)
        }
    }

    private fun failSnapWaiters(why: String) {
        snapWaiters.values.forEach { it.completeExceptionally(IOException(why)) }
    }

    private fun pingLoop() {
        try {
            while (!closing) {
                Thread.sleep(PING_INTERVAL_MS)
                send(Gcp.T_PING, 0, ByteArray(0))
            }
        } catch (_: InterruptedException) {
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "HudLink"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val PING_INTERVAL_MS = 500L
        const val UNKNOWN_HOLDER = "unknown"

        /** Connects and completes HELLO/HELLO_ACK. Throws [BusyException] or [DaemonRejectedException] on refusal. */
        @Throws(IOException::class)
        fun open(host: String, appName: String, listener: Listener): HudLink {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, DaemonInstaller.PORT), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                return HudLink(s, appName, listener)
            } catch (e: Throwable) {
                try {
                    s.close()
                } catch (_: IOException) {
                }
                throw e
            }
        }

        /**
         * [open], retried every [stepMs] for up to [timeoutMs] while the daemon is not yet
         * listening, fails the handshake, or reports BUSY with an unknown holder (previous
         * session still tearing down). BUSY with a named holder and daemon rejections are thrown at once.
         */
        @Throws(IOException::class)
        fun openWithRetry(
            host: String,
            appName: String,
            listener: Listener,
            timeoutMs: Long = 10_000,
            stepMs: Long = 300
        ): HudLink {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val failure: IOException = try {
                    return open(host, appName, listener)
                } catch (e: BusyException) {
                    if (e.holder != UNKNOWN_HOLDER) throw e
                    e
                } catch (e: DaemonRejectedException) {
                    throw e
                } catch (e: IOException) {
                    e
                }
                if (System.currentTimeMillis() + stepMs >= deadline) {
                    throw failure
                }
                Log.d(TAG, "open retry: ${failure.message}")
                try {
                    Thread.sleep(stepMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("прервано").apply { initCause(e) }
                }
            }
        }
    }
}
