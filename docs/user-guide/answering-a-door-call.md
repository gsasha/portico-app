# Answering a door call

What pressing Answer does, the exchange with Home Assistant, every way a call ends, the error texts, and the real limit on call audio away from home.

The ring screen has an **Answer** button (and the ring notification's Answer action opens the same
screen and presses it). Pressing it is the only thing that starts an answer: a ring, a call event, a
granted permission, a re-delivered intent or a rotation never opens the microphone and never sends
`portico/answer`.

**The exchange** is the Lovelace card's (`portico-ha` `www/portico-card.js` `_answer`), in its order:
microphone, a WebRTC offer with every ICE candidate gathered (non-trickle, 4 s cap), `portico/answer
{door_id, call_id, client_kind: "browser"}` (it must reply `mode: relay`), `portico/webrtc_offer
{call_id, door_id, sdp}`, the reply's `sdp` as the remote answer, audio up. `client_kind` is `browser` because
the phone takes the browser's media path; `app` would let the server pick `direct` for a driver this
app cannot dial. The card never sends `portico/webrtc_ice`, so neither does the app.

**Errors** (`:core` `AnswerErrors`): `already_answered` is "Someone else already answered this call",
dismisses the ring, hides Answer and is never retried; `not_available` is "The call has ended" and dismisses the ring;
`refused` ("This door does not let Portico answer it.") keeps the ring and may be pressed again; `device_busy` has
its own message and may be tried again by pressing again; `not_supported` shows the device's own reason; an old
server (`unknown_command`) and `unknown_door` have their own lines; any other code is shown with the code.
The shared call store is marked ANSWERED just before the claim goes out, so HA's own `answered` event
for the phone's claim never posts "Answered elsewhere" and the later `ended` never posts "Missed call".

**Every end path** goes through `AnswerSession.finish` once: timers cancelled, media closed, audio mode
restored, the foreground service stopped, and `portico/hangup` sent when the phone holds the claim and
the call is not already over. Remote hang-up (an `ended` event, or a snapshot with no live call), the
300 s cap (from the claim), media or WebSocket loss (8 s grace to come back), the user's Hang up, the
app swiped away (`CallService.onTaskRemoved`), and a claim that never got a reply (10 s) all end it.
A hang-up that could not be sent is owed: it goes out when the socket is back, and the claimed call
is kept in `portico_active_call` prefs so the next process start releases a call a killed process left.

**Parts.** `:core` `call/`: `AnswerSession` (the path), `AnswerCoordinator` (the press gate, the ring,
the store, the service, the sweep), `AnswerCommands`/`AnswerErrors`, `CallUi`, `SerialMedia`. `:app`
`call/`: `WebRtcMedia` (libwebrtc, `io.github.webrtc-sdk:android` 137.7151.05, the only WebRTC library
the app has; behind the `MediaPort` interface, with a fake in the tests), `AndroidAudioRoute`
(`MODE_IN_COMMUNICATION`, speaker, audio focus), `CallService` (foreground service of type
`microphone`, only while a call is up), `InCallActivity` (status, timer, mute, Hang up, Try again).
New permissions: `RECORD_AUDIO` (requested by `InCallActivity`), `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MICROPHONE`, `MODIFY_AUDIO_SETTINGS`.

**Not proven here (no phone):** everything in `WebRtcMedia`, `AndroidAudioRoute`, `CallService` and the
two activities. **A real limit:** libwebrtc sends UDP from the phone straight to the SFU's address in
`portico-ha`'s answer. The embedded tailnet client carries only the WebSocket (a TCP forwarder), not
UDP, so away from home the call connects only if the phone can reach Home Assistant's media port
itself (home Wi-Fi, or the Tailscale app as a VPN). Otherwise the screen says "The call audio did not
connect" after 15 s and releases the claim.

**Call audio over the websocket (DEV-811).** Away from home the audio is carried as 40 ms frames on the Home Assistant websocket
(`portico/media_open`). The app asks for `l16` (16 kHz, 1280 bytes a frame) by default; Setup > Call audio > **Compatibility** asks for `pcmu`
(8 kHz mu-law, 320 bytes) as before. The reply's codec must equal the requested one. The microphone and speaker are prepared at the chosen
rate **before** the open (on a gate the open is the answer); a device that cannot be prepared at 16 kHz falls back to 8 kHz (`why=prep16`), one that
cannot be prepared at all fails the call before any open. After the start the recorder must be recording, the player playing and a first capture
frame must arrive within 2 s; otherwise the call ends and the phone is marked "wideband failed" (8 kHz for 24 hours; a second failure within 7
days makes it permanent until an app update or toggling Compatibility). Setup > Advanced > **Call audio path** (Automatic / Frames / WebRTC)
forces the transport on the home network only, resets after 24 hours and is shown on the call screen. At close of every frames call the Journal
gets two lines: `frames v=2 call=... codec rate secs sent dropped recv underruns maxgap p95gap qmin qmax` and `audio dev call=... first uppeak dnpeak
upclip dnclip readmax aec ns route why mark fail` (first word in the reporter's list, at most 240 characters, no colon or dot). Device behaviour
(prepare, start check, AEC at 16 kHz) is **not** proven off a phone.
