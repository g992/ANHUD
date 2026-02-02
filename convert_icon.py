#!/usr/bin/env python3
"""
Скрипт для конвертации иконки в PNG и создания разных размеров для Android.
"""

from PIL import Image
import os

# Путь к исходной иконке
SOURCE_ICON = "icon_stop.jpeg"

# Размеры для разных плотностей экрана
SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

# Базовая директория для ресурсов
RES_DIR = "app/src/main/res"

def main():
    # Проверяем, что исходный файл существует
    if not os.path.exists(SOURCE_ICON):
        print(f"❌ Файл {SOURCE_ICON} не найден!")
        return

    print(f"📂 Открываем {SOURCE_ICON}...")

    # Открываем исходное изображение
    img = Image.open(SOURCE_ICON)

    # Конвертируем в RGB (на случай если есть альфа-канал)
    if img.mode != 'RGB':
        img = img.convert('RGB')

    print(f"   Размер исходного изображения: {img.size}")
    print(f"   Режим: {img.mode}")

    # Создаем иконки для каждой плотности
    for density, size in SIZES.items():
        # Путь к директории
        drawable_dir = os.path.join(RES_DIR, f"drawable-{density}")

        # Создаем директорию если не существует
        os.makedirs(drawable_dir, exist_ok=True)

        # Путь к выходному файлу
        output_path = os.path.join(drawable_dir, "ic_end_navigation.png")

        # Изменяем размер с сохранением качества (LANCZOS для лучшего качества)
        resized_img = img.resize((size, size), Image.Resampling.LANCZOS)

        # Сохраняем как PNG
        resized_img.save(output_path, "PNG", optimize=True)

        print(f"✅ {density:8s} ({size:3d}x{size:3d}px) -> {output_path}")

    print("\n🎉 Готово! Иконки созданы для всех плотностей экрана.")
    print("\nТеперь нужно:")
    print("1. Удалить старую vector drawable иконку:")
    print("   rm app/src/main/res/drawable/ic_end_navigation.xml")
    print("2. Пересобрать проект:")
    print("   ./gradlew assembleDebug")

if __name__ == "__main__":
    main()
