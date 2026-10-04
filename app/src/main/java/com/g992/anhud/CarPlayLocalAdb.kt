package com.g992.anhud

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ConnectException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CarPlayAdbUnavailableException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/** Bounded unauthenticated local ADB transport used by GeeGeek; never connects off-device. */
internal class CarPlayLocalAdb(private val port: Int = 5555) : CarPlayPatchShell {
    override fun execute(command: String): String {
        // Raw shell preserves JSON bytes: a PTY may translate LF into CRLF.
        val output = exchange("shell,raw:$command; __anhud_cp_rc=\$?; printf '\\n$EXIT_MARKER%s\\n' \"\$__anhud_cp_rc\"")
        val marker = output.lastIndexOf("\n$EXIT_MARKER")
        check(marker >= 0) { "ADB не вернул код завершения команды." }
        val code = output.substring(marker + EXIT_MARKER.length + 1).trim().toIntOrNull()
        check(code == 0) { "Команда ADB завершилась с кодом ${code ?: "?"}: ${output.substring(0, marker).take(240).trim()}" }
        return output.substring(0, marker)
    }

    override fun restartAdbdAsRoot() { exchange("root:") }

    private fun exchange(service: String): String = Socket().use { socket ->
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 2500)
        } catch (e: ConnectException) {
            throw CarPlayAdbUnavailableException("Локальный ADB 127.0.0.1:$port недоступен. Включите сетевой ADB на ГУ.", e)
        }
        socket.soTimeout = 4000
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val deadline = System.nanoTime() + 15_000_000_000L
        fun readFully(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) throw CarPlayAdbUnavailableException("Истекло время ожидания локального ADB.")
                socket.soTimeout = minOf(4000, remainingMs.toInt())
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count <= 0) throw CarPlayAdbUnavailableException("Локальный ADB закрыл соединение.")
                offset += count
            }
        }
        fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = byteArrayOf()) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).array()
            output.write(header)
            output.write(payload)
            output.flush()
        }
        fun receive(): Packet {
            if (System.nanoTime() >= deadline) throw CarPlayAdbUnavailableException("Истекло время ожидания локального ADB.")
            val bytes = ByteArray(24)
            readFully(bytes)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val size = header.int
            val checksum = header.int
            check(header.int == command.inv() && size in 0..65536) { "Некорректный пакет ADB." }
            val payload = ByteArray(size)
            readFully(payload)
            // ADB 0x01000001 omits checksums; our 0x01000000 negotiation requires them.
            check(checksum == payload.sumOf { it.toInt() and 255 }) { "Повреждённый пакет ADB." }
            return Packet(command, arg0, arg1, payload)
        }
        send(CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
        val handshake = receive()
        check(handshake.command != AUTH) { "Локальный ADB требует авторизацию. Разрешите подключение на ГУ." }
        check(handshake.command == CNXN) { "Локальный ADB не подтвердил подключение." }
        send(OPEN, 1, 0, (service + '\u0000').toByteArray(Charsets.UTF_8))
        val result = ByteArrayOutputStream()
        var remoteId: Int? = null
        while (true) {
            val packet = receive()
            check(packet.arg1 == 1) { "Неверный канал ADB." }
            if (remoteId == null) remoteId = packet.arg0
            check(packet.arg0 == remoteId) { "Канал ADB изменился." }
            when (packet.command) {
                OKAY -> Unit
                WRTE -> {
                    check(result.size() + packet.payload.size <= 1048576) { "Ответ ADB слишком большой." }
                    result.write(packet.payload)
                    send(OKAY, 1, packet.arg0)
                }
                CLSE -> {
                    send(CLSE, 1, packet.arg0)
                    return@use result.toString("UTF-8")
                }
                else -> error("Неожиданный ответ ADB.")
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    companion object {
        private const val EXIT_MARKER = "__ANHUD_CP_EXIT__:"
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val WRTE = 0x45545257
        private const val CLSE = 0x45534c43
    }
}
