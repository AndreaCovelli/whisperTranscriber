# WhisperTranscriber

An Android app that transcribes audio files using AI — powered by [Microsoft Azure Speech MAI Transcribe](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe) or [Google Gemini](https://ai.google.dev/).
Share one or more audio files from a file manager or audio player to queue them for transcription.

---

## Features

- **Multi-provider transcription** — choose between Microsoft MAI Transcribe 2 or Gemini (Flash Lite, Flash, Pro, or a custom model)
- **Batch processing** — share multiple audio files at once; jobs are staged locally, queued, and processed sequentially
- **AI summarization** — generate summaries manually or automatically after transcription, with a separately selected Gemini model (requires a Gemini API key)
- **Speaker labels** — optionally ask Gemini to label distinguishable speakers in transcripts
- **Background sharing** — stay in the sharing app while audio is queued, and receive the transcript or summary in a result notification
- **Transcription history** — all transcripts are saved locally in a searchable Room database
- **Secure key storage** — API keys are encrypted with AES-256-GCM via Android Keystore and never stored in plaintext
- **Automatic audio conversion** — unsupported formats and Microsoft WAV inputs are converted to 16 kHz mono WAV using Android MediaCodec decoders
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

Windows:

```bat
gradlew.bat assembleDebug
```

macOS / Linux:

```bash
./gradlew assembleDebug
```

Install JDK 21 and the Android SDK (platform 37), and configure `ANDROID_HOME` or
a local, untracked `local.properties`. Use the checked-in wrapper. Run only one
Gradle invocation at a time in this workspace.

The APK will be written to `app/build/outputs/apk/debug/`.

### 3. Run tests

```bat
gradlew.bat spotlessCheck detekt lintDebug --console=plain
gradlew.bat testDebugUnitTest --console=plain
gradlew.bat connectedDebugAndroidTest --console=plain
```

For macOS / Linux, replace `gradlew.bat` with `./gradlew`. Connected tests need an
attached device or emulator and cover the debug instrumentation suite. JSON export
coverage runs separately against the `minifiedSmoke` build below.

On Linux with KVM, the reproducible managed-device suite is:

```bash
# Permission recreation runs on a fresh install before the general suite grants access.
./gradlew pixel6Api34DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.andreacovelli.whispertranscriber.NotificationPermissionRecreationInstrumentedTest
./gradlew pixel6Api34DebugAndroidTest --console=plain

# Verify JSON exports with R8 enabled; production release signing is unchanged.
./gradlew pixel6Api34MinifiedSmokeAndroidTest -PinstrumentationBuildType=minifiedSmoke \
  -Pandroid.testInstrumentationRunnerArguments.class=com.andreacovelli.whispertranscriber.HistoryExportInstrumentedTest
```

The `minifiedSmoke` build uses release optimization with debug signing and includes
only the export instrumentation sources in `app/src/androidTest/export/java/`.
Run these Gradle commands sequentially. On a headless host, add
`-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`, as CI does.

### ADB on a headless server

Install Android SDK Platform Tools and add its `platform-tools` directory to
`PATH`. The examples below use `adb` from that installation. A headless server
needs no GUI because authorization and pairing happen on the phone.

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

If the server is a VM or container, pass the USB device through to it and ensure
ADB can access the device and open its local port.

See the official Android documentation for
[connecting to a device over Wi-Fi](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

---

## Configuration

All configuration is done inside the app — no config files or build secrets needed.

1. Open **Settings** (gear icon on the main screen)
2. Select your **transcription provider**
3. For Microsoft Speech, enter your **region or HTTPS base endpoint**
4. Paste the corresponding **API key** and leave the field — the app validates it, then encrypts and saves it on success

### Getting API keys

- **Microsoft Speech**: Create an Azure Speech resource, then copy its key and either its region or HTTPS base endpoint from the Azure portal.
- **Gemini**: Generate a key at [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)

### Custom Gemini model

Select **Custom Model** in either the transcription or summarizer selector in Settings and enter a Gemini model identifier. When you leave the field, the app validates the name against the Google API, saves it on success, and shows the model's display name.

### Summaries, speaker labels, and background sharing

Choose the **Summarizer** model separately from the transcription provider. Enable
**Automatic Summarization** to summarize completed transcripts. **Speaker Diarization**
is available for Gemini transcription and labels speakers when they can be
distinguished.

Enable **Stay in current app when sharing** to queue audio without opening the main
screen. Allow notifications to receive the transcript, or the summary when automatic
summarization is enabled. These options are off by default.

### Custom Microsoft Speech endpoint

The Microsoft endpoint setting accepts either an Azure Speech region such as `eastus` or a custom HTTPS resource base endpoint such as `https://my-resource.cognitiveservices.azure.com`. Enter only the base endpoint; the app appends the supported transcription path and API version.

---

## How It Works

### Microsoft MAI Transcribe flow

1. The audio file is sent in a single request to the Azure Speech `transcriptions:transcribe` endpoint using API version `2025-10-15`.
2. The request enables enhanced mode with model `MAI-Transcribe-2`.
3. The API processes the audio and returns the full transcript in the response.
4. The finished transcript is saved to local history.

Microsoft MAI inputs must be strictly smaller than **250,000,000 bytes** and shorter than **two hours**. The app inspects both native and converted inputs before upload.

### Gemini flow

1. Audio is uploaded through the Gemini Files API and polled until it is active.
2. The model returns a structured JSON response containing the detected language code and transcript text.
3. Complete, valid results are saved to local history; the app attempts to delete uploaded files after success, failure, or cancellation.

Measurable native and converted audio is limited to 9.5 hours. Blocked, truncated,
or malformed responses preserve staged audio for retry. Transient failures allow
up to three automatic retries.

### Audio conversion

When audio is shared into the app, it is first copied into no-backup app storage so queued jobs and retries do not depend on the original share URI staying alive. A share is limited to **10 files**, **250 MB per file**, **500 MB per batch**, and a maximum of **50 unfinished jobs**. Copies stop if less than **64 MiB** would remain free.

If the selected provider does not natively support the file's MIME type, `HardwareAudioConverter` uses Android's `MediaExtractor` and `MediaCodec` to decode the audio and resample it to **16 kHz mono WAV** before sending. Microsoft WAV inputs are also re-encoded to this format. Startup maintenance preserves files referenced by active or retryable work and removes unreferenced staged files after 24 hours. Successful inputs are deleted as soon as their transcript is durable.

---

## Supported Audio Formats

| Provider           | Accepted MIME types                                                                               |
|--------------------|---------------------------------------------------------------------------------------------------|
| **Microsoft MAI**  | `audio/wav`, `audio/x-wav`, `audio/wave`, `audio/mp3`, `audio/mpeg`, `audio/flac`, `audio/x-flac` |
| **Gemini**         | `audio/wav`, `audio/x-wav`, `audio/wave`, `audio/mp3`, `audio/mpeg`, `audio/aiff`, `audio/x-aiff`, `audio/aac`, `audio/ogg`, `audio/vorbis`, `audio/flac`, `audio/x-flac` |
| **Auto-converted** | Other accepted audio shares are converted to 16 kHz mono WAV when Android can decode them |

Microsoft accepts the WAV MIME types above, but the app re-encodes them before
upload. Share intake accepts supported audio extensions (`aac`, `aif`, `aiff`, `amr`,
`flac`, `m4a`, `mp3`, `mp4`, `oga`, `ogg`, `opus`, `wav`, `wave`, `webm`); conversion
still depends on the device's decoder support.

---

## Screens

### Main

The main screen shows every active job and up to the 100 most recent terminal cards. Terminal cards are retained for 30 days; transcription history remains until the user explicitly deletes it. Failed or cancelled jobs can be retried while staged audio remains available and the queue has capacity. Share the audio again to create a new transcription after success.

### History

A searchable list of all past transcriptions. Each item can be expanded to reveal the full text and any summary. Clearing a transcript preserves its summary and metadata. Clearing either field cancels pending summarization so late results cannot restore cleared content. Individual items or the entire history can be deleted. TXT, CSV, and JSON exports read a consistent database snapshot.

Text sharing uses inline text below 128 KiB UTF-8 and a temporary text file for larger results. Shared text files expire after 24 hours and are removed during maintenance or the next file share. Oversized clipboard requests show an error with a sharing alternative.

### Settings

Configure the transcription and summarizer models, manage API keys, enter a Microsoft Speech region or custom endpoint, and control automatic summarization, Gemini speaker labels, and background sharing. Keys can be forgotten (deleted from encrypted storage) at any time.

---

## Architecture

- `MainActivity` hosts Compose navigation; `ShareReceiverActivity` accepts Android share intents through a retained intake ViewModel.
- Main and History ViewModels expose immutable state through `StateFlow` and existing MVU messages/reducers. History uses Room-backed Paging with date grouping.
- `TranscriptionQueueService`, `QueueExecutionCoordinator`, and `TranscriptionExecutionRunner` stage, recover, serialize, and persist work across WorkManager and the Android job service.
- Room stores history, FTS search, and persistent queue state (schema v15). Content mutations and claims are transactional; prior-process claims recover independently of surviving scheduler entries.
- `ApiKeyManager` and `KeystoreCrypto` encrypt credentials in DataStore. Provider transports cancel each operation independently.
- `HardwareAudioConverter` uses MediaExtractor/MediaCodec for 16 kHz mono WAV conversion.

UI state is collected with `collectAsStateWithLifecycle()`. Persistence, network requests, and conversion run outside composables.

**Navigation routes:**

| Route      | Screen                   |
|------------|--------------------------|
| `main`     | Main (start destination) |
| `history`  | History                  |
| `settings` | Settings                 |

---

## Tech Stack

Versions below match the checked-in [version catalog](gradle/libs.versions.toml).

| Library                    | Version    | Purpose                      |
|----------------------------|------------|------------------------------|
| Jetpack Compose BOM        | 2026.09.00 | Declarative UI               |
| Material3                  | (via BOM)  | Material Design 3 components |
| Navigation Compose         | 2.10.2     | In-app navigation            |
| Lifecycle / ViewModel      | 2.11.0     | MVVM lifecycle management    |
| Room                       | 2.8.5      | Local SQLite database        |
| DataStore Preferences      | 1.2.1      | Persistent key-value storage |
| OkHttp                     | 5.5.0      | Microsoft Speech HTTP client |
| Google Gen AI SDK          | 1.78.0     | Gemini API client            |
| KSP                        | 2.3.12     | Room and Hilt code generation |
| Kotlin                     | 2.4.21     | Language / Compose plugin    |

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

CI scans each pushed commit and each pull request's commits with Gitleaks, and manual CI runs scan the full Git history, while `.gitleaks.toml` excludes generated build, Gradle, and local-tool directories from filesystem scans. Grype receives only the CycloneDX SBOM generated from `releaseRuntimeClasspath` and fails on fixed high or critical findings; it does not crawl the workspace.

Equivalent scoped local scans are:

```bash
gitleaks git --config .gitleaks.toml --redact .
gitleaks dir --config .gitleaks.toml --redact .
./gradlew :app:cyclonedxDirectBom --console=plain
grype sbom:app/build/reports/cyclonedx-release/bom.json --only-fixed --fail-on high
```

The checked-in wrapper uses Gradle 9.8.1 with Detekt 1.23.8. The tooling-only Gradle 10 deprecation follow-up is tracked in [issue #159](https://github.com/AndreaCovelli/whisperTranscriber/issues/159) for review by 2027-01-17; Detekt 2.0 was still prerelease (`2.0.0-alpha.6`) as of 2026-10-19.

Dependency updates are configured through [Renovate](renovate.json), which extends
the shared `AndreaCovelli/renovate-config` preset. CI runs quality checks, the debug
build and unit tests, separate `debug` and `minifiedSmoke` device suites, security
scans, and actionlint for `.github/` changes. Path filters select applicable checks;
manual CI runs execute all checks. `CI Gate` aggregates the results. See [the release guide](release-guide.txt) for
signing setup and tag-triggered releases.

---

## License

This project is closed source (for now).
