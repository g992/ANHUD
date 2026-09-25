# ЯН MonjaroMOD 30.3.0 M+mHUD v2 vs ANHUD: интенты навигатора

Источник: `ЯН_MonjaroMOD_v30.3.0_M+mHUD_v2.apk` из `~/Downloads`, SHA-256 `39167536646215bc53be94d489b3cc94a94727cf87f097c869b7d8e13a1f2e14` (`ru.yandex.yandexnavi` 30.3.0 / 739564630, targetSdk 29). Проверены smali APK и текущие приёмники ANHUD (`NavigationReceiver.kt`, `MapRouteTelemetry.kt`). Предыдущий разбор `1.3. Мега-ЯН v.30.3.0_F100_kill.apk` служит ориентиром; побайтового сравнения нет, поскольку того APK сейчас нет локально. Проверка статическая, без запуска на устройстве.

## TL;DR

- **Мини-карта появилась:** `MinimapBroadcaster` создаёт отдельную `OffscreenMapWindow` MapKit, снимает скриншоты и отправляет JPEG в `com.yandex.MINIMAP`. Управляется интентами `com.yandex.MINIMAP_ENABLE` / `com.yandex.MINIMAP_DISABLE`.
- **ANHUD пока не получит кадры:** отправитель вызывает `setPackage` только для `plus.monjaro`, `ack48.monjarodev.mhud` и `ack48.monjarodev.mhud.dev`. `com.g992.anhud` в списке нет; регистрация приёмника в ANHUD сама по себе недостаточна.
- Стоковый `NavigationCarAppService` остаётся отдельным каналом Android Auto / Car App Library с ограничением на допустимый хост. Новый `MINIMAP` использует обычный broadcast JPEG и к этому каналу не относится.
- Мод — другая линия, не та, что в `MODIFICATIONS.md`: старый `YandexBroadcastHelper` + `JAM_IMAGE` + порт MirrorHUD/Monjaro (полосы, светофоры) + поток мини-карты, адресованный MHUD-пакетам.
- Пропали: `com.yandex.TRAFFICLIGHT`, `NAV_ACTIVE`, `ROADCAMERA`, extra `maneuver_type`.
- `com.g992.anhud` в APK не упоминается.

## Где модовый код

| dex | Классы |
|---|---|
| `classes18` | `ru.YandexBroadcastHelper`, `ru.TLListener`, `ru.DensitySetting`, `ru.DensityLevel`, `ru.NavChannelSetting`, `ru.zoomdpi` |
| `classes19` | `ru.yandex.yandexnavi.ui.util.LaneSignListener`, `NotificationLanesBroadcaster`, `MinimapBroadcaster` и его `EnableReceiver` / `CaptureRunnable` / `JpegRunnable` / `LocListener` |

Врезки: `ContextManeuverView.setupRegularManeuver` → MANEUVER/NIXT/NEXTSTREET; `ContextEtaView`, `SpeedLimitView` → ETA/лимит; `guidance/jams/ProgressView.setProgress` → JAM_IMAGE; `mapkit/.../DistancesProviderImpl.start/stop` → `TLListener` + `LaneSignListener` на Windshield API. После инициализации MapKit вызывается `MinimapBroadcaster.onMapKitReady`, а при смене маршрута — `onRouteChanged`.
Нового статического receiver'а мини-карты в манифесте нет: `EnableReceiver` регистрируется динамически после инициализации MapKit.

## Сводная таблица

| Action | MonjaroMOD M+mHUD v2 | ANHUD | Статус по статическому анализу |
|---|---|---|---|
| `com.yandex.MANEUVER` | `maneuver_bitmap` (Bitmap) | `maneuver_bitmap`, `maneuver_type` | ✅ форматы совместимы; `maneuver_type` не приходит → fallback на `ManeuverRecognition.analyze` |
| `com.yandex.NIXT` | `next_text` | `next_text` | ✅; формат `"300  м"` (двойной пробел), значение отстаёт на одно обновление |
| `com.yandex.NEXTSTREET` | `next_street` | `next_street` | ✅ |
| `com.yandex.SPEEDLIMIT` | `speedlimit_text` | то же | ✅ |
| `com.yandex.ARRIVAL` / `DISTANCE` / `TIME` | `Arrival_text` / `Distance_text` / `Time_text` | то же | ✅ |
| `com.yandex.JAM_IMAGE` | `jam_bitmap` (ARGB_8888, размер ProgressView, ≤1/с) | — | 🆕 не слушаем |
| `com.yandex.MINIMAP_ENABLE` / `com.yandex.MINIMAP_DISABLE` | входящие команды для `MinimapBroadcaster` | — | ✅ ЯН принимает после инициализации MapKit; ANHUD их пока не отправляет |
| `com.yandex.MINIMAP` | `minimap_jpeg` (`byte[]` JPEG), `minimap_has_route` (`bool`), `minimap_src` (`String`); при простое без JPEG | — | 🔒 адресно трём MHUD-пакетам; ANHUD не получит |
| `plus.monjaro.TRAFFIC_LIGHT_UPDATE` (глобально) | `tl_color`, `tl_countdown`, `tl_arrow`, `tl_id`, `tl_position`, `tl_source` | `NavigationReceiver` + `WindshieldTrafficLightBatcher` | ✅ принимается; замена `com.yandex.TRAFFICLIGHT` |
| `com.yandex.TRAFFICLIGHT` | — | `traffic_light_id`, `is_visible`, `signal_color`, `countdown`, `timestamp`, `arrow_bitmap`, `arrow_direction` | ❌ удалено, блок светофоров мёртв |
| `com.yandex.ROADCAMERA` | — | `camera_id`, `distance_text`, `camera_icon` | ❌ удалено |
| `com.yandex.NAV_ACTIVE` | — | `is_active` (только лог) | ❌ удалено, у нас и так игнорируется |
| `com.yandex.TRIP_STATUS_BITMAP` | — | `trip_status_bitmap` | ❌ не шлёт |
| `com.yandex.ROUTE_POLYLINE` | — | `polyline_lats/lons`, `route_id`, … | ❌ не шлёт → нет маршрута на карте |
| `com.yandex.ROUTE_TELEMETRY` / `ROUTE_STATE` | — | `route_sampled`, `route_jams`, `route_lane_points`, `route_state` | ❌ не шлёт |
| `*.ROUTE_ALERTS` | — | `route_alerts` | ❌ не шлёт |
| `*.MANEUVER_BLOCK_BITMAP` | — | `maneuver_block_bitmap`, `lane_*` | ❌ не шлёт |
| `com.yandex.LANE_SIGN` / `LANES` / `LANE_DIST` / `LANES_BITMAP` / `LANES_BITMAP_CLEAR` | адресно MHUD | — | 🔒 до нас не дойдёт |
| `plus.monjaro.NAVIGATION_ENDED` | адресно MHUD | — | 🔒 |

MHUD-пакеты (`setPackage` на каждый): `plus.monjaro`, `ack48.monjarodev.mhud`, `ack48.monjarodev.mhud.dev`.

**Итог по карте:** встроенная MapLibre-карта ANHUD останется без маршрута: `ROUTE_POLYLINE`/`ROUTE_TELEMETRY` эта сборка не отправляет. Отдельный поток готовых кадров Яндекс-карты есть, но для ANHUD он недоступен до изменения адресатов в APK или другого согласованного способа передачи.

## Детали новых интентов

### `com.yandex.JAM_IMAGE`
- Extra: `jam_bitmap` — картинка полосы пробок (прогресс-бар маршрута), ARGB_8888.
- Отправка из `ProgressView.setProgress`, троттлинг 1 с, только если view видима и размер > 0. Без флагов → нужен динамический receiver с `RECEIVER_EXPORTED`.

### `com.yandex.MINIMAP_ENABLE` / `com.yandex.MINIMAP_DISABLE` → `com.yandex.MINIMAP`

- `MinimapBroadcaster.onMapKitReady` регистрирует динамический `EnableReceiver` на `ENABLE` и `DISABLE`. Пока процесс ЯН не инициализировал MapKit, команду принимать некому. На Android 13+ регистрация идёт с `RECEIVER_EXPORTED` (значение `2`); на более ранних версиях — без флага.
- `ENABLE` читает `minimap_width` и `minimap_height` (`int`, **нужно передать оба**): положительные значения ограничиваются 96–1920 px по каждой оси и округляются вниз до кратности 8. Без ранее заданных размеров `OffscreenMapWindow` не создаётся. Кадр снимается после прогрева 800 мс.
- Остальные extras команды `ENABLE`: `minimap_labels` (`bool`, показывать подписи); `minimap_roads` (`bool`, оставить дороги); `minimap_view` (`int`: `0` — объёмный вид, `1`/`2` — 2D с разным масштабом, `3` принудительно включает дороги и режим `1`); `minimap_zoom` (`float`, `0` — автоматический); `minimap_overlay` (`float`, размер указателя); `minimap_route` (`float`, толщина маршрута); `minimap_hide_on_route_end` (`bool`). `DISABLE` выключает захват; также принимает `minimap_hide_on_route_end`.
- Для каждого кадра используется `OffscreenMapWindow.captureScreenshot()`, JPEG quality `60`, затем `com.yandex.MINIMAP` с `minimap_jpeg` (`byte[]`), `minimap_has_route` (`bool`) и `minimap_src` (`String`, здесь `ru.yandex.yandexnavi`). Флаги интента `0x10000020`. Константа `minimap_bitmap` в классе объявлена, но в исходящий интент не записывается.
- Цикл запланирован каждые 33 мс, с одним JPEG worker и пропуском кадров при его занятости; это **верхняя частота попыток**, а не гарантированные 30 FPS. Карта MapKit настроена на максимум 15 FPS. Размер JPEG не ограничен проверкой перед `sendBroadcast`, поэтому большие кадры требуют проверки лимита Binder на устройстве.
- Если включено `minimap_hide_on_route_end` и маршрута нет, отправляется один `com.yandex.MINIMAP` с `minimap_has_route=false`, `minimap_src` и **без** `minimap_jpeg`; затем цикл останавливается. Получатель должен очистить устаревший кадр.
- Все исходящие `MINIMAP` посылаются отдельно через `setPackage` только установленным `plus.monjaro`, `ack48.monjarodev.mhud` и `ack48.monjarodev.mhud.dev`. В текущем APK нет отправки в `com.g992.anhud`.

Команда для проверки после запуска ЯН и инициализации MapKit (в текущем APK она не изменит адресатов кадров):

```bash
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_ENABLE --ei minimap_width 320 --ei minimap_height 320
adb shell am broadcast -p ru.yandex.yandexnavi -a com.yandex.MINIMAP_DISABLE
```

### `plus.monjaro.TRAFFIC_LIGHT_UPDATE` (глобальный, от `ru.TLListener`)
- Флаги `0x01000020` (INCLUDE_BACKGROUND | INCLUDE_STOPPED) — доходит и до manifest-receiver'а.
- Источник: Windshield `onTrafficLightsChanged` / `onTrafficLightsCountdownUpdated`. Один интент на светофор.

| Extra | Тип | Значение |
|---|---|---|
| `tl_color` | String | `RED` / `YELLOW` (включая RED_AND_YELLOW) / `GREEN` / `""` |
| `tl_countdown` | String | секунды или `""` |
| `tl_arrow` | String | `RouteDirectionArrow.name()`: `FORWARD` / `LEFT` / `RIGHT` / `UTURN_LEFT` |
| `tl_id` | **String** | id светофора из Windshield |
| `tl_position` | int | индекс в списке Windshield (0 — ближайший); светофор без сигнала пропускается, но индекс растёт |
| `tl_source` | String | `"yandex_windshield"` |

При каждом изменении и тике отсчёта уходит весь список пачкой, маркера конца пачки нет. ANHUD собирает пачку (`WindshieldTrafficLightBatcher`) и заменяет ею весь набор.
Пустой список светофоров → один интент со всеми полями `""` (= очистить).
Адресная копия для MHUD дополнительно несёт `tl_dist_m` (float, всегда -1.0) — дистанции нет.

### Полосы (только MHUD)
- `LANE_SIGN`: `lat`, `lon`, `lanes` (String), `dist_m` (double, опц.); до 5 развязок, разделитель `;`.
- `LANES`: `lane_data_v2`, опц. `dist`, `metrics`.
- `LANE_DIST`: `dist` (`"350"`, `"1,2"`), `metrics` (`" м"`/`" км"`), `dist_m`. Тик 1 с.
- `LANES_BITMAP`: `lanes_bitmap` (Bitmap). `LANES_BITMAP_CLEAR`: без extras.
- Формат полосы: `dir1,dir2:<highlighted|NONE>:<LaneKind>`, полосы через `|`. Пример: `LEFT90,STRAIGHT_AHEAD:STRAIGHT_AHEAD:PLAIN_LANE|STRAIGHT_AHEAD:NONE:BUS_LANE`.
- Получить можно только перепатчив массив пакетов в `<clinit>` `LaneSignListener.smali` и `NotificationLanesBroadcaster.smali` (добавить `com.g992.anhud`).

### Настройки мода
| Пункт | Prefs | Ключ | Эффект |
|---|---|---|---|
| «DPI» | `zoomdpi_prefs` | `density_level` (float) | масштаб рендера MapKit, перезапуск процесса |
| «Аудиоканал навигации» | `nav_channel_prefs` | `nav_channel` (bool) | озвучка через usage NAVIGATION_GUIDANCE |

Остальные навигационные broadcast'ы шлются без отдельного переключателя. Мини-карта управляется `MINIMAP_ENABLE` / `MINIMAP_DISABLE`.

## Баги мода
- В этой версии `LaneSignListener.attachGuidance` вызывает `attachDrivingRoute` напрямую; описанный для прошлого APK вызов отсутствующего `SpeedLimitBroadcaster.attach()` больше не обнаружен.
- Светофоры уходят дважды (глобально от `TLListener` и адресно от `LaneSignListener`).
- `MinimapBroadcaster.refreshLiveTargets` добавляет в `sLiveTargets` **неустановленные** MHUD-пакеты, хотя `anyTargetInstalled` трактует непустой список как наличие получателя. Если установлены все три пакета, автоматический запуск по смене маршрута не сработает; если не установлен ни один, запуск может происходить впустую. Прямой `MINIMAP_ENABLE` не зависит от этой проверки, но отправка кадров всё равно ограничена установленными MHUD-пакетами.

## Что делать в ANHUD

1. ~~**Светофоры**~~ — сделано: пачки по `tl_position`, стрелка по `tl_arrow`, пустой интент очищает.
2. **Пробки:** опционально принять `com.yandex.JAM_IMAGE` (`jam_bitmap`) как готовую картинку для HUD-блока.
3. **Манёвр:** `maneuver_type` не придёт — убедиться, что `ManeuverRecognition` покрывает все иконки.
4. **NIXT:** парсер должен терпеть двойной пробел (`normalizeText` уже схлопывает).
5. **Признак ведения:** NAV_ACTIVE нет; опираться на таймаут `touchNavigatorIntentTimeout` + уведомление навигатора.
6. **Маршрут/карта:** `ROUTE_*` не приходят; отдельный поток `MINIMAP` даёт готовую картинку Яндекс-карты, а не геометрию маршрута для MapLibre.
7. **Использовать мини-карту в ANHUD:** сначала обеспечить отправку `com.yandex.MINIMAP` в `com.g992.anhud` (например, патчем списка адресатов `TARGET_PACKAGES` с последующей пересборкой и подписью APK). Затем в ANHUD принимать `minimap_jpeg`, декодировать и показывать последний кадр в блоке карты; при `minimap_has_route=false` без JPEG очищать его. Отправлять `ENABLE` с размерами при включении блока и `DISABLE` при выключении. Проверить на устройстве доставку, частоту, задержку, размер кадра и нагрузку. Только после этого выбирать, заменять ли визуально текущую MapLibre-карту.
