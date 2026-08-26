# WhisperTranscriber

An Android app that transcribes audio files using AI — powered by [Microsoft Azure Speech MAI Transcribe](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe) or [Google Gemini](https://ai.google.dev/).
 Share one or more audio files with the app from any file manager or audio player, and receive transcripts in seconds.

---

## Features

- **Multi-provider transcription** — choose between Microsoft MAI Transcribe or Gemini (Flash Lite, Flash, Pro, or a custom model)
- **Batch processing** — share multiple audio files at once; jobs are staged locally, queued, and processed sequentially
- **AI summarization** — generate a concise summary of any transcript (requires a Gemini API key)
- **Transcription history** — all transcripts are saved locally in a searchable Room database
- **Secure key storage** — API keys are encrypted with AES-256-GCM via Android Keystore and never stored in plaintext
- **Automatic audio conversion** — formats not natively accepted by the selected provider are transparently converted to 16 kHz mono WAV using hardware-accelerated MediaCodec
- **Copy & share** — copy or share any transcript or summary to any app installed on the device

---

## Requirements

|                          |                                                        |
|--------------------------|--------------------------------------------------------|
| **Minimum Android**      | 8.0 Oreo (API 26)                                      |
| **Target / Compile SDK** | 36 / 37                                                |
| **JDK**                  | 21 (resolved automatically via Gradle toolchain)       |
| **API key**              | Microsoft Speech **or** Gemini (at least one required) |

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
gradlew.bat testDebugUnitTest --console=plain
gradlew.bat connectedDebugAndroidTest --console=plain
```

On Linux with KVM, the reproducible managed-device suite is:

```bash
./gradlew pixel6Api34DebugAndroidTest --console=plain
```

### ADB on a headless server

This server already has ADB 37.0.0 installed at
`~/android-sdk/platform-tools/adb`. The examples below assume `adb` is on
`PATH`; otherwise, replace `adb` with that full path. A headless server needs no
GUI because authorization and pairing happen on the phone.

#### Recommended: wireless debugging

The phone and server must be reachable on the same LAN or VPN.

1. On the phone, enable **Developer options → Wireless debugging**.
2. Select **Pair device with pairing code**.
3. On the server over SSH, run:

   ```bash
   adb pair PHONE_IP:PAIRING_PORT
   ```

   Enter the six-digit code shown on the phone.

4. Return to the main **Wireless debugging** screen and note its IP address and
   port. This connection port is usually different from the pairing port:

   ```bash
   adb connect PHONE_IP:CONNECTION_PORT
   adb devices -l
   ```

The result should show the device with state `device`. You can then run:

```bash
adb -s PHONE_IP:CONNECTION_PORT shell
./gradlew connectedDebugAndroidTest
```

If the device and server are on different networks, use a VPN such as WireGuard
or Tailscale. Never expose an ADB port directly to the public internet.

#### USB connection

Connect the phone physically to the server, enable **USB debugging**, unlock the
phone, and accept the RSA authorization prompt. Then run:

```bash
adb devices -l
```

If Ubuntu reports insufficient USB permissions:

```bash
sudo apt install android-sdk-platform-tools-common
sudo usermod -aG plugdev "$USER"
sudo udevadm control --reload-rules
```

Log out and back in, reconnect the cable, and retry.

If the server is a VM or container, the USB device must first be passed through
to it. In the current restricted workspace, ADB cannot access `/dev/bus/usb` or
open its local port, so run these commands from the server's normal SSH shell
rather than inside the sandbox or container.

See the official Android documentation for
[connecting to a device over Wi-Fi](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

---

## Configuration

All configuration is done inside the app — no config files or build secrets needed.

1. Open **Settings** (gear icon on the main screen)
2. Select your **transcription provider**
3. Paste the corresponding **API key** into the appropriate field — it is encrypted and saved automatically

### Getting API keys

- **Microsoft Speech**: Create an Azure Speech resource, then copy its key and region from the Azure portal.
- **Gemini**: Generate a key at [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)

### Custom Gemini model

Select **Custom Model** in Settings and type any valid Gemini model identifier (e.g. `gemini-3-flash`). The app validates the name against the Google API in real time and shows the model's display name on success.

---

## How It Works

### Microsoft MAI Transcribe flow

1. The audio file is sent in a single request to the Azure Speech `transcriptions:transcribe` endpoint.
2. The request enables enhanced mode with model `mai-transcribe-1.5`.
3. The API processes the audio and returns the full transcript in the response.
4. The finished transcript is saved to local history.

Microsoft MAI inputs must be strictly smaller than **250,000,000 bytes** and shorter than **two hours**. The app inspects both native and converted inputs before upload.

### Gemini flow

1. Audio is uploaded through the Gemini File API
2. The model returns a structured JSON response containing the detected language code and transcript text
3. The finished transcript is saved to local history.

### Audio conversion

When audio is shared into the app, it is first copied into no-backup app storage so queued jobs and retries do not depend on the original share URI staying alive. A share is limited to **10 files**, **250 MB per file**, **500 MB per batch**, and a maximum of **50 unfinished jobs**. Copies stop if less than **64 MiB** would remain free.

If the selected provider does not natively support the file's MIME type, `HardwareAudioConverter` uses Android's `MediaExtractor` and `MediaCodec` to decode the audio and resample it to **16 kHz mono WAV** before sending. Startup maintenance preserves files referenced by active or retryable work and removes unreferenced staged files after 24 hours. Successful inputs are deleted as soon as their transcript is durable.

---

## Supported Audio Formats

| Provider           | Accepted MIME types                                                                               |
|--------------------|---------------------------------------------------------------------------------------------------|
| **Microsoft MAI**  | `audio/wav`, `audio/x-wav`, `audio/wave`, `audio/mp3`, `audio/mpeg`, `audio/flac`, `audio/x-flac` |
| **Gemini**         | `audio/wav`, `audio/mp3`, `audio/aiff`, `audio/aac`, `audio/ogg`, `audio/flac`                    |
| **Auto-converted** | Any format not in the list above is converted to 16 kHz mono WAV automatically                    |

---

## Screens

### Main

The main screen shows every active job and up to the 100 most recent terminal cards. Terminal cards are retained for 30 days; transcription history remains until the user explicitly deletes it.

### History

A searchable list of all past transcriptions. Each item can be expanded to reveal the full text and any summary. Individual items or the entire history can be deleted.

### Settings

Configure the transcription provider, manage API keys, enter the Microsoft Speech region, and enter a custom Gemini model name. Keys can be forgotten (deleted from encrypted storage) at any time.

---

## Architecture

```
MainActivity.kt              — Single Activity, NavHost, share-intent handling
MainViewModel.kt             — Sequential queue orchestration, staging, settings state
HistoryViewModel.kt          — Room history as StateFlow
ApiKeyManager.kt             — Encrypted credential storage (DataStore + Keystore)
TranscriptionProviders.kt    — Microsoft Speech + Gemini transcription providers
data/HistoryDatabase.kt      — Room entity, DAO, FTS, and migrations (schema v15)
security/KeystoreCrypto.kt   — AES-256-GCM encryption helpers
media/HardwareAudioConverter.kt — MediaCodec audio → 16 kHz mono WAV
ui/theme/                    — Material Design 3 theme, colors, typography
```

**Pattern:** single-Activity MVVM with Jetpack Compose. No fragments. UI state is exposed as `StateFlow` and collected with `collectAsStateWithLifecycle()`.

**Navigation routes:**

| Route      | Screen                   |
|------------|--------------------------|
| `main`     | Main (start destination) |
| `history`  | History                  |
| `settings` | Settings                 |

---

## Tech Stack

| Library                    | Version    | Purpose                      |
|----------------------------|------------|------------------------------|
| Jetpack Compose BOM        | 2026.06.01 | Declarative UI               |
| Material3                  | (via BOM)  | Material Design 3 components |
| Navigation Compose         | 2.9.8      | In-app navigation            |
| Lifecycle / ViewModel      | 2.11.0     | MVVM lifecycle management    |
| Room                       | 2.8.4      | Local SQLite database        |
| DataStore Preferences      | 1.2.1      | Persistent key-value storage |
| OkHttp                     | 5.4.0      | Microsoft Speech HTTP client |
| OkHttp Logging Interceptor | 5.4.0      | HTTP logging (bodies off)     |
| Google Generative AI SDK   | 1.63.0     | Gemini API client            |
| KSP                        | 2.3.10     | Room annotation processing   |
| Kotlin                     | 2.4.10     | Language                     |

---

## Permissions

| Permission                                      | Reason                                              |
|-------------------------------------------------|-----------------------------------------------------|
| `android.permission.INTERNET`                   | Call Microsoft Speech and Gemini APIs               |
| `android.permission.POST_NOTIFICATIONS`         | Show background progress and result notifications   |
| `android.permission.FOREGROUND_SERVICE`         | Continue user-requested transcription in background |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Declare the foreground data-sync service type     |
| `android.permission.RUN_USER_INITIATED_JOBS`    | Use Android 14 user-initiated jobs when available   |

Audio files are accessed via Android share intents — no `READ_EXTERNAL_STORAGE` permission is needed.

## Repository security

CI scans full Git history with Gitleaks while `.gitleaks.toml` excludes generated build, Gradle, and local-tool directories from filesystem scans. Grype receives only the CycloneDX SBOM generated from `releaseRuntimeClasspath` and fails on fixed high or critical findings; it does not crawl the workspace.

Equivalent scoped local scans are:

```bash
gitleaks git --config .gitleaks.toml --redact .
gitleaks dir --config .gitleaks.toml --redact .
./gradlew :app:cyclonedxDirectBom --console=plain
grype sbom:app/build/reports/cyclonedx-release/bom.json --only-fixed --fail-on high
```

The build intentionally remains on Gradle 9.6.1 and Detekt 1.23.8 until Detekt 2.0 is stable. The accepted tooling-only Gradle 10 deprecation is tracked in [issue #159](https://github.com/AndreaCovelli/whisperTranscriber/issues/159) for review by 2026-10-26.

---

## License

This project is closed source (for now).
