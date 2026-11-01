# Hybrid Talkie V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a buildable four-person Android talkie with authenticated RFCOMM peers, an HTTPS/WebSocket relay, offline-member gateway forwarding, and nearby/remote team invitations.

**Architecture:** Each Android device captures and encodes its own headset microphone with Opus and mixes received sources locally. A star gateway forwards original audio frames across authenticated Bluetooth sockets and one TLS WebSocket uplink. The Go relay tracks logical members independently of their physical gateway connections and requires membership credentials before forwarding.

**Tech Stack:** Android Java 17, SDK 36/minimum 29, Gradle 9.5/AGP 9.3, OkHttp 4.12, native libopus 1.5.2 via NDK/CMake, Go net/http and Gorilla WebSocket, Nginx/systemd on the existing 139 server.

---

## Workspace and execution

Work in the managed `hybrid-talkie-v1` worktree on `codex/hybrid-talkie-v1`. The source checkout remains available. Execute without requesting another design approval; the user approved implementation on 2026-11-01. One implementer owns the independent server subtree while the primary agent implements the Android client; review server spec compliance before code quality, then review the integrated changes. Commit only intended files and push the development branch.

## Shared protocol contract

All paths start with `/ble-talkie`. UUIDs are canonical lowercase strings. Secrets are cryptographically random 32-byte URL-safe Base64 without padding; credentials never appear in URLs or logs.

Audio packets are big endian: `BTK1` (4 bytes), room UUID (16), sender UUID (16), stream epoch (uint64), sequence (uint32), source monotonic milliseconds (uint64), payload length (uint16), Opus bytes (1–1275). Header size is 58 bytes. Bluetooth frames use a uint32 big-endian payload length followed by a one-byte kind (`1` JSON control, `2` audio) and payload. Limit total payload to 16 KiB. WebSockets use text control and binary audio.

The relay implements create/import room, remote join requests with owner approval, member upsert/removal, participant authentication, proxy attach/detach, roster broadcasts and audio forwarding. A member token authenticates that member; an owner token authorizes team administration. A gateway must present the delegated token for every attached member. Route generations prevent stale gateways from reclaiming a member after network fallback. Audio is checked against the connection's currently authorized member routes, bounded, deduplicated and never returned to its ingress connection.

### Task 1: Authenticated Go relay and its behavior tests

**Files:** `server/go.mod`, `server/cmd/relay/main.go`, `server/relay/*`, `docs/protocol-v1.md`.

- [x] Add failing integration tests using `httptest.Server` and real WebSocket clients. Verify pending applicants cannot receive audio, only an owner can approve, the fifth member is rejected, and a forged sender cannot send another member's audio.
- [x] Implement `relay.New()` as an HTTP handler under the fixed prefix, then run `go test ./...` until those behaviors pass.
- [x] Add a failing A/B/C test: A authenticates, attaches authorized B, C authenticates, a B packet sent by A reaches C once and is not echoed to A, and C's packet reaches A for local fan-out.
- [x] Implement route-aware fan-out, route generation checks, bounded control/audio queues, heartbeat/read deadlines and in-memory room/request expiry. Re-run the integration tests and `go vet ./...`.
- [x] Document the actual JSON request/event shapes and keep the Android implementation synchronized with that contract. Add health, app invitation and APK download routes.

Command: `go test ./...` in `server`. Cross-compile with `GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o ../artifacts/relay ./cmd/relay`.

### Task 2: Android build and binary protocol

**Files:** `android/settings.gradle`, `android/build.gradle`, `android/app/build.gradle`, Gradle wrapper files, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/cn/sunnystring/bletalkie/core/*`, `android/app/src/test/java/cn/sunnystring/bletalkie/core/*`.

- [x] Bootstrap the Android build with cached tooling and the pinned native Opus source archive; avoid requiring Android Studio to build from a terminal.
- [x] First write failing tests for the packet layout and truncated/oversize payload rejection:

```java
AudioPacket packet = new AudioPacket(room, sender, 7L, 3, 120L, new byte[]{1,2,3});
assertEquals(61, packet.encode().length);
assertArrayEquals(packet.payload, AudioPacket.decode(packet.encode()).payload);
assertThrows(IllegalArgumentException.class, () -> AudioPacket.decode(new byte[57]));
```

- [x] Implement `AudioPacket` with strict length, UUID and payload checks. Add bounded per-source duplicate suppression and test a restarted epoch is accepted even when sequence numbers reset.
- [x] Run `:app:testDebugUnitTest` and check that no Android device is required for these routing/protocol tests.

### Task 3: Full-duplex native Opus audio and verified routes

**Files:** `android/app/src/main/cpp/CMakeLists.txt`, `opus_jni.cpp`, `audio/OpusCodec.java`, `AudioEngine.java`, `AudioRoute.java`, `third_party/opus-1.5.2.tar.gz`, license and origin metadata.

- [x] Add JNI encode/decode with handle lifetime and bounds validation; capture 320 samples per 20 ms frame at 16 kHz using the communication microphone source.
- [x] Run independent capture and playback loops. Decode each received source separately into bounded jitter queues and mix with saturation, excluding the local member.
- [x] Add routing selection and changes for API 31+ communication devices and API 29–30 Bluetooth SCO. Display actual input/output routing and require explicit user action before starting microphone capture.
- [x] Build native libraries and run a device-side encode/decode diagnostic before claiming that the codec is usable. End microphone and audio threads on stop or permission loss.

### Task 4: Bluetooth, network and offline gateway controller

**Files:** `transport/BluetoothTransport.java`, `transport/RelayClient.java`, `team/TeamController.java`, `team/TeamState.java`, `TalkieService.java`.

- [x] Implement secure RFCOMM listen/connect, bounded framing, nickname introduction, owner acceptance and member credentials over the encrypted socket.
- [x] Persist local room/member credentials and import accepted offline members when the owner comes online. Use invite-code join requests and owner approval for remote members.
- [x] Authenticate the primary member over WebSocket, attach delegated Bluetooth members, preserve original audio sender IDs, and fan remote packets out only to the required local members.
- [x] Increment per-member route generations on path changes; prefer a healthy Bluetooth gateway and use the member's network credentials when that path is lost and a network is available.
- [x] Treat lack of both Bluetooth and internet as disconnected. Drop old audio instead of replaying it after reconnect. Support explicit member removal and manual gateway reconnection.
- [x] Exercise hybrid forwarding with the server integration test and Android unit tests; reserve three-/four-phone radio and acoustic acceptance for real devices.

### Task 5: Native team UI and invitations

**Files:** `MainActivity.java`, `QrScanActivity.java`, Android resources and manifest intent filters.

- [x] Build a Chinese native screen for nickname, create/join room, nearby devices, invite-code sharing/QR, four member slots, pending invitation accept/reject, mute and start/end call.
- [x] Request only the runtime permissions needed for the initiated action. Keep microphone use in a foreground service with an ongoing notification.
- [x] Show connection type and gateway plus actual audio-route status. Show actionable failures for permissions, Bluetooth pairing, full team and expired invitations.
- [ ] Install the debug APK on the attached phone, open the app, inspect logs and a screenshot, and exercise create room, server connection, mute/end call and permission behavior.

### Task 6: Deployment, integrated review and delivery

**Files:** `deploy/ble-talkie.service`, `deploy/nginx-ble-talkie-location.conf`, `deploy/README.md`, `README.md`, build/verification artifacts ignored by Git.

- [x] Deploy the tested relay as a separate systemd service bound to an unused loopback port, under a dedicated service identity and release directory. Serve the built APK through `/ble-talkie/download`.
- [x] Replace the reserved Nginx responses with a prefix-preserving WebSocket proxy. Back up current files, run `nginx -t`, reload, and wait for the expected JSON response rather than only HTTP 200. Roll back on failure.
- [x] Verify public health, a real three-logical-member proxy exchange, unauthorized frames/room admission, APK download and unchanged existing home page.
- [x] Review spec compliance and then code quality, fix identified issues, and rerun the affected checks.
- [ ] Update the README with build/install instructions, test results and the precise unverified real-radio scenarios. Commit and push the branch; report the APK URL and source commit without claiming multi-phone acoustic tests were run.

## Acceptance boundaries

The current ADB inventory has one real phone. Native codec, app startup, earphone route and public relay integration can be checked now. Simulated logical members prove relay behavior but cannot prove simultaneous headset/RFCOMM radio performance, four physical phones, or the 30-minute acoustic/latency targets. Record these separately and leave no feature claim based solely on a passing simulated test.
