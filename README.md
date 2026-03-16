# WhisperTranscriber

An Android app that transcribes audio files using AI — powered by [ElevenLabs](https://elevenlabs.io/) or [Google Gemini](https://ai.google.dev/).
 Share one or more audio files with the app from any file manager or audio player, and receive transcripts in seconds.

---

## Features

- **Multi-provider transcription** — choose between ElevenLabs or Gemini (Flash Lite, Flash, Pro, or a custom model)
- **Batch processing** — share multiple audio files at once; jobs are queued and processed sequentially
- **AI summarization** — generate a concise summary of any transcript (requires a Gemini API key)
- **Transcription history** — all transcripts are saved locally in a searchable Room database
- **Secure key storage** — API keys are encrypted with AES-256-GCM via Android Keystore and never stored in plaintext
- **Automatic audio conversion** — formats not natively accepted by the selected provider are transparently converted to 16 kHz mono WAV using hardware-accelerated MediaCodec
- **Copy & share** — copy or share any transcript or summary to any app installed on the device

---

## Requirements

| | |
|---|---|
| **Minimum Android** | 8.0 Oreo (API 26) |
| **Target / Compile SDK** | 36 (Android 15) |
| **JDK** | 21 (resolved automatically via Gradle toolchain) |
| **API key** | ElevenLabs **or** Gemini (at least one required) |

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/AndreaCovelli/WhisperTranscriber.git
cd WhisperTranscriber
```

### 2. Build

```bat
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

The APK will be written to `app/build/outputs/apk/debug/`.

### 3. Run tests

```bat
gradlew.bat testDebugUnitTest          # unit tests
gradlew.bat connectedAndroidTest       # instrumented tests (requires a device/emulator)
```

---

## Configuration

All configuration is done inside the app — no config files or build secrets needed.

1. Open **Settings** (gear icon on the main screen)
2. Select your **transcription provider**
3. Paste the corresponding **API key** into the appropriate field — it is encrypted and saved automatically

### Getting API keys

- **ElevenLabs**: Sign up at [ElevenLabs.com](https://www.ElevenLabs.com/) and copy your API key from the dashboard
- **Gemini**: Generate a key at [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)

### Custom Gemini model

Select **Custom Model** in Settings and type any valid Gemini model identifier (e.g. `gemini-3-flash`). The app validates the name against the Google API in real time and shows the model's display name on success.

---

## How It Works

### ElevenLabs flow

1. Audio file is uploaded to ElevenLabs's servers
2. A transcript job is created referencing the uploaded URL
3. The app polls the status endpoint every 5 seconds (up to ~10 minutes)
4. The finished transcript is saved to local history; the remote file is deleted

### Gemini flow

1. Audio bytes are sent inline with the transcription prompt
2. The model returns a structured JSON response containing the detected language code and transcript text
3. The result is saved to local history immediately

### Audio conversion

When the selected provider does not natively support the file's MIME type, `HardwareAudioConverter` uses Android's `MediaExtractor` and `MediaCodec` to decode the audio and resample it to **16 kHz mono WAV** before sending. Temporary files older than 3 days are cleaned up automatically on app start.

---

## Supported Audio Formats

| Provider | Accepted MIME types |
|---|---|
| **ElevenLabs** | `audio/3gpp`, `audio/aac`, `audio/ac3`, `audio/aiff`, `audio/amr`, `audio/flac`, `audio/mp4`, `audio/mpeg` (MP3), `audio/ogg`, `audio/opus`, `audio/wav`, `audio/webm`, `audio/x-ms-wma`, `video/mp4`, `video/3gpp`, `video/webm`, `video/quicktime`, `video/avi`, and more |
| **Gemini** | `audio/wav`, `audio/mp3`, `audio/aiff`, `audio/aac`, `audio/ogg`, `audio/flac` |
| **Auto-converted** | Any format not in the list above is converted to 16 kHz mono WAV automatically |

---

## Screens

### Main

The main screen shows the queue of active transcription jobs. Each card displays the file name, provider/model, current status, and — once complete — the full transcript with **Copy**, **Share**, and **Summarize** buttons.

### History

A searchable list of all past transcriptions. Each item can be expanded to reveal the full text and any summary. Individual items or the entire history can be deleted.

### Settings

Configure the transcription provider, manage API keys, and enter a custom Gemini model name. Keys can be forgotten (deleted from encrypted storage) at any time.

---

## Architecture

```
MainActivity.kt              — Single Activity, NavHost, share-intent handling
MainViewModel.kt             — Job queue (Channel), transcription orchestration
HistoryViewModel.kt          — Room history as StateFlow
ApiKeyManager.kt             — Encrypted credential storage (DataStore + Keystore)
ElevenLabsApiService.kt      — Retrofit interface for ElevenLabs v2 REST API
data/HistoryDatabase.kt      — Room entity, DAO, and migrations (schema v4)
security/KeystoreCrypto.kt   — AES-256-GCM encryption helpers
media/HardwareAudioConverter.kt — MediaCodec audio → 16 kHz mono WAV
ui/theme/                    — Material Design 3 theme, colors, typography
```

**Pattern:** single-Activity MVVM with Jetpack Compose. No fragments. UI state is exposed as `StateFlow` and collected with `collectAsStateWithLifecycle()`.

**Navigation routes:**

| Route | Screen |
|---|---|
| `main` | Main (start destination) |
| `history` | History |
| `settings` | Settings |

---

## Tech Stack

| Library | Version | Purpose |
|---|---|---|
| Jetpack Compose BOM | 2026.02.01 | Declarative UI |
| Material3 | (via BOM) | Material Design 3 components |
| Navigation Compose | 2.9.7 | In-app navigation |
| Lifecycle / ViewModel | 2.10.0 | MVVM lifecycle management |
| Room | 2.8.4 | Local SQLite database |
| DataStore Preferences | 1.2.0 | Persistent key-value storage |
| Retrofit + Gson | 3.0.0 | ElevenLabs REST client |
| OkHttp Logging Interceptor | 5.3.2 | HTTP debug logging |
| Google Generative AI SDK | 1.42.0 | Gemini API client |
| KSP | 2.3.6 | Room annotation processing |
| Kotlin | 2.3.10 | Language |

---

## Permissions

| Permission | Reason |
|---|---|
| `android.permission.INTERNET` | Required to call ElevenLabs and Gemini APIs |

Audio files are accessed via Android share intents — no `READ_EXTERNAL_STORAGE` permission is needed.

---

## License

This project is closed source (for now).
