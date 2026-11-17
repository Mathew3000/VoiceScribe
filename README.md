# VoiceScribe

Transcribe WhatsApp voice messages to text. Share a voice message to the app; it transcribes and shows the text.

- **Schnell / Genauer** - on-device Whisper (small / medium int8, via sherpa-onnx), no network after the model download.
- **Hoch** - sends the audio to your own GPU server (`server/`, faster-whisper `large-v3`) with signed requests.

The language of the message is chosen in the app ("Sprache": German by default, 15 other languages, or automatic
detection) and applies to all three modes.

## Download

Newest build: **Releases -> Latest build -> VoiceScribe.apk** (built by GitHub Actions on every push to `main`).
It is a debug-signed APK; allow "install unknown apps" on the phone.

## Layout

- `app/` - Android app (Kotlin, no XML layouts, minSdk 26, arm64-v8a)
- `server/` - transcription server and its auth model; setup guide in `server/README.md`
- `.github/workflows/build-apk.yml` - builds the APK

## Build locally

Download `sherpa-onnx-2.0.1.aar` from the [sherpa-onnx v2.0.1 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) into `app/libs/`, then `./gradlew assembleDebug` (JDK 17+).

Pairing codes and device secrets are created by `server/manage.py` and never belong in this repo (`server/data/` is git-ignored).
