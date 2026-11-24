# WebSocket contract (vendored files and replay tests)

The contract files vendored from portico-ha, how each is pinned by a sha256, which tests replay them, and the commands to re-vendor after a contract change.

Four files are vendored in `core/src/test/resources/`, byte copies of `portico-ha`'s `contract/` (`ws_v3.json`, `app_commands_v3.txt` and
`push_v2.json` from `a16b38f`, main after M7 3a, 3c, 3f and 3b-S; `ws_media_v2.json` from M6 E3, head `fa64e8b`, unchanged), each with its sha256 pinned by
`WsContractTest` (and `push_v2.json` by `ContractTest`). `palette_v1.json` and `icons_v1.json` (blocks) are pinned by
`ButtonPaletteTest`:

| File | What | Pinned in |
| :--- | :--- | :--- |
| `ws_v3.json` | one case per request (380), with every frame HA sent back, recorded from the real handlers; includes the recorded `talk_state` and `house_call` frames and the five `portico/house/...` commands | `WsContract.PINNED_SHA256` |
| `ws_media_v2.json` | the away-call-audio exchange (`door_id`, `unknown_door`) | `WsContract.MEDIA_PINNED_SHA256` |
| `app_commands_v3.txt` | the commands the app sends, one a line, sorted | `WsContract.COMMANDS_PINNED_SHA256` |
| `push_v2.json` | the push messages (29 cases: push/v2 plus the six v2.1 house cases) | `ContractTest.PINNED_SHA256` |

- `WsContractReplayTest` (`:core`) replays every case of `ws_v3.json` through `PorticoLink`, `HaProtocol`, the
  `RegistrationMachine`, `PushCommands`, `Handshake`, the `DoorsCache`, `ContentCommands` and the answer path's
  builders and parsers. The request the app builds must equal the file's, field by field. The `id` is left out of
  that comparison, and the reply frames are renumbered to the app's id, because the file's `_meta` says ids are per
  connection. Where the file's request differs from the app's on purpose the expectation names the field:
  `answer` is recorded with `client_kind: "app"` (the phone sends `"browser"`: it takes the browser's media path to
  the SFU, so the server answers `relay`), and `hangup`, `media_open` and `push/register` records leave out an
  optional field (`reason`, `codec`, `app_version`) the app always sends. A case in the file that the test does not
  list fails the run, and a command another caller sends (dial, open_relay, the editor) must not be in the app's list.
- **Connection log (DEV-705).** `ws_v3.json` (247 cases) and `app_commands_v3.txt` are re-vendored from portico-ha `acdd778` (main
  after #173), which adds `portico/connlog/report` (phone), `get` and `clear` (administrator). The app sends only `report`; the
  other two cases are in the file because the server's table covers them.
- **`talk_state` (M7).** `ws_v3.json` is re-vendored from portico-ha `56798e3` (main after #151 and #152), which records the
  server's `talk_state` frames (the three "(talk to the gate)" cases). `WsContractReplayTest` feeds them through
  `PorticoLink` (events to the subscription's id, the result to the command's), and `TalkStateServerFramesTest` (`core/call`)
  parses every recorded frame with `parseTalkState` and drives a `TalkSession` with them. The server's `dialing` and a
  failed dial's `ended` carry `call_id: null`, which the parser drops by design (a refused dial ends through the run
  reply's `busy` error); the server never sends `time_cap`. `talk_state_cases.json` stays as the hand-made remainder (its
  `about` says what and why).
- **House calls (M7 3b-A).** The three files above are re-vendored from portico-ha `a16b38f`. `app_commands_v3.txt` now lists the five
  `portico/house/...` commands (answer, decline, hangup, presence, media_open), which the app's builders (`HouseCommands`) name, and
  `WsContractReplayTest` replays every recorded case of them (the request the app builds, the reply it maps to words), the
  `house_call` frames (to the link's house sink) and the roster documents. `ContractTest` replays the six v2.1 house push cases through the
  DOOR decision, where a `ct: house` message is silent (a house ring is not a door ring); the house ring has its own decision (3b-B).
- **Panel call (D-20261121-c98a).** `ws_v3.json` is now 424 cases (sha256 `e241646a...4d`) and `app_commands_v3.txt` (sha256 `66a0bf01...ab2`) lists `portico/panel/call_result`, byte-identical to portico-ha main at the merge of #370 (`9c86515`). The server also sends one new event type on `portico/calls/subscribe`, `{type: "panel_call", request_id, target: "everyone"}`, which an older client ignores. The recorded `panel call result` cases (an ordinary user, a declared screen and an administrator get `unauthorized`; a phone that is not a panel gets `not_a_panel`; a bad id gets `invalid_format`) are server-only; the app's answer is pinned by `PanelCallTest`. The heartbeat gains the boolean `panel_call` (this build understands the request); the server asks only an app that says so.
- **Panel mode (DEV-807 slice 2).** `ws_v3.json` is now 419 cases (sha256 `fe0faf79...fb`) and `app_commands_v3.txt` (sha256 `1f9521c5...a6d`) lists `portico/wall/heartbeat`, both from portico-ha main after #357 (`9f719d4`). The recorded `wall heartbeat` cases (an ordinary user, a declared screen and an administrator get `unauthorized`; a phone that is not a panel gets `not_a_panel`; a bad `front_reason` gets `invalid_format`) are replayed through `PanelHeartbeatCommands`. The other new cases (`phones make_panel_screen`, `elements`, `pages migrate`, `camera webrtc_offer`, `phones set`, `ui settings set`) are server-only. The test-only `NOT_YET_VENDORED` allowance of slice 1 is gone: `commandsSentEqualTheVendoredList` is exact again. A panel names its screen (`endpoint`) in `content/get`, `subscribe`, `run`, `snapshot` and `live`; `PorticoLink` adds it centrally (`PorticoLinkEndpointTest`).
- **Ring lines to HA (DEV-762 A1).** `ws_v3.json` is now 380 cases (sha256 `b92fef67...430`, portico-ha main after #197): 17 `connlog diag ...` cases (ring-decision Journal lines accepted; `crash` / `exit` items and `get kind=diag|crash|exit`, which the app does not send yet) and the changed invalid-`kind` message. `app_commands_v3.txt`, `ws_media_v2.json`, `push_v2.json` are unchanged.
- **Journal lines to HA (DEV-713 / DEV-705).** `ws_v3.json` is now 363 cases (sha256 `95f2e3e1...2d8`, portico-ha main after #190): 22 `connlog journal ...`
  cases for the `kind: journal` item of `portico/connlog/report` (the reply adds `journal: {lines, dropped}`) and the administrator's `get` filters and
  `clear`. `app_commands_v3.txt` is unchanged. `JournalReporterContractTest` builds the recorded items with the app's own reporter and sends them
  through the real queue and sender; `WsContractReplayTest` replays the rest.
- **Live video (DEV-713).** `ws_v3.json` was 341 cases (S2: sha256 `a40b950a...daf`, portico-ha main after #189; the 8 `caller live_rtsp:` cases put the same `live_rtsp` object on the CALLER's cameras, `callers.<id>.cameras[]`, which the ring and in-call screens read through the same parser; S1b before it: 333 cases, sha256 `49afe106...3e8`, after #188; its 13 `live_rtsp:` cases add the camera tile's
  `live_rtsp` object, parsed into `ContentCamera.liveRtsp`, and the admin's `live_rtsp_base` / `live_sub_streams` settings, which the app never sends;
  `app_commands_v3.txt` is unchanged). Before it, S1 vendored 320 cases from portico-ha `6e1a39c` (main after #186) and
  `app_commands_v3.txt` from PR #187 (`3c00ad0`), which adds `portico/content/live`, `live_ice` and `live_close`. Stage S1 builds them
  (`LiveCommands`), so `WsContract.NOT_YET_SENT` is empty and both `WsContractTest::commandsSentEqualTheVendoredList` and the
  client-call check require every command of the list in the sources. `WsContractReplayTest` replays every recorded live case: the
  request `LiveCommands` builds equals the file's, and the result and `session` / `answer` / `candidate` / `error` events read into the
  typed `Reply` / `Event`. The camera `live` flag and the document `show_fps` are parsed into `ContentCamera.live` and
  `ContentProfile.showFps` (only a JSON boolean true counts) and cached by `ContentCodec` (written only when true, so the codec
  version stays 3).
- `WsMediaContractTest` replays `ws_media_v2.json` through `MediaCommands` and the frame events.
- `WsContractTest::commandsSentEqualTheVendoredList` scans the `"portico/..."` literals in the `:core` and `:app`
  sources and requires exactly the commands of `app_commands_v3.txt` (the server's own test checks the same file
  against its handlers and its auth table).
- `SignalingClientTest` (`:app`) has its MockWebServer fake HA answer with the same frames after HA's auth
  handshake, which the contract does not record. It reads core's copy of `ws_v3.json`; there is no second one.
  `app/build.gradle.kts` declares the file as the unit-test task's input.

Behaviour depends on the error `code` only, never the `message`: HA writes the `invalid_format`
text, and it can change between HA versions.

To re-vendor after a contract change:

```sh
git -C ../portico-ha fetch origin
for f in ws_v3.json ws_media_v2.json app_commands_v3.txt push_v2.json; do
  git -C ../portico-ha show origin/main:contract/$f > core/src/test/resources/$f
done
sha256sum core/src/test/resources/{ws_v3.json,ws_media_v2.json,app_commands_v3.txt,push_v2.json}
```

Put the sums in `WsContract` (and `ContractTest`), update `EXPECTED_CASES` and the replay's expectations for any new
or changed case, and run `:core:test :app:testDebugUnitTest`.
