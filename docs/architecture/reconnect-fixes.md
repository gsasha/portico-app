# Reconnecting when the network changes (DEV-705)

The fixes made after the connection log showed slow reconnects: reconnect at once on a network change, parallel tailnet dials, a wake lock in Doze, a short back-off after a drop, telling the tailnet node about network changes, and away call audio. The 0.6.84 link watchdog is described on [the reconnect overview](reconnect-and-link-watchdog.md).

The connection log showed a phone taking 8 s to reconnect after moving from mobile data to Wi-Fi and 24 s on the way back.
Two fixes, both measured from real reports:

- **A change of the default network is a clear event, so the reconnect starts at once.** `PathRechecker` now reconnects
  at once on the callback itself for the first change after a quiet moment (a timer armed with a delay is held back while a
  dozing phone sleeps: a Wi-Fi join waited 8.5 s for the next callback) and 0.5 s after for a follow-up in the same burst (it was 3 s), whatever the open path was: a socket on the old network can be dead whether it
  was direct or on the tailnet, and with no socket open (waiting out a back-off after no signal) the new network resets the
  back-off and connects now. Only a change of kind (Wi-Fi to mobile) never waits; churn on the same kind (Wi-Fi to Wi-Fi)
  is held to one reconnect per 5 s (it was 30 s). Nothing happens while paused, none while there is no network (the return
  of signal is the next change) and a live call defers it to the call's end. The first network seen at start only primes
  the machine, so a cold start does not connect twice.
- **A tailnet dial no longer waits 20 s on one attempt.** A dial to the home peer from a node that had only just joined
  could sit for the whole timeout while a fresh dial a second later connected. The Go forwarder now starts another dial
  every second (three at most, and at once when every running one has failed) and the first to connect wins; a late
  connection is closed. It started at 3 s at first, but the first reports showed a cold node's dial taking 3.4 to 4.1 s:
  the second dial, started at 3 s, connected in 0.4 s, so it now starts at 1 s. The total stays bounded by the same 20 s.
- **A hung dial is cancelled after 4 s and replaced (DEV-764, 0.6.87).** After a doze wake on mobile data (2026-11-20) the
  whole first wave of dials (0 s, 1 s, 2 s) hung until the 20 s bound, while the first dial of the next connection,
  made right after, connected in about 1 s. Each hedged dial now has its own 4 s deadline (`hedgeAttemptTimeout`); a dial
  that hits it is replaced by a fresh one and does not use up the three-attempt budget. Go log line: `tailnet_dial_cap`.
  Why the first wave hangs is not proven (a guess: the node's path to the peer is not established yet and dials started
  before it are lost); the fix does not depend on it.
- **A network change in Doze holds the CPU for up to 15 s.** The reconnect hops between threads, and a dozing phone fell
  asleep between the hops (a Wi-Fi join was evaluated 5.5 s late, when the next callback woke it). `PathRecheckWatcher`
  takes a `PARTIAL_WAKE_LOCK` with its own 15 s timeout from each network callback (new normal permission `WAKE_LOCK`, no
  prompt).
- **After a drop, look again every few seconds.** The retry back-off doubled up to 60 s, so a phone whose socket just dropped
  because Home Assistant restarted waited up to 35 s after HA was back (a measured 59 s reconnect). For 5 minutes after the link
  was last connected the cap is 8 s; after that, and for a phone that never connected, it is the long 60 s again, so a night
  without signal is not a night of probes (`Backoff.markConnected`).
- **The tailnet node is told at once that the network changed.** Android 11+ gives Go no netlink, so tailscale's network
  monitor only noticed a change when it next re-read the interface table the app supplies. A held tailnet socket on mobile
  data died with a timeout, and the next two dials each hung the full 20 s while the node (up for 0.8 s) still sent from the
  old network: a 57 s reconnect in the log. `GoTailnetEngine.pushNetwork` now calls `Tailnet.linkChanged()` (the node's
  `netmon.Monitor.InjectEvent`, which rebinds and probes again) whenever the interface table or the active network handle
  changed; signal-strength callbacks do not nudge it.
- **Away call audio is always on (0.6.64: the Setup switch is gone).** Ring 5 on 2026-10-24 was answered while the link was on the tailnet with the switch off:
  the call used WebRTC, whose UDP media cannot reach Home Assistant from outside the LAN, so it sat silent for 15 s and failed
  (`audio end … sent=0 recv=0 why=failed`, no `audio start`). The `audio end` line comes from the WebRTC wrapper only, which is
  how the path was told apart. On, a call whose link is on the tailnet takes the frames at once; a Home Assistant without the
  matching door option answers `media_open` with a clear refusal instead.
