package com.g992.anhud

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Невидимая Activity для ручного завершения навигации.
 *
 * Используется для добавления кнопки завершения навигации в автомобильные лаунчеры,
 * которые поддерживают запуск Activity. Решает проблему с автоматическим детектом
 * завершения навигации.
 *
 * Поведение:
 * 1. Отправляет broadcast для очистки навигации
 * 2. Сразу закрывается (без UI)
 *
 * Использование из лаунчера:
 * - Добавить кнопку с запуском Activity: com.g992.anhud/.EndNavigationActivity
 *
 * Использование из ADB:
 * - adb shell am start -n com.g992.anhud/.EndNavigationActivity
 */
class EndNavigationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // НЕ вызываем setContentView() - никакого UI

        // Отправляем broadcast для очистки навигации
        val intent = Intent(OverlayBroadcasts.ACTION_CLEAR_NAVIGATION)
            .setPackage(packageName)
        sendBroadcast(intent)

        // Логируем для отладки
        UiLogStore.append(LogCategory.SYSTEM, "Навигация завершена вручную через EndNavigationActivity")

        // Сразу закрываем Activity без анимации
        finish()

        // Отключаем анимацию закрытия
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
