# The ring and in-call screens, and a station call as the main screen

How a call from the station is shown (it opens the Home screen, since 0.6.33-alpha), and how the in-call screen draws the caller's cameras and buttons. The old ring screen is documented for the record.

## The ring and in-call screens draw the caller (A2)

The document's `callers` map is keyed by the door's **endpoint id** (`Door.endpointId`, from `portico/doors`). For a
ringing or live call the screens look up `callers[<that door's endpoint id>]`, else `callers["*"]`, else nothing (the
screen is then exactly what it was before 0.5.0). An entry with nothing in it is an answer too (the editor chose to show
nothing for that caller); it does not fall through to `*`. `:core`'s `Callers` and `CallerModel` decide it.

- **Ring screen** (removed in 0.6.33-alpha, APP-03: a call from the station opens the main screen, see "A station call is the main screen" below; the text of this bullet is what the ring screen did until 0.6.32-alpha and is kept for the record). Answer and Stop stayed first. Under them: the caller's camera
  stills (`callers[...].cameras`, ids `c-<12 hex>`), full width, and the `ring_buttons` as buttons that run through
  `portico/content/run {action_id}` with the same rules as Home (off when unavailable or offline, "asks first" confirms,
  one result line for 20 seconds). The line "Opening the door happens in the intercom app..." is replaced by a shorter one
  when there are buttons. A `call` button is never drawn there (placing a call from a call screen is M7's).
- **In-call screen** (`InCallActivity`): Hang up and Mute stay first; under them the same stills and the
  `call_buttons`, only while the call is live (not before, not after it ended).
- **Stills.** `portico/content/snapshot {camera_id}` every 1.5 seconds per camera (design O3), only while the call
  rings (ring) or is live (in-call), the screen is visible and the link is up. **One request in flight per camera, for the
  whole app**: `ContentRepository.beginSnapshot` refuses a second until the answer (a picture, a refusal or a lost
  socket) or 10 seconds pass, so Home, the ring screen and the in-call screen can never stack requests (`StillGate`,
  `StillSchedule`; fake-clock tests). A picture is kept in memory only and never shown once the link is down.
- **Decoration, never logic.** Every draw and every request on these two screens is inside a `try` that swallows its
  failure: whatever goes wrong with a picture or a button, Answer, Stop, Hang up, Mute, the microphone dialog, the
  `another_call` refusal and the notifications behave exactly as before. No manifest, notification, PendingIntent or
  permission changed in A2.
- Over the tailnet, or on a call that carries its audio over the same WebSocket (away media), a still every 1.5 s
  shares that socket with the audio; design risk S5 says to measure it once and, if a still takes more than about
  2 seconds, drop to `width: 320` (`ContentCommands.snapshot` takes `width`).

- **Not verified on a phone (Home, ring and in-call):** how any of it looks and feels, the tab strip and the swipe,
  the confirmation dialog, the stills over the lock screen, still refresh over mobile data and the tailnet, battery with
  the screens open. Home's layout arithmetic was rendered at 1080x2400 (content is about 3,100 px for four cameras and
  six buttons, so it scrolls); the A2 screens were not rendered: they only add blocks below Answer / Stop / Hang up in
  layouts that already scroll.

## A station call is the main screen (APP-03, 0.6.33-alpha)

A call from the station has no screen of its own. The ring's full-screen intent, its notification and its Answer action open
`HomeActivity` (`singleTop`; `CLEAR_TOP` brings an open Settings back to it). `:core`'s `StationCalls` maps the ring that is up and
the answer under way to one `StationCall` (ringing, connecting, in the call; the door's reference and its socket id); `HomeModel`
gives the Talk button (or the call button aimed at the gate) whose door it is the state `RINGING_IN` (green with a pulsing wave,
the tile itself does nothing) and then `TALK_CONNECTING` / `TALK_IN_CALL`, exactly the outgoing call's look. The call bar below it
shows Decline (red) and Answer (green) while it rings, then the outgoing call's bar (End, "Calling <name>", the duration, the route
chooser). A door with no Talk button on the visible page gets the bar alone (`StationCalls.surface`). Home is over the lock screen
only while such a call lasts (set in code), and every other press on it asks for the unlock first (the A-02 rule). The hold-and-answer
button of the old ring screen is gone with it (hold is gated off, `HoldConfig.HOLD_ENABLED`); the caller's stills and `ring_buttons`
of A2 were that screen's too. The house-call ring screen (`HouseRingActivity`) is unchanged.
