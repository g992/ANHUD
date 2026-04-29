# Интеграция проекции карты

Гайд описывает перенос текущего функционала карты из ветки `mapper-check` в другое Android-приложение. Ориентир по ветке: `fbfd3ba map works, offline ok`.

Реальная текущая архитектура: карта встроена прямо в HUD через `HudMapController` и `MapView` MapLibre. Отдельного приложения-renderer или surface-provider в текущем варианте интеграции нет.

## Что получится

- Блок карты внутри HUD/overlay.
- Текущая GPS-позиция в виде стрелки.
- Маршрут поверх карты по полилинии Яндекс Навигатора.
- Цвет маршрута по пробкам.
- Обрезка уже пройденной части маршрута по текущей позиции.
- Настройки: масштаб, автомасштаб по скорости, наклон, размер стрелки, размер кэша.
- Прогрев тайлов вдоль маршрута.
- Оффлайн-загрузка регионов.
- Редактор позиции, размера и яркости блока карты.

## 1. Gradle

Добавь MapLibre.

Если используется version catalog, в `gradle/libs.versions.toml`:

```toml
[versions]
maplibreAndroid = "12.3.1"

[libraries]
maplibre-android-sdk = { group = "org.maplibre.gl", name = "android-sdk", version.ref = "maplibreAndroid" }
```

В `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.maplibre.android.sdk)
}
```

Без version catalog:

```kotlin
dependencies {
    implementation("org.maplibre.gl:android-sdk:12.3.1")
}
```

## 2. Manifest

Минимальные permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Если карта живёт внутри overlay поверх навигатора:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

Если overlay работает из foreground service с геолокацией:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<service
    android:name=".HudBackgroundService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync|location" />
```

В `application` должен быть указан свой `Application`, где будут инициализироваться MapLibre и stores:

```xml
<application
    android:name=".AnhudApp"
    ...>
```

## 3. Assets и drawables

Скопируй:

```text
app/src/main/assets/styles/hud_minimal.json
app/src/main/assets/offline_regions_ru.json
app/src/main/res/drawable/ic_nav_arrow.xml
app/src/main/res/drawable/ic_transparent_puck.xml
```

`hud_minimal.json` использует OpenFreeMap:

```text
https://tiles.openfreemap.org/planet
```

Ключ карт не нужен. Интернет нужен для первичной загрузки тайлов и для скачивания оффлайн-регионов.

## 4. Kotlin-файлы

Скопируй эти файлы из `app/src/main/java/com/g992/anhud`:

```text
HudMapController.kt
MapRenderSettings.kt
MapRouteTelemetry.kt
MapStyleConfig.kt
MapCacheController.kt
OfflineRegionCatalog.kt
OfflineRegionSizeEstimator.kt
GeoBoundariesRegionResolver.kt
```

После копирования замени строку:

```kotlin
package com.g992.anhud
```

на пакет целевого приложения.

Проверь `R.drawable.ic_nav_arrow`, `R.drawable.ic_transparent_puck` и строки ресурсов, если переносишь UI настроек.

## 5. Инициализация

В `Application.onCreate()` добавь то, что сейчас делает `AnhudApp`:

```kotlin
class YourApp : Application() {
    private val mapRouteTelemetryReceiver = MapRouteTelemetryReceiver()

    override fun onCreate() {
        super.onCreate()

        MapLibre.getInstance(applicationContext, null, WellKnownTileServer.MapLibre)
        MapRenderSettingsStore.initialize(applicationContext)
        MapRouteTelemetryStore.initialize(applicationContext)
        MapCacheController.initialize(applicationContext)

        val filter = IntentFilter().apply {
            addAction(MAP_ROUTE_TELEMETRY_ACTION)
            addAction(MAP_ROUTE_STATE_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mapRouteTelemetryReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mapRouteTelemetryReceiver, filter)
        }
    }
}
```

Нужные imports:

```kotlin
import android.app.Application
import android.content.IntentFilter
import android.os.Build
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
```

## 6. Встраивание карты

Минимальная интеграция в любой `FrameLayout`:

```kotlin
private var hudMapController: HudMapController? = null

fun attachMap(context: Context, container: FrameLayout) {
    val controller = hudMapController ?: HudMapController(context).also {
        hudMapController = it
    }
    controller.attachTo(container)
    controller.setVisible(true)
}

fun releaseMap() {
    hudMapController?.release()
    hudMapController = null
}
```

`HudMapController` сам создаёт `MapView`, отключает gestures/touch, загружает стиль, включает `LocationComponent`, слушает настройки и слушает маршрут.

`releaseMap()` обязательно вызывай при закрытии overlay/window/activity.

## 7. Как карта включена в текущем overlay

Если целевое приложение похоже на ANHUD, переноси изменения из этих текущих файлов:

```text
HudOverlayController.kt
HudBackgroundService.kt
OverlayBroadcasts.kt
OverlayPrefs.kt
MainActivity.kt
MainActivityPositionDialogs.kt
MainActivityOverlayUi.kt
MainActivityPresets.kt
PrefsJson.kt
GuideContent.kt
SettingsActivity.kt
```

Роли файлов:

- `OverlayPrefs.kt` хранит позицию, размер, яркость и включение карты.
- `OverlayBroadcasts.kt` добавляет extras `EXTRA_MAP_*` и `PREVIEW_TARGET_MAP`.
- `HudBackgroundService.kt` слушает изменения маршрута через `MapRouteTelemetryStore.addListener(...)` и вызывает `overlayController.refresh()`.
- `HudOverlayController.kt` создаёт `mapContainerView`, `mapContentView`, placeholder и `HudMapController`.
- `MainActivity.kt` добавляет карточку блока карты и switch включения.
- `MainActivityPositionDialogs.kt` добавляет `OverlayTarget.MAP`, preview, drag, размер и яркость.
- `SettingsActivity.kt` добавляет вкладку настроек карты и оффлайн-загрузки.
- `PrefsJson.kt` добавляет export/import настроек карты и `map_render_settings`.

В текущем `HudOverlayController` карта показывается, когда:

```kotlin
mapEnabled && MapRouteTelemetryStore.current().hasRoute
```

В preview-режиме вместо реальной карты показывается placeholder. Если в другом приложении карта должна быть видна без маршрута, убери проверку `hasRoute` в логике показа.

## 8. Позиция, размер и яркость карты

В `OverlayPrefs.kt` нужны ключи и методы для:

```text
overlay_map_x_dp
overlay_map_y_dp
overlay_map_width_dp
overlay_map_height_dp
overlay_map_alpha
overlay_map_enabled
```

Минимум:

```kotlin
OverlayPrefs.mapPositionDp(context)
OverlayPrefs.setMapPositionDp(context, xDp, yDp)
OverlayPrefs.mapSizeDp(context)
OverlayPrefs.setMapSizeDp(context, widthDp, heightDp)
OverlayPrefs.mapAlpha(context)
OverlayPrefs.setMapAlpha(context, alpha)
OverlayPrefs.mapEnabled(context)
OverlayPrefs.setMapEnabled(context, enabled)
```

В `OverlayBroadcasts.kt` нужны:

```kotlin
const val EXTRA_MAP_X_DP = "extra_map_x_dp"
const val EXTRA_MAP_Y_DP = "extra_map_y_dp"
const val EXTRA_MAP_WIDTH_DP = "extra_map_width_dp"
const val EXTRA_MAP_HEIGHT_DP = "extra_map_height_dp"
const val EXTRA_MAP_ALPHA = "extra_map_alpha"
const val EXTRA_MAP_ENABLED = "extra_map_enabled"
const val PREVIEW_TARGET_MAP = "map"
```

## 9. Получение маршрута

Текущий код поддерживает два входа маршрута.

### Вариант A: `com.yandex.ROUTE_POLYLINE`

Это обрабатывает `NavigationReceiver`.

Action:

```text
com.yandex.ROUTE_POLYLINE
```

Extras:

```text
route_active: Boolean
route_id: String?
polyline_lats: DoubleArray
polyline_lons: DoubleArray
polyline_count: Int
```

При активном маршруте текущий код собирает `List<LatLng>` и вызывает:

```kotlin
MapRouteTelemetryStore.replaceRoutePolyline(
    context.applicationContext,
    routeId,
    points
)
```

При завершении:

```kotlin
MapRouteTelemetryStore.clearRoutePolyline(context.applicationContext)
```

Если в другом приложении нет `NavigationReceiver`, сделай свой receiver для этого action или вызывай эти методы из своего источника маршрута.

### Вариант B: `com.yandex.ROUTE_TELEMETRY`

Это обрабатывает `MapRouteTelemetryReceiver`, зарегистрированный в `Application`.

Action:

```text
com.yandex.ROUTE_TELEMETRY
```

Extras:

```text
route_start: "lat,lon"
route_end: "lat,lon"
route_sampled: "lat,lon;lat,lon;lat,lon"
route_jams: "FREE;LIGHT;HARD;VERY_HARD"
```

Состояние маршрута:

```text
action = com.yandex.ROUTE_STATE
extra route_state = BUILT | ARRIVED | CANCELLED
```

`ARRIVED` и `CANCELLED` очищают сохранённую геометрию маршрута.

Пример проверки:

```bash
adb shell am broadcast \
  -a com.yandex.ROUTE_TELEMETRY \
  --es route_start "55.751244,37.618423" \
  --es route_end "55.760000,37.620000" \
  --es route_sampled "55.751244,37.618423;55.752000,37.618900;55.754000,37.619300;55.760000,37.620000" \
  --es route_jams "FREE;LIGHT;HARD"
```

## 10. Как маршрут доходит до карты

`MapRouteTelemetryStore` хранит snapshot в `SharedPreferences` `map_route_telemetry` и уведомляет listeners:

```kotlin
MapRouteTelemetryStore.addListener { snapshot ->
    // snapshot.hasRoute
    // snapshot.routePoints
    // snapshot.routeJams
    // snapshot.state
}
```

`HudMapController` слушает этот store и перерисовывает слой маршрута. `HudBackgroundService` тоже слушает store, чтобы пересчитать видимость overlay.

Для интеграции в другое приложение лучше использовать именно listener API. Не завязывайся на отдельный broadcast статуса маршрута: в текущем UI ANHUD он не нужен для показа карты.

## 11. Настройки карты

`MapRenderSettingsStore` хранит настройки в `SharedPreferences`:

```text
map_render_settings
```

Поля:

- `zoom`: масштаб карты, 10.0-21.0.
- `autoZoomEnabled`: автомасштаб по скорости.
- `autoZoomAt0Kmh`: масштаб при 0 км/ч.
- `autoZoomAt60Kmh`: масштаб при 60 км/ч.
- `autoZoomAt90Kmh`: масштаб при 90 км/ч.
- `tilt`: наклон камеры, 0-80.
- `arrowScalePercent`: размер стрелки, 7-30.
- `cacheSizeStep`: индекс размера кэша.
- `downloadRouteEnabled`: прогревать кэш вдоль маршрута.
- `offlineRegionId`: выбранный регион из каталога.
- `offlineManualLabel`, `offlineManualLat1`, `offlineManualLon1`, `offlineManualLat2`, `offlineManualLon2`: ручной bbox.

Менять настройки нужно так:

```kotlin
MapRenderSettingsStore.update { settings ->
    settings.copy(
        zoom = 17.8,
        tilt = 58.0,
        arrowScalePercent = 30
    )
}
```

`HudMapController` применит изменения через listener.

## 12. Автомасштаб

`HudMapController` берёт скорость из `NavigationHudStore.snapshot().speedKmh` и слушает `NavigationHudStore`.

Если в целевом приложении нет `NavigationHudStore`, есть два варианта:

- перенести `NavigationHudStore` вместе с существующей навигационной частью;
- заменить в `HudMapController` получение скорости на свой источник скорости.

Без этого ручной масштаб работает, но автомасштаб по скорости надо адаптировать.

## 13. Кэш маршрута и оффлайн

`MapCacheController` делает три вещи:

- выставляет максимальный ambient cache MapLibre;
- при `downloadRouteEnabled=true` прогревает тайлы вдоль маршрута через `MapSnapshotter`;
- создаёт и удаляет offline regions MapLibre.

Размеры кэша из текущего кода:

```text
256 MB, 512 MB, 1024 MB, 2048 MB, 4096 MB
```

Для оффлайн-регионов используются:

```text
OfflineRegionCatalog.kt
OfflineRegionSizeEstimator.kt
GeoBoundariesRegionResolver.kt
app/src/main/assets/offline_regions_ru.json
```

Скачать регион:

```kotlin
MapCacheController.startOfflineRegionDownload(entry)
```

Удалить регион:

```kotlin
MapCacheController.deleteOfflineRegion(regionId)
```

Очистить все managed offline downloads:

```kotlin
MapCacheController.clearOfflineDownloads()
```

`GeoBoundariesRegionResolver` при первой загрузке региона ходит в:

```text
https://www.geoboundaries.org/api/current
```

Геометрия кэшируется в:

```text
filesDir/offline_region_geometry
```

## 14. Стиль карты

Стиль подключается через `MapStyleConfig.kt`:

```kotlin
const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"
```

`ensureHudMapStyleCached(context)` кладёт json стиля в MapLibre offline resource cache через `OfflineManager.putResourceWithUrl(...)`, после чего `HudMapController` загружает стиль из этого cache URL.

Если меняешь путь asset, поменяй `MAP_STYLE_ASSET_PATH`.

## 15. UI настроек карты

Для такого же экрана настроек перенеси из `SettingsActivity.kt`:

- поля `mapZoomValue`, `mapAutoZoomZeroValue`, `mapAutoZoomSixtyValue`, `mapAutoZoomNinetyValue`;
- `mapTiltValue`, `mapArrowValue`, `mapCacheValue`, `mapOfflineRegionValue`;
- `mapAutoZoomSwitch`, `mapRouteDownloadSwitch`;
- `setupMapTab()`;
- `syncMapUiFromPrefs()`;
- `showOfflineDownloadsDialog()`;
- `OfflineDownloadsAdapter`;
- `OfflineRegionAdapter`;
- helpers `formatMapDecimal`, `formatStorageBytes`, `currentOfflineMaxZoom`.

В `activity_settings.xml` нужен контейнер:

```xml
<LinearLayout
    android:id="@+id/tabMapContent"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="20dp"
    android:visibility="gone" />
```

В `strings.xml` перенеси строки:

```text
tab_map_settings
map_settings_*
```

## 16. UI позиции карты

Для главного экрана и редактора позиции перенеси соответствующие изменения:

```text
activity_hud_display_settings.xml
dialog_position_editor.xml
MainActivity.kt
MainActivityPositionDialogs.kt
```

Нужные id:

```text
positionMapCard
mapProjectionSwitch
dialogPreviewMapBlock
```

Нужные строки:

```text
position_map_block_label
guide_main_map_title
guide_main_map_body
```

## 17. Export/import настроек

Если в целевом приложении есть экспорт настроек, перенеси изменения `PrefsJson.kt` для:

```text
overlay_map_x_dp
overlay_map_y_dp
overlay_map_width_dp
overlay_map_height_dp
overlay_map_alpha
overlay_map_enabled
map_render_settings
```

Без этого карта будет работать, но настройки карты не попадут в экспорт.

## 18. Проверка после переноса

1. Приложение собирается с MapLibre.
2. В `Application` выполнены все `initialize(...)`.
3. Выданы location permissions.
4. `HudMapController.attachTo(...)` вызывается на живом `FrameLayout`.
5. При закрытии вызывается `HudMapController.release()`.
6. Приходит маршрут через `com.yandex.ROUTE_POLYLINE` или `com.yandex.ROUTE_TELEMETRY`.
7. `MapRouteTelemetryStore.current().hasRoute == true` после получения маршрута.
8. Overlay refresh вызывается при изменении маршрута.
9. Стиль `styles/hud_minimal.json` найден в assets.
10. Стрелка использует `ic_nav_arrow`, фон маркера использует `ic_transparent_puck`.
11. При включённом `downloadRouteEnabled` прогревается кэш маршрута.
12. Оффлайн-регионы видны в настройках и скачиваются.

## 19. Что не относится к текущей реализации

Для этой интеграции используй только актуальные файлы из `app/src/main`. Прочие прототипы, старые README и удалённые shell-скрипты не нужны для встроенной карты через `HudMapController`.
