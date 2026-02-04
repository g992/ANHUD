#!/bin/bash
# Полная сборка и подпись APK

cd "$(dirname "$0")"

echo "========================================="
echo "  ПОЛНАЯ СБОРКА YANDEX MAPS EXTENDED"
echo "========================================="

./build.sh
if [ $? -eq 0 ]; then
    ./sign.sh
    if [ $? -eq 0 ]; then
        echo ""
        echo "========================================="
        echo "✅ Сборка завершена успешно!"
        echo "========================================="
        echo ""
        echo "📲 Установка:"
        echo "   adb install -r $(cd .. && pwd)/output/signed/YM_EXTENDED_FULL_signed.apk"
    fi
fi
