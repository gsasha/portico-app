package com.meda.portico.core.push

/**
 * Facts of the `push/v2` wire contract (sender spec §4.1, §4.2; portico-ha
 * `custom_components/portico/push/payload.py`). The vendored fixture
 * `core/src/test/resources/push_v2.json` is the shared source of truth; `ContractTest` checks
 * these constants against it.
 *
 * Version note: **push/v2** (portico-ha M6 E3, 2026-10-20) is v1.1 with `pv: "2"`, a `door_ref` the server derives
 * from the door's endpoint id (`d-` and 32 hex: never an entry id, never a URL), and two optional keys reserved for the
 * house calls (M7): `ct` (the call type: `door` when absent, or `house`) and `from_ref` (an opaque caller reference).
 * The app checks `ct` BEFORE it requires `door_ref`; `ct: house` is ignored on 0.5.0. A message with `pv: "1"` is
 * unknown and ignored (the sender no longer sends it). M7 adds the house cases as v2.1: cases, not a wire version.
 * The two v1.1 behaviours decided by the owner on 2026-10-06 (P2 plan §7 Q6) stay: `ended` with no ring seen is silent,
 * and `kind: "test"` posts a quiet notification.
 */
object Contract {
    /** The only contract version this app understands. Any other `pv` is ignored and journalled. */
    const val PV = "2"

    /** `ct` (the call type) of a door call, and the value an absent `ct` means. */
    const val CT_DOOR = "door"

    /** Clock-skew allowance: a ring is late only when `now > exp + SKEW_MS` (spec §4.3). */
    const val SKEW_MS = 15_000L

    /** The server's default ring window (`exp = t + ring_window`). An entry option on the server. */
    const val DEFAULT_RING_WINDOW_S = 45

    /** Server TTL for `answered` / `ended` (`payload.DISMISS_TTL_S`): they can arrive up to 60 s late. */
    const val DISMISS_TTL_S = 60

    /** Calls the store remembers, the same slack as the server's `_ENDED_MEMORY` (P2 plan §3.3). */
    const val STORE_CAPACITY = 64

    const val KIND_RING = "ring"
    const val KIND_ANSWERED = "answered"
    const val KIND_ENDED = "ended"

    /** v1.1: a quiet test notification, never a ring (P2 plan §7 Q6). A contract case. */
    const val KIND_TEST = "test"

    /** `seq` is fixed per kind on the server (`payload.SEQ`): ordering within one call. */
    const val SEQ_RING = 1
    const val SEQ_ANSWERED = 2
    const val SEQ_ENDED = 3

    /** The server's `_ID_RE`: opaque ids only (entry ULIDs, `gso-<ns>-<n>-<rand>` call ids). */
    val ID_RE = Regex("^[A-Za-z0-9._:-]{1,128}$")
}
