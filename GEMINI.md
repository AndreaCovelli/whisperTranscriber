# WhisperTranscriber - Gemini CLI Context

When using libraries, use context7 to check for updated info.

## Project Overview
WhisperTranscriber is an Android application that transcribes audio files using AI, leveraging either AssemblyAI or Google Gemini. It allows users to share audio files from any app, processes them (including automatic conversion to 16 kHz mono WAV via MediaCodec when necessary), and returns transcripts.

Key features include:
- **Multi-provider Transcription:** AssemblyAI and Google Gemini (supports custom models).
- **Batch Processing:** Queued sequential processing of multiple shared audio files.
- **AI Summarization:** Transcript summarization using the Gemini API.
- **Local History:** Transcripts are saved locally in a searchable Room SQLite database.
- **Secure Storage:** API keys are encrypted with AES-256-GCM via Android Keystore.
- **Audio Conversion:** Hardware-accelerated conversion to 16 kHz mono WAV for incompatible formats.

## Architecture & Technology Stack
- **Platform:** Android (Min SDK 26, Target SDK 36)
- **Language:** Kotlin (Java 17 compatibility)
- **UI Framework:** Jetpack Compose (Material Design 3)
- **Architecture Pattern:** Single-Activity MVVM (Model-View-ViewModel)
- **Navigation:** Jetpack Navigation Compose
- **Local Storage:** Room Database (schema v4), DataStore Preferences
- **Networking:** Retrofit, OkHttp Logging Interceptor
- **AI Integrations:** AssemblyAI REST API, Google Generative AI SDK

## Development Conventions
- **UI State Management:** UI state is exposed as `StateFlow` from ViewModels and collected in Compose using `collectAsStateWithLifecycle()`.
- **Code Organization:**
  - `MainActivity.kt`: Single Activity host and intent handling.
  - ViewModels (`MainViewModel.kt`, `HistoryViewModel.kt`) orchestrate business logic.
  - `data/`: Room database entities and DAOs.
  - `media/`: Hardware audio conversion logic.
  - `security/`: Keystore encryption logic.
  - `ui/theme/`: Material 3 Compose theme definitions.
- **Secrets Management:** No secrets or configuration files are tracked in version control. All API keys are managed inside the app's settings screen and securely encrypted on-device.

## Building and Running
The project uses the Gradle build system (Kotlin DSL).

**Note:** Ensure that your Java environment variable (e.g., `JAVA_HOME`) is correctly set in your terminal as an environment variable before running Gradle commands:
```
$env:JAVA_HOME = "C:\Users\<User>\AppData\Local\Programs\Android Studio\jbr"
```

- **Build Debug APK:**
  ```bash
  ./gradlew assembleDebug       # macOS / Linux
  gradlew.bat assembleDebug     # Windows
  ```
  The resulting APK will be located at `app/build/outputs/apk/debug/`.

- **Run Tests:**
  ```bash
  ./gradlew testDebugUnitTest    # Unit tests
  ./gradlew connectedAndroidTest # Instrumented tests (requires a connected device or emulator)
  ```
