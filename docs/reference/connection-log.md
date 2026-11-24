# Connection log and diagnostics reports

What the connection log records, what a report never contains, how reports are queued and sent to Home Assistant, and the Journal lines (live, audio, ring decisions) sent with them.

So that a slow or failed connection can be debugged later, the app keeps one short report for each **connection
sequence** and sends it to the owner's Home Assistant. A sequence starts with a trigger and ends when the socket is
connected (or the app gives up):

- triggers: the default network changed (`net_change`: Wi-Fi to mobile, mobile to Wi-Fi, Wi-Fi to Wi-Fi, none to anything,
  anything to none), the app started (`app_start`), an established socket was lost (`reconnect`), the app was woken or the
  user asked to reconnect (`wake`, `user`);
- while it is open it records, as offsets in ms from its start: every default-network change (`net`), every step of the
  connect (`ev`: the connector's stages, socket open, state changes, closes, the back-off waits, and since 0.6.91 the link
  race: `race` with `start`, `won_new`, `won_old`, `gaveup`, `skip`; see [the link race](../architecture/reconnect-and-link-watchdog.md)), the path hint that was
  applied, which path won, and tsnet's own phase times;
- when it ends it becomes one JSON object of about 0.5-1.5 KB (never more than 3900 characters; the middle of a very busy
  sequence is dropped and counted in `dropped`).

**What a report never contains:** a Wi-Fi name, an IP address, a gateway, a web address, a token or key, the Home Assistant
host, the phone's name. Only fixed words (`wifi`, `cellular`, `none`, `tailnet_open`, an error class such as
`NAME_NOT_RESOLVED`), numbers and booleans. The server checks again (known keys only; anything that looks like a web address
or a long token is replaced; at most 4 KB). Reports go only to your own Home Assistant, over the app's own authenticated
connection (so over the tailnet when away); they are not sent to Google, Tailscale or anyone else.

Reports wait in a small queue (preferences; at most 100 reports and 256 KB, the oldest dropped first and counted) and are sent
as `portico/connlog/report` once the socket is connected, at most one batch (20 reports, 48 KB) in flight and one every 10
seconds. An error backs off 15 minutes, doubling to 6 hours; an older Home Assistant plugin without the command answers
`unknown_command` and the app stops sending for that run. Settings has "Send connection logs to Home Assistant" (**on by
default**; switching it off empties the queue and records nothing) and a line showing how many wait, how many were accepted
and when. Home Assistant keeps 30 days (`portico-ha`, `portico/connlog/get`, administrators only). Code: `:core`
`connlog/` (`ConnLog`, `ConnLogQueue`, `ConnLogSender`, `ConnNetMapper`), `:app` `connlog/` (`ConnNetWatcher`,
`PrefsConnLogStore`), the wiring in `AppGraph`, and `SignalingClient`'s `onTrace`.

**Live and audio Journal lines (DEV-713 / DEV-705).** The same queue, sender, socket and switch also carry the Journal's `live ...` (live
video) and `audio start|end ...` (call audio) lines, so the owner can read their results from Home Assistant. `JournalReporter` (`:core`
`connlog/`) is offered every line the Journal stores (`PushJournal.lineObserver`, set once in `AppGraph`), keeps only lines whose first word is
`live`, `audio` or (DEV-762 A1) one of the ring-decision words `push`, `ws`, `ui`, `hold`, `house`, `frames`, `net`, `layout` (portico-ha's `JOURNAL_WORDS`; the permission and channel state at ring time is already inside the `push ring` / `ui` lines), so a ring's decision reaches Home Assistant by itself and nobody copies the Journal; the Journal screen's Copy button stays, turns the `HH:mm:ss.SSS` prefix into the epoch time (`t`), and sends batches as items of `reports`:
`{"kind":"journal","id":"<12 hex>","app":"0.6.16-alpha","lines":[{"t":<epoch ms>,"line":"<text>"}]}` (no `v`; an older plugin rejects the item and
the app counts it as sent). A line is checked with portico-ha's own rules before it is queued (printable ASCII and `…`, at most 240 characters,
cut with `…`; no URL, address, host name, `@`, `Bearer`, `tskey-`, `token=` key or long hex / base64 run): one the server would refuse is
dropped here and counted, never sent. Batches go 5 seconds after the first waiting line, at 20 lines, or at once on `live stop` /
`audio end`; at most 50 lines per item, 200 lines waiting (the oldest dropped and counted) and 1000 lines per ten minutes. The hand-off from the
Journal is a non-blocking in-memory enqueue; the flush runs on the connlog executor. Switching "Send diagnostics to Home Assistant" (formerly "Send connection logs") off
stops it and forgets what waits. A failure of the reporter is journalled once as `connlog journal fault ex=<class>` (not a `live` or `audio` line,
so never reported). Lines waiting less than 5 seconds are lost if the process dies.
