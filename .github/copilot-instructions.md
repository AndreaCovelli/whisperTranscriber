# Copilot Instructions

When using libraries, use context7 to check for updated info.

## Build & Test Commands

Always use the Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` on Unix) — never invoke `gradle` directly. The required JDK (21) is declared in `gradle/gradle-daemon-jvm.properties` and resolved automatically via the Foojay toolchain resolver; set `JAVA_HOME` just for the current session.

```bat
# Build
gradlew.bat assembleDebug
gradlew.bat assembleRelease

# Test
gradlew.bat testDebugUnitTest                                          # unit tests
gradlew.bat connectedAndroidTest                                       # instrumented tests
gradlew.bat testDebugUnitTest --tests "com.andreacovelli.whispertranscriber.ExampleUnitTest"  # single test class

# Clean
gradlew.bat clean
```

## Architecture

Single-Activity app (`MainActivity`) using **Jetpack Compose + MVVM**. No fragments.

**Package:** `com.andreacovelli.whispertranscriber`

```
MainActivity.kt           — Activity, NavHost, share intent handling
MainViewModel.kt          — Transcription orchestration, job queue, provider dispatch
HistoryViewModel.kt       — Room history as StateFlow
ApiKeyManager.kt          — Encrypted credential storage (DataStore + Keystore)
AssemblyAiApiService.kt   — Retrofit interface + data classes for AssemblyAI v2 API
data/HistoryDatabase.kt   — Room entity (TranscriptionHistory), DAO, migrations
security/KeystoreCrypto.kt — AES-256-GCM encryption for API keys
media/HardwareAudioConverter.kt — MediaCodec audio → 16kHz mono WAV conversion
ui/theme/                 — Material Design 3 theming
```

**Navigation** (`AppRoutes` object):
- `"main"` → `MainScreen` (start destination)
- `"history"` → `HistoryScreen`
- `"settings"` → `SettingsScreen`

Slide left/right transitions (300ms). Navigation callbacks are passed as lambdas into composables.

**Data flow:**
1. Audio URIs arrive via share intents (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) or in-app selection → `MainViewModel.handleAudioShareMultiple()`
2. Jobs enqueued to `Channel<TranscriptionJobState>(Channel.UNLIMITED)`, processed sequentially by a single processor coroutine
3. Audio converted to 16kHz WAV via `HardwareAudioConverter` if needed
4. Dispatched to AssemblyAI (upload → create transcript → poll until complete) or Gemini (direct inline call), keyed by `TranscriptionProvider` enum
5. Results inserted into Room DB via DAO; UI observes `StateFlow`

## Key Conventions

**StateFlow pattern** — private mutable, public immutable:
```kotlin
private val _uiState = MutableStateFlow(UiState())
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```
UI collects with `collectAsStateWithLifecycle()`.

**TranscriptionProvider enum** — AssemblyAI has no model name; Gemini variants carry a `modelName` string:
```kotlin
ASSEMBLY_AI
GEMINI_FLASH_LITE("gemini-flash-lite-latest")
GEMINI_FLASH("gemini-flash-latest")
GEMINI_PRO("gemini-pro-latest")
```
Provider + API keys persisted via DataStore keys `assembly_ai_api_key_enc`, `gemini_api_key_enc`, `transcription_provider`.

**API key security** — Keys are always encrypted with `KeystoreCrypto.encryptToBase64()` (AES-256-GCM) before writing to DataStore, and decrypted on read. Never store or log plaintext keys.

**Room migrations** — Use explicit `Migration(from, to)` objects with raw `ALTER TABLE` SQL. Schema is at version 3. Add a new `Migration` object and register it in the `Room.databaseBuilder` call when changing the schema.

**Retrofit client** — Base URL `https://api.assemblyai.com/`. OkHttp timeouts: connect 30s, read/write 5min. `Authorization` header is redacted in logs.

**Audio conversion** — `HardwareAudioConverter` uses `MediaCodec` to decode any audio format and resample to 16kHz mono PCM, then wraps in a WAV container. Required for formats not natively accepted by the APIs.

**Dependency injection** — None (no Hilt/Dagger). ViewModels are created via `viewModels()` / `ViewModelProvider`; `ApiKeyManager` and `RetrofitClient` are passed as constructor parameters or singletons.

**Version catalog** — All dependencies declared in `gradle/libs.versions.toml`. Add new dependencies there, not inline in `build.gradle.kts`.

**minSdk 26**, **targetSdk/compileSdk 36**, **JVM target 17**, Kotlin 2.2.10, AGP 9.1.0, KSP 2.3.6 for Room annotation processing.
