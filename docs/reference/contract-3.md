# Contract 3 (M6 A1)

The handshake, doors, subscriptions, content, answering and error behaviour of contract 3, the only contract the app speaks.

The design reference (the maintainer's unpublished editor design, section 3) is summarised on this page. No aliases and no v1 fallback.

- **The handshake.** After `auth_ok` the first command is `portico/version`. Only `{contract: 3}` goes on:
  `portico/doors`, then `portico/push/register`, `portico/calls/subscribe`, `portico/content/subscribe` and the
  phone's own `portico/content/get` (no parameters). The link counts as ready (the status dot green) from the version reply.
- **`ContractMismatch` is a retrying state of one connection** (review 7, R7-05). `contract != 3`, `unknown_command`,
  any other failure and a malformed result all mean the app cannot confirm the server speaks its contract. The link
  closes the socket and tells its listener, `SignalingClient` drops the socket without waiting for the server's close
  frame and stays in its backoff loop (`WAITING_TO_RETRY`, up to a minute between attempts), nothing is registered,
  and `onSocketClosed` clears the mismatch, so the next connect asks `portico/version` again. It is not an
  `AUTH_REJECTED` (that one is terminal until a Setup save). So 0.5.0 installed before the server restart is harmless:
  a few minutes of mismatch, then it connects. Settings' path line says "this Home Assistant runs a different Portico
  version (server contract N, this app needs 3); retrying" (`FailureClass.CONTRACT_MISMATCH`).
- **Doors.** `portico/doors` returns `{door_id, door_ref, endpoint_id, name, driver, capabilities, camera_entity_id,
  available, away_media}` and no secrets. The `DoorsCache` keeps `door_id` (what answer, offer, media and the calls
  events name), `door_ref` (what a push names), `endpoint_id` (what the `callers` map is keyed by), `driver` and `name`;
  every other key is tolerated and dropped. The persisted cache moved from the key `devices_v1` to `doors_v3`; the old
  one holds entry ids that are not `door_ref`s, so it is removed on first read and never migrated. A phone asleep with
  the socket down keeps its old refs until it connects (design section 6).
- **Subscriptions.** `portico/calls/subscribe` sends one snapshot per door (`{snapshot: true, door_id, call_id,
  state}`; `unavailable` means the door left), then `call_state_changed` events (`prev_state`); an end is `state:
  "ended"` with its call id and **no `idle` echo follows** (the app no longer infers an end from an idle).
  `portico/content/subscribe` sends `{type: "content_changed", endpoint, rev}`: the app refetches its document when the
  `rev` differs from the one it holds or is missing, and ignores another endpoint's change. The `devices_changed` event
  was never emitted by the server and its branch is deleted. Because the server sends none, a calls event that names a
  door the cache does not know (a door set up while the socket is up) or a snapshot saying a door left makes the link ask
  `portico/doors` again, at most 8 times a connection and never while one is in flight.
- **Content.** `content/get` returns `{endpoint, kind, rev, home_name?, pages, callers, roster}`. `ContentProfile`
  keeps `cameras`/`actions` as the first page's (kept for older readers) and adds `pages`, `callers` (keyed by
  endpoint id, `*` for the rest) and `roster` (M7's; parsed and cached, not drawn); unknown keys are tolerated.
  `content/run`, `content/snapshot` and the two content commands take an optional `endpoint` in their builders (a
  kiosk card's; the phone names none). Home draws `pages` and the ring and in-call screens draw `callers` (A2, see [the ring and in-call screens](../user-guide/ring-and-in-call-screens.md));
  the whole document is cached as one JSON blob (`content_v1` key, blob version 2).
- **Answering.** `portico/answer`, `webrtc_offer` and `media_open` name `door_id` (the offer's door lets the server check
  that the door owns the call). New errors: `refused` (the door does not let Portico answer; replaces `claims_disabled`,
  retryable, ring kept), `not_available` ("The call has ended", ring dismissed) and `unknown_door` (replaces
  `unknown_device`). `portico/hangup {call_id, reason}` ends a DOOR call (`user_hangup`, `error` and `timeout` on the answer path) and a dialled one
  (`cancelled` while it rang, `hangup` once answered). A HOUSE call (`hso-` id) ends with `portico/house/hangup {call_id}` and
  nothing else (M7: the server's schema refuses a `reason`); `HouseCommands.hangupFor` picks by the id.
- **`app_too_old`.** `portico/push/register` refuses `app_version` below 0.5.0 (or none). A 0.5.0 build is never refused;
  if a server ever said it, Settings shows "This Home Assistant needs a newer Portico app. Update the app." and the
  phone stops registering for that process (no retry storm). It cannot happen before A3 cuts 0.5.0.
- **Removed.** `portico/devices`, `portico/subscribe`, `portico/content/list` (the app never sent the last) answer
  `unknown_command`; `portico/doors/secrets` does not exist.
