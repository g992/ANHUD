# TurnSignalsTest

Приложение работает как embedded renderer карты для другого Android-приложения.

Внутри есть две части:
- экран настроек-лаунчер, который меняет параметры рендера;
- `ContentProvider`, который создаёт embedded renderer и отдаёт `SurfaceControlViewHost.SurfacePackage`.

Renderer умеет отдавать:
- карту текущей местности;
- текущую GPS-позицию;
- маршрут с пробками, если он пришёл через `com.yandex.ROUTE_TELEMETRY`;
- карту без маршрута, если внешний маршрут не поступал.

Renderer полностью non-interactive:
- scroll/zoom/rotate/tilt отключены;
- touch на root и `MapView` перехватывается и съедается;
- host-приложение должно считать этот surface только выводом картинки.

## Компоненты

- launcher с настройками: [`app/src/main/java/com/g992/turnsignalstest/SettingsActivity.kt`](app/src/main/java/com/g992/turnsignalstest/SettingsActivity.kt)
- хранилище настроек: [`app/src/main/java/com/g992/turnsignalstest/RenderSettings.kt`](app/src/main/java/com/g992/turnsignalstest/RenderSettings.kt)
- renderer provider: [`app/src/main/java/com/g992/turnsignalstest/MapRenderProvider.kt`](app/src/main/java/com/g992/turnsignalstest/MapRenderProvider.kt)
- приём внешнего маршрута: [`app/src/main/java/com/g992/turnsignalstest/RouteTelemetry.kt`](app/src/main/java/com/g992/turnsignalstest/RouteTelemetry.kt)
- регистрация компонентов: [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml)
- минимальный стиль карты: [`app/src/main/assets/styles/hud_minimal.json`](app/src/main/assets/styles/hud_minimal.json)

## Требования

- Android `11+` на стороне renderer и host
- host использует `SurfaceView`
- renderer-приложению выданы разрешения геолокации

## Настройки

Launcher-activity одна: `SettingsActivity`.

Доступные параметры:
- `Zoom`: масштаб карты
- `Height`: наклон камеры
- `Arrow`: размер стрелки в процентах
- `Bright`: яркость линий карты
- `Lines`: ширина линий карты

Все настройки сохраняются в `SharedPreferences` и автоматически применяются к активному renderer.

## Протокол

Authority:

```text
content://com.g992.turnsignalstest.render
```

Все методы вызываются через `ContentResolver.call(...)`.

Базовый формат ответа одинаковый для всех методов:
- `ok: Boolean`
- `error: String?`

Если `ok = false`, остальные поля нужно считать недействительными, кроме диагностических.

### Версия и feature negotiation

Контракт versioned.

Текущая версия:

```text
protocolVersion = 1
```

Для runtime negotiation есть отдельный вызов `get_protocol_info`.

Текущие feature-флаги:
- `surface_package`
- `route_status_query`
- `route_status_broadcast`
- `embedded_route_rendering`
- `settings_zoom`
- `settings_tilt`
- `settings_arrow_scale`
- `settings_brightness`
- `settings_line_width`

Host должен считать `protocolVersion` и `features` источником истины, если контракт будет расширяться дальше.

### Размеры и валидность surface

`width` и `height` в provider нормализуются через `coerceAtLeast(1)`.

Это значит:
- `0` и отрицательные значения формально не ломают provider;
- внутренне они будут превращены в `1x1`;
- host не должен использовать это как рабочий режим;
- practically нужно передавать реальный размер контейнера `SurfaceView`.

Минимальный осмысленный размер определяется уже host-приложением и UX, а не provider. Для контракта размер меньше нескольких пикселей считается допустимым только как технический fallback.

### `create_surface`

Создаёт embedded renderer и возвращает новый `SurfacePackage`.

Extras:
- `hostToken: IBinder` — брать из `SurfaceView.getHostToken()`
- `displayId: Int` — обычно `display?.displayId ?: Display.DEFAULT_DISPLAY`
- `width: Int`
- `height: Int`

Ответ при успехе:
- `ok = true`
- `surfacePackage: SurfaceControlViewHost.SurfacePackage`
- `hasRoute: Boolean`
- `routeBuilt: Boolean`
- `routeState: String?`
- `replacedExisting: Boolean`

Ответ при ошибке:
- `ok = false`
- `error = "..."`

Типовые ошибки:
- `Missing host token`
- `Display <id> is not available`
- `Surface embedding requires Android 11+`
- `No context`

Модель жизненного цикла renderer singleton:
- provider держит только один активный embedded instance на процесс;
- ownership по host не ведётся;
- повторный `create_surface` всегда освобождает предыдущий instance перед созданием нового;
- `replacedExisting = true` означает, что до этого уже был активный renderer, который был заменён.

Если один и тот же host вызвал `create_surface` повторно, не вызвав `release_surface`, старый instance всё равно будет закрыт и заменён новым.

### `resize_surface`

Меняет размер уже созданного renderer.

Extras:
- `width: Int`
- `height: Int`

Ответ при успехе:
- `ok = true`

Ответ при ошибке:
- `ok = false`
- `error = "Renderer is not created"`

Дополнительных полей у успешного ответа нет. Размеры так же нормализуются минимум к `1x1`.

### `release_surface`

Освобождает активный renderer.

Extras:
- не нужны

Ответ:
- `ok = true`
- `released: Boolean`

Текущее поведение:
- `released = true`, если активный renderer существовал и был освобождён;
- `released = false`, если активного renderer не было;
- повторный `release_surface` допустим и не считается ошибкой.

Сейчас `release_surface` не возвращает отдельный error-case при отсутствии renderer, потому что отсутствие активного instance трактуется как уже достигнутое целевое состояние.

### `get_route_status`

Возвращает текущее состояние внешнего маршрута.

Ответ:
- `ok = true`
- `hasRoute: Boolean`
- `routeBuilt: Boolean`
- `routeState: String?`

Смысл полей:
- `hasRoute = true` — у renderer сейчас есть геометрия маршрута для рисования;
- `routeBuilt = true` — маршрут построен или геометрия маршрута уже есть;
- `routeState` — последнее известное состояние из `com.yandex.ROUTE_STATE`.

### `get_protocol_info`

Возвращает техническую информацию о контракте.

Ответ:
- `ok = true`
- `protocolVersion: Int`
- `features: ArrayList<String>`

## Жизненный цикл surface и отказоустойчивость

`SurfacePackage` нужно считать одноразовым живым объектом, привязанным к текущему процессу renderer.

Host обязан быть готов к пересозданию renderer в следующих случаях:
- процесс renderer был убит системой;
- renderer-приложение было force-stop;
- `SurfacePackage` перестал рисоваться;
- `setChildSurfacePackage(...)` больше не даёт валидного вывода;
- host считает текущий child surface инвалидным.

Рекомендуемая последовательность восстановления:
1. best-effort вызвать `release_surface`;
2. отвязать текущий child surface через `SurfaceView.clearChildSurfacePackage()`;
3. освободить локальный `SurfacePackage`, если host его держит;
4. повторно вызвать `create_surface`;
5. заново передать новый `SurfacePackage` в `setChildSurfacePackage(...)`.

Provider не пытается восстанавливать старый instance после смерти процесса. После такого события нужно всегда создавать новый renderer.

## Маршрут

Renderer-процесс принимает маршрут через broadcast:
- `com.yandex.ROUTE_TELEMETRY`
- `com.yandex.ROUTE_STATE`

Обработка реализована в [`app/src/main/java/com/g992/turnsignalstest/RouteTelemetry.kt`](app/src/main/java/com/g992/turnsignalstest/RouteTelemetry.kt).

Поведение:
- если пришла телеметрия маршрута, surface-renderer рисует маршрут и пробки;
- если телеметрии нет, renderer рисует только карту и позицию;
- на `ARRIVED` и `CANCELLED` маршрут убирается;
- `BUILT` не очищает геометрию маршрута.

## Оповещение host о состоянии маршрута

Есть два механизма.

Синхронный запрос:
- `get_route_status`

Асинхронное оповещение:

```text
action = com.g992.turnsignalstest.ROUTE_STATUS_CHANGED
```

Extras:
- `has_route: Boolean`
- `route_built: Boolean`
- `route_state: String?`

Обычно host делает так:
- после `create_surface` сразу читает `get_route_status`;
- дальше слушает `ROUTE_STATUS_CHANGED` для обновления своего UI.

## Минимальный пример host-кода

Ниже схема для host-приложения, которое вставляет renderer в свой `SurfaceView`.

```kotlin
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

class HostActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private var surfacePackage: SurfaceControlViewHost.SurfacePackage? = null

    private val routeStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val hasRoute = intent.getBooleanExtra("has_route", false)
            val routeBuilt = intent.getBooleanExtra("route_built", false)
            val routeState = intent.getStringExtra("route_state")
            // обновить свой UI
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(
            FrameLayout(this).apply {
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(800, 800),
                )
            },
        )

        surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    attachRenderer(surfaceView.width, surfaceView.height)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    resizeRenderer(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    releaseRenderer()
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.g992.turnsignalstest.ROUTE_STATUS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(routeStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(routeStatusReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(routeStatusReceiver)
        super.onStop()
    }

    private fun attachRenderer(width: Int, height: Int) {
        val extras = Bundle().apply {
            putBinder("hostToken", surfaceView.hostToken)
            putInt("displayId", display?.displayId ?: Display.DEFAULT_DISPLAY)
            putInt("width", width.coerceAtLeast(1))
            putInt("height", height.coerceAtLeast(1))
        }

        val result = contentResolver.call(
            "content://com.g992.turnsignalstest.render",
            "create_surface",
            null,
            extras,
        ) ?: error("Renderer did not return a result")

        check(result.getBoolean("ok")) {
            result.getString("error") ?: "Unknown renderer error"
        }

        surfacePackage = result.getParcelable(
            "surfacePackage",
            SurfaceControlViewHost.SurfacePackage::class.java,
        )

        if (surfacePackage == null) {
            error("Renderer returned empty SurfacePackage")
        }

        surfaceView.setChildSurfacePackage(surfacePackage)

        val hasRoute = result.getBoolean("hasRoute")
        val routeBuilt = result.getBoolean("routeBuilt")
        val routeState = result.getString("routeState")
        val replacedExisting = result.getBoolean("replacedExisting")
    }

    private fun resizeRenderer(width: Int, height: Int) {
        val result = contentResolver.call(
            "content://com.g992.turnsignalstest.render",
            "resize_surface",
            null,
            Bundle().apply {
                putInt("width", width.coerceAtLeast(1))
                putInt("height", height.coerceAtLeast(1))
            },
        ) ?: error("resize_surface returned null")

        check(result.getBoolean("ok")) {
            result.getString("error") ?: "resize_surface failed"
        }
    }

    private fun releaseRenderer() {
        surfaceView.clearChildSurfacePackage()
        surfacePackage?.release()
        surfacePackage = null

        val result = contentResolver.call(
            "content://com.g992.turnsignalstest.render",
            "release_surface",
            null,
            null,
        ) ?: return

        check(result.getBoolean("ok")) {
            result.getString("error") ?: "release_surface failed"
        }

        val released = result.getBoolean("released")
    }

    private fun queryProtocol() {
        val result = contentResolver.call(
            "content://com.g992.turnsignalstest.render",
            "get_protocol_info",
            null,
            null,
        ) ?: return

        check(result.getBoolean("ok"))
        val version = result.getInt("protocolVersion")
        val features = result.getStringArrayList("features").orEmpty()
    }

    private fun queryRouteStatus() {
        val result = contentResolver.call(
            "content://com.g992.turnsignalstest.render",
            "get_route_status",
            null,
            null,
        ) ?: return

        check(result.getBoolean("ok"))
        val hasRoute = result.getBoolean("hasRoute")
        val routeBuilt = result.getBoolean("routeBuilt")
        val routeState = result.getString("routeState")
    }

    override fun onDestroy() {
        releaseRenderer()
        super.onDestroy()
    }
}
```

## Практические правила для host

- всегда иметь у себя логику повторного `create_surface`, если renderer умер;
- после `surfaceDestroyed` освобождать renderer через `release_surface`;
- не передавать `0x0`, даже если provider умеет внутренне зажать размер до `1x1`;
- после `create_surface` сразу читать `hasRoute`, `routeBuilt` и `routeState`;
- при расширении контракта сначала вызывать `get_protocol_info`;
- не рассчитывать на ownership текущего renderer по host: новый `create_surface` всегда перезаписывает singleton instance в provider.

## Сборка

Текущий APK:
- [`app/build/outputs/apk/debug/app-debug.apk`](app/build/outputs/apk/debug/app-debug.apk)
