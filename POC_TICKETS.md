# Offline Broadcast POC — Ticket Breakdown

## Epics Overview

| Epic | Area | Tickets |
|---|---|---|
| E1 | Project Setup | T1 |
| E2 | Hotspot Layer | T2, T3 |
| E3 | Local HTTP + SSE Server | T4, T5, T6, T7 |
| E4 | Sender UI | T8, T9, T10, T11 |
| E5 | Receiver Page | T12, T13, T14 |
| Spikes | Risk Validation | S1, S2 |

---

## Spikes (Do These First)

### S1 — Spike: Verify `startLocalOnlyHotspot()` end-to-end on a real device

**Goal:** Confirm that a third-party Android app can start a local hotspot, retrieve the SSID/password from the callback, and that another device can actually join and make an HTTP request to the host.

**Why first:** Everything downstream depends on this. If the IP assignment or connectivity between devices doesn't work as expected, the architecture needs to change before any server code is written.

**Done when:**
- [ ] App starts hotspot on a real Android device (not emulator)
- [ ] SSID and password are retrieved from `LocalOnlyHotspotReservation`
- [ ] A second device joins the hotspot using those credentials
- [ ] Second device can `curl` or browser-request `http://<host-ip>:<port>/` and get a response

---

### S2 — Spike: Verify SSE works in Chrome Android over local hotspot

**Goal:** Confirm that `EventSource` in Chrome on Android receives events from a local HTTP server running on the hotspot host device in real time.

**Why first:** SSE over a local-only LAN is the core broadcast primitive. If Android's network stack throttles or blocks keep-alive connections on local-only hotspots, the entire approach changes.

**Done when:**
- [ ] Receiver device opens `http://<host-ip>:<port>/events` in Chrome
- [ ] Server pushes a test event
- [ ] Chrome receives and logs the event with no timeout or connection drop after 30 seconds

---

## E1 — Project Setup

### T1 — Scaffold new native Android project with embedded web UI

**Goal:** Set up the new project with a native Android shell that can host the web Sender UI and run background services (HTTP server, hotspot).

**Notes:**
- Choose between: pure Kotlin native app with a WebView, or Capacitor wrapping a web UI
- HTTP server and hotspot APIs require native Android code regardless of choice
- Receiver page is plain HTML/JS — no framework needed

**Done when:**
- [ ] Project builds and runs on a real Android device
- [ ] Basic "Hello World" screen visible
- [ ] Project structure separates: native layer / sender UI / receiver page assets

---

## E2 — Hotspot Layer

### T2 — Implement Android `startLocalOnlyHotspot()`

**Goal:** Start and stop an offline Wi-Fi hotspot from within the app, and surface the network credentials to the rest of the app.

**Permissions required:**
- Android < 13: `ACCESS_FINE_LOCATION`
- Android 13+: `NEARBY_WIFI_DEVICES`

**Done when:**
- [ ] App requests the correct runtime permission based on API level
- [ ] `WifiManager.startLocalOnlyHotspot()` starts the hotspot on button tap
- [ ] SSID and password are retrieved from `LocalOnlyHotspotReservation`
- [ ] Hotspot is stopped cleanly on session end or app close
- [ ] Host IP on the hotspot interface (`wlan0` or equivalent) is resolved and stored

---

### T3 — Handle hotspot lifecycle and error states

**Goal:** Make the hotspot robust — handle cases where it fails to start, is taken over by another app, or the device doesn't support it.

**Done when:**
- [ ] `onFailed()` callback surfaces a user-readable error message
- [ ] `onStopped()` callback restarts or notifies the user
- [ ] App handles the case where another app already holds the hotspot reservation
- [ ] Hotspot state (starting / active / stopped) is reflected in the Sender UI

---

## E3 — Local HTTP + SSE Server

### T4 — Start a local HTTP server bound to the hotspot IP

**Goal:** Run a lightweight HTTP server on the device that listens on the hotspot interface IP and serves content to connected receivers.

**Suggested library:** NanoHTTPD (Android/Java) or Ktor (Kotlin)

**Done when:**
- [ ] HTTP server starts on app launch / session start
- [ ] Server binds to the hotspot IP and a fixed port (e.g. 8080)
- [ ] `GET /` returns a 200 with placeholder HTML
- [ ] Server shuts down cleanly when hotspot stops

---

### T5 — Implement SSE `/events` endpoint

**Goal:** Create a persistent SSE stream that all receiver browsers connect to and receive push events from.

**SSE response headers:**
```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

**Event format:**
```
data: {"id":"msg-123","text":"Gate change to B12","ts":1728370800}\n\n
```

**Done when:**
- [ ] `GET /events` keeps the connection open
- [ ] Server maintains a list of all active SSE client connections
- [ ] A test event pushed from the server appears in all connected browser tabs
- [ ] Disconnected clients are removed from the list cleanly

---

### T6 — Implement `POST /message` and broadcast

**Goal:** Accept a message from the Sender UI and immediately push it to all connected SSE clients.

**Request body:**
```json
{ "text": "Boarding now at Gate B12" }
```

**Done when:**
- [ ] `POST /message` accepts a JSON body
- [ ] Server assigns a unique ID and timestamp to the message
- [ ] Message is broadcast as an SSE event to all connected receivers
- [ ] Message is appended to the in-memory history store

---

### T7 — Implement `GET /history` endpoint

**Goal:** Allow receivers who join late to fetch all past messages sent in the current session.

**Response:**
```json
[
  { "id": "msg-001", "text": "Welcome aboard", "ts": 1728370700 },
  { "id": "msg-002", "text": "Delay of 20 minutes", "ts": 1728370800 }
]
```

**Done when:**
- [ ] `GET /history` returns all messages sent in the current session in chronological order
- [ ] Returns empty array `[]` when no messages have been sent
- [ ] History is cleared when the session ends (hotspot stopped)

---

## E4 — Sender UI

### T8 — Start / Stop Broadcast screen

**Goal:** Give the Sender a clear entry point to start a broadcast session and see the hotspot status.

**Done when:**
- [ ] "Start Broadcast" button triggers hotspot + server startup
- [ ] Loading state shown while hotspot is starting
- [ ] Active state shown with session info (network name, number of connected receivers)
- [ ] "Stop Broadcast" ends the session and stops hotspot + server

---

### T9 — Display QR Code 1 (Wi-Fi credentials)

**Goal:** Show a scannable QR code encoding the hotspot credentials so receivers can join with one scan.

**QR format:**
```
WIFI:S:<SSID>;T:WPA;P:<password>;;
```

**Done when:**
- [ ] QR code is generated from the SSID and password retrieved in T2
- [ ] QR is displayed prominently on the Sender's screen after hotspot starts
- [ ] Scanning with native camera on Android 10+ and iOS 11+ prompts to join the network
- [ ] Label below QR reads "Step 1: Scan to join the network"

---

### T10 — Display QR Code 2 (Server URL)

**Goal:** Show a second scannable QR code encoding the local server URL so receivers can open the page in their browser.

**QR content:** `http://192.168.x.x:8080`

**Done when:**
- [ ] QR code is generated from the resolved hotspot IP + port
- [ ] Displayed alongside QR 1 on the Sender screen
- [ ] Scanning with native camera opens `http://<ip>:8080` in Chrome/Safari
- [ ] Label reads "Step 2: Scan to open the message feed"

---

### T11 — Message composition and send

**Goal:** Let the Sender type a message and send it to all connected receivers in one tap.

**Done when:**
- [ ] Text input field for composing a message
- [ ] "Send" button triggers `POST /message` to the local server
- [ ] Input clears after successful send
- [ ] Sent message appears in a local history list on the Sender screen
- [ ] Error state shown if POST fails

---

## E5 — Receiver Page

### T12 — Receiver HTML page served from local server

**Goal:** Create the HTML/JS page that is served at `GET /` and acts as the receiver's interface.

**Notes:**
- Plain HTML + vanilla JS — no framework, no build step
- Must load fast (served over local LAN, but keep it minimal)
- Bundled as a static asset inside the native app

**Done when:**
- [ ] `GET /` serves the receiver HTML page
- [ ] Page loads in Chrome Android and Safari iOS without errors
- [ ] Page title: "Messages"
- [ ] Page shows a placeholder "Waiting for messages..." state

---

### T13 — SSE connection and live message rendering

**Goal:** Connect the receiver page to the SSE stream and display incoming messages in real time.

**Done when:**
- [ ] `EventSource` connects to `GET /events` on page load
- [ ] Each incoming SSE event renders a new message card on the page
- [ ] Messages render newest-first (or with clear visual order)
- [ ] Connection error state shown if SSE drops

---

### T14 — Load message history on join

**Goal:** Fetch and display past messages for receivers who join mid-session.

**Done when:**
- [ ] On page load, `GET /history` is called before opening SSE
- [ ] Past messages are rendered first, then live messages append below
- [ ] No duplicate messages if a past message arrives again via SSE

---

### T15 — Basic receiver page styling

**Goal:** Make the receiver page readable, clear, and accessible at a glance — even across the cabin or room.

**Done when:**
- [ ] Large, legible font (minimum 20px body, 28px+ message text)
- [ ] High contrast — dark background, white text (or vice versa)
- [ ] Each message card clearly separated
- [ ] Page is responsive — readable on both small and large phone screens
- [ ] No horizontal scrolling

---

## Ticket Order / Suggested Sequence

```
S1 → S2 (spikes first — validate the core assumptions)
    │
    ▼
T1 (project scaffold)
    │
    ▼
T2 → T3 (hotspot layer)
    │
    ▼
T4 → T5 → T6 → T7 (server layer, in order)
    │
    ├── T8 → T9 → T10 → T11 (Sender UI, in order)
    └── T12 → T13 → T14 → T15 (Receiver page, in order)
```

Sender UI and Receiver Page can be built in parallel once the server layer is done.
