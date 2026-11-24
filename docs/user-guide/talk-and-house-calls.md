# Talk to the door, call buttons and house calls

The parts of Home that start a call from the phone or receive one from another phone in the house: the Talk button, `call` buttons, the roster tab, and ringing for house calls. These bullets were carved out of the old Home section; the text is unchanged. The rest of Home is on [Home: cameras and buttons](home-screen.md).

## Home: Talk to the door (0.5.6-alpha)

- **Talk to the door (0.5.6-alpha, inert).** A `talk` button acts on its block's door. It is READY only when the server says
  `available: true` (`TalkGate`, the one rule Home, the coordinator and the session all ask); today no door reports `CanDial`,
  so every Talk button arrives `available: false, reason: talk_off` and is the same disabled button with the same words as
  before: nothing is sent, however it is pressed. Its other reasons (`no_door`, `not_connected`, `busy`, `phone_only`) have
  their own words. For a READY button a press asks for the microphone (Home asks, once per attempt, as the call screens do),
  then sends `portico/content/run` with the action id only; the reply's `call_id` is followed through `portico/calls`
  events (`TalkSession` in `:core`: connecting, in call, ended); when the station answers the audio attaches over the
  answer path's WebRTC (`portico/webrtc_offer` with the dialled call id); the same button then says Cancel (connecting) or
  Stop (in the call) and ends it with `portico/hangup {call_id, reason}`. It gives up if the station does not answer in 20 s
  or the audio does not connect in 10 s, ends at 300 s, and on a lost link after 8 s. A door ringing ends it. How it ended
  is one line on the button for 4 s. No websocket command was added. A server that offers Talk needs the dial prerequisites
  (blocks design 7.2, P1 to P3); until then this is dead code with tests, which is the point.

## Home: Call buttons

- **Call buttons (built against fakes; no backend call connects yet).** A `call` action (`target`,
  `reason` on `content/get`) is drawn like any button, with `phone-in-talk` by default, and is off with its
  reason (not reachable, on a call, Do Not Disturb, calls switched off, not available any more, "this Home
  Assistant cannot place calls yet"). A press goes straight to `OutgoingCallActivity`: there is **no confirm
  step** for a call, and a `confirm` flag from a server is ignored. `:core`'s `PlaceSession` mirrors
  `AnswerSession` (microphone, `portico/content/run {action_id}`, ringing, the far end's `house_call`
  events, audio up) with one `finish()` for every exit; `PlaceCoordinator` holds one call. The caller's
  audio is the `PlaceMediaPort` (contract in `PlacePorts.kt`), which ships as `UnavailablePlaceMedia`
  until M7 3b-B wires the house bridge's legs; the `house_call` events now reach it through the link's house sink. Ending a
  house call sends `portico/house/hangup {call_id}`. On a server without call targets a press ends at once with "This Home Assistant
  cannot place calls yet".

## Home: The roster page (M7 3b-A)

- **The roster page (M7 3b-A).** When, and only when, the server's `content/get` roster shows house calls ON for this phone
  (`HouseGate.enabled`: some phone, screen or group row has a reason other than `not_available`, which is what every such row says
  while house calls are off; a gate row alone never counts), Home's pager gets one more tab, "Call", at the end. Its rows are the
  roster in the server's order (Everyone first where offered): a dot (filled or hollow, coloured) and one line of words ("Available",
  "Rings by notification", "Not reachable", "Do not disturb", "On a call", "Calls switched off", "No phone is set to receive house
  calls", "Not connected to Home Assistant"), and a Call button of 48 dp or more. A press sends `portico/content/run` with the row's
  `r-...` action id and nothing else, then opens the call screen. With house calls off there is NO tab, NO row, no new screen, no
  new permission and no new manifest entry: `HomeModelRosterTest` proves the Home view is equal to the roster-less one for every
  recorded document of the contract file, every link state and every selection. Doors (the gate's roster row) are not drawn here:
  the gate is Talk's path. A `house_roster_changed` event makes the link read the document again (one read at a time).

## Home: Receiving and talking (M7 3b-B)

- **Receiving and talking (M7 3b-B).** A house ring is `house_call` `ringing` whose `to` is this phone's own endpoint (the socket) or a
  push with `ct: house` (`HousePush`, parsed apart from the door decision: a house message can neither ring as a door nor end a door
  call). Both reach `HouseRingCoordinator`, which shows the ring only if: house calls are on for this phone (the roster), the phone's
  own **"Receive house calls in Portico"** switch is on (**default OFF**), Android's Do Not Disturb is not on (read only through the
  second switch, "Tell the house when Do Not Disturb is on", default on), the phone is not paused, the call is not a repeat or a late
  ring, **no door is ringing or on a call (live or held)**, and no other call of this phone is live. A dropped ring is not queued. The
  caller is named from the document (`callers`, the roster; a push's `from_ref` is `e-` + sha256 of the endpoint id, matched against the
  endpoints the document names); a caller it does not name is "Someone", never an id or a reference. `HouseRingActivity` (the door ring
  screen's layout) offers Answer and Decline; Answer is the person's press and the ONLY thing that answers. An answered ring is a
  `PlaceSession` (`portico/house/answer`, then audio), so it is one call at a time, uses the same microphone prompt, foreground service,
  Mute and Hang up, and a door ring or a door Answer ends it first. The audio is `HouseMedia`: WebRTC at home (offer and answer through
  `portico/house/media_open`), the frame channel when the socket is on the tailnet; per-call frame counters (sent, not sent, received,
  concealed, largest gap) are written to the push journal and the log when the frames close. The phone tells the house its own Do not
  disturb (`portico/house/presence`) only while house calls are on, on a ready connection and on a change; not receiving counts as Do not
  disturb, so a caller is told before dialling. With house calls off none of this exists on screen, on the wire or in the manifest's
  permissions (the one added manifest entry is `HouseRingActivity`, which only a house ring can start).
