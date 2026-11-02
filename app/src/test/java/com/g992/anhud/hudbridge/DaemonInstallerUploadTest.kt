package com.g992.anhud.hudbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DaemonInstallerUploadTest {
    private val daemon = ByteArray(5000) { (it * 7).toByte() }
    private val part = DaemonInstaller.QNX_PATH + ".part"

    @Test
    fun installsVerifiedUpload() {
        val qnx = FakeUpload()
        assertTrue(DaemonInstaller.copyViaUpload(qnx, daemon))
        assertTrue(qnx.commands.any { it.contains("chmod 755 $part") && it.contains("mv -f $part ${DaemonInstaller.QNX_PATH}") })
    }

    @Test
    fun corruptedUploadNeverReachesTheRealPath() {
        val qnx = FakeUpload(stored = { it.copyOf(it.size - 1) })
        assertFalse(DaemonInstaller.copyViaUpload(qnx, daemon))
        assertFalse(qnx.commands.any { it.contains("mv ") })
        assertTrue(qnx.commands.contains("rm -f $part"))
    }

    @Test
    fun telnetSessionFallsThrough() {
        val qnx = FakeUpload(supported = false)
        assertFalse(DaemonInstaller.copyViaUpload(qnx, daemon))
        assertTrue(qnx.commands.isEmpty())
    }

    @Test
    fun failedUploadFallsThrough() {
        val qnx = FakeUpload(failure = IOException("write failed"))
        assertFalse(DaemonInstaller.copyViaUpload(qnx, daemon))
    }

    @Test
    fun lostConnectionIsNotHiddenAsFallback() {
        val qnx = FakeUpload(failure = QnxConnectionLostException("gone"))
        try {
            DaemonInstaller.copyViaUpload(qnx, daemon)
            throw AssertionError("expected the lost connection to surface")
        } catch (e: QnxConnectionLostException) {
            assertEquals("gone", e.message)
        }
    }

    @Test
    fun sshOutputMatchesTelnetShape() {
        assertEquals(
            "42 7",
            SshQnxShell.mergeOutput("42 7\n", "Could not chdir to home directory /root: No such file or directory\n")
        )
        assertEquals("out\nerr", SshQnxShell.mergeOutput("out\n", "err\n"))
    }

    private class FakeUpload(
        private val supported: Boolean = true,
        private val stored: (ByteArray) -> ByteArray = { it },
        private val failure: IOException? = null
    ) : QnxSession {
        val commands = ArrayList<String>()
        private var file = ByteArray(0)

        override fun uploadFile(path: String, bytes: ByteArray): Boolean {
            if (!supported) return false
            failure?.let { throw it }
            file = stored(bytes)
            return true
        }

        override fun exec(command: String, timeoutMs: Long): QnxShell.Result {
            commands += command
            val out = if (command.startsWith("cksum")) DaemonInstaller.cksumOf(file) else ""
            return QnxShell.Result(out, 0)
        }

        override fun execBatch(lines: List<String>, timeoutMs: Long) = QnxShell.Result("", 0)
        override fun interrupt() = Unit
        override fun reconnect() = Unit
    }
}
