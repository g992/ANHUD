# Мини-карта Яндекс Навигатора (YN 30.3.0, мод для ANHUD)

Источник: `YN_30.3.0_anhud_signed.apk` (`ru.yandex.yandexnavi`, versionName 30.3.0, targetSdk 29).
Весь код мини-карты лежит в `classes19.dex`, класс `ru.yandex.yandexnavi.ui.util.MinimapBroadcaster`
с вложенными `EnableReceiver`, `CaptureRunnable`, `JpegRunnable`, `LocListener`. Лог-тег `MhudMinimap`,
строка версии в логе: `offscreen map started v70.157 T75 150ms/8fps`.

Ниже перечислены **все** строки `minimap_*` / `MINIMAP*` из всех 19 dex: других extras и действий в APK нет.

## 1. Конвейер

1. **Инициализация.** `MapkitModule…safeProvideOnMainThread$1` и `ym` (classes17) вызывают
   `MinimapBroadcaster.onMapKitReady(app)`. Метод вызывает `restoreStyle()` и `ensureReceiver()`, а затем `postTick()`.
   Динамический `EnableReceiver` слушает `com.yandex.MINIMAP_ENABLE` и `com.yandex.MINIMAP_DISABLE`.
   На API 33+ используется флаг `2` (`RECEIVER_EXPORTED`). Пока MapKit в процессе ЯН не поднят, команды теряются.
2. **Маршрут.** `LaneSignListener.attachDrivingRoute()` и `sendLaneSignClear()` вызывают
   `MinimapBroadcaster.onRouteChanged()` (последний ещё вызывает `hideRouteLine()`). Если мини-карта выключена,
   хотя бы один адресат установлен и условие idle не выполняется, `onRouteChanged` **сам вызывает `setEnabled(true)`**.
   Активный маршрут берётся из `LaneSignListener.peekLiveDrivingRoute()`.
3. **Окно.** `ensureStarted()` создаёт `MapKitFactory.createOffscreenMapWindow(w, h)`, но только если размер уже
   задан (`sCaptureW/H > 0`). Дальше настраивается карта: `setMaxFps(8)`, `MapType.VECTOR_MAP`,
   `setNightModeEnabled(true)`, `MapMode.DRIVING`, `setAwesomeModelsEnabled(false)`, `setModelsEnabled(false)`,
   `setIndoorEnabled(false)`, `setRoads3dEnabled(false)`, `setRoads3dCastShadowEnabled(false)`,
   `setExtendedVisibleObjectsEnabled(false)`, `setDiscoveryModeEnabled(false)`, затем `applyMapStyle()`.
   После этого добавляется placemark курсора, собственный `LocationManager` (`UseInBackground.ALLOW`, `Purpose.GENERAL`)
   и вызываются `applyViewMode()` и `updateRouteLine()`.
4. **Цикл** (`tick()` на main looper, `postDelayed` 150 мс):
   heartbeat → idle-проверка → `ensureStarted()` → `captureOnce()`.
   `captureOnce()` первые 800 мс после старта окна ничего не делает. Потом он ставит фокус, обновляет линию маршрута,
   берёт позицию и курс из маршрута (`syncCameraFromRoute`), пересчитывает автозум и вызывает
   `map.move(CameraPosition(point, sZoom, sHeading, sTilt))`. Последний шаг — `OffscreenMapWindow.captureScreenshot()`.
5. **Отправка.** `sendBitmapAsync()` отбрасывает кадр, если с прошлой отправки прошло меньше 150 мс.
   Пока предыдущий JPEG кодируется (`sJpegBusy`), снимок не делается. Кодирование идёт в потоке `MhudMinimapJpeg`
   (`JpegRunnable` → `sendBitmap`, `JPEG quality 60`).

Камера: точка — текущая позиция на маршруте (`PolylineUtils.pointByPolylinePosition`); без маршрута — GPS.
Азимут — направление маршрута на 3 м вперёд; без маршрута — `location.heading`. Карта всегда «по курсу».
Фокус: `setFocusPoint(0.5·w, 0.86·h)` + `PointOfView.SCREEN_CENTER`, то есть машина у нижнего края (86 % высоты).

## 2. Входящие команды

### `com.yandex.MINIMAP_ENABLE` — все extras (`EnableReceiver.onReceive`)

Порядок обработки: labels → view/roads → zoom → overlay → route → hide_on_route_end → size → `setEnabled(true)`.

| Extra | Тип | Если не передан | Диапазон / обработка | Эффект (что дёргает) | Когда применяется |
|---|---|---|---|---|---|
| `minimap_width` | int | 0 → размер не меняется | применяется только если **оба** > 0; 96…1920, вниз до кратного 8 | `setCaptureSize`: размер `OffscreenMapWindow`. При изменении: `persistStyle()` + `dropOffscreen()`, окно пересоздаётся (снова прогрев 800 мс) | живое обновление |
| `minimap_height` | int | 0 | то же | то же | живое |
| `minimap_view` | int | **0 (сброс при каждом ENABLE)** | 0, 1, 2, 3, любое другое | `setViewMode` → `applyViewMode()`, см. таблицу режимов ниже. `3` = `setRoadsOnly(true)` + режим `1` | живое; при том же значении `return` без переприменения |
| `minimap_zoom` | float | **0 (сброс)** | режим 0: без ограничений, `0` = 16. Режимы 1/2/≥4: база автозума, итог 12.5…18.5 | `setUserZoom` → `applyViewMode()`; зум в `CameraPosition` | живое; зум сглаживается на 35 % за тик |
| `minimap_overlay` | float | **1.0 (сброс)** | 0.25…2.5 (`overlayScale()`) | `IconStyle.setScale()` курсора-стрелки | живое (`applyOverlaySizes`) |
| `minimap_route` | float | **1.0 (сброс)** | 0.25…2.5 (`routeScale()`); толщина `max(3, 10·k)` px, то есть 3…25 | `PolylineMapObject.setStrokeWidth()` линии маршрута | живое |
| `minimap_labels` | bool | **остаётся прежним** (`hasExtra`) | — | `false` → `sHideLabels=true` → правило `"elements":"label"` off; в обычном режиме ещё скрываются `poi` и `transit`. См. §3 | живое (`applyViewMode` → `applyMapStyle`) |
| `minimap_roads` | bool | **остаётся прежним** | игнорируется, если `minimap_view=3` | `true` → «только дороги»: стиль скрывает все слои, кроме дорог; `setPoiLimit(0)`; `setBuildingsHeightScale(0,0)`. См. §3 | живое |
| `minimap_hide_on_route_end` | bool | **остаётся прежним** | — | `true`: без маршрута цикл останавливается и уходит один idle-broadcast | живое |

Пустой `ENABLE` (без extras) возвращает view=0, zoom=0, overlay=1, route=1, а labels, roads, hide и размер оставляет прежними.

### Режимы `minimap_view` (`applyViewMode`)

| Значение | `set2DMode` | Roads3d | Высота зданий | Наклон (tilt) | Зум | Автозум |
|---|---|---|---|---|---|---|
| `0` (по умолчанию) | false | on, без теней | 0.2 | 40° | фикс. 16 или `minimap_zoom` | нет |
| `1` | true | off | 0 | 0° | база 15.5 | да |
| `2` | true | off | 0 | 0° | база 13.5 | да |
| `3` | → `roads=true` + режим `1` | | | | | |
| **любое другое** (`4`, `5`, `-1`…) | false | on, без теней | **1.0 (полная)** | **72°** | база **17.5** | да |

Последняя строка — недокументированный «перспективный» 3D-режим (ветка `else` в `applyViewMode`).
Ветка `i == 3` внутри `applyViewMode` (2D, база 15) через интент недостижима: ресивер превращает 3 в 1.
`sLookAhead` (180/0/40) записывается, но нигде не используется, потому что `cameraTarget()` возвращает точку как есть.

**Автозум** (`applyAutoZoom`, режимы ≠ 0):
`v = clamp(speed·3.6, 15, 160)` км/ч; `z = base − log2(v/60)·1.15`.
Если `minimap_zoom ≠ 0`, роль базы играет `minimap_zoom` (`z += uzoom − base`), и тогда на малой скорости зум может
подняться **выше** базы (до +2.3 при 15 км/ч). Если `minimap_zoom = 0`, `z ≤ base`. Итог ограничивается 12.5…18.5,
сглаживание `sZoom += (z − sZoom)·0.35` каждые 150 мс.
Пример при `uzoom=15`: 15 км/ч → 17.3, 60 → 15, 120 → 13.85, 160 → 13.4.

### `com.yandex.MINIMAP_DISABLE`

- Необязательный extra `minimap_hide_on_route_end` (bool, применяется только если передан).
- `setEnabled(false)` → `stopLoop()`: снимает tick, отписывает `LocationManager` (`unsubscribe` + `suspend`)
  и вызывает `dropOffscreen()`. Idle-broadcast при этом **не отправляется**: получатель сам гасит кадр.

Других входящих действий (style, update, layers) нет.

## 3. Слои и стиль карты

**Отдельного extra для слоёв (пробки, POI, камеры, дорожные события, тема) в этом APK нет.**
Слоями управляют `minimap_roads`, `minimap_labels` и `minimap_view` через `Map.setMapStyle(JSON)` в `applyMapStyle()`.
Перед каждой установкой вызывается `map.resetMapStyles()`.

| Режим | Что скрыто (`stylers.visibility = off`) |
|---|---|
| обычный, подписи вкл. | теги `vegetation, park, cemetery, landcover, landscape`; тег `path` (пешеходные дорожки) |
| обычный, `minimap_labels=false` | то же + все `elements: label` + теги `poi, transit` |
| `minimap_roads=true`, подписи вкл. | теги `vegetation, park, cemetery, landcover, landscape, water, building, poi, transit, admin, land` + `setPoiLimit(0)` + здания высотой 0 |
| `minimap_roads=true`, `minimap_labels=false` | то же + все `elements: label` |

Остальное зашито и extras не управляется:

- **Ночная тема всегда включена** (`setNightModeEnabled(true)`), дневной вариант не предусмотрен.
- **Слой пробок на карте не включён** (`TrafficLayer` не создаётся). Пробки видны только как раскраска линии маршрута:
  `RouteHelper.updatePolyline(line, route, JamStyle, true)`, цвета из `ensureJamStyle()`, альфа `0xE6`.
  FREE `#82EA0E`, LIGHT `#FFFF41`, HARD `#FF5413`, VERY_HARD `#932100`, BLOCKED `#1A1A1A`,
  UNKNOWN `#A0A0A0` (для маршрута, построенного офлайн, — `#177EE6`).
- Пройденная часть маршрута скрыта (`hidePassed`, +5 м вперёд). Перекрытые участки: `setOverlappedRouteOpacity(0)`.
  Стиль линии: `gradientLength 8`, `turnRadius 0`, `innerOutline off`, zIndex 100.
- 3D-модели, «awesome»-модели, indoor, discovery mode и extended visible objects выключены. Дорожных событий и камер нет.
- POI: в обычном режиме `setPoiLimit(null)`, то есть стандартный лимит MapKit.
- **Курсор**: всегда жёлтая стрелка 56×56 `#FFCC00` с обводкой `#1A1A1A` (`createArrowBitmap`), не плоская,
  `NO_ROTATION`, zIndex 250. Выбранная в ЯН иконка машины не используется: `applyCursorModel` из приложения не вызывается.

## 4. Исходящий broadcast `com.yandex.MINIMAP`

- Отправляется адресно (`setPackage`) каждому установленному пакету из `TARGET_PACKAGES`:
  `plus.monjaro`, `ack48.monjarodev.mhud`, `ack48.monjarodev.mhud.dev`, `com.g992.anhud`.
  При targetSdk 29 ограничение видимости пакетов не действует.
- Флаги `0x10000020` (`FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES`).
- **Кадр** (`sendBitmap`): `minimap_jpeg` (`byte[]`, JPEG q=60, размер = окно), `minimap_has_route`
  (`peekLiveDrivingRoute() != null`), `minimap_src` (`"ru.yandex.yandexnavi"`).
- **Idle** (`notifyMinimapIdle`, один раз за период простоя): `minimap_has_route=false`, `minimap_src`, без JPEG.
- Частота: тик 150 мс, кадр не чаще 1 раза в 150 мс и только когда JPEG-поток свободен, то есть ≤ ~6.6 к/с
  (MapKit ограничен 8 FPS). Кадры шлются **всегда**, даже стоя на месте: `shouldSendFrame()` — мёртвый код.
- `minimap_bitmap` объявлен как константа, но не используется.

## 5. Подводные камни

1. **Heartbeat 20 с.** `sLastEnableAt` обновляется только `setEnabled(true)` при **отсутствии** idle.
   Если `tick()` видит, что прошло больше 20 000 мс, он пишет «heartbeat stale -> disable». ANHUD шлёт ENABLE раз в 5 с.
2. **Задержка после idle.** При idle (`hide_on_route_end` и нет маршрута) ENABLE не обновляет `sLastEnableAt`,
   а `stopLoop()` не сбрасывает `sEnabled`. Когда маршрут появляется, первый tick из `onRouteChanged` может сразу
   отключить карту как «stale», и запуск произойдёт только на следующем ENABLE (у ANHUD — до 5 с).
3. **ENABLE сбрасывает не всё.** `view`, `zoom`, `overlay` и `route` получают значения по умолчанию, если их нет в интенте.
   `labels`, `roads` и `hide_on_route_end` остаются прежними («липкие»). Комментарий в
   `HudOverlayController.sendMinimapEnable` («resets every style extra») поэтому неточен.
4. **`minimap_view=3` включает `roads` навсегда.** Следующий ENABLE с `view=1` без `minimap_roads=false` оставит режим
   «только дороги». Поэтому `minimap_roads` нужно всегда передавать явно.
5. **Настройки не переживают перезапуск ЯН.** `persistStyle()` пишет SharedPreferences `mhud_minimap`
   (`roads`, `hide_labels`, `hide_idle`, `view`, `cw`, `ch`, `overlay`, `route`, `uzoom`). Но `restoreStyle()` читает
   их только при `sSizeReady == true`, а при холодном старте это всегда `false` (проверено в smali). После рестарта
   действуют значения по умолчанию: подписи вкл., roads выкл., hide выкл., view 0. Размер тоже не восстанавливается,
   поэтому без ENABLE с размерами окно не создаётся.
6. **Автозапуск без ENABLE.** `onRouteChanged()` включает захват сам. Если размер в этом процессе ещё не задан,
   окна не будет, и через 20 с сработает stale.
7. **Типы extras.** `zoom`, `overlay` и `route` читаются через `getFloatExtra`. Значения `int` или `double` будут
   проигнорированы: подставится значение по умолчанию. В adb используйте `--ef`.
8. Смена размера, `labels`, `roads`, `view` или `zoom` переприменяет `applyViewMode()` и сбрасывает текущий зум к базе.
   Смена размера ещё и пересоздаёт окно с прогревом 800 мс.
9. При 1920×1920 JPEG может подходить к лимиту Binder (~1 МБ на транзакцию); безопасно держать окно ≤ 800 px.

## 6. Что ANHUD уже использует / что можно добавить

Сейчас `HudOverlayController.requestMinimap()` / `sendMinimapEnable()` отправляют `minimap_width`, `minimap_height`
(уже ограничены 96…1920 и кратны 8), `minimap_zoom` (настройка 12…17.5, по умолчанию 15) и
`minimap_hide_on_route_end=true`. Heartbeat каждые 5 с, DISABLE при скрытии блока. Приём: `NavigationReceiver` →
`YandexVisualStore.acceptMinimap()`, кадры без маршрута отбрасываются.

Отсюда следует, что ANHUD всегда работает в режиме `view=0` (3D, 40°, **фиксированный** зум без автозума).

Не используются, можно добавить в настройки:

| Extra | Идея для UI ANHUD |
|---|---|
| `minimap_view` 0/1/2/≥4 | «Вид мини-карты»: 3D, 2D-близко, 2D-далеко, перспектива 72° (передавать, например, `4`) |
| `minimap_roads` | «Только дороги» (минималистичная карта для HUD). Передавать всегда явно |
| `minimap_labels` | «Подписи и POI». Передавать всегда явно |
| `minimap_overlay` | «Размер курсора» 0.25…2.5 |
| `minimap_route` | «Толщина маршрута» 0.25…2.5 (3…25 px) |
| `minimap_zoom` в 2D-режимах | при выборе 1/2/≥4 это уже база автозума, итог 12.5…18.5 |

Цвета, тема (только ночная), слой пробок, курсор, FPS и качество JPEG без пересборки мода не меняются.

### Команды для проверки (ЯН запущен, MapKit инициализирован, есть маршрут)

```bash
# База: 3D, фикс. зум 15, полный набор флагов (всегда передавать roads/labels явно)
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_ENABLE \
  --ei minimap_width 480 --ei minimap_height 480 --ef minimap_zoom 15 \
  --ei minimap_view 0 --ez minimap_roads false --ez minimap_labels true \
  --ef minimap_overlay 1.0 --ef minimap_route 1.0 --ez minimap_hide_on_route_end true

# 2D с автозумом (база 15.5), без подписей и POI
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_ENABLE \
  --ei minimap_width 480 --ei minimap_height 480 --ei minimap_view 1 --ef minimap_zoom 0 \
  --ez minimap_roads false --ez minimap_labels false

# «Только дороги» + толстый маршрут + крупный курсор
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_ENABLE \
  --ei minimap_width 480 --ei minimap_height 480 --ei minimap_view 1 \
  --ez minimap_roads true --ez minimap_labels false --ef minimap_route 1.8 --ef minimap_overlay 1.5

# Скрытый перспективный режим: tilt 72°, полная высота зданий, база 17.5
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_ENABLE \
  --ei minimap_width 480 --ei minimap_height 320 --ei minimap_view 4 --ez minimap_roads false

# Выключить
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_DISABLE

# Лог мода
adb logcat -s MhudMinimap
```

Без повторной команды захват остановится через 20 с. Для долгого теста повторяйте ENABLE, например
`while true; do adb shell am broadcast …; sleep 5; done`.
