package com.g992.anhud

import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.CancellationException

internal interface CarPlayPatchShell {
    fun execute(command: String): String
    fun restartAdbdAsRoot()
}

internal data class CarPlayPatchOptions(
    val restartPending: Boolean = false
)

/** Temporary bind mount, recovered from GeeGeek 0.2.0-beta1 r1/k. Never writes /vendor. */
internal class CarPlayConfigPatcher(
    private val shell: CarPlayPatchShell,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val isActive: () -> Boolean = { true },
    private val progress: (String) -> Unit = {},
    private val setRestartPending: (Boolean) -> Unit = {}
) {
    fun apply(options: CarPlayPatchOptions): String {
        var mounted = false
        try {
            report("Проверка локального ADB…")
            if (!isRoot(command("id"))) {
                report("Запрос root для ADB…")
                // adbd closes the connection during restart; the subsequent id is authoritative.
                runCatching { shell.restartAdbdAsRoot() }
                var root = false
                var lastFailure: Exception? = null
                repeat(5) {
                    if (!root) {
                        checkActive()
                        pause(2000)
                        try {
                            root = isRoot(command("id"))
                            lastFailure = null
                        } catch (e: Exception) { lastFailure = e }
                    }
                }
                if (!root && lastFailure is java.io.IOException) throw lastFailure!!
                check(root) { "ADB не получил root. Прошивка должна поддерживать adb root." }
            }
            report("Чтение конфигурации CarPlay…")
            val original = command("cat $CONFIG")
            val modified = patchConfig(original)
            val patched = modified ?: original
            val existingMount = hasMount(command("cat /proc/mounts"))
            check(!existingMount || modified == null) {
                "Конфиг уже подменён, но передача маршрута выключена. Перезагрузите ГУ перед активацией."
            }
            if (modified == null && (!options.restartPending || !existingMount)) {
                setRestartPending(false)
                return "Передача маршрута уже включена. Подмена не требуется."
            }
            if (modified != null) {
                report("Подготовка и проверка копии…")
                command("[ ! -L $TEMP_B64 ] && [ ! -L $TEMP_JSON ]")
                val encoded = Base64.getEncoder().encodeToString(patched.toByteArray(Charsets.UTF_8))
                encoded.chunked(2048).forEachIndexed { index, chunk ->
                    command("printf '%s' '$chunk' ${if (index == 0) ">" else ">>"} $TEMP_B64")
                }
                command("base64 -d $TEMP_B64 > $TEMP_JSON")
                check(command("cat $TEMP_JSON") == patched) { "Копия конфига не совпала с подготовленной. Подмена отменена." }
            }
            // Like GeeGeek's default: restart only the service; killing the UI hangs an active projection.
            val servicePids = pids(command("pidof com.autolink.carplay || true"))
            // Recheck the source immediately before mounting; don't replace a config changed by another app.
            check(command("cat $CONFIG") == original && hasMount(command("cat /proc/mounts")) == existingMount) {
                "Конфигурация изменилась во время подготовки. Повторите активацию."
            }
            if (modified != null) {
                report("Включение передачи маршрута…")
                setRestartPending(true)
                command("mount --bind $TEMP_JSON $CONFIG")
            }
            mounted = true
            check(hasMount(command("cat /proc/mounts")) && command("cat $CONFIG") == patched) {
                "Не удалось подтвердить подмену конфигурации."
            }
            if (servicePids.isNotEmpty()) {
                report("Перезапуск сервиса CarPlay…")
                // Only validated numeric PIDs of the exact service package; no broad pkill -f.
                command("kill ${servicePids.sorted().joinToString(" ")}")
                pause(3000)
                val surviving = pids(command("pidof com.autolink.carplay || true"))
                check(servicePids.intersect(surviving).isEmpty()) { "CarPlay не перезапустился." }
            }
            checkActive()
            check(command("cat $CONFIG") == patched && hasMount(command("cat /proc/mounts"))) {
                "Подмена не сохранилась после перезапуска CarPlay."
            }
            setRestartPending(false)
            return "Передача маршрута включена до перезагрузки ГУ. Подключите CarPlay и запустите маршрут."
        } catch (e: Exception) {
            if (mounted) throw IllegalStateException(
                "Подмена установлена, активация не завершена: ${e.message}. Перезагрузка ГУ сбросит подмену.", e
            )
            throw e
        }
    }

    private fun checkActive() {
        if (!isActive() || Thread.currentThread().isInterrupted) throw CancellationException("Активация остановлена")
    }

    private fun command(value: String): String {
        checkActive()
        return shell.execute(value)
    }

    private fun report(value: String) {
        checkActive()
        progress(value)
    }

    companion object {
        const val CONFIG = "/vendor/etc/carplay/carplay_config.json"
        const val TEMP_JSON = "/data/local/tmp/anhud_carplay_config_mod.json"
        const val TEMP_B64 = "/data/local/tmp/anhud_carplay_config_mod.b64"
        private val feature = Regex("(\"featureRouteGuidance\"\\s*:\\s*)([01])(?=\\s*[,}])")

        internal fun patchConfig(original: String): String? {
            check(original.toByteArray(Charsets.UTF_8).size in 1..262144) { "Неподдерживаемый размер конфигурации CarPlay." }
            JSONObject(original) // Validate JSON before creating any files.
            val matches = feature.findAll(original).toList()
            check(matches.size == 1) { "В конфиге должен быть один числовой параметр featureRouteGuidance (0 или 1)." }
            val value = matches.single().groups[2]!!
            if (value.value == "1") return null
            return original.replaceRange(value.range, "1")
        }

        private fun isRoot(value: String) = Regex("^uid=0(?:\\(|\\s|$)").containsMatchIn(value.trim())

        private fun hasMount(value: String) = value.lineSequence().any {
            it.split(Regex("\\s+")).getOrNull(1) == CONFIG
        }

        private fun pids(value: String): Set<Int> {
            if (value.isBlank()) return emptySet()
            return value.trim().split(Regex("\\s+")).map {
                it.toIntOrNull()?.takeIf { pid -> pid > 1 }
                    ?: error("Некорректный PID CarPlay. Перезапуск отменён.")
            }.toSet()
        }
    }
}
