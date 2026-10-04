package com.g992.anhud

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CarPlayConfigPatcherTest {
    private val config = "{\n  \"featureRouteGuidance\" : 0,\n  \"other\": \"Без изменений\"\n}\n"

    @Test
    fun changesOnlyTheFlagPreservingWhitespaceAndUnicode() {
        assertEquals(config.replace(": 0", ": 1"), CarPlayConfigPatcher.patchConfig(config))
        assertNull(CarPlayConfigPatcher.patchConfig(config.replace(": 0", ": 1")))
        for (invalid in listOf("", "{}", "{\"featureRouteGuidance\":10}",
            "{\"featureRouteGuidance\":false}", "{\"featureRouteGuidance\":0.1}",
            "{\"featureRouteGuidance\":0,\"nested\":{\"featureRouteGuidance\":0}}", "not json")) {
            assertThrows(Exception::class.java) { CarPlayConfigPatcher.patchConfig(invalid) }
        }
    }

    @Test
    fun mountsVerifiedCopyRestartsOnlyServiceAndRepeatedStartupEnsuresRoot() {
        val shell = FakeShell()
        val patcher = CarPlayConfigPatcher(shell, pause = {})
        assertTrue(patcher.apply(CarPlayPatchOptions()).contains("включена до перезагрузки"))
        assertEquals(config.replace(": 0", ": 1"), shell.current)
        assertEquals(listOf("kill 123"), shell.commands.filter { it.startsWith("kill ") })
        assertFalse(shell.commands.any { it.contains("com.autolink.carplay.app") })
        assertTrue(shell.commands.indexOf("cat ${CarPlayConfigPatcher.TEMP_JSON}") <
            shell.commands.indexOfFirst { it.startsWith("mount --bind ") })
        val before = shell.commands.size
        shell.root = false
        assertTrue(patcher.apply(CarPlayPatchOptions()).contains("уже включена"))
        assertFalse(shell.commands.drop(before).any { it.startsWith("mount ") || it.startsWith("kill ") })
        assertEquals("id", shell.commands.drop(before).first())
        assertEquals(1, shell.rootRequests)
    }

    @Test
    fun requestsRootWhenAdbdIsNotRoot() {
        val shell = FakeShell().apply { root = false }
        CarPlayConfigPatcher(shell, pause = {}).apply(CarPlayPatchOptions())
        assertEquals(1, shell.rootRequests)
        assertTrue(shell.commands.contains("kill 123"))
    }

    @Test
    fun adbStillRestartingAfterRootKeepsTransientFailureForAutomaticRetry() {
        val shell = FakeShell().apply { root = false; idFailuresAfterRoot = 5 }
        assertThrows(java.io.IOException::class.java) {
            CarPlayConfigPatcher(shell, pause = {}).apply(CarPlayPatchOptions())
        }
        assertEquals(1, shell.rootRequests)
        assertFalse(shell.mounted)
        assertFalse(shell.commands.any { it.startsWith("printf ") })
    }

    @Test
    fun rejectedRootStopsBeforeWriting() {
        val shell = FakeShell().apply { root = false; rootSupported = false }
        assertThrows(IllegalStateException::class.java) {
            CarPlayConfigPatcher(shell, pause = {}).apply(CarPlayPatchOptions())
        }
        assertEquals(1, shell.rootRequests)
        assertFalse(shell.mounted)
        assertFalse(shell.commands.any { it.startsWith("printf ") })
    }

    @Test
    fun corruptedCopyAndChangedSourceStopBeforeMount() {
        for (changeSource in listOf(false, true)) {
            val shell = FakeShell().apply { corruptCopy = !changeSource; changeBeforeMount = changeSource }
            assertThrows(IllegalStateException::class.java) {
                CarPlayConfigPatcher(shell, pause = {}).apply(CarPlayPatchOptions())
            }
            assertFalse(shell.mounted)
            assertFalse(shell.commands.any { it.startsWith("kill ") })
        }
    }

    @Test
    fun cancellationStopsBeforeMountAndUnchangedPidReportsIncompleteActivation() {
        val cancelled = FakeShell()
        assertThrows(Exception::class.java) {
            CarPlayConfigPatcher(cancelled, pause = {}, isActive = { cancelled.encoded.isEmpty() })
                .apply(CarPlayPatchOptions())
        }
        assertFalse(cancelled.mounted)
        val stuck = FakeShell().apply { restartFails = true }
        val failure = assertThrows(IllegalStateException::class.java) {
            CarPlayConfigPatcher(stuck, pause = {}).apply(CarPlayPatchOptions())
        }
        assertTrue(stuck.mounted)
        assertTrue(failure.message!!.startsWith("Подмена установлена, активация не завершена"))
    }

    @Test
    fun retryAfterFailedRestartDoesNotOverwriteMountedCopyAndCompletesRestart() {
        var pending = false
        val shell = FakeShell().apply { restartFails = true }
        val patcher = CarPlayConfigPatcher(shell, pause = {}, setRestartPending = { pending = it })
        assertThrows(IllegalStateException::class.java) { patcher.apply(CarPlayPatchOptions()) }
        assertTrue(pending)
        shell.restartFails = false
        val before = shell.commands.size
        patcher.apply(CarPlayPatchOptions(restartPending = pending))
        assertFalse(pending)
        val retry = shell.commands.drop(before)
        assertTrue(retry.contains("kill 123"))
        assertFalse(retry.any { it.startsWith("printf ") || it.startsWith("mount --bind ") })
    }

    @Test
    fun malformedPidAndExistingDisabledMountNeverGetOverwritten() {
        for (mounted in listOf(false, true)) {
            val shell = FakeShell().apply { this.mounted = mounted; badPid = !mounted }
            assertThrows(IllegalStateException::class.java) {
                CarPlayConfigPatcher(shell, pause = {}).apply(CarPlayPatchOptions())
            }
            assertFalse(shell.commands.any { it.startsWith("mount --bind ") || it.startsWith("kill ") })
        }
    }

    private inner class FakeShell : CarPlayPatchShell {
        val commands = mutableListOf<String>()
        var current = config
        var encoded = ""
        var copy = ""
        var root = true
        var rootSupported = true
        var rootRequests = 0
        var idFailuresAfterRoot = 0
        var mounted = false
        var corruptCopy = false
        var changeBeforeMount = false
        var restartFails = false
        var badPid = false
        var killed = false
        private var configReads = 0

        override fun restartAdbdAsRoot() { rootRequests++; root = rootSupported }

        override fun execute(command: String): String {
            commands.add(command)
            return when {
                command == "id" -> {
                    if (rootRequests > 0 && idFailuresAfterRoot-- > 0) throw java.io.IOException("adbd restarting")
                    if (root) "uid=0(root) gid=0(root)" else "uid=2000(shell)"
                }
                command == "cat ${CarPlayConfigPatcher.CONFIG}" -> {
                    configReads++
                    if (changeBeforeMount && configReads == 2) current = current.replace("Без изменений", "Изменено")
                    current
                }
                command == "cat /proc/mounts" -> if (mounted) "device ${CarPlayConfigPatcher.CONFIG} ext4 ro 0 0\n" else "device /vendor ext4 ro 0 0\n"
                command.startsWith("[ ! -L ") -> ""
                command.startsWith("printf '%s' '") -> {
                    val chunk = command.substringAfter("printf '%s' '").substringBefore("'")
                    encoded = if (command.contains(" >> ")) encoded + chunk else chunk
                    ""
                }
                command.startsWith("base64 -d ") -> {
                    copy = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                    if (corruptCopy) copy += "broken"
                    ""
                }
                command == "cat ${CarPlayConfigPatcher.TEMP_JSON}" -> copy
                command == "pidof com.autolink.carplay || true" -> if (badPid) "123; reboot" else if (killed) "789" else "123"
                command.startsWith("mount --bind ") -> { mounted = true; current = copy; "" }
                command.startsWith("kill ") -> { killed = !restartFails; "" }
                else -> error("Unexpected command: $command")
            }
        }
    }
}
