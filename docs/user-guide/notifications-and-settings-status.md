# Notifications, the ring and Settings status

What the phone posts for each ring outcome, the notification channels (including Do Not Disturb handling), the full-screen intent, and the problems Settings lists with a fix button.

## Ring UI and Settings status (P2, PR-F)

What the phone posts for each decided `Outcome` is computed in `:core`
(`core/.../ring/NotificationPlan.kt`, JVM-tested) and applied by `ring/AndroidOutcomeHandler`,
which replaced PR-E's logging handler in `AppGraph`.

| Outcome | What is posted |
| :--- | :--- |
| `Ring` | CallStyle incoming call on `portico_ring_v2` with a full-screen intent to `HomeActivity` (APP-03: a station call has no ring screen; see below), ongoing, `FLAG_INSISTENT`, `setTimeoutAfter(ringUntilMs - now)`, plus an AlarmManager cancel at `ringUntilMs` |
| `Dismiss` | stop that call's ring; "Answered elsewhere" on `portico_status_v1`, 10 s timeout |
| `Missed` | stop that call's ring if it is up; quiet "Missed call" on `portico_status_v1`, kept until cleared |
| `Silent` (`after_terminal`) | re-post the call's last status notification with `setOnlyAlertOnce(true)` (e.g. "ended after answered") |
| `Silent` (other) | nothing |
| `Quiet` (`kind: test`) | one quiet "Portico push ring notification works" on `portico_status_v1`; never a ring |

- **Channels:** `portico_ring_v2` IMPORTANCE_HIGH (default ringtone on the alarm stream; A-09, 0.6.29-alpha: Setup's "Doorbell sound" row (no longer in the Settings layout, see the [Settings reference](../reference/settings.md)) opened the system sound picker and, for a chosen sound, the same channel is created under `portico_ring_v2_s<8 hex of the URI hash>` and every other ring-family id is deleted, since a channel's sound cannot be edited; `:core` `RingSound`, bypass-DND requested at creation, vibration; `_v1` is retired and deleted at start-up);
  `portico_status_v1` IMPORTANCE_DEFAULT with no sound and no vibration. A future change of
  importance or sound means a new id (`_v2`) listed in `Channel.RETIRED_IDS`.
- **No foreground service** on the ring path (the optional ["Stay connected"](stay-connected.md) service, below, is separate
  and never rings): CallStyle is legal because a full-screen intent is attached. If the system refuses the CallStyle, a plain notification with Open / Stop follows.
- **Answering is on the ring screen** (see ["Answering a door call"](answering-a-door-call.md)). The ring path itself carries no
  media: CallStyle's Answer opens the ring screen, and pressing Answer there starts the call. The
  screen also has a button that stops ringing on this phone, and says that opening the door happens
  in the intercom app or on the panel. Decline stops ringing on this phone only.
- **Backstop alarm:** `setAndAllowWhileIdle(RTC_WAKEUP)`. No exact-alarm permission: the exact cap
  is `setTimeoutAfter`, and the alarm only has to guarantee an eventual way out.
- **Request codes and notification ids** come from `core/.../ring/RequestCodes.kt` only: per door
  slot (`DoorSlots`, persisted) and action. `RequestCodesTest` proves they never collide.
- **Settings** shows, problems first, each with a fix button: notification permission (requested
  from Settings; "not asked" is its own state), the two channels, the full-screen-intent permission
  as `canUseFullScreenIntent()` reports it on Android 14+, Do Not Disturb, background restriction,
  battery guidance, the connection to HA, the push ring notification registration and HA's per-phone switch
  (from the register result and `portico/push/status`), and the local ring switch with a generic note
  that switching it off stops Portico alerting on a phone that also runs another intercom app.
  Portico does not detect other apps (no `<queries>`): that would be one installer's glue in a
  public app (P2 plan §3.6).

No new dependencies. `MergedManifestTest` also asserts `USE_FULL_SCREEN_INTENT`,
`POST_NOTIFICATIONS`, the ring activity and receiver not exported, `showWhenLocked`,
`turnScreenOn`, no exact-alarm or overlay permission, and no `<queries>` element.

Only a real phone (P4) can prove the full-screen intent over the keyguard, CallStyle acceptance,
the HIGH-priority path in Doze, the full-screen permission on a sideloaded install, and how the
screens look.

## A refused CallStyle is visible

**A refused CallStyle is visible:** when Android refuses the CallStyle ring and the plain
fallback is used, the refusal (its exception class name) is kept in the `portico_ring` prefs and
Settings shows it under "Ringing on this phone" until a later CallStyle is accepted
(`core/.../ring/CallStyleFallback.kt`, `SetupStatus.ringStyleLines`).
