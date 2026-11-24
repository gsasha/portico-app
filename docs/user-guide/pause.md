# Pause

How to pause Portico on a phone (for one hour, four hours or until resumed), exactly what a pause stops, and what it deliberately leaves running.

Requested 2026-10-09: *"add a pause button to the portico app."* With Portico and the old intercom
app on one phone, either can be paused so the other can be tested alone (the intercom app got
the same switch in 0.52.0-alpha; the rules are ported from its `PauseRules.java`).

**Where.** The first section of Settings, **Pause this phone**: **Pause for 1 hour**, **Pause for 4
hours**, **Pause until I resume**. While paused, Settings opens with a bold "Paused until 14:30" (or
"Paused until you resume") line and a **Resume** button instead of the three choices.

**What paused stops.** Each is refused at its choke point, not at a screen, so a path nobody
thought of is covered too:

| Thing | How |
| :--- | :--- |
| the WebSocket to Home Assistant | `SignalingClient.pause()` closes it; `start`, `wake` (a pushed ring), `reconnect` (a Settings save) and the retry timer leave it in `PAUSED`: not running, no ticker, no link start (so no Firebase id request, no registration attempt, no `portico/push/status`) |
| the tailnet node | `TailnetController.pause()` stops it, up or joining; `ensureUp`/`awaitForwarder` and a Settings save's key start no join (the key waits for the resume); `FallbackConnector.open` refuses |
| every push, any kind (ring, answered, ended, test, garbage) | **dropped first thing** in `MessagingServiceLogic.onMessage`, before `Decision`, the `CallStore`, the dispatcher (so no socket wake and no ring backstop alarm) and any notification; one log line, `portico push: dropped (paused) kind=<kind>`, with no call id, door or registration value |
| rings already up | cleared when the pause starts (`AndroidOutcomeHandler.stopAllRings`: notification and backstop alarm) |
| a socket event queued as the pause began | not alerted |

**What is NOT paused: the FCM registration at Home Assistant.** Nothing changes server-side: HA
keeps this phone's registration and its per-phone switch, so pushes still arrive and are dropped
here, cheaply. A Firebase id or token that rotates while paused is kept by the registration
machine and reaches HA on the resume's first connect, not before.

**The state** is three values (paused, since, until) plus the last resume time, on a private
prefs file (`portico_pause`), written with `commit()` BEFORE anything is torn down, so a process
killed mid-teardown comes back paused.

**Resume** (Settings' button, the notification's **Resume** action, or the deadline) does what a
normal start does: the socket connects (direct first, the tailnet when that fails), the machine
registers on `auth_ok`, Settings updates. **A ring from before the resume is never rung**: a ring
whose call time is not newer than the resume time is recorded as missed and alerts nothing
(`Decision`'s `BEFORE_RESUME`). The call time is the push's `t`; on the socket (whose snapshot on
the first connect after a resume may show a call that began during the pause) it is the
`gso-<epoch ns>-…` call id's stamp. An unknown time is not withheld.

**The deadline** is wall-clock (it must survive a reboot) and is re-checked on every process
start, on boot (which also re-arms the alarm) and by whichever check runs first after it passes.
The in-boot alarm is `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` to the non-exported
`PauseReceiver`: inexact under Doze, but it needs no exact-alarm permission (the permission set
is unchanged; `MergedManifestTest` pins it). A deadline noticed late ended AT the deadline. A
clock set earlier than the moment the pause began, or a corrupt stored state (a deadline not after
its start, or more than 4 hours after it), ends a timed pause.

**While paused** a silent, ongoing notification, "Portico is paused. It will not ring until 14:30.
Resume", stays up on its own channel (`portico_paused_v1`, IMPORTANCE_LOW, no sound), and Settings'
connection, registration and tailnet lines each say "Paused" (INFO) instead of claiming a failure.

**Fail open.** Any exception while reading the pause state means NOT paused: a corrupt flag never
loses a ring.

The rules are `:core`'s `PauseRules` / `PauseKeeper` (`PauseRulesTest`: the intercom app's table,
minus its wall-panel rows, plus Portico's own). Only a phone can show the alarm's timing under
Doze, the notification and the Settings screen.
