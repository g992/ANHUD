#!/bin/bash
# Декомпиляция APK с минимальными логами

cd "$(dirname "$0")/.."

if [ -z "$1" ]; then
    echo "Использование: $0 <путь_к_apk>"
    exit 1
fi

APK_PATH="$1"
APK_NAME=$(basename "$APK_PATH" .apk)
OUTPUT_DIR="decompiled_${APK_NAME}"

echo "📦 Декомпиляция: $APK_NAME"
apktool d "$APK_PATH" -o "$OUTPUT_DIR" -f 2>&1 | grep -E "(Using Apktool|Built apk|Error|Exception)" | head -3

if [ -d "$OUTPUT_DIR" ]; then
    echo "✅ Декомпилировано в: $OUTPUT_DIR"
else
    echo "❌ Ошибка декомпиляции"
    exit 1
fi
