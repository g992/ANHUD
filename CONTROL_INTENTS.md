# ANHUD Control Intents

Экспортированный ресивер: `com.g992.anhud/.HudStatusReceiver`

Базовый шаблон:

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a ACTION_NAME ...
```

## `G992.ANHUD.STATUS`

Интент для управления состоянием HUD и отдельными UI-блоками.

Поддерживаемые extras:

- `ENABLE` (`bool|0/1|"true"/"false"`): включает или выключает HUD.
- `STOP_NAVIGATION` (`bool|0/1|"true"/"false"`): завершает активную навигацию.
- `NATIVE_NAV` (`bool|0/1|"true"/"false"`): включает или выключает штатную навигацию машины через car API.
- `NAV` (`bool|0/1|"true"/"false"`): показывает или скрывает навигационный блок.
- `LANE_GUIDANCE` (`bool|0/1|"true"/"false"`): показывает или скрывает блок полос.
- `ARROW` (`bool|0/1|"true"/"false"`): показывает или скрывает отдельный overlay-блок стрелки. Это не штатная навигация машины.
- `SPEED_LIMIT` (`bool|0/1|"true"/"false"`): показывает или скрывает блок лимита скорости.
- `HUDSPEED` (`bool|0/1|"true"/"false"`): показывает или скрывает общий слот `HUD Speed / Strelka`.
- `HUD_ALERT_SOURCE` (`"HUDSPEED"|"STRELKA"`): выбирает, какой источник отображать в общем слоте и на проекции.
- `ROAD_CAMERA` (`bool|0/1|"true"/"false"`): показывает или скрывает блок камер.
- `TRAFFIC_LIGHT` (`bool|0/1|"true"/"false"`): показывает или скрывает блок светофоров.
- `SPEED_ALERT` (`bool|0/1|"true"/"false"`): включает или выключает alert превышения скорости.
- `SPEED_ALERT_THRESHOLD` (`int`): порог alert превышения.
- `SPEEDOMETER` (`bool|0/1|"true"/"false"`): показывает или скрывает спидометр.
- `TURN_SIGNALS` (`bool|0/1|"true"/"false"`): показывает или скрывает блок поворотников.
- `CLOCK` (`bool|0/1|"true"/"false"`): показывает или скрывает часы.
- `MAP` (`bool|0/1|"true"/"false"`): показывает или скрывает блок карты.

Поведение:

- `ENABLE=true` сработает только если у приложения есть permission на overlay.
- `ENABLE=false` выключает HUD и дополнительно останавливает активную навигацию.
- `STOP_NAVIGATION=true` останавливает маршрут, не выключая сам HUD.
- `STOP_NAVIGATION=true` не отключает сам механизм штатной навигации насовсем: если `NATIVE_NAV=true` и продолжат приходить route updates, штатная навигация сможет стартовать снова.
- `NATIVE_NAV=false` записывает постоянное состояние `native_nav_enabled=false` и сразу останавливает текущую штатную навигацию.
- `NATIVE_NAV=true` только разрешает штатную навигацию; фактический старт произойдет, когда придут данные активного маршрута.
- Можно передавать только те extras, которые нужно изменить.
- `HUDSPEED` и `HUD_ALERT_SOURCE` работают вместе: `HUDSPEED` управляет общим `enable`-статусом слота, а `HUD_ALERT_SOURCE` выбирает содержимое этого слота.
- Если выбран `HUD_ALERT_SOURCE=STRELKA`, входящие данные `HUD Speed` не отображаются, пока источник не переключён обратно.

Примеры:

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez ENABLE true
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez ENABLE false
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez STOP_NAVIGATION true
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez NATIVE_NAV false
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez NATIVE_NAV true
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez NAV false --ez SPEED_LIMIT true --ez SPEED_ALERT true --ei SPEED_ALERT_THRESHOLD 15
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez SPEEDOMETER true --ez CLOCK false
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez MAP true
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez ARROW false --ez LANE_GUIDANCE false
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez HUDSPEED false --ez ROAD_CAMERA false --ez TRAFFIC_LIGHT false --ez TURN_SIGNALS false
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.STATUS --ez HUDSPEED true --es HUD_ALERT_SOURCE STRELKA
```

Ограничения текущего контракта `G992.ANHUD.STATUS`:

- Управляет `ENABLE`, `STOP_NAVIGATION` и видимостью основных HUD-блоков.
- `NATIVE_NAV` управляет штатной навигацией машины отдельно от overlay-блока `ARROW`.
- Пока не умеет менять связанные опции вроде `HUDSPEED_LIMIT`, `HUDSPEED_LIMIT_ALERT`, `HUDSPEED_LIMIT_ALERT_THRESHOLD`, `SPEEDOMETER_SHOW_UNIT_TEXT`, `ARROW_ONLY_WHEN_NO_ICON`, `TRAFFIC_LIGHT_MAX_ACTIVE`.
- Не умеет менять layout/scale/alpha/position/preview-настройки.
- Для полного набора overlay-настроек сейчас используйте пресеты через `ANHUD_SET_PRESET`.

## `ANHUD_SET_PRESET`

Интент для применения пресета по номеру.

Поддерживаемые keys для номера пресета:

- `PRESET`
- `INDEX`
- `preset`
- `index`
- `preset_number`
- `PRESET_NUMBER`

Поведение:

- Нумерация пресетов начинается с `1`.
- Если пресет не найден или не применился, интент игнорируется.
- После успешного применения отправляется полный refresh overlay.

Примеры:

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a ANHUD_SET_PRESET --ei PRESET 1
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a ANHUD_SET_PRESET --ei INDEX 2
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a ANHUD_SET_PRESET --es preset 3
```

## `G992.ANHUD.SET_PRESET`

Легаси-алиас для `ANHUD_SET_PRESET`. Работает так же, отличается только `action`.

Примеры:

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.SET_PRESET --ei PRESET 1
```

```bash
adb shell am broadcast -n com.g992.anhud/.HudStatusReceiver -a G992.ANHUD.SET_PRESET --ei PRESET_NUMBER 4
```
