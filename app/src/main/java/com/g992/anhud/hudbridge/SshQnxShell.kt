package com.g992.anhud.hudbridge

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Opens the QNX shell: SSH when the head unit accepts it, telnet otherwise. */
object QnxConnection {
    private const val TAG = "QnxConnection"

    /** [QnxAuthException] only comes from telnet: an SSH refusal alone proves nothing. */
    @Throws(IOException::class)
    fun open(host: String, user: String, password: String): QnxSession {
        return try {
            SshQnxShell.open(host, user, password).also { Log.i(TAG, "QNX shell over ssh") }
        } catch (e: IOException) {
            Log.w(TAG, "ssh unavailable (${e.message}), using telnet")
            QnxShell.open(host, user, password)
        } catch (e: LinkageError) {
            Log.w(TAG, "ssh library unusable on this device, using telnet", e)
            QnxShell.open(host, user, password)
        }
    }
}

/**
 * Root shell on QNX over SSH. Every command is its own exec channel, so a failed or hung one
 * cannot leave the next one typing into a half-read line, and bytes reach a file unchanged.
 * The host key is not pinned: the peer is the other VM on the internal vt3 link.
 */
class SshQnxShell private constructor(
    private val host: String,
    private val user: String,
    private val password: String
) : QnxSession {

    private lateinit var session: Session

    @Throws(IOException::class)
    override fun exec(command: String, timeoutMs: Long): QnxShell.Result {
        return run(command.trim(), null, timeoutMs)
    }

    /** One script on one channel; as with telnet, only the last line's exit code is reported. */
    @Throws(IOException::class)
    override fun execBatch(lines: List<String>, timeoutMs: Long): QnxShell.Result {
        return run(lines.joinToString("\n") { it.trim() }, null, timeoutMs)
    }

    /** Nothing to interrupt: a hung command dies with its channel in [run]. */
    override fun interrupt() = Unit

    @Throws(IOException::class)
    override fun reconnect() {
        close()
        connect()
    }

    /** `cat`, not SFTP: no extra subsystem and the same exit code path as any command. */
    @Throws(IOException::class)
    override fun uploadFile(path: String, bytes: ByteArray): Boolean {
        val result = run("cat > $path", bytes, UPLOAD_TIMEOUT_MS)
        if (!result.ok) {
            throw IOException("запись $path не удалась (код ${result.exitCode}): ${result.output}")
        }
        return true
    }

    override fun close() {
        if (::session.isInitialized) {
            session.disconnect()
        }
    }

    private fun connect() {
        try {
            val s = JSch().getSession(user, host, SSH_PORT)
            s.setPassword(password)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "password")
            s.setServerAliveInterval(KEEPALIVE_MS)
            s.setServerAliveCountMax(KEEPALIVE_COUNT)
            s.connect(CONNECT_TIMEOUT_MS)
            session = s
        } catch (e: JSchException) {
            throw IOException("ssh: ${e.message}", e)
        }
    }

    private fun run(command: String, stdin: ByteArray?, timeoutMs: Long): QnxShell.Result {
        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            throw QnxConnectionLostException("связь с QNX оборвалась: ${e.message}", e)
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            channel.setCommand(TELNET_ENV + command)
            channel.setOutputStream(out)
            channel.setErrStream(err)
            val remoteStdin = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            remoteStdin.use { pipe -> stdin?.let { pipe.write(it) } }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed) {
                if (!session.isConnected) {
                    throw QnxConnectionLostException("связь с QNX оборвалась")
                }
                if (System.currentTimeMillis() > deadline) {
                    throw IOException("QNX не ответила на команду: $command")
                }
                Thread.sleep(POLL_MS)
            }
            if (channel.exitStatus < 0) {
                throw QnxConnectionLostException("связь с QNX оборвалась")
            }
            return QnxShell.Result(mergeOutput(out.toString("UTF-8"), err.toString("UTF-8")), channel.exitStatus)
        } catch (e: JSchException) {
            throw QnxConnectionLostException("связь с QNX оборвалась: ${e.message}", e)
        } catch (e: QnxConnectionLostException) {
            throw e
        } catch (e: IOException) {
            if (!session.isConnected) {
                throw QnxConnectionLostException("связь с QNX оборвалась: ${e.message}", e)
            }
            throw e
        } finally {
            channel.disconnect()
        }
    }

    companion object {
        private const val SSH_PORT = 22
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val UPLOAD_TIMEOUT_MS = 30_000L
        private const val KEEPALIVE_MS = 5_000
        private const val KEEPALIVE_COUNT = 3
        private const val POLL_MS = 10L

        /**
         * sshd gives a thinner environment than telnet: no LD_LIBRARY_PATH or /scripts, umask 022,
         * HOME=/root (which does not exist). Daemons started from here must see what they see
         * when started from telnet, and files they make must stay as open.
         */
        internal const val TELNET_ENV =
            "umask 000; cd /; HOME=/; LD_LIBRARY_PATH=/lib64/dll/pci:/lib/:/usr/lib:/lib/dll; " +
                "PATH=/bin:/usr/bin:/usr/sbin:/sbin:/scripts; SECPOL_ENABLE=0; " +
                "export HOME LD_LIBRARY_PATH PATH SECPOL_ENABLE; "

        private const val NO_HOME_NOTICE = "Could not chdir to home directory"

        @Throws(IOException::class)
        fun open(host: String, user: String, password: String): SshQnxShell {
            return SshQnxShell(host, user, password).apply { connect() }
        }

        /** Telnet mixes stderr into the output; sshd's own missing-home notice is not command output. */
        internal fun mergeOutput(stdout: String, stderr: String): String {
            val errors = stderr.lineSequence().filterNot { it.startsWith(NO_HOME_NOTICE) }.joinToString("\n")
            return (stdout + errors).trim()
        }
    }
}
