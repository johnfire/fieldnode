# Fieldnode

**A personal Android capture app that turns your phone into a field node for your own AI/agent backend.**
Capture a thought, a shared snippet, or a photo — anywhere, even offline — and it queues locally and
dispatches to *your* server, which can triage, store, or route it however you like.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
· Native Kotlin · Android 11+ · sideloaded (personal use)

---

## Why

Phone assistants try to be everything and own nothing. Fieldnode is the opposite: it does **one** job —
be the always-with-you front door to a backend *you* run. The phone is the only device that goes into
the world with you (the customer site, the studio, the walk where the idea happens), so its job is to
**capture and dispatch**, not to be clever. The intelligence lives on your server.

It's built **personal and sideloaded on purpose** — that unlocks `MANAGE_EXTERNAL_STORAGE` (full-device
file access) that an app store would never allow, which makes the file-agent half genuinely useful.

## What it does

```
┌──────────────────────── Android app (Kotlin) ───────────────────────┐
│  CAPTURE            share-target (text / image) · manual note        │
│       │                                                              │
│       ▼                                                              │
│  OFFLINE QUEUE      one file per capture; never blocks on network    │
│       │                                                              │
│       ▼                                                              │
│  DISPATCH ──HTTPS+device token──►  your forwarder  ──►  your backend │
│                                                                      │
│  + a safe FILE ENGINE: full-device files behind a trash/scope/log    │
│    gate, with a debug-UI file browser                                │
└──────────────────────────────────────────────────────────────────────┘
```

Two halves:

1. **Capture → queue → dispatch.** Share anything to Fieldnode (or type a note); it persists to an
   offline-first queue and posts to your HTTPS endpoint with a device token. Failures stay queued and
   retry — nothing is lost away from signal. A tiny example **forwarder** (`server/`, Python/FastAPI)
   shows the server side: it holds your backend's key server-side and forwards captures to it (the
   reference target is [Open Brain](https://github.com/), but it's a ~40-line template you can point at
   anything).

2. **A safe full-device file engine.** Because it's sideloaded, the app can read/move/delete files
   across the whole device — so that power is **caged by construction**: delete routes to a trash (never
   a raw `rm`), overwrite trashes the prior version, every mutation is confined to an *earned-scope*
   allowlist and written to an append-only action log, and destructive ops pass a confirm gate. There's
   a debug-UI file browser to drive it all by hand.

## Build & run the app

Requires Android Studio / the Android SDK and a device on **Android 11+** (API 30).

```bash
./gradlew :app:assembleDebug          # build
adb install -r app/build/outputs/apk/debug/app-debug.apk   # install
# (on MIUI/Xiaomi, prefer scripts/deploy.sh — it installs via `pm install` to skip the install prompt)
```

On first launch, grant **All-files access** when prompted (`MANAGE_EXTERNAL_STORAGE`). Unit tests for
the file engine and capture queue run on the JVM with no device:

```bash
./gradlew :app:testDebugUnitTest
```

## The forwarder (server)

A thin, authenticated bridge so the phone never holds your backend's secret. See [server/README.md](server/README.md)
for the one-time setup (Docker + Apache + Let's Encrypt) and `server/.env.example` for config. In short:

- `POST /capture` — header `X-Device-Token`; body `{text, kind, ...}`. Forwards `text` to your backend.
- `GET /health` — liveness.

## Configure the phone

The app reads its endpoint + token from `Fieldnode/fleet.config` on the device:

```
endpoint=https://fieldnode.example.com/capture
token=<your device token>
```

(To flush the queue from automation, broadcast to the receiver explicitly — it's not exported, so
only the privileged adb shell can reach it, not other apps:
`adb shell am broadcast -n de.christopherrehm.fieldnode/.DispatchReceiver -a de.christopherrehm.fieldnode.DISPATCH`)

> **Known trade-off:** `fleet.config` holds the device token and (if configured) the ntfy token in
> plaintext under `/storage/emulated/0/Fieldnode/`, which is readable by any app holding storage
> access — a conscious call to avoid building a settings screen before v0 proved the concept. Both
> tokens are narrowly scoped (device token: post-captures-only, revocable; ntfy token: read-scoped to
> one topic), which limits the blast radius of a leak, but this is not app-private storage. When a
> settings screen lands, move this to app-private storage (`filesDir`) or `EncryptedSharedPreferences`.

## A note from building this on a Redmi Note 8 Pro (MIUI)

The repo includes a Doze "survival canary" foreground service. Finding on stock MIUI 12.5.7: a
foreground service is **not killed** in deep idle — but a self-scheduled timer can't reliably fire
through true hardware-suspend Doze, so notify-back features should use **high-priority push**
(FCM/ntfy/UnifiedPush), not a timer. MIUI also needs Autostart on + battery "No restrictions" + lock-in-recents.

## Status

Working: full-device file engine + safety gate, capture (text/image share-target + manual), offline
queue, and end-to-end dispatch to a backend over HTTPS. Not yet: voice capture, image-attachment
dispatch, automatic background dispatch on connectivity, on-device triage.

## License

[Apache License 2.0](LICENSE). Copyright © 2026 Christopher Rehm.

> Personal/sideloaded tool. It requests full-device file access; review the code before granting it.
> Provided as-is, no warranty. Not affiliated with Google or Xiaomi.
