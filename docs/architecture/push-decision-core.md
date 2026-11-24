# Push decision core

The pure-Kotlin code in `:core` that decides what the phone does with a push ring notification, and how its contract file is vendored and pinned.

`core/src/main/kotlin/com/meda/portico/core/push/` holds what the phone does with a push ring notification
push (P2 plan §3.3, sender spec §4.3): `PushMessage` parses an FCM data map and never throws,
`Decision.receive` decides and records in one step, `CallStore` is the one store both the push
path and the WebSocket path write (`SocketEvents` is the socket's entry point), `Redact` gives the
log-safe `sha256[:8]` of a registration value. No Android code lives there; each `Outcome` type's
KDoc says what the Android layer does with it.

`core/src/test/resources/push_v2.json` is a byte copy of `portico-ha`'s `contract/push_v2.json`,
and `ContractTest` runs all of its cases through `Decision.receive`. The test pins the file's
sha256. To re-vendor after a contract change (always a version bump on both sides):

```sh
cp ../portico-ha/contract/push_v2.json core/src/test/resources/push_v2.json
sha256sum core/src/test/resources/push_v2.json   # put this in ContractTest.PINNED_SHA256
```

Then update `ContractTest.EXPECTED_CASES` and the "vendored from" commit in its KDoc, and run
`:core:test`. The pinned copy is **push/v2** (`portico-ha` `fa64e8b`, M6 E3; 23 cases): `pv: "2"`,
a `door_ref` of `d-` and 32 hex (derived from the door's endpoint id, never an entry id or a URL), and
two optional keys reserved for the house calls (M7): `ct` (`door` when absent, or `house`) and
`from_ref` (an opaque caller reference). `PushMessage.parse` checks `ct` **before** it requires
`door_ref`: a `ct: house` message is `Ignored(UNKNOWN_KIND)`, journalled like the other ignore reasons,
not rung and not recorded (the fixture's three house cases pin it); M7 adds the house cases as v2.1.
A message with `pv: "1"` is unknown and ignored. It still matches the app's behaviours: `ended` with
no ring seen is `silent`, and `kind: "test"` is `quiet` (one quiet notification).
