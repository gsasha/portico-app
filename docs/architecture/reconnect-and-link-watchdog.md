# Reconnecting and the link watchdog

This page explains how the app keeps its Home Assistant WebSocket alive: which path it picks (direct or tailnet), when it reconnects, and the link watchdog added in 0.6.84-alpha (DEV-764) that notices a dead link in seconds. The earlier reconnect fixes (DEV-705) are in [Reconnecting when the network changes](reconnect-fixes.md).

## Choosing a path

`FallbackConnector` opens the socket. Normally it tries the direct `ha_url` first (5 s) and uses the tailnet when that fails; a slow direct attempt is not cut off and wins if it opens first.
A path hint (`PathHint`) can start the tailnet first, for example on mobile data. See [Tailnet client](tailnet-client.md).

**New in 0.6.84: tailnet first on a network where the tailnet won last time.** If the last socket on the same Android network (same default-network id) ended up on the tailnet,
a reconnect there starts the tailnet at once, and the direct attempt races alongside for at most 3 s (`PathHint.DIRECT_CAP_MS`). Before this, the tailnet waited for the direct attempt to fail,
and a measured 20 s DNS timeout came before the tailnet was even tried. The flag is cleared by a new network and by a direct win, so a Wi-Fi that has become the house again still wins direct.

## The link watchdog

A tailnet link, or a Wi-Fi that quietly stopped forwarding, can show "connected" while no byte moves. OkHttp's own ping noticed such a dead link after about 2.5 minutes in one measurement, and a ring was
answered into a dead tunnel in that time. `LinkWatchdog` (`:core`, `core/ha/LinkWatchdog.kt`) is an application-level liveness check made of plain decisions with no clock or thread of its own:

- **Any inbound text proves the link.** Silence is probed with Home Assistant's own `ping` command (`{"id":N,"type":"ping"}`, answered `pong`), which is part of HA's WebSocket API, so the server needs no change.
- **Normal rule.** Silent for 30 s (10 s while a ring is up or the screen is on): send a ping. A ping unanswered for 8 s is a dead link. If a command was sent and nothing at all came back for 3 s, the link is probed too, and
  the ping then has 4 s to be answered.
- **Fast rule.** From the start of a ring (until it ends, at most 60 s) and for 15 s after an Answer press, the link is checked every 2 s and one missed ping (2 s) is a dead link on Wi-Fi (the rule below changes this on mobile data and the tailnet). A new ring pings at once if the last frame is older than 1 s.
  The window survives a reconnect, and a link race swap: the socket that replaces a dead one is still inside it (the window is copied to the connection that takes over).
- **On a dead link** the app drops the socket and reconnects at once instead of waiting out the 30 s socket timeout.

## The link race (0.6.91, DEV-811): make before break on mobile data and the tailnet

On cellular a reconnect took about 10 s, and the 2 s rule dropped sockets that were still working, so the call screen sat on "connecting". On a **mobile or tailnet link** (the path is the tailnet, or the default network is cellular) the strict deadline now starts a race instead of a drop:

- The strict deadline (2 s in the fast rule, the normal 8 s / 4 s otherwise) gives one `RACE` verdict per silence. The link is declared dead only at **6 s** (fast rule) or **4 s after the normal deadline**. Wi-Fi keeps today's strict rule. The network is asked only when the strict deadline has passed. An open stream (live video, away audio) on the socket withholds only the race, never the 6 s deadline.
- `SignalingClient` opens a **second socket** beside the working one (`Connector.openCandidate`): auth, version, doors, `calls/subscribe`, `content/subscribe`. The candidate registers no push, reads no profile, tells no listener and writes nothing shared (doors, path, timeline, Setup lines) until it wins; its call events are buffered. At most one candidate; one that is not subscribed within 15 s is closed.
- **First to be heard wins.** Any frame from the old link closes the candidate (`won by=old`; in the ring window the next candidate waits 20 s, or until the unanswered ping is 4 s old, so a 6 s death still has one prepared; outside the ring window the gap changes nothing). A candidate subscribed first takes over (`won by=new`): the old socket is closed the normal way (a command in flight on it is `Lost`, the answer path sends it again on the new one), the listener sees a disconnect and a new login as on any reconnect, the ring window and the profile come across, and the events the candidate heard are replayed, collapsed to the latest word per call (a ring the old link already delivered is dropped).
- **Commands during the race.** While a candidate exists the working link takes no command except a hangup: the answer path gets "not sent" and its 250 ms poll (up to 8 s) sends it once, on the winner. This is what stops an Answer being granted on a link the swap then discards.
- The old link is declared dead while the candidate is still on the way: it is dropped and the candidate carries on (today's reconnect, started earlier). A candidate that then fails reconnects at once. If no candidate can be opened (no cached token, refused URL, connector error) the link is dropped and reconnected at once (`link-race-skip why=`), exactly as before the race.
- Setup, Call audio: **Reconnect while ringing**, Make before break (default) or Classic (the strict rule everywhere, 0.6.90 behaviour).
- Journal (`net`): `link-race-start fast= silent= path=`, `link-race-won by=old|new ms= old=open|dropped path=`, `link-race-gaveup why=refused|closed|timeout ms=`, `link-race-cancel why=`, `link-race-skip why=`; the candidate's handshake lines are prefixed `cand-`, the swap is `link-swap`. The connection log gets a `race` event (`start`, `won_new`, `won_old`, `gaveup`, `skip`).

The constants are in `LinkWatchdog`'s companion object and are covered by `LinkWatchdogTest`; they were chosen from measurements of one installation and are not proven on every phone and network.

## Commands lost with a dead socket are sent again

`PatientCommandPort` wraps the command channel. A press of Answer that lands while the link is still getting ready (the link reports ready when `portico/version` answers, but doors and subscriptions follow a moment later)
is retried every 250 ms for up to 4 s instead of failing with "not ready". New in 0.6.84: a command whose socket died under it (the watchdog tore a stalled tunnel down) is sent again after the reconnect for up to 8 s,
but only while it is still wanted: a hang-up is always sent, anything else only while the ring is still up. The 8 s stays under the call session's own 10 s step timer so a late resend cannot claim a call nobody is answering any more.

## What the user sees

While the link is being rebuilt, Home, the ring window and the answer wait say **HA reconnecting** (`home_status_reconnecting`) instead of a failure. The connection log (see [Connection log](../reference/connection-log.md)) carries the reasons.

## Related

- [Reconnecting when the network changes (DEV-705)](reconnect-fixes.md)
- [Stay connected](../user-guide/stay-connected.md): the policy for holding or releasing the socket
- [Pause](../user-guide/pause.md): a paused phone keeps no socket
- [Status and error texts](../reference/status-and-error-texts.md)
