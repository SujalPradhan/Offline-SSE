# User Flow

The interaction between the Sender (Presenter) and the Receivers (Audience) is designed to be completely frictionless.

## 1. Onboarding
1.  **Sender:** The presenter taps "Start Hotspot & Server" on the Android device.
2.  **Sender:** The app generates two QR codes on the screen.
3.  **Receiver:** The attendee scans the **"Join Wi-Fi" QR code** with their native camera app. Their phone instantly joins the local network (no password typing).
4.  **Receiver:** The attendee scans the **"Open Link" QR code**, which pops open their native browser to `http://<Android-IP>:8080/`.

## 2. Interaction
*   **Live Messaging:** The Sender types a message. The Kotlin server pushes it via SSE, and it appears instantly on every Receiver's screen.
*   **Audio (TTS):** If the Receiver taps "Enable Audio", the browser uses the Web Speech API to read incoming messages aloud.
*   **Live Polling:** The Sender creates a poll. Receivers see interactive buttons. When a Receiver votes, a `POST /vote` is sent to the Android phone, which instantly broadcasts the updated percentage bars to everyone via SSE.
