# Offline-SSE Architecture

This application operates as a **zero-infrastructure, decentralized broadcast hub** running entirely on a single Android device.

## Core Components

1.  **HotspotManager (Kotlin)**
    *   Uses Android's `WifiManager.startLocalOnlyHotspot()` to broadcast a local Wi-Fi AP.
    *   Acts as the DHCP server and handles the physical transport layer for all connected devices.

2.  **BroadcastServer (Kotlin)**
    *   A custom, multi-threaded embedded HTTP server running on `ServerSocket(8080)`.
    *   **HTTP Endpoints:**
        *   `GET /`: Serves the Receiver HTML/JS web application.
        *   `GET /history`: Returns a JSON array of all past messages/polls.
        *   `GET /events`: Keeps an open Server-Sent Events (SSE) socket for real-time pushing.
        *   `POST /vote`: Accepts audience poll responses.

3.  **Sender Dashboard (WebView / JS Bridge)**
    *   The presenter's UI, hosted locally at `file:///android_asset/sender/index.html`.
    *   Uses `@JavascriptInterface` to send commands (Start Hotspot, Broadcast Message, Start Poll) down to the native Kotlin layer.
    *   Receives Base64-encoded JSON payloads back from Kotlin to render the live audience feed.

4.  **Receiver App (Browser)**
    *   A completely standard HTML/JS page served directly by the `BroadcastServer`.
    *   Uses standard `EventSource` to receive live updates without any third-party libraries.
