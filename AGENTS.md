# Repository Guidelines

## Project Structure & Module Organization
This repository is a single-module Android app in `app/`. Kotlin sources live under `app/src/main/java/com/andreacovelli/whispertranscriber/`, with UI split into `ui/screens`, `ui/components`, and `ui/theme`. Persistence and queue state are in `data/`, audio conversion in `media/`, and key storage in `security/`. Android resources are in `app/src/main/res/`. Unit tests live in `app/src/test/...`; instrumentation tests live in `app/src/androidTest/...`. CI workflows are in `.github/workflows/`, and static-analysis config lives in `config/detekt/`.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` builds the debug APK.
- `./gradlew testDebugUnitTest` runs JVM unit tests.
- `./gradlew connectedDebugAndroidTest` runs instrumented tests on a device/emulator.
- `./gradlew lintDebug` runs Android lint checks.
- `./gradlew spotlessApply` formats Kotlin and Gradle files.
- `./gradlew detekt` runs static analysis on `main`, `test`, and `androidTest` sources.
- `./gradlew spotlessCheck detekt lintDebug` runs the main quality gate locally.
- `./gradlew clean` clears local build outputs if KSP or lint caches get corrupted.
- Use one Gradle invocation at a time per workspace. Parallel local Gradle runs against the same repo can trigger Kotlin/KSP cache corruption.
- `--no-daemon` is useful when diagnosing cache or incremental-build issues, but it is not required for normal day-to-day development.
- Run Gradle verification sequentially in this repo. Do not launch multiple Gradle commands in parallel against the same workspace; that can trigger Kotlin/KSP incremental cache corruption on its own.

## Coding Style & Naming Conventions
Use 4-space indentation and standard Kotlin style. Keep composables in `PascalCase`, functions and properties in `camelCase`, and constants in `UPPER_SNAKE_CASE`. Prefer small UI components in `ui/components/` and keep screen orchestration in `ui/screens/`. Formatting is enforced by Spotless with ktlint; static analysis uses Detekt with `config/detekt/detekt.yml`. Avoid editing generated `app/build/` outputs.

## Testing Guidelines
Write unit tests with JUnit4 in `app/src/test/` for pure logic such as query building, view-model helpers, and repository behavior. Use instrumentation tests in `app/src/androidTest/` for Room, WorkManager, or Android framework behavior. Name test files after the class or feature under test, for example `HistorySearchQueryBuilderTest.kt`. Run `./gradlew testDebugUnitTest` before opening a PR; add targeted instrumentation coverage when changing migrations or background work.
When changing Room schemas or migrations, keep exported schemas under `app/schemas/` in sync and verify the upgrade path with `connectedDebugAndroidTest`.
If build corruption appears during a single sequential run, inspect `build/reports/kotlin-build/` and `app/build/kspCaches/*/logs/` before changing toolchain settings.
Temporary Kotlin/KSP diagnostics are enabled. Kotlin build reports are written under `build/reports/kotlin-build/`, and KSP incremental logs appear under `app/build/kspCaches/*/logs/`. If cache corruption happens again during a single sequential run, use those artifacts to distinguish a real Kotlin/KSP incremental issue from parallel-build contention.

## Commit & Pull Request Guidelines
Recent history follows short conventional-style subjects such as `fix: simplify FTS migration` and `ci: consolidate workflows and address review feedback`. Keep commits focused and imperative. PRs should explain user-visible changes, note migrations or queue/worker behavior changes, and link issues with `Closes #<number>` only when fully resolved. Include screenshots for UI changes and list the verification commands you ran.
If a PR changes search, migrations, or quality tooling, mention the relevant checks explicitly, for example `detekt`, `lintDebug`, `testDebugUnitTest`, and `connectedDebugAndroidTest`.

## Security & Configuration Tips
Do not commit API keys, staged audio, or local build artifacts. Keep secrets in the app’s encrypted storage flow, and review backup-related changes carefully because the app excludes sensitive local data from backup/restore.
