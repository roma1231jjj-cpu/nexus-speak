# Nexus Speak 🟣

[![Release](https://img.shields.io/github/v/release/roma1231jjj-cpu/nexus-speak?label=release)](https://github.com/roma1231jjj-cpu/nexus-speak/releases/latest)
[![Android](https://img.shields.io/badge/Android-13%2B-3DDC84)](https://github.com/roma1231jjj-cpu/nexus-speak/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-8B5CF6)](LICENSE)
[![Language](https://img.shields.io/badge/UI-RU%20%2F%20EN-2563EB)](#languages)

A voice assistant for Android that talks to **your own** AI provider. Wake word, on-screen chat, phone control, screen reading, notification reading, three themes, Russian and English UI. No built-in key, no bundled provider — the app is code only.

> Part of the **Nexus × АутSquad** family (АутSquad is us — the owner and the AI that builds these apps).

**Русская версия:** [README.md](README.md) · **Screenshots:** [docs/](docs)

## Table of contents

- [Install](#install)
- [Keys and privacy](#keys-and-privacy)
- [Update integrity](#update-integrity)
- [Settings backup](#settings-backup)
- [Supported providers](#supported-providers-openai-compatible)
- [How to call it](#how-to-call-it)
- [Features](#features)
- [Languages](#languages)
- [Themes](#themes)
- [Build from source](#build-from-source)
- [Limitations (honest list)](#limitations-honest-list)
- [What's new](#whats-new)

## Install

1. Download **Nexus.apk** from [Releases](https://github.com/roma1231jjj-cpu/nexus-speak/releases/latest).
2. Allow installs from unknown sources and install the APK.
3. Android 13 or newer (`minSdk 33`). On Android 8–12 the APK will not install at all: the system refuses a build that requires API 33.
4. Open **Settings → “Choose provider”** → pick one from the list or type your own base URL → paste your API key.
5. Models and prices load automatically. Tap **“Make default assistant”**.

Updates are checked against `update.json` in this repository but are **never downloaded silently**: the version check only reports that a build exists, and the file is fetched after you press “Download”. Both the checksum and the signature are verified — see [Update integrity](#update-integrity).

## Keys and privacy

This build ships **no API key and no default provider** — only app code.
You enter the key yourself; it is stored in the app's private preferences on the phone.
The app sends nothing anywhere except to the provider you chose.

## Update integrity

`update.json` in the repository root is the only update manifest:

```json
{
  "versionCode": 59,
  "versionName": "1.10.6",
  "apkUrl": "https://…/Nexus.apk",
  "sha256": "64 hex characters — SHA-256 of the APK",
  "notes": "what's new"
}
```

What the app does:

- the version check (silent check on opening settings, or the “Check for updates” button) **never starts a download**;
- the APK (about 11 MB today) is fetched only after an explicit “Download” tap;
- SHA-256 is computed while the file is written and compared with `sha256` from the manifest. On mismatch the file is deleted and the update is cancelled with an error. A missing or malformed `sha256` means no update is offered at all (“обновление без контрольной суммы не устанавливаю” — I don't install updates without a checksum);
- before installing, the SHA-256 of the APK signing certificate is compared with the signature of the installed app (`PackageManager.getPackageInfo(…, GET_SIGNING_CERTIFICATES)`). A foreign, re-signed or broken APK is refused;
- installation goes through a `PackageInstaller` session, the final confirmation is the system one.

Check it yourself:

```bash
sha256sum Nexus.apk                        # compare with "sha256" in update.json
apksigner verify --print-certs Nexus.apk   # which key signed the file
```

## Settings backup

The app keeps two copies of your settings:

- `files/settings-backup.json` — complete, inside the app's private storage. Survives an update, disappears with the app, so it is not the only way back;
- `Downloads/Nexus Speak/nexus-settings-backup.json` — stripped copy: **no API keys, tokens, sessions and no personal data** (`userInfo`, the auto-answer greeting and chat history are never written there). That file is safe to send or show.

Restore reads a file you pick: the system SAF dialog (`ACTION_OPEN_DOCUMENT`, `application/zip` or any file) returns a JSON or a ZIP containing the backup, read through `ContentResolver`, so the app needs no access to your whole Downloads folder. The chosen file is remembered and after a reinstall the app tries it again on its own.

## Supported providers (OpenAI-compatible)

Polza.ai · OpenRouter · OpenAI · DeepSeek · Groq · Mistral · Together AI · Google Gemini · local server (LM Studio / Ollama) · any custom base URL.

Just enter the URL — the app detects the provider, tries `/models` and `/v1/models`, downloads the model list with prices per million tokens and picks the chat model and the vision model for you.

## How to call it

| Way | What works |
|---|---|
| Say “**Nexus** …” | “Nexus, what time is it”, “Nexus, turn on the flashlight” |
| Assistant button / gesture | long press the power button or a navigation gesture |
| Headset button | if Nexus Speak is the default assistant |
| App icon | big screen with **Voice** and **Chat** modes |

Wake-word pronunciations understood: `нэксус`, `нексус`, `нэкси`, `некси`, `нэкс`, `некс`, `nexus`.

## Features

### Dialogue
- **Two modes**: 🎤 Voice (overlay above any app) and 💬 Chat (messenger-style history).
- **Streaming answers** — text appears as it is generated, speech starts per sentence.
- **Messenger bubbles**: yours on the right, Nexus on the left.
- **Multiple sessions**: history, auto-generated titles, switch and delete.
- **Voice setup**: pick an installed Russian voice, set rate and pitch, test the sound.
- **Memory about you**: a “What Nexus knows about you” block is injected into every request. The AI can maintain it itself: “remember that I hate phone calls”, “what do you remember about me?”, “forget about work”.
- **Personas**: 7 built-in characters (brief, friendly, secretary, engineer, translator, kids) plus your own prompts. The hidden system prompt is not editable and never shown.
- **Macros** — your own phrase-commands: open an app, open a link, flashlight, volume, timer, pause.
- **Pause and resume**: a Pause button, auto-pause after two empty turns, voice commands “pause”, “stop”, “start over”.
- **Conversation window**: how long to wait for a follow-up after an answer (immediately / 10 / 20 / 30 / 60 s).

### Phone control
- Open apps and links, search the web.
- Flashlight, volume, alarms and timers, calls and SMS, contacts.
- **Neither a call nor an SMS is ever sent silently**: Nexus opens the system dialer (`ACTION_DIAL`) or the messaging composer (`smsto:`) with the number and the ready text filled in — you confirm and send.
- Screen reading (screenshot → vision model): “what's on the screen?”.
- Weather, rates, news — through the built-in web tool.

### Autonomy
- **Wake word** on the system speech recognizer (Google Speech Services required; without them the app says so plainly).
- **Notification reading** aloud with a spam filter and quiet hours (23:00–08:00).
- **Who is calling** — announces the contact name.
- **Call auto-answer**: after N seconds it picks up, speaks a greeting and hangs up (Android does not let an app listen to call audio).
- Quick-settings tile: turn listening on and off with one tap.

### Models
- Model catalogue with prices in ₽ straight from the provider.
- Separate models: text, fallback, vision, vision-fallback — all through search and pick, no manual IDs.
- Daily spend tracking.

## Languages

Settings → **Language / Язык — Русский / English**. Switching is instant, scroll position is kept, no restart. Everything the user sees is translated: settings, main screen, assistant panel states, chat and history, dialogs, toasts, notification and tile. Dialogues and what you type are never translated. Speech recognition and voice commands follow the UI language.

## Themes

Three named themes next to the language switch:

| Theme | Palette |
|---|---|
| **Midnight** | black + violet #8B5CF6 |
| **Frost** | white + blue #2563EB |
| **Graphite** | graphite + mint #46D39A |

Plus **AMOLED** — pure black on top of Midnight. Colours live in theme attributes, so every screen, glass card, chat bubble, dialog, status bar and system widget icon changes together — no restart, no per-theme layouts. A live 2.5D globe and floating provider orbs (Anthropic, OpenAI, Gemini, DeepSeek, Kimi, Grok, Mistral, Qwen) come from the same source.

## Build from source

The Gradle wrapper is committed (`gradle/wrapper/gradle-wrapper.jar`, Gradle 8.7), so you only need JDK 17+ and the Android SDK. The `gradlew`/`gradlew.bat` scripts are not in the repository, so run the wrapper directly:

```bash
# debug build, signed with the debug key
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug

# release build, needs your own signing key (below)
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease

# or, with Gradle 8.7+ installed
gradle assembleRelease
```

Java only, no Compose. `minSdk 33`, `targetSdk 34`. The `-Plowapi` flag lowers `minSdk` to 28 for an old emulator and screenshots only — never for a release.

Repository layout: `app/` — the Android app (code in `app/src/main/java/com/local/aivoice`), `docs/` — screenshots, `update.json` — the auto-update manifest.

### Signed release

A release is signed only with your own key: signing a release with the debug key is forbidden, and without a key the build fails with “Нет ключа подписи” (no signing key).

```bash
keytool -genkeypair -v -keystore nexus-release.jks -alias nexus \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keystore.properties` in the project root (listed in `.gitignore`, never committed):

```properties
storeFile=nexus-release.jks
storePassword=…
keyAlias=nexus
keyPassword=…
```

Environment variables work too: `NEXUS_KEYSTORE`, `NEXUS_KEYSTORE_PASSWORD`, `NEXUS_KEY_ALIAS`, `NEXUS_KEY_PASSWORD`. The APK lands in `app/build/outputs/apk/release/`.

**The APK attached to the releases of this repository is signed with a debug key** — it is a side-loaded build, not a Play Store one. An update from `update.json` only installs over an app with the same signature, so build your fork with your own key.

## Limitations (honest list)

- Android does not expose call audio to apps — the “auto-answer” can only greet and hang up.
- The voice comes from the system TTS engine. If no Russian voice is installed, Nexus says so and offers to open the synthesis settings.
- Tokens are spent on every request, jokes and weather included.
- Edge neural voices respond 403 from Russia; the app remembers that for a day and uses the phone engine instead of wasting 12 seconds per phrase.

## What's new

**1.10.5 — two-way language switching**
Language switching stopped being one-directional: EN→RU used to leave code-set strings in English because the source text was remembered already translated. Now any text is first reduced to the Russian original through a reverse dictionary and then translated to the current language — for layouts, code and `contentDescription` alike. Dialogues and your own messages are marked `L.keep()` and never translated. The “listen to Nexus” switch and dialog buttons are painted with theme colours (they were white on white), buttons are no longer all-caps, and the language switch is labelled Russian / English. Recognition and voice commands follow the UI language, with English “new chat”, “pause”, “be quiet” added. Splash is skippable by touch and capped at 2.6 s, the TTS check no longer freezes settings for three seconds, recognition timeout is released when the screen closes, and permissions are requested by button instead of all at once on start.

**1.10.4 — English on every screen**
The language switch no longer sticks: translation is applied on every screen rebuild, so late strings (answers, timings, statuses, popups) are translated too. On the light theme the background provider logos are dimmed — coloured blobs used to read as artifacts.

**1.10.3 — listens at once, livelier voice**
The voice panel no longer thinks for 2–3 seconds before starting: the recognizer is warmed at app start (`Warm`) and the synthesizer comes up after the microphone is already on. Wake-service stop wait is shorter (60 ms steps, 600 ms max). The default voice is the liveliest available: with Google Speech Services installed the app picks a network Russian voice (`ru-ru-x-ruc-network` and similar) instead of “compact”.

**1.10.2 — same look on every Android, English screenshots**
Theme glow and glass cards are no longer built from XML gradients with theme colours: those gradients are unsupported on some ROMs and the screen crashed on open (`Resources$NotFoundException: drawable/bg_blobs`). Glow is drawn by code (`NxBlobs`), the card is a solid theme layer with a light highlight on top. Same look, no crashes. Three English screenshots: settings in Midnight, settings in Frost, main screen in Graphite.

**1.10.1 — three themes, switch next to language**
A **Theme / Тема** block appeared right under the language switch. Palettes live in theme attributes (`nxBg`, `nxPrimary`, `nxTextMain` and so on) instead of screen code, so main screen, assistant panel, chat, settings, pickers, glass cards, bubbles and status bar all take colour from the theme. The choice is stored as `theme` and applied instantly via `recreate()` with scroll kept. Frost is light: dark status bar icons, blue accents, readable text. AMOLED stays on top of Midnight and turns itself off when Frost is chosen.

**1.10.0 — bilingual UI RU / EN**
A **Language / Язык — Русский / English** block in settings. Instant switching, settings scroll kept, no restart. Everything a human sees is translated: the whole settings screen (voice, models and API, persona, auto-answer, macros, updates), main screen, assistant panel states (“Listening…”, “Thinking…”, “Speaking…”), chat and history, pickers, toasts, shade notification and quick-settings tile. Translations live in `assets/i18n_en.json` and are picked up at screen open, so new strings need no layout edits. Default language comes from the system: Russian phone → Russian UI, anything else → English. Technology and provider names (OpenAI, Edge, ElevenLabs, Gemini, DeepSeek, SpeechKit) are never translated.

Older releases (1.9.x and before) are described in the Russian [README.md](README.md) and on the [releases page](https://github.com/roma1231jjj-cpu/nexus-speak/releases).
