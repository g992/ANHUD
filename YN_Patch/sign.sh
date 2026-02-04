#!/bin/bash
# Подпись APK с минимальными логами

cd "$(dirname "$0")/.."

APK_INPUT="${1:-output/YM_EXTENDED_FULL.apk}"
APK_ALIGNED="output/aligned/$(basename "$APK_INPUT" .apk)_aligned.apk"
APK_SIGNED="output/signed/$(basename "$APK_INPUT" .apk)_signed.apk"

mkdir -p output/aligned output/signed

echo "📐 Выравнивание APK..."
/Users/G992/Library/Android/sdk/build-tools/35.0.0/zipalign -f 4 "$APK_INPUT" "$APK_ALIGNED" 2>&1 | grep -v "^Verifying" | head -1

echo "🔑 Подпись APK..."
/Users/G992/Library/Android/sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$APK_SIGNED" \
  "$APK_ALIGNED" 2>&1 | grep -E "Error|Exception"

if [ -f "$APK_SIGNED" ]; then
    echo "✅ APK подписан: $APK_SIGNED"
    ls -lh "$APK_SIGNED" | awk '{print "   Размер:", $5}'

    # Проверка подписи (краткая)
    /Users/G992/Library/Android/sdk/build-tools/35.0.0/apksigner verify "$APK_SIGNED" 2>&1 | head -1
else
    echo "❌ Ошибка подписи"
    exit 1
fi
