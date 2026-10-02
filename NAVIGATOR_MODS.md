# ЯН 30.3.0 ANHUD vs ANHUD: интенты навигатора

Источник: `YN_30.3.0_anhud_signed.apk` из `~/Downloads`, SHA-256 `c3a49ff099f5a6ce47c1944059c69206a17c7173fe3a8ff20528ef8e1f5243fb` (`ru.yandex.yandexnavi` 30.3.0 / 739564630, targetSdk 29). Проверены smali APK и текущий приёмник ANHUD. Проверка статическая, без запуска на устройстве. Этот APK подписан (v2/v3). Старые выводы о недоступности адресных потоков относились к другой сборке.

## TL;DR

- **Мини-карта появилась:** `MinimapBroadcaster` создаёт отдельную `OffscreenMapWindow` MapKit, снимает скриншоты и отправляет JPEG в `com.yandex.MINIMAP`. Управляется интентами `com.yandex.MINIMAP_ENABLE` / `com.yandex.MINIMAP_DISABLE`.
- **ANHUD включён в адресаты:** в массивах `MinimapBroadcaster`, `LaneSignListener` и `NotificationLanesBroadcaster` добавлен `com.g992.anhud`.
- Стоковый `NavigationCarAppService` остаётся отдельным каналом Android Auto / Car App Library с ограничением на допустимый хост. Новый `MINIMAP` использует обычный broadcast JPEG и к этому каналу не относится.
- Мод — другая линия, не та, что в `MODIFICATIONS.md`: `YandexBroadcastHelper` + `JAM_IMAGE` + порт MirrorHUD/Monjaro (полосы, светофоры) + поток мини-карты, адресованный также ANHUD.
- Пропали: `com.yandex.TRAFFICLIGHT`, `NAV_ACTIVE`, `ROADCAMERA`, extra `maneuver_type`.
- В `LaneSignListener.sendNavigationEndedBroadcast` отправляется `source=yandex_lane_clear` и `route_gone=true` даже при очистке полос после развязки. По этому сочетанию нельзя завершать весь маршрут.

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
| `com.yandex.JAM_IMAGE` | `jam_bitmap` (ARGB_8888, размер ProgressView, ≤1/с) | `YandexVisualStore` | ✅ полоска под мини-картой; устройство не проверено |
| `com.yandex.MINIMAP_ENABLE` / `com.yandex.MINIMAP_DISABLE` | входящие команды для `MinimapBroadcaster` | `HudOverlayController` | ✅ команда с размерами при показе блока |
| `com.yandex.MINIMAP` | `minimap_jpeg` (`byte[]` JPEG), `minimap_has_route` (`bool`), `minimap_src` (`String`); при простое без JPEG | `YandexVisualStore` | ✅ ANHUD в адресатах; доставка на устройстве не проверена |
| `plus.monjaro.TRAFFIC_LIGHT_UPDATE` (глобально) | `tl_color`, `tl_countdown`, `tl_arrow`, `tl_id`, `tl_position`, `tl_source` | `NavigationReceiver` + `WindshieldTrafficLightBatcher` | ✅ принимается; замена `com.yandex.TRAFFICLIGHT` |
| `com.yandex.TRAFFICLIGHT` | — | `traffic_light_id`, `is_visible`, `signal_color`, `countdown`, `timestamp`, `arrow_bitmap`, `arrow_direction` | ❌ удалено, блок светофоров мёртв |
| `com.yandex.ROADCAMERA` | — | `camera_id`, `distance_text`, `camera_icon` | ❌ удалено |
| `com.yandex.NAV_ACTIVE` | — | `is_active` (только лог) | ❌ удалено, у нас и так игнорируется |
| `com.yandex.TRIP_STATUS_BITMAP` | — | `trip_status_bitmap` | ❌ не шлёт |
| `com.yandex.ROUTE_POLYLINE` | — | `polyline_lats/lons`, `route_id`, … | ❌ не шлёт → нет маршрута на карте |
| `com.yandex.ROUTE_TELEMETRY` / `ROUTE_STATE` | — | `route_sampled`, `route_jams`, `route_lane_points`, `route_state` | ❌ не шлёт |
| `*.ROUTE_ALERTS` | — | `route_alerts` | ❌ не шлёт |
| `*.MANEUVER_BLOCK_BITMAP` | — | `maneuver_block_bitmap`, `lane_*` | ❌ не шлёт |
| `com.yandex.LANE_SIGN` / `LANES` / `LANE_DIST` / `LANES_BITMAP` / `LANES_BITMAP_CLEAR` | адресно получателям | `YandexVisualStore` | ✅ ANHUD в адресатах; доставка на устройстве не проверена |
| `plus.monjaro.NAVIGATION_ENDED` | адресно получателям | `NavigationReceiver` | ⚠️ `source=yandex_lane_clear` очищает только полосы |

Адресаты (`setPackage` на каждый): `plus.monjaro`, `ack48.monjarodev.mhud`, `ack48.monjarodev.mhud.dev`, `com.g992.anhud`.

**Итог по карте:** `ROUTE_POLYLINE`/`ROUTE_TELEMETRY` эта сборка не отправляет. ANHUD теперь принимает поток готовых JPEG-кадров Яндекс-карты; отображение на устройстве ещё требует проверки.

## Детали новых интентов

### `com.yandex.JAM_IMAGE`
- Extra: `jam_bitmap` — картинка полосы пробок (прогресс-бар маршрута), ARGB_8888.
- Отправка из `ProgressView.setProgress`, троттлинг 1 с, только если view видима и размер > 0. Без флагов → нужен динамический receiver с `RECEIVER_EXPORTED`.

### `com.yandex.MINIMAP_ENABLE` / `com.yandex.MINIMAP_DISABLE` → `com.yandex.MINIMAP`

- `MinimapBroadcaster.onMapKitReady` регистрирует динамический `EnableReceiver` на `ENABLE` и `DISABLE`. Пока процесс ЯН не инициализировал MapKit, команду принимать некому. На Android 13+ регистрация идёт с `RECEIVER_EXPORTED` (значение `2`); на более ранних версиях — без флага.
- `ENABLE` читает `minimap_width` и `minimap_height` (`int`, **нужно передать оба**): положительные значения ограничиваются 96–1920 px по каждой оси и округляются вниз до кратности 8. Без ранее заданных размеров `OffscreenMapWindow` не создаётся. Кадр снимается после прогрева 800 мс.
- Остальные extras команды `ENABLE`: `minimap_labels` (`bool`, показывать подписи); `minimap_roads` (`bool`, оставить дороги); `minimap_view` (`int`: `0` — объёмный вид, `1`/`2` — 2D с разным масштабом, `3` принудительно включает дороги и режим `1`); `minimap_zoom` (`float`, `0` — автоматический); `minimap_overlay` (`float`, размер указателя); `minimap_route` (`float`, толщина маршрута); `minimap_hide_on_route_end` (`bool`). `DISABLE` выключает захват; также принимает `minimap_hide_on_route_end`.
- Для каждого кадра используется `OffscreenMapWindow.captureScreenshot()`, JPEG quality `60`, затем `com.yandex.MINIMAP` с `minimap_jpeg` (`byte[]`), `minimap_has_route` (`bool`) и `minimap_src` (`String`, здесь `ru.yandex.yandexnavi`). Флаги интента `0x10000020`. Константа `minimap_bitmap` в классе объявлена, но в исходящий интент не записывается.
- Карта MapKit в этом APK настроена на максимум 8 FPS (`setMaxFps(8)`); реальную частоту и лимит Binder нужно проверить на устройстве.
- Если включено `minimap_hide_on_route_end` и маршрута нет, отправляется один `com.yandex.MINIMAP` с `minimap_has_route=false`, `minimap_src` и **без** `minimap_jpeg`; затем цикл останавливается. Получатель должен очистить устаревший кадр.
- Исходящие `MINIMAP` посылаются адресно, в том числе в `com.g992.anhud`.

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

### Полосы (включая ANHUD)
- `LANE_SIGN`: `lat`, `lon`, `lanes` (String), `dist_m` (double, опц.); до 5 развязок, разделитель `;`.
- `LANES`: `lane_data_v2`, опц. `dist`, `metrics`.
- `LANE_DIST`: `dist` (`"350"`, `"1,2"`), `metrics` (`" м"`/`" км"`), `dist_m`. Тик 1 с.
- `LANES_BITMAP`: `lanes_bitmap` (Bitmap). `LANES_BITMAP_CLEAR`: без extras.
- Формат полосы: `dir1,dir2:<highlighted|NONE>:<LaneKind>`, полосы через `|`. Пример: `LEFT90,STRAIGHT_AHEAD:STRAIGHT_AHEAD:PLAIN_LANE|STRAIGHT_AHEAD:NONE:BUS_LANE`.
- `com.g992.anhud` уже есть в массивах `LaneSignListener` и `NotificationLanesBroadcaster`.

### Настройки мода
| Пункт | Prefs | Ключ | Эффект |
|---|---|---|---|
| «DPI» | `zoomdpi_prefs` | `density_level` (float) | масштаб рендера MapKit, перезапуск процесса |
| «Аудиоканал навигации» | `nav_channel_prefs` | `nav_channel` (bool) | озвучка через usage NAVIGATION_GUIDANCE |

Остальные навигационные broadcast'ы шлются без отдельного переключателя. Мини-карта управляется `MINIMAP_ENABLE` / `MINIMAP_DISABLE`.

## Баги мода
- В этой версии `LaneSignListener.attachGuidance` вызывает `attachDrivingRoute` напрямую; описанный для прошлого APK вызов отсутствующего `SpeedLimitBroadcaster.attach()` больше не обнаружен.
- Светофоры уходят дважды (глобально от `TLListener` и адресно от `LaneSignListener`).
- Автоматический запуск `MinimapBroadcaster` зависит от `refreshLiveTargets`; ANHUD посылает прямой `MINIMAP_ENABLE` при включённом блоке и повторяет команду до получения кадра.

## Что делать в ANHUD

1. ~~**Светофоры**~~ — сделано: пачки по `tl_position`, стрелка по `tl_arrow`, пустой интент очищает.
2. ~~**Пробки**~~ — `JAM_IMAGE` показывается полосой под мини-картой; проверить на устройстве.
3. **Манёвр:** `maneuver_type` не придёт — убедиться, что `ManeuverRecognition` покрывает все иконки.
4. **NIXT:** парсер должен терпеть двойной пробел (`normalizeText` уже схлопывает).
5. **Признак ведения:** NAV_ACTIVE нет; опираться на таймаут `touchNavigatorIntentTimeout` + уведомление навигатора.
6. **Маршрут/карта:** `ROUTE_*` не приходят; старый приём отключён, блок карты переключён на JPEG-поток `MINIMAP`.
7. **Мини-карта ANHUD:** приём JPEG, очистка и команды `ENABLE`/`DISABLE` реализованы. Проверить на устройстве доставку, частоту, задержку, размер кадра и нагрузку.
