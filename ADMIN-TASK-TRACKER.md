# Hermes Android — Task Tracker

**Project:** hermes-android (fork of raulvidis/hermes-android)
**Repo:** wjfuller-uk/hermes-android
**Goal:** Pixel 4a as always-on Hermes voice assistant — wake word, voice conversation, camera, home network proxy
**Started:** May 2025

---

## ✅ Completed

### Foundation (v0.1.0–v0.3.0)
- [x] Clone raulvidis/hermes-android (38 existing tools, WS relay, pairing, TTS)
- [x] Fork to wjfuller-uk/hermes-android on GitHub
- [x] Create `tools/android_voice.py` — voice pipeline: VAD, Whisper STT, Edge TTS, `hermes -z` dialogue
- [x] Modify `tools/android_relay.py` — binary WS frames, `/voice/start`, `/voice/stop`, `/camera`, `/shell` HTTP routes
- [x] Modify `tools/android_tool.py` — 4 new tools: `android_voice_start`, `android_voice_stop`, `android_camera_capture`, `android_shell`
- [x] Create `VoiceService.kt` — foreground service: mic → PCM 16kHz mono → binary WebSocket frames
- [x] Create `AudioPlayer.kt` — AudioTrack playback for incoming TTS binary frames
- [x] Modify `RelayClient.kt` — `sendBinary()` and incoming binary frame handler for TTS audio
- [x] Modify `CommandDispatcher.kt` — voice/camera/shell route dispatch
- [x] Modify `ActionExecutor.kt` — `startVoiceMode`, `stopVoiceMode`, `takePhoto`, `execShell`
- [x] Modify `AndroidManifest.xml` — RECORD_AUDIO, CAMERA, microphone foreground service type
- [x] VPS pipeline verified: Edge TTS, Whisper, VAD, relay imports all working

### UI (v0.4.0)
- [x] Create `VoiceScreen.kt` — Compose UI with waveform animation (12 animated bars, Canvas), conversation bubbles (LazyColumn), voice state machine (IDLE/LISTENING/PROCESSING/SPEAKING), dark theme
- [x] Create `VoiceActivity.kt` — hosts Compose UI
- [x] Add "🎤 Voice Assistant" button to `MainActivity.kt`
- [x] Voice-first home screen — connection setup inline, settings via gear icon

### Infrastructure
- [x] GitHub Actions build workflow (ubuntu-latest, JDK 17, ~3 min) — VPS is ARM64, can't run x86_64 AAPT2
- [x] GitHub Releases with APK uploads
- [x] Google Drive APK uploads via `gog` CLI
- [x] Install plugin on VPS — registered `hermes-android` with Hermes
- [x] Create `android-relay.service` systemd service — relay auto-starts on reboot
- [x] Tailscale connectivity (VPS `100.111.44.87`, phone `100.74.233.23`)

### Multi-device & Notifications (v0.5.0)
- [x] Multi-device relay architecture — device registry dict, routing via `?device=<id>`
- [x] In-app logging (`AppLogger.kt`, `LogActivity.kt`)
- [x] URL-safe device ID format: `brand-model-serialhash` (no slashes in URLs)
- [x] Push notification tool `android_notify` — 4 channels: hermes, reminder, alert, calendar
- [x] Calendar tool `android_calendar_events` — pulls from Google calendar via `gog`

### Bug Fixes (v0.5.1–v0.5.3)
- [x] Fix relay race condition — `_cleanup_phone_ws` closing replacement connection
- [x] Disable server-side aiohttp heartbeat — OkHttp 20s ping conflicted with aiohttp 30s ping causing `close_code=1006`
- [x] Fix device ID URL encoding — `Build.FINGERPRINT` contained slashes
- [x] Fix double-connect race — `isConnecting` guard flag in `RelayClient.connect()`

### Text Chat (v0.5.3)
- [x] Add `ChatInputBar` composable to VoiceScreen — text field + send button, appears when connected
- [x] Add `sendChat()` method to RelayClient — sends `{type: "chat", text: "..."}` via WebSocket
- [x] Add `onChatResponse` callback for receiving Hermes responses
- [x] Add `notifyChatResponse()` handler in RelayClient
- [x] Handle `chat_response` messages from relay in `handleMessage()`
- [x] Wire up chat in VoiceActivity with message display in chat bubbles
- [x] Add chat handler to relay (`android_relay.py`) — receives text, calls `hermes -z`, returns response
- [x] Fix `hermes` command not in PATH — added venv/bin to systemd service Environment

---

## 🔴 Currently Broken / Blocked

- [ ] **Ollama weekly usage limit** — `hermes -z` returns HTTP 429. Chat pipeline works but Hermes can't respond. Waiting for limit reset or plan upgrade.
- [ ] **Voice mode not working end-to-end** — mic captures audio but STT/Hermes/TTS pipeline untested on device. User reports "mic still not working."
- [ ] **Double reconnect loops persist** — `isConnecting` guard only protects `connect()`, not `autoConnect()`. Two concurrent reconnect loops visible in phone logs (two "Reconnecting in 1000ms" messages simultaneously).

---

## 📋 To Do — v0.6.0 (Next)

### Connection Stability
- [ ] Fix double-reconnect bug — guard `autoConnect()` and `scheduleReconnect()` with same `isConnecting` flag
- [ ] Add exponential backoff to reconnect with jitter to prevent thundering herd
- [ ] Persist connection state across app restarts properly (avoid re-pairing)

### Voice Mode
- [ ] Debug and fix end-to-end voice pipeline (mic → PCM → relay → Whisper → Hermes → TTS → phone speaker)
- [ ] Test AudioPlayer works on device (PCM 16kHz playback)
- [ ] Add voice state indicators that actually reflect pipeline state (not just relay commands)

### Text Chat Polish
- [ ] Add "typing..." indicator while waiting for Hermes response
- [ ] Persist chat history across app restarts (local Room DB or SharedPreferences)
- [ ] Add markdown rendering in response bubbles (basic bold/italic/links)
- [ ] Handle long responses gracefully (scroll, expand)

---

## 📋 To Do — v0.7.0

### Camera
- [ ] Implement CameraX for proper photo capture (current `takePhoto()` falls back to screenshot)
- [ ] Add camera preview in app for framing before capture
- [ ] Support front/rear camera switching
- [ ] Add "what do you see?" quick-action button that captures + sends to Hermes vision

### Home Network Proxy
- [ ] Phone as Hermes' hands on home network (scan, ping, reach local devices)
- [ ] Expose network tools via relay: `network_scan`, `device_ping`, `service_check`
- [ ] Smart home integration (Hue, Home Assistant, etc.)

### Notifications
- [ ] Test `android_notify` end-to-end (tool → relay → phone notification)
- [ ] Add notification actions (reply, dismiss, snooze from notification)
- [ ] Notification forwarding to Hermes (phone notification → VPS context)

---

## 📋 To Do — v1.0 (Wake Word)

### Wake Word (Porcupine)
- [ ] Integrate Porcupine SDK for on-device wake word detection
- [ ] Custom "Hey Hermes" wake word model
- [ ] Battery optimization — Porcupine DSP vs always-on mic trade-off
- [ ] Wake word → voice mode activation flow
- [ ] Visual/audio feedback on wake word detection

### Audio
- [ ] Switch from raw PCM to Opus codec (~32kbps, much lower bandwidth)
- [ ] Implement streaming STT (start processing before user stops speaking)
- [ ] Add "thinking" audio filler while Hermes processes

### Dedicated Device Setup
- [ ] Pixel 4a as always-on, always-plugged-in device
- [ ] Kiosk mode / lock-to-app (prevent accidental exit)
- [ ] Auto-start on boot (BroadcastReceiver → launch VoiceActivity)
- [ ] Screen timeout override (keep screen on while in voice mode)
- [ ] Battery health management (charge limit, thermal throttling awareness)

---

## 🏗 Architecture Notes

### Stack
| Component | Tech |
|---|---|
| Android app | Kotlin, Jetpack Compose, OkHttp WebSocket |
| VPS relay | Python 3.12, aiohttp WebSocket server |
| STT | faster-whisper (base model, local on VPS) |
| TTS | Edge TTS (`en-GB-LibbyNeural`), MiniMax/Xiaomi fallback |
| Hermes | `hermes -z` one-shot mode (same as Telegram integration) |
| Connectivity | Tailscale (encrypted tunnel, no port exposure) |
| Build | GitHub Actions (ubuntu-latest, JDK 17) |

### Key Files
| File | Purpose |
|---|---|
| `hermes-android-bridge/app/src/main/kotlin/.../ui/VoiceScreen.kt` | Compose UI: waveform, chat bubbles, text input, connection panel |
| `hermes-android-bridge/app/src/main/kotlin/.../VoiceActivity.kt` | Hosts Compose UI, wires RelayClient callbacks |
| `hermes-android-bridge/app/src/main/kotlin/.../client/RelayClient.kt` | WebSocket client: connect, send/receive, chat, binary audio |
| `hermes-android-bridge/app/src/main/kotlin/.../server/CommandDispatcher.kt` | Route incoming commands to ActionExecutor |
| `hermes-android-bridge/app/src/main/kotlin/.../service/VoiceService.kt` | Foreground service: mic capture → binary WS frames |
| `hermes-android-bridge/app/src/main/kotlin/.../client/AudioPlayer.kt` | TTS audio playback from binary WS frames |
| `~/.hermes/plugins/hermes-android/android_relay.py` | VPS relay: WS server, device registry, chat handler, voice pipeline bridge |
| `~/.hermes/plugins/hermes-android/android_voice.py` | Voice pipeline: VAD → Whisper → Hermes → TTS |
| `~/.hermes/plugins/hermes-android/android_tool.py` | 46+ Hermes tools for phone control |
| `/etc/systemd/system/android-relay.service` | Systemd service: auto-start relay with pairing code + PATH |

### Device IDs
| Device | ID | Tailscale IP |
|---|---|---|
| VPS | v2202510304079387800 | `100.111.44.87` |
| Pixel 8 (test) | google-pixel-8-unknown | `100.74.233.23` |
| Pixel 4a (target) | offline 68d | `100.127.120.29` |

### Known Constraints
- VPS is ARM64 — can't run x86_64 AAPT2, must use GitHub Actions for builds
- Kotlin 1.9.22 with Compose compiler 1.5.10 (uses `composeOptions`, NOT `org.jetbrains.kotlin.plugin.compose`)
- OkHttp 20s ping interval is the only keepalive — aiohttp heartbeat disabled to prevent `close_code=1006`
- `webrtcvad` fails to import on Python 3.11 — energy-based VAD fallback
- `hermes` binary at `/root/.hermes/hermes-agent/venv/bin/hermes` — must be in relay service PATH

### Version History
| Version | Date | Key Changes |
|---|---|---|
| v0.4.0 | May 2025 | Compose UI, waveform, voice-first screen, GitHub Actions |
| v0.5.0 | May 2025 | Multi-device, notifications, calendar, in-app logging |
| v0.5.1 | May 2025 | Voice-first home screen, notification/calendar tools |
| v0.5.2 | May 2025 | Fix heartbeat conflict (1006), URL-safe device ID |
| v0.5.3 | May 2025 | Text chat input, double-connect guard, PATH fix |
