package com.g992.anhud

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CarPlayLocalAdbTest {
    @Test
    fun rawShellPreservesJsonBytesAndAcknowledgesFragmentedUtf8Output() {
        val expected = "{\r\n\"road\":\"Улица\"\n}\n"
        withServer { port, input, output ->
            val handshake = read(input)
            assertEquals(CNXN, handshake.command)
            send(output, CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
            val open = read(input)
            assertEquals(OPEN, open.command)
            assertTrue(String(open.payload).startsWith("shell,raw:cat /example;"))
            send(output, OKAY, 9, 1)
            val bytes = (expected + "\n__ANHUD_CP_EXIT__:0\n").toByteArray()
            bytes.asList().chunked(3).forEach { chunk ->
                send(output, WRTE, 9, 1, chunk.toByteArray())
                val ack = read(input)
                assertEquals(OKAY, ack.command)
                assertEquals(1, ack.arg0)
                assertEquals(9, ack.arg1)
            }
            send(output, CLSE, 9, 1)
            assertEquals(CLSE, read(input).command)
        }.useClient { port -> assertEquals(expected, CarPlayLocalAdb(port).execute("cat /example")) }
    }

    @Test
    fun rejectsAuthWithoutSendingAnyRootOrShellCommand() {
        withServer { _, input, output ->
            assertEquals(CNXN, read(input).command)
            send(output, AUTH, 1, 0, byteArrayOf(1, 2, 3))
            assertEquals(-1, input.read())
        }.useClient { port ->
            val error = assertThrows(IllegalStateException::class.java) { CarPlayLocalAdb(port).execute("id") }
            assertTrue(error.message!!.contains("авторизацию"))
        }
    }

    @Test
    fun rejectsMissingExitCodeAndNonZeroExit() {
        for (response in listOf("no marker", "permission denied\n__ANHUD_CP_EXIT__:1\n")) {
            withServer { _, input, output ->
                read(input)
                send(output, CNXN, 0x01000000, 4096)
                read(input)
                send(output, OKAY, 9, 1)
                send(output, WRTE, 9, 1, response.toByteArray())
                read(input)
                send(output, CLSE, 9, 1)
                read(input)
            }.useClient { port ->
                assertThrows(IllegalStateException::class.java) { CarPlayLocalAdb(port).execute("id") }
            }
        }
    }

    @Test
    fun refusesInvalidPacketSizeBeforeAllocatingPayload() {
        withServer { _, input, output ->
            read(input)
            output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CNXN).putInt(0x01000000).putInt(4096)
                .putInt(Int.MAX_VALUE).putInt(0).putInt(CNXN.inv()).array())
            output.flush()
        }.useClient { port ->
            assertThrows(IllegalStateException::class.java) { CarPlayLocalAdb(port).execute("id") }
        }
    }

    private class TestServer(private val block: (Int, DataInputStream, DataOutputStream) -> Unit) {
        fun useClient(client: (Int) -> Unit) {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        block(server.localPort, DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()))
                    }
                }
                try {
                    client(server.localPort)
                    future.get(10, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    private fun withServer(block: (Int, DataInputStream, DataOutputStream) -> Unit) = TestServer(block)
    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)
    private fun read(input: DataInputStream): Packet {
        val bytes = ByteArray(24)
        input.readFully(bytes)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val payload = ByteArray(header.int)
        val checksum = header.int
        assertEquals(command.inv(), header.int)
        input.readFully(payload)
        assertEquals(checksum, payload.sumOf { it.toInt() and 255 })
        return Packet(command, arg0, arg1, payload)
    }
    private fun send(output: DataOutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray = byteArrayOf()) {
        output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).array())
        output.write(payload)
        output.flush()
    }
    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val WRTE = 0x45545257
        private const val CLSE = 0x45534c43
    }
}
