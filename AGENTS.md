# AGENTS.md

## Scope and Sources of Truth

These instructions apply to the entire repository.

- This is a single-module Android application. Keep production work inside `app/`
  unless the change is explicitly about repository tooling, documentation, or CI.
- Treat the checked-in Gradle configuration and source code as authoritative. The
  README can lag behind dependency versions or implementation details.
- Preserve unrelated local changes. Do not edit generated build output or commit
  machine-local configuration.
- Prefer the smallest change that fully solves the problem. Avoid opportunistic
  refactors in feature and bug-fix work.

## Product and Architecture

WhisperTranscriber accepts one or more shared audio files, stages them in
app-controlled storage, transcribes them with Microsoft Speech or Gemini, and stores
the results locally. It also supports summaries, searchable history, retries,
background execution, and result notifications.

The app uses:

- Kotlin and Jetpack Compose. `MainActivity` owns the app UI and navigation;
  `ShareReceiverActivity` is a transient entry point for Android share intents.
- ViewModels, `StateFlow`, and small MVU-style reducers/messages for UI state.
- Hilt for dependency injection.
- Room for transcription history, FTS search, and persistent queue state.
- WorkManager and an Android `JobService` for background execution paths.
- DataStore plus Android Keystore encryption for settings and API keys.
- `MediaExtractor`/`MediaCodec` for audio conversion.
- Gradle Kotlin DSL, a version catalog, Spotless/ktlint, Detekt, and Android Lint.

## Repository Map

- `app/src/main/java/com/andreacovelli/whispertranscriber/`
  - Root entry points and Android components: activities, application class,
    workers, job services, and notification receivers.
  - `background/`: background-share policies, notifications, and follow-up work.
  - `data/`: Room entities, DAOs, database migrations, and repositories.
  - `di/`: Hilt bindings and shared dispatchers/clients.
  - `history/`: history grouping, search-query construction, and export formats.
  - `media/`: hardware-backed audio conversion.
  - `queue/`: staged audio and persisted queue execution models.
  - `security/`: Keystore encryption and sensitive-key helpers.
  - `services/`: queue, validation, and summarization services used by ViewModels.
  - `settings/`: provider settings and encrypted API-key persistence.
  - `summary/`: provider-facing summarization repository.
  - `transcription/`: providers, MIME rules, execution runner, status constants,
    and transcription persistence.
  - `ui/components/`: reusable Compose components.
  - `ui/main/` and `ui/history/`: ViewModels, messages, reducers, and UI models.
  - `ui/screens/`: screen composition and navigation-level callbacks.
  - `ui/theme/`: Material 3 theme definitions.
- `app/src/main/AndroidManifest.xml`: components, share-intent filters, and permissions.
- `app/src/main/res/`: strings, themes, icons, and backup rules.
- `app/src/test/`: JVM unit tests for pure Kotlin behavior.
- `app/src/androidTest/`: device tests for Room, Android storage, MediaCodec,
  Keystore, share intents, and Compose UI.
  - `java/`: the debug instrumentation suite.
  - `export/java/`: JSON export coverage for the `minifiedSmoke` build only.
- `app/schemas/`: committed Room schema snapshots. These are required migration
  artifacts, not disposable generated output.
- `config/detekt/detekt.yml`: Detekt rules.
- `gradle/libs.versions.toml`: dependency and plugin versions.
- `.github/workflows/`: CI, dependency submission, and tagged release automation.
- `release-guide.txt`: release-keystore setup and release process.

## Toolchain and Local Setup

- Use the checked-in Gradle wrapper, never a system Gradle installation.
- The project requires JDK 21 through `kotlin.jvmToolchain(21)`.
- The app currently has `minSdk = 26`, `targetSdk = 36`, and `compileSdk = 37`.
- The current Room database schema version is 15.
- Android instrumentation work needs an installed Android SDK and the appropriate
  emulator system image.
- On Windows, Android Studio's bundled JBR is commonly located at:

  ```powershell
  $env:JAVA_HOME = Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr"
  ```

- On macOS/Linux, ensure `JAVA_HOME` points to a JDK 21 installation if Gradle
  cannot resolve Java automatically.

Run only one Gradle invocation at a time in this workspace. Concurrent Gradle runs
can corrupt Kotlin or KSP incremental caches. Do not use Gradle's `--parallel` flag
here, and do not launch verification tasks in parallel shells.

## Common Commands

Use `./gradlew` on macOS/Linux and `gradlew.bat` on Windows.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew spotlessCheck detekt lintDebug
./gradlew pixel6Api34DebugAndroidTest
./gradlew connectedDebugAndroidTest
```

The debug suite excludes notification permission recreation by default. Run it
separately on a fresh install before the general suite, and run export coverage
against the minified build:

```bash
./gradlew pixel6Api34DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.andreacovelli.whispertranscriber.NotificationPermissionRecreationInstrumentedTest
./gradlew pixel6Api34MinifiedSmokeAndroidTest -PinstrumentationBuildType=minifiedSmoke \
  -Pandroid.testInstrumentationRunnerArguments.class=com.andreacovelli.whispertranscriber.HistoryExportInstrumentedTest
```

`minifiedSmoke` uses release optimization with debug signing and includes only
the export instrumentation sources. Run each invocation sequentially.

Additional focused commands:

```bash
./gradlew spotlessApply
./gradlew detekt
./gradlew lintDebug
./gradlew clean
```

- `pixel6Api34DebugAndroidTest` uses the repository's managed Pixel 6/API 34
  x86_64 device and is the preferred reproducible instrumentation run. CI adds
  `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`.
- `connectedDebugAndroidTest` is for a manually attached emulator or device.
- Use `spotlessApply` only when formatting changes are intended, then review its
  diff.
- Use `clean` for diagnosed build-cache problems, not as a routine first step.
- `--no-daemon` is a diagnostic option, not a normal requirement.

If a sequential build reports Kotlin/KSP cache corruption, rerun it with
`-Pkotlin.build.report.output=file -Pksp.incremental.log=true`, then inspect
`build/reports/kotlin-build/` and `app/build/kspCaches/*/logs/` before changing
toolchain or incremental-build settings.

## Implementation Conventions

### Kotlin and Compose

- Follow standard Kotlin style with 4-space indentation.
- Use `PascalCase` for types and composables, `camelCase` for functions and
  properties, and `UPPER_SNAKE_CASE` for constants.
- Keep screen-level orchestration in `ui/screens/` and reusable presentation in
  `ui/components/`.
- Keep composables driven by immutable state and callbacks. Put persistence,
  network, queue, and conversion work outside composables.
- Route user actions through the existing ViewModel message/action patterns.
  Preserve reducer purity when editing `MainMvu.kt` or `HistoryMvu.kt`.
- Collect observable state with lifecycle-aware APIs and avoid starting duplicate
  long-running work from recomposition.
- Put user-visible text in `res/values/strings.xml`; do not hard-code it in Kotlin
  unless it is protocol data or an internal diagnostic.
- Use the injected coroutine dispatchers for blocking or I/O work. Do not block
  the main thread.

### Dependency Injection and Boundaries

- Extend Hilt modules in `di/AppModule.kt` when shared construction or bindings
  are needed. Avoid service locators and ad hoc global singletons.
- Keep provider-specific API behavior in `transcription/` or `summary/`, not in UI
  classes.
- Keep queue orchestration and persistence transactional where ownership or claim
  state is involved. A job must not be executed or summarized twice.
- Reuse centralized job and summary status constants. Stored status values are
  database data; changing them can require a migration.

### Audio, Queue, and Background Work

- Persisted jobs must operate on staged app-owned audio. Do not assume an incoming
  share URI will remain readable after the receiving activity exits.
- Stage shared audio under `noBackupFilesDir/queued_audio`. Enforce 10 files per
  intent, 250 MB per file, 500 MB per batch, 50 unfinished jobs, and 64 MiB free
  after each streamed copy.
- Microsoft MAI inputs must be strictly below 250,000,000 bytes and two hours.
- Preserve provider-specific MIME support and conversion behavior when changing
  upload code.
- Always clean up temporary conversion and staged files on success, failure, and
  cancellation where ownership permits.
- Keep cancellation, retry, notification, and process-restart behavior aligned
  across WorkManager, `BackgroundShareJobService`, queue DAOs, and the execution
  runner.
- Distinguish retryable failures from terminal failures deliberately. Do not
  create unbounded retry loops.

### Room and Search

- Never use destructive migration fallback for user data.
- For every schema change:
  1. Increment the Room database version.
  2. Add an explicit migration from the previous version.
  3. Keep all earlier migrations registered.
  4. Regenerate and commit the matching JSON snapshot in `app/schemas/`.
  5. Add or update instrumentation coverage for the upgrade path and affected
     queries.
- Keep the `transcription_history` content table and its FTS table synchronized.
- Build FTS expressions through the existing history search-query helper instead
  of interpolating raw user text into SQL.
- Do not hand-edit Room schema JSON to make a check pass; regenerate it from the
  annotated schema.

## Testing Strategy

Choose the lowest test level that exercises the real behavior:

- Use JVM tests for reducers, formatting, grouping, provider selection, MIME
  decisions, query construction, and other pure logic.
- Use instrumentation tests for Room schemas/migrations/FTS, Keystore, DataStore,
  WorkManager or job services, content URIs, MediaCodec, and Compose interaction.
- Prefer deterministic fakes and injected dependencies over timing delays or real
  network calls.
- Name tests after the feature or class under test and describe observable
  behavior in test method names.
- Add a regression test when fixing a defect whenever the failure can be
  reproduced reliably.

Minimum verification by change type:

- Documentation only: review the rendered Markdown and final diff.
- Pure Kotlin logic: `testDebugUnitTest`.
- UI or resources: `spotlessCheck detekt lintDebug`, relevant unit tests, and
  targeted Compose instrumentation where behavior changed.
- Queue, worker, storage, or background behavior: unit tests plus targeted
  instrumentation.
- Room entity, DAO, FTS, schema, or migration changes:
  `testDebugUnitTest` followed by `pixel6Api34DebugAndroidTest`; confirm the
  exported schema diff.
- Build logic or dependency changes:
  `spotlessCheck detekt lintDebug`, `testDebugUnitTest`, and `assembleDebug`.

Run these sequentially. If a full device suite is unavailable, run all safe local
checks and report exactly what was not run and why.

## Formatting and Static Analysis

- Spotless with ktlint is authoritative for Kotlin and Gradle Kotlin DSL format.
- Detekt scans `main`, `test`, and `androidTest` Kotlin sources using
  `config/detekt/detekt.yml`.
- Android Lint runs against the debug variant in the normal quality gate.
- Do not suppress rules broadly. Use a narrow suppression only when the code is
  correct, the rule is inapplicable, and the reason is clear.
- Do not edit `build/`, `app/build/`, `.gradle/`, generated KSP output, or lint
  intermediates.

## Security and Privacy

- Never commit API keys, release keystores, passwords, tokens, local audio,
  database dumps, `local.properties`, or `.env` files.
- API keys must remain encrypted through `ApiKeyManager` and `KeystoreCrypto`.
  Never persist or log plaintext credentials.
- Do not enable HTTP body logging for requests that can contain keys, audio, full
  transcripts, or summaries.
- DataStore contains sensitive settings and is intentionally excluded by
  `backup_rules.xml` and `data_extraction_rules.xml`. Review any backup change as
  a security-sensitive change.
- Treat shared audio, transcripts, summaries, filenames, and notification content
  as private user data. Minimize retention and diagnostic logging.
- Release signing material belongs in local secure storage and GitHub Actions
  secrets only. Follow `release-guide.txt`; never add a keystore to the repository.

## Git, CI, and Releases

- Use focused Conventional Commit subjects such as:
  `fix(queue): prevent duplicate claims`, `test(data): cover migration 13 to 14`,
  or `docs: refresh contributor guidance`.
- Stage only files belonging to the requested change and review the staged diff
  before committing.
- Do not bypass hooks, force-push shared branches, or rewrite history unless the
  user explicitly requests it.
- Pull requests should explain user-visible behavior, queue/background effects,
  migrations, and the exact verification performed. Include screenshots for
  visible UI changes.
- CI runs the quality gate (`spotlessCheck detekt lintDebug`) followed by the
  debug build and JVM tests (`assembleDebug testDebugUnitTest`) in one job,
  alongside managed-device instrumentation (`debug` and `minifiedSmoke` suites),
  security scans, and actionlint for `.github/` changes. Path filters select
  applicable checks; manual CI runs execute all checks.
  `CI Gate` is the required aggregate check.
- Dependency updates use `renovate.json`, which extends the shared
  `AndreaCovelli/renovate-config` preset.
- Use the checked-in `.gitleaks.toml` for Gitleaks filesystem scans so generated
  Gradle, build, and local-tool directories are skipped; keep Git-history scans
  enabled for committed content.
- Run Grype against `app/build/reports/cyclonedx-release/bom.json`, never a
  workspace `dir:` source. The SBOM is already limited to
  `releaseRuntimeClasspath`.
- Terminal cards expire after 30 days and history is retained until explicit
  deletion. Preserve this distinction in cleanup and migration work.
- Releases are triggered by tags matching `v*`; the release workflow derives
  `versionName` from the tag and `versionCode` from Git history. Do not create or
  push a release tag unless explicitly requested.

## Completion Checklist

Before handing off a change:

1. Re-read the request and inspect `git status` and the complete diff.
2. Confirm no unrelated, generated, local, or sensitive files are included.
3. Run the smallest sufficient sequential verification set above.
4. Check that migrations, schema snapshots, resources, and tests stay consistent
   with code changes.
5. Report the files changed, commands run, and any checks that could not be run.
