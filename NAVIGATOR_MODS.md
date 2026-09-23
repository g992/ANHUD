# Мега-ЯН 30.3.0 vs ANHUD: интенты навигатора

Источник: `1.3. Мега-ЯН v.30.3.0_F100_kill.apk` (`ru.yandex.yandexnavi` 30.3.0 / 739564630, targetSdk понижен до 29, модовые dex собраны 05–09.09.2026).
Сравнение с тем, что читает ANHUD (`NavigationReceiver.kt`, `MapRouteTelemetry.kt`) и с baseline `MODIFICATIONS.md` (YN 26.7.2).

## TL;DR

- **Мини-карты в этой сборке нет.** Ни Bitmap-broadcast'а карты, ни Surface/VirtualDisplay/SharedMemory/ContentProvider/сокета в модовом коде. Проверено grep'ом по всем 20 dex и jadx-исходникам.
- Единственный канал «карта наружу» — **стоковый** `NavigationCarAppService` (Android Auto / Car App Library, категории `NAVIGATION` + `FEATURE_CLUSTER`). Хост отдаёт Surface, но allowlist пропускает только Google gearhead/templates host или приложение с системным `android.car.permission.TEMPLATE_RENDERER`. ANHUD хостом стать не может.
- Мод — другая линия, не та, что в `MODIFICATIONS.md`: старый `YandexBroadcastHelper` + новый `JAM_IMAGE` + порт MirrorHUD/Monjaro (полосы, светофоры), почти всё адресно для MHUD-пакетов.
- Пропали: `com.yandex.TRAFFICLIGHT`, `NAV_ACTIVE`, `ROADCAMERA`, extra `maneuver_type`.
- `com.g992.anhud` в APK не упоминается.

## Где модовый код

| dex | Классы |
|---|---|
| `classes18` | `ru.YandexBroadcastHelper`, `ru.TLListener`, `ru.DensitySetting`, `ru.DensityLevel`, `ru.NavChannelSetting`, `ru.zoomdpi` |
| `classes19` | `ru.yandex.yandexnavi.ui.util.LaneSignListener` (тег `NavLaneSign`, «MirrorHUD patch»), `NotificationLanesBroadcaster` (`NavNotifLanes`) |
| `classes20` | `bin.mt.signature.KillerApplication` (подмена подписи, «_kill»), `org.lsposed.hiddenapibypass` |

Врезки: `ContextManeuverView.setupRegularManeuver` → MANEUVER/NIXT/NEXTSTREET; `ContextEtaView`, `SpeedLimitView` → ETA/лимит; `guidance/jams/ProgressView.setProgress` → JAM_IMAGE; `mapkit/.../DistancesProviderImpl.start/stop` → `TLListener` + `LaneSignListener` на Windshield API.
Новых компонентов в манифесте нет, входящих receiver'ов мод не регистрирует.

## Сводная таблица

| Action | Мега-ЯН 30.3.0 | ANHUD | Статус |
|---|---|---|---|
| `com.yandex.MANEUVER` | `maneuver_bitmap` (Bitmap) | `maneuver_bitmap`, `maneuver_type` | ✅ работает; `maneuver_type` больше не приходит → всегда fallback на `ManeuverRecognition.analyze` |
| `com.yandex.NIXT` | `next_text` | `next_text` | ✅; формат `"300  м"` (двойной пробел), значение отстаёт на одно обновление |
| `com.yandex.NEXTSTREET` | `next_street` | `next_street` | ✅ |
| `com.yandex.SPEEDLIMIT` | `speedlimit_text` | то же | ✅ |
| `com.yandex.ARRIVAL` / `DISTANCE` / `TIME` | `Arrival_text` / `Distance_text` / `Time_text` | то же | ✅ |
| `com.yandex.JAM_IMAGE` | `jam_bitmap` (ARGB_8888, размер ProgressView, ≤1/с) | — | 🆕 не слушаем |
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

**Итог по карте:** на этой сборке встроенная MapLibre-карта ANHUD останется без маршрута. Маршрутные интенты (`ROUTE_POLYLINE`/`ROUTE_TELEMETRY`/…) шлёт какая-то другая сборка — не эта.

## Детали новых интентов

### `com.yandex.JAM_IMAGE`
- Extra: `jam_bitmap` — картинка полосы пробок (прогресс-бар маршрута), ARGB_8888.
- Отправка из `ProgressView.setProgress`, троттлинг 1 с, только если view видима и размер > 0. Без флагов → нужен динамический receiver с `RECEIVER_EXPORTED`.

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

Тумблеров для broadcast'ов нет — шлются всегда.

## Баги мода
- `LaneSignListener.attachGuidance` вызывает несуществующий `SpeedLimitBroadcaster.attach()` → `NoClassDefFoundError` глушится `catch Throwable`, `attachDrivingRoute` по этому пути не вызывается.
- Светофоры уходят дважды (глобально от `TLListener` и адресно от `LaneSignListener`).

## Что делать в ANHUD

1. ~~**Светофоры**~~ — сделано: пачки по `tl_position`, стрелка по `tl_arrow`, пустой интент очищает.
2. **Пробки:** опционально принять `com.yandex.JAM_IMAGE` (`jam_bitmap`) как готовую картинку для HUD-блока.
3. **Манёвр:** `maneuver_type` не придёт — убедиться, что `ManeuverRecognition` покрывает все иконки.
4. **NIXT:** парсер должен терпеть двойной пробел (`normalizeText` уже схлопывает).
5. **Признак ведения:** NAV_ACTIVE нет; опираться на таймаут `touchNavigatorIntentTimeout` + уведомление навигатора.
6. **Маршрут/карта:** с этой сборкой ROUTE_* не приходят. Нужна сборка с этими патчами или свой патч.

## Мини-карта: как проверить новую сборку

Если появится APK, где мини-карта есть:
```bash
apktool d -f -o out app.apk
grep -rhoE 'const-string [vp][0-9]+, "(com\.yandex|plus\.monjaro)\.[A-Z_]+"' out | sort -u
grep -rlE 'createVirtualDisplay|ImageReader|SharedMemory|HardwareBuffer|Bitmap;->compress' out/smali_classes1[8-9]* out/smali_classes2*
diff <(grep -oE 'android:name="[^"]+"' old/AndroidManifest.xml | sort) <(grep -oE 'android:name="[^"]+"' out/AndroidManifest.xml | sort)
```
Вероятные варианты и приём:
- **Bitmap в broadcast** (как JAM_IMAGE): принимать тем же receiver'ом. Лимит Binder ~1 МБ → маленькое разрешение, низкий FPS (ARGB 400×400 ≈ 640 КБ).
- **Surface через bound service / Presentation на VirtualDisplay**: ANHUD создаёт `SurfaceTexture`/`TextureView` в оверлее и передаёт `Surface` в сервис навигатора. Нужны имя сервиса и AIDL/Messenger-протокол.
- **Car App cluster**: требует системного разрешения — нереально без root/системной подписи.
