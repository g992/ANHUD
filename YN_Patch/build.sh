#!/bin/bash
# Сборка APK с минимальными логами

cd "$(dirname "$0")/.."

echo "🔨 Компиляция APK..."
apktool b ym_base_check/decompiled -o output/YM_EXTENDED_FULL.apk 2>&1 | grep -E "(Built apk|Error|Exception)" || echo "✅ Компиляция завершена"

if [ -f output/YM_EXTENDED_FULL.apk ]; then
    echo "✅ APK создан: output/YM_EXTENDED_FULL.apk"
    ls -lh output/YM_EXTENDED_FULL.apk | awk '{print "   Размер:", $5}'
else
    echo "❌ Ошибка компиляции"
    exit 1
fi
