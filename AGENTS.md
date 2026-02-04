# Repository Guidelines
DO NOT CRATE A LOT OF .md FILES, send SHORT reports direct in chat
## Project Structure & Module Organization
- Single Gradle module: `:app`.
- Kotlin source: `app/src/main/java/com/g992/anhud`.
- Resources: `app/src/main/res` (layouts, drawables, values), assets: `app/src/main/assets`.
- Tests: `app/src/test` (unit) and `app/src/androidTest` (instrumented).
- Reference artifacts like `*_decompile` and `extracted` exist; avoid editing unless required for a task.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repo root:
- `./gradlew build` — full build.
- `./gradlew assembleDebug` — debug APK.
- `./gradlew assembleRelease` — release APK.
- `./gradlew test` — JVM unit tests.
- `./gradlew connectedAndroidTest` — instrumented tests on device/emulator.
- `./gradlew clean` — remove build outputs.

## Coding Style & Naming Conventions
- Kotlin + Android SDK, targeting Java 11 bytecode.
- 4-space indentation; use Android Studio/Kotlin default formatting.
- Classes in PascalCase, functions/vars in lowerCamelCase.
- Android resources follow `lowercase_with_underscores`.
- UI strings are in Russian; keep translations consistent with existing tone.
- Broadcast actions use `com.g992.anhud`; Yandex-specific actions use `yandex.auto.navi.mirror`.

## Testing Guidelines
- Unit tests: JUnit (`app/src/test/.../*Test.kt`).
- Instrumented tests: AndroidX JUnit + Espresso (`app/src/androidTest/.../*Test.kt`).
- Example targeted run: `./gradlew test --tests com.g992.anhud.YourTest`.

## Commit & Pull Request Guidelines
- Commit history favors short, descriptive messages, sometimes version-tagged (e.g., `0v14, ...`); no strict Conventional Commits.
- Keep commits focused; mention user-visible changes or fixes.
- PRs should include a summary, test evidence (commands or screenshots), and any required permissions or device steps.

## Configuration Tips
- `local.properties` should include `MAPKIT_API_KEY=...` for Yandex MapKit.
- Overlay and usage-stats permissions are required; confirm manifest changes align with new features.
