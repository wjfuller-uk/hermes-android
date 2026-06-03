# Hermes App Merge — Implementation Plan

> **For Hermes:** Use `subagent-driven-development` skill to implement this plan task-by-task.

**Goal:** Merge the Hermes Voice app (VAD wake, settings UI, TV support) into Hermes Bridge (35+ remote control commands, chat, voice pipeline, widgets) — one APK to rule them all.

**Architecture:** Start from the Bridge codebase (`hermes-android`, 37 files, ~6,100 LOC). Port the 5 Voice-specific features (WakeWordEngine, SettingsManager, DebugPanel, TvActivity, BootReceiver) into it. The relay server needs zero changes — it already handles both protocols in a single WebSocket endpoint.

**Tech Stack:** Kotlin 1.9.22, Compose BOM 2024.02.00, OkHttp 4.12.0, Ktor Server (Netty), Gson, minSdk 26 / targetSdk 34

---

## Strategy Decision

| Direction | Files to add | LOC to add | Risk |
|-----------|-------------|-----------|------|
| A: Bridge features → Voice app | 25+ | ~5,000 | High — rewiring 35+ commands, Ktor server, AccessibilityService |
| **B: Voice features → Bridge app** | **5** | **~500** | **Low — adding standalone modules to a working app** |

**Chosen: Direction B.** The Bridge already has voice, chat, widgets, and all remote control. We add Voice-specific improvements (VAD wake, settings UI, TV launcher, boot) as self-contained modules.

---

## Architecture (after merge)

```
┌────────────────── Hermes Unified APK ──────────────────┐
│                                                        │
│  ┌── Launchers ────────────────────────────────────┐  │
│  │  VoiceActivity (phone, LAUNCHER)                │  │
│  │  TvActivity (TV, LEANBACK_LAUNCHER)             │  │
│  └─────────────────────────────────────────────────┘  │
│                                                        │
│  ┌── Voice Pipeline ───────────────────────────────┐  │
│  │  WakeWordEngine (VAD) → VoiceService (PCM)      │  │
│  │  → RelayClient (WS binary) → relay              │  │
│  │  → STT → Hermes → TTS → AudioPlayer             │  │
│  └─────────────────────────────────────────────────┘  │
│                                                        │
│  ┌── Remote Control (existing, untouched) ─────────┐  │
│  │  BridgeAccessibilityService → ScreenReader      │  │
│  │  → ActionExecutor (tap/type/swipe/shell/camera) │  │
│  │  ← CommandDispatcher ← BridgeServer (:8765)     │  │
│  │  ← RelayClient ← relay (WS command envelope)    │  │
│  └─────────────────────────────────────────────────┘  │
│                                                        │
│  ┌── UI ───────────────────────────────────────────┐  │
│  │  VoiceScreen (chat, bubbles, markdown, cards)   │  │
│  │  DebugPanel (settings + debug, gear icon ⚙)    │  │
│  │  TvScreen (TV: Status/Settings/Debug tabs)      │  │
│  │  DiagnosticsScreen, LogActivity                 │  │
│  └─────────────────────────────────────────────────┘  │
│                                                        │
│  ┌── Infrastructure ───────────────────────────────┐  │
│  │  SettingsManager (VAD threshold, persisted)     │  │
│  │  BootReceiver (auto-start on BOOT_COMPLETED)    │  │
│  │  Notifier (push notifications, 4 channels)      │  │
│  │  NotificationStore, AppLogger, CrashReporter    │  │
│  └─────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
         │
         │ WebSocket (ws://100.111.44.87:8766)
         │   - JSON commands: request_id + method + path + body
         │   - Voice: type-based messages + binary PCM
         │   - Chat: type: "chat" → "chat_response" + cards
         ▼
┌───────────── android_relay.py (port 8766) ─────────────┐
│  Device registry → hermes_voice_bridge → Hermes LLM     │
│  HTTP tool endpoints ← android_tool.py (42 tools)       │
└─────────────────────────────────────────────────────────┘
```

---

## What changes

### Files to create (5)
| # | File | Source | Purpose |
|---|------|--------|---------|
| 1 | `wake/WakeWordEngine.kt` | hermes-voice | VAD-based energy detection |
| 2 | `SettingsManager.kt` | hermes-voice | Persisted VAD threshold |
| 3 | `ui/DebugPanel.kt` | hermes-voice | Settings + debug slide-up |
| 4 | `TvActivity.kt` | hermes-voice | TV launcher with tabs |
| 5 | `receiver/BootReceiver.kt` | hermes-voice | Auto-start on boot |

### Files to modify (5)
| # | File | Change |
|---|------|--------|
| 6 | `service/VoiceService.kt` | Integrate WakeWordEngine: only stream when VAD detects speech |
| 7 | `VoiceActivity.kt` | Add gear ⚙ icon → DebugPanel; init SettingsManager |
| 8 | `BridgeApplication.kt` | Init SettingsManager on startup |
| 9 | `AndroidManifest.xml` | Add TvActivity, BootReceiver, TV feature flags, permissions |
| 10 | `app/build.gradle.kts` | Bump version to 0.9.0 |

### Files unchanged
- Relay server: **zero changes needed**
- All 35+ command implementations: untouched
- Chat, widgets, VoiceScreen: untouched
- AccessibilityService, ScreenReader, ActionExecutor: untouched
- BridgeServer, BridgeRouter: untouched

---

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Bridge's VoiceService streams ALL mic audio — adding VAD might break existing voice mode | VAD is an optional gate, on by default; VoiceService checks `SettingsManager.vadThreshold` before streaming |
| DebugPanel reads `VoiceUiState` — Bridge uses different state model | Adapt DebugPanel to read from Bridge's `RelayClient` callbacks + `VoiceService` state directly |
| TV activity might conflict with `VoiceActivity` (both exported) | Different intent filters: LAUNCHER (phone) vs LEANBACK_LAUNCHER (TV) — Android resolves correctly per device type |
| BootReceiver declared in manifest but not implemented yet | Create the receiver file Bridge expects |

---

## Tasks

### Task 1: Copy WakeWordEngine from Voice to Bridge

**Objective:** Port the VAD-based wake word engine so the Bridge can detect speech before streaming audio

**Files:**
- Create: `app/src/main/java/com/hermesandroid/bridge/wake/WakeWordEngine.kt`
- Source: `/root/hermes-voice/app/src/main/java/com/hermes/voice/wake/WakeWordEngine.kt`

**Step 1: Read source and copy**

Copy the file from hermes-voice, change `package com.hermes.voice.wake` → `package com.hermesandroid.bridge.wake`. No other changes — it's a standalone utility class.

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
cd /root/hermes-android
git add app/src/main/java/com/hermesandroid/bridge/wake/
git commit -m "feat: port WakeWordEngine from hermes-voice"
```

---

### Task 2: Copy SettingsManager from Voice to Bridge

**Objective:** Persist VAD threshold across app restarts

**Files:**
- Create: `app/src/main/java/com/hermesandroid/bridge/SettingsManager.kt`
- Source: `/root/hermes-voice/app/src/main/java/com/hermes/voice/SettingsManager.kt`

**Step 1: Create file with adjusted package**

Change `package com.hermes.voice` → `package com.hermesandroid.bridge`, and `PREFS_NAME` to `"hermes_bridge_settings"`. Keep the same structure otherwise.

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/SettingsManager.kt
git commit -m "feat: port SettingsManager from hermes-voice"
```

---

### Task 3: Integrate WakeWordEngine into Bridge's VoiceService

**Objective:** Add VAD gating to VoiceService so it only streams mic audio when speech is detected

**Files:**
- Modify: `app/src/main/java/com/hermesandroid/bridge/service/VoiceService.kt`

**Step 1: Add VAD gating in the audio recording loop**

In the while loop that reads from AudioRecord, before writing binary frames, add RMS check:

```kotlin
import com.hermesandroid.bridge.SettingsManager
import com.hermesandroid.bridge.wake.WakeWordEngine

// In the audio read loop, before relayClient?.sendBinary(...):
val rms = WakeWordEngine.calculateRms(buffer, bytesRead)
val threshold = SettingsManager.vadThreshold.toFloat()
if (rms >= threshold) {
    relayClient?.sendBinary(buffer.copyOf(bytesRead))
    WakeWordEngine.notifySpeech()
} else {
    WakeWordEngine.notifySilence()
}
```

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/service/VoiceService.kt
git commit -m "feat: add VAD gating to VoiceService via WakeWordEngine"
```

---

### Task 4: Add DebugPanel (settings + debug UI) to Bridge

**Objective:** Port the settings/debug panel that slides up from the gear ⚙ icon

**Files:**
- Create: `app/src/main/java/com/hermesandroid/bridge/ui/DebugPanel.kt`
- Source: `/root/hermes-voice/app/src/main/java/com/hermes/voice/ui/DebugPanel.kt`

**Step 1: Adapt for Bridge**

Read the Voice source, then adapt:
- Package: `com.hermesandroid.bridge.ui`
- Import `com.hermesandroid.bridge.SettingsManager` instead of `com.hermes.voice.SettingsManager`
- Import `com.hermesandroid.bridge.wake.WakeWordEngine` instead of `com.hermes.voice.wake.WakeWordEngine`
- Import `com.hermesandroid.bridge.client.RelayClient` instead of `com.hermes.voice.client.RelayClient`
- Import `com.hermesandroid.bridge.service.VoiceService` instead of `com.hermes.voice.service.VoiceService`
- Device ID: `"hermes-bridge"`
- Remove dependency on `VoiceUiState` — read state directly from `RelayClient.isConnected`, `VoiceService.currentState`, `WakeWordEngine.getThreshold()`, etc.

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/ui/DebugPanel.kt
git commit -m "feat: add DebugPanel (settings + debug) to Bridge"
```

---

### Task 5: Wire gear ⚙ icon into VoiceActivity to open DebugPanel

**Objective:** Add the settings gear icon to VoiceActivity, opening DebugPanel on tap

**Files:**
- Modify: `app/src/main/java/com/hermesandroid/bridge/VoiceActivity.kt`

**Step 1: Add gear icon and DebugPanel overlay**

In the `setContent { ... }` block, add a `var showDebug` boolean state. Wrap the existing content in a Box, add a gear TextButton in the top-end corner, and conditionally show DebugPanel:

```kotlin
var showDebug by remember { mutableStateOf(false) }

// Inside the top-level layout, after VoiceScreen or as a Box wrapper:
// Gear button at top-right
TextButton(
    onClick = { showDebug = true },
    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
) {
    Text("⚙", fontSize = 24.sp)
}

// Debug panel overlay
if (showDebug) {
    DebugPanel(onDismiss = { showDebug = false })
}
```

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/VoiceActivity.kt
git commit -m "feat: wire gear icon + DebugPanel into VoiceActivity"
```

---

### Task 6: Init SettingsManager in BridgeApplication

**Objective:** Ensure SettingsManager is initialized at app startup so VAD thresholds persist

**Files:**
- Modify: `app/src/main/java/com/hermesandroid/bridge/BridgeApplication.kt`

**Step 1: Add init call**

After other singleton init calls, add:

```kotlin
import com.hermesandroid.bridge.SettingsManager
SettingsManager.init(this)
```

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/BridgeApplication.kt
git commit -m "feat: init SettingsManager in BridgeApplication"
```

---

### Task 7: Create BootReceiver for auto-start on boot

**Objective:** Auto-start VoiceService when device boots (critical for always-on TV box)

**Files:**
- Create: `app/src/main/java/com/hermesandroid/bridge/receiver/BootReceiver.kt`

**Step 1: Create receiver**

```kotlin
package com.hermesandroid.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hermesandroid.bridge.service.VoiceService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
```

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/receiver/
git commit -m "feat: add BootReceiver for auto-start on boot"
```

---

### Task 8: Create TvActivity with TV-optimized UI

**Objective:** Add a TV launcher activity with D-pad navigable Status/Settings/Debug tabs

**Files:**
- Create: `app/src/main/java/com/hermesandroid/bridge/TvActivity.kt`
- Source: `/root/hermes-voice/app/src/main/java/com/hermes/voice/TvActivity.kt` (the full v0.3.1 TvActivity we just built)

**Step 1: Port with adjusted package and imports**

Read the source file from hermes-voice (it has the complete TvActivity with TvScreen, TvStatusTab, TvSettingsTab, TvDebugTab, TvButton, SectionCard, InfoPanel, DebugLabel composables). Then adapt:
- Package: `com.hermesandroid.bridge`
- ALL imports from `com.hermes.voice.*` → `com.hermesandroid.bridge.*`
- `com.hermes.voice.ui.*` → `com.hermesandroid.bridge.ui.*`
- `com.hermes.voice.client.RelayClient` → `com.hermesandroid.bridge.client.RelayClient`
- `com.hermes.voice.service.VoiceService` → `com.hermesandroid.bridge.service.VoiceService`
- `com.hermes.voice.wake.WakeWordEngine` → `com.hermesandroid.bridge.wake.WakeWordEngine`
- `com.hermes.voice.SettingsManager` → `com.hermesandroid.bridge.SettingsManager`
- Device ID: `"hermes-bridge-tv"`

**Step 2: Verify**

```bash
cd /root/hermes-android && ./gradlew compileReleaseKotlin 2>&1 | tail -5
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hermesandroid/bridge/TvActivity.kt
git commit -m "feat: add TvActivity with TV-optimized settings/debug UI"
```

---

### Task 9: Update AndroidManifest.xml

**Objective:** Declare all new components and TV feature flags

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Add TV feature flags** (after existing `<uses-feature>` declarations):

```xml
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-feature android:name="android.software.leanback" android:required="false" />
```

**Step 2: Add permissions** (if not already present):

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

**Step 3: Add TvActivity** (after VoiceActivity):

```xml
<activity
    android:name=".TvActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:screenOrientation="landscape"
    android:keepScreenOn="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent-filter>
</activity>
```

**Step 4: Add BootReceiver** (if not already declared):

```xml
<receiver
    android:name=".receiver.BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

**Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add TV activity, BootReceiver, and feature flags to manifest"
```

---

### Task 10: Bump version, build, and verify

**Objective:** Update version to 0.9.0, do a clean build, verify both launchers present

**Files:**
- Modify: `app/build.gradle.kts`

**Step 1: Bump version**

```kotlin
versionCode = 11
versionName = "0.9.0"
```

**Step 2: Full build**

```bash
cd /root/hermes-android && ./gradlew assembleRelease 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL with zero warnings

**Step 3: Verify both launcher activities in APK**

```bash
unzip -p app/build/outputs/apk/release/app-release.apk AndroidManifest.xml 2>/dev/null | grep -o 'android.intent.category.LAUNCHER\|android.intent.category.LEANBACK_LAUNCHER'
```
Expected: both `LAUNCHER` and `LEANBACK_LAUNCHER` appear

**Step 4: Verify APK size**

```bash
ls -lh app/build/outputs/apk/release/app-release.apk
```
Expected: ~6–8 MB (normal for Compose + Ktor + OkHttp)

**Step 5: Commit and push**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 0.9.0"
git push origin main
```

---

## Verification Checklist

After all tasks complete:

- [ ] `./gradlew assembleRelease` passes with zero errors and zero warnings
- [ ] APK contains both `VoiceActivity` (LAUNCHER) and `TvActivity` (LEANBACK_LAUNCHER)
- [ ] `VoiceService` uses VAD threshold from `SettingsManager` before streaming audio
- [ ] Gear ⚙ icon visible on VoiceScreen → opens DebugPanel with VAD slider + debug info
- [ ] DebugPanel Save button persists VAD threshold to SharedPreferences
- [ ] TvActivity renders Status/Settings/Debug tabs correctly (landscape, D-pad navigable)
- [ ] Existing Bridge functionality untouched: AccessibilityService, 35+ commands, chat, widgets, HTTP server on :8765
- [ ] `AndroidManifest.xml` declares `touchscreen` and `leanback` as optional features
- [ ] `BootReceiver` registered for `BOOT_COMPLETED` and `QUICKBOOT_POWERON`

---

## What stays the same

These Bridge components require **zero changes**:
- `CommandDispatcher.kt` — all 35+ endpoints
- `ActionExecutor.kt` — tap, type, swipe, shell, camera, etc.
- `BridgeAccessibilityService.kt` — screen reading
- `ScreenReader.kt` — a11y tree traversal
- `RelayClient.kt` — WebSocket transport (already handles both protocol types)
- `VoiceScreen.kt` — chat UI, bubbles, markdown, cards (unchanged)
- `BridgeServer.kt` / `BridgeRouter.kt` — local HTTP server on :8765
- `Notifier.kt` — push notifications
- `BridgeNotificationListener.kt` — notification reading
- `RelayConnectionService.kt` — wake lock for persistent connection
- `CrashReporter.kt`, `AppLogger.kt`, `PermissionHelper.kt`
- All widgets (`CalendarWidget`, `WidgetCard`, `WidgetRegistry`)
- `android_relay.py` / `hermes_voice_bridge.py` — relay server unchanged

## Total impact

| Metric | Count |
|--------|-------|
| Files created | 5 |
| Files modified | 5 |
| Lines added | ~500 |
| Lines deleted | ~0 |
| Risk to existing features | None (additive changes only) |
| Relay changes needed | 0 |
