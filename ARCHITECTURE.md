# Offline Broadcast Primitive — Architecture & Design Q&A

## Core Idea
One device (Sender) broadcasts a payload to N nearby devices (Receivers) using a local HTTP server over an offline hotspot. No WebRTC, no internet, no receiver-side install.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  SENDER DEVICE                      │
│                                                     │
│  ┌──────────────┐     ┌──────────────────────────┐  │
│  │  Native App  │────▶│   Local HTTP Server      │  │
│  │  (Sender UI) │     │                          │  │
│  │              │     │  GET  /          → HTML   │  │
│  │  [Compose]   │     │  GET  /events    → SSE    │  │
│  │  [Send]      │     │  GET  /history   → JSON   │  │
│  └──────────────┘     │  POST /message   → inbox  │  │
│                       └──────────┬───────────────┘  │
│                                  │                  │
│            Wi-Fi Hotspot (offline, no internet)      │
└──────────────────────────────────┼──────────────────┘
                                   │  HTTP over LAN
              ┌────────────────────┼────────────────────┐
              │                    │                    │
     ┌────────▼──────┐   ┌────────▼──────┐   ┌────────▼──────┐
     │  Receiver 1   │   │  Receiver 2   │   │  Receiver N   │
     │               │   │               │   │               │
     │ Browser       │   │ Browser       │   │ Browser       │
     │ (Chrome/Safari│   │ (Chrome/Safari│   │ (Chrome/Safari│
     │               │   │               │   │               │
     │  SSE listener │   │  SSE listener │   │  SSE listener │
     └───────────────┘   └───────────────┘   └───────────────┘
```

### Components

| Component | Responsibility |
|---|---|
| **Native Sender App** | Sender UI — compose, pick, and send messages; manages hotspot and local server |
| **Local HTTP Server** | Runs on Sender's device; serves the receiver page and SSE stream |
| **Wi-Fi Hotspot** | Offline LAN — Sender's phone is the access point |
| **SSE `/events` endpoint** | Persistent stream; pushes payloads to all connected receivers simultaneously |
| **Receiver Page** | Plain HTML/JS served from Sender's device; opened manually by receiver in their browser |

### Message Flow

```
Sender types/picks message
        │
        ▼
POST /message  ──▶  Server stores payload in memory
                            │
                            ▼
                    SSE stream pushes event
                    to ALL open /events connections
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
         Receiver 1    Receiver 2    Receiver N
         renders UI    renders UI    renders UI
```

---

## User Flow

### Sender Side

```
1. Open app
        │
2. Tap "Start Broadcast"
        │
        ├── App creates a Wi-Fi Hotspot
        └── App starts local HTTP server on e.g. 192.168.43.1:8080
        │
3. Two QR codes are displayed:
   QR 1 — Wi-Fi credentials (SSID + password)
   QR 2 — Server URL (http://192.168.43.1:8080)
        │
4. Compose a message (type, pick from presets)
        │
5. Tap "Send"
        └── POST /message → SSE pushes to all receivers
        │
6. Repeat from step 4 for further messages
```

### Receiver Side (Revised — Two-Step)

```
1. Receiver sees QR codes on Sender's screen
        │
2. Scan QR 1 with native camera
   → phone prompts "Join [network name]?" → tap Join
        │
3. Phone joins the offline hotspot
        │
4. Scan QR 2 with native camera
   → native camera opens URL in Chrome / Safari
        │
5. Page loads from Sender's local server
        └── JavaScript opens SSE connection to /events
        │
6. When Sender sends a message:
        └── SSE event fires → page updates in real time
        │
7. Receiver sees message in their browser
        │
8. Receiver can scroll up to see message history
   (fetched from GET /history on page load)
```

---

## Technical Verification (Revised POC)

After researching actual platform behaviour, here is what is confirmed working and what is not.

### ✅ Confirmed Working

| Component | Platform | Evidence |
|---|---|---|
| `WifiManager.startLocalOnlyHotspot()` | Android 8+ | Official API — creates offline LAN hotspot; system generates SSID/password, app retrieves them from the callback |
| Local HTTP server on Android | Android | NanoHTTPD / Ktor — well-established libraries; bind to hotspot IP, serve HTML + SSE |
| Local HTTP server on iOS | iOS | GCDWebServer — well-established; runs inside native app |
| QR code with Wi-Fi credentials (auto-join prompt) | Android 10+, iOS 11+ | Native camera on both platforms reads `WIFI:S:...` format and prompts to join |
| QR code with URL (opens in browser) | Android, iOS | Native camera opens URL in default browser after network is joined |
| SSE in Chrome / Safari (full browser) | Android, iOS | Fully supported — `EventSource` API is standard |
| Receiver `fetch POST` back to local server | Android, iOS | Plain HTTP over LAN — no special permissions needed |
| In-memory message history on server | Both | Server stores messages, receiver fetches `GET /history` on page load |

### ❌ Blocked / Removed

| Component | Why Removed |
|---|---|
| **iOS programmatic hotspot creation** | No public or private API exists on iOS. User must manually enable Personal Hotspot in Settings. |
| **Captive portal auto-popup** | Captive portal is a router-level feature requiring DNS hijacking. Mobile apps cannot do DNS hijacking without root/system permissions. Removed from POC entirely. |
| **SSE in iOS CNA (mini-browser)** | iOS Captive Network Assistant is sandboxed, has a 128KB resource limit, kills persistent connections, and is not designed to host live apps. SSE does not work reliably in it. |
| **App-controlled SSID/password on Android** | `startLocalOnlyHotspot()` does not allow the app to choose the SSID or password — the system generates them. App reads them after creation and encodes them into the QR. |

---

## What Changes in the Revised POC

The "magical" captive portal auto-popup is removed. The receiver flow is a deliberate two-step instead:

| Original (Planned) | Revised (Verified) |
|---|---|
| Scan one QR → hotspot joined + page auto-opens | Scan QR 1 → join hotspot; Scan QR 2 → open page in browser |
| OS opens captive portal automatically | Receiver manually opens URL via second QR scan |
| SSE in captive portal mini-browser | SSE in full Chrome / Safari |
| iOS and Android equally | Android-first; iOS requires manual hotspot setup |

The core primitive — **offline SSE broadcast to N browsers over a local hotspot** — is unchanged and fully viable. Only the entry UX for receivers changes.

---

## Q&A

### Q: Do both users need to install the app?

| Role | Needs Install? | Why |
|---|---|---|
| **Sender** | ✅ Yes — native app | A PWA alone cannot create a hotspot or run a local HTTP server visible to other devices. Needs native OS access. |
| **Receiver** | ❌ No | After joining the hotspot and scanning the URL QR, the receiver opens the page in their existing browser — no install, no PWA prompt. |

---

### Q: How does the receiver see the message without any app?

**Step 1 — Join the Network (QR 1)**
Native camera on iOS 11+ and Android 10+ reads `WIFI:S:...` QR format and prompts to join the network. No third-party app needed.

**Step 2 — Open the Page (QR 2)**
Native camera reads a URL QR and opens it in the default browser (Chrome on Android, Safari on iOS). The page is served from the Sender's local HTTP server over the LAN.

**Step 3 — SSE in the Browser**
The page is plain HTML + JavaScript. It opens an SSE connection to `/events` and renders incoming messages in real time. SSE is fully supported in both Chrome and Safari.

The receiver never installs anything. They scan twice and they're in.

---

### Q: Will TTS and other forms of media work later?

Yes — the Sender's local server controls everything served. Receivers are in a full browser, not a sandboxed mini-browser.

| Format | Works? | How |
|---|---|---|
| **Large text / high contrast** | ✅ | Pure CSS on the served HTML page |
| **TTS / spoken audio** | ✅ | Web Speech API (`speechSynthesis`) — on-device voice synthesis, fully offline |
| **Pre-recorded audio** | ✅ | Served as `.mp3`/`.ogg` from local server |
| **Images / icons** | ✅ | Served as static assets |
| **Pre-approved translations** | ✅ | Translation JSON bundled into the page at load time |
| **Receiver language preference** | ✅ | Picked on page load; stored in `localStorage` (full browser, not sandboxed) |
| **Short video clips** | ⚠️ | Small files work; degrades with many receivers |
| **Interactive HTML/JS (games)** | ✅ | Full web apps served from local server |
| **Contact cards (vCard)** | ✅ | Served as downloadable `.vcf` |

---

### Q: What are all the forms of media that can be shared?

**Text:** Plain text, formatted HTML, translations, message history

**Audio:** TTS via Web Speech API, pre-recorded `.mp3`/`.ogg`, notification chimes

**Visual:** Images, SVGs, short video clips, QR codes rendered in-page, full HTML/JS apps

**Structured Data:** Contact cards (`.vcf`), tappable URLs, JSON payloads

**Hard limits:**
- No push notifications (browser tab must be open)
- SSE drops if receiver switches away from the browser tab
- Large file streaming degrades under many concurrent receivers

---

### Q: Has Anand mentioned one-to-many or many-to-many?

Anand's words only ever describe **one-to-many**:
> *"Five of us connect. We all get the same link."*
> *"that will allow me to send my contact card to all five with a tab"*
> *"if within a small group we are able to send a piece of information to everyone"*

The only moment he hints at bidirectionality:
> *"I am able to send and **receive** information"*

But even here he is describing the Sender's capability, not the group's. All concrete examples are one Sender pushing to many. Many-to-many was never described.

---

### Q: Can the receiver send something back?

| Direction | Possible? | How |
|---|---|---|
| Sender → Receivers | ✅ | SSE push |
| Receiver → Server | ✅ | `fetch POST` over LAN |
| Server → all Receivers | ✅ | SSE broadcast |
| Receiver → Receiver (direct) | ❌ | No path — browsers on the LAN cannot discover or talk to each other |
| Receiver → Receiver (via server) | ✅ | Receiver POSTs → server → SSE to all |

---

### Q: Why are we getting rid of the constraints Anand proposed?

**"Physical tap as the trigger" → replaced with QR scan**
NFC tap is one-to-one by design. No native "tap-to-broadcast" exists on iOS or Android. QR scan preserves the proximity intent without the one-to-one constraint.

**"Works equally across Android and iOS" → Android-first for POC**
iOS does not allow programmatic hotspot creation. For the POC, the Sender must be on Android. iOS Sender support requires guiding the user to Settings manually — a V1 concern.

**"No receiver install" → still true**
Kept. Receivers use their existing browser. No install, no PWA prompt.

**"The constraint that never moves: fully offline."**
Everything above the network layer (HTTP server, SSE, HTML page, TTS, translations) runs with zero internet dependency.

---

## Why Not WebRTC for One-to-Many?

WebRTC requires a full SDP handshake per peer (offer → answer → ICE negotiation). For 10 receivers that is 10 separate handshakes — a star topology pretending to be a broadcast. SSE replaces this: one persistent HTTP connection per receiver, one push from the server reaches all of them simultaneously.

---

## Key Design Decisions

### SSE over WebSockets
- Unidirectional — matches the announcement model exactly
- Native `EventSource` browser API — no library on the receiver page
- Plain HTTP — simpler server implementation
- Bidirectionality (for games) added later via `fetch POST`, not WebRTC

### Two-QR flow over Captive Portal
- Captive portal requires router-level DNS control — not available to a mobile app
- Two-QR is two steps but fully reliable on both platforms
- No sandboxing, no CNA limitations, no iOS/Android inconsistencies

### Memory over Disk
- Message history in server's in-memory store
- Receivers joining late fetch `GET /history`
- Nothing written to disk or sent to any cloud

---

## POC Scope (V0)

| Feature | In POC? | Notes |
|---|---|---|
| Android hotspot via `startLocalOnlyHotspot()` | ✅ | Android-first |
| Local HTTP + SSE server | ✅ | Core broadcast primitive |
| Two QR codes (credentials + URL) | ✅ | Receiver join flow |
| Message history / replay | ✅ | `GET /history` on page load |
| Typed message composition (Sender) | ✅ | |
| Text rendering on Receiver | ✅ | Full browser — no sandboxing |
| iOS Sender (manual hotspot) | ⬜ V1 | Guide user to Settings |
| TTS audio on Receiver | ⬜ V1 | Web Speech API |
| Pre-approved translations | ⬜ V1 | |
| High contrast / accessibility modes | ⬜ V1 | |
| Preset/picked messages | ⬜ V1 | |
| NFC tap trigger | ⬜ V2 | One-to-one only |
| Video / rich media | ⬜ V2 | |
