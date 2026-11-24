# Tailnet client (DEV-557): decisions

The host name, addresses and tag below are examples (`ha.example.com`, `192.168.2.x`, `tag:portico-phone`); substitute your own.

Goal: a phone away from home joins the user's tailnet with the enrollment QR's single-use
`tailscale_authkey` and reaches Home Assistant through it, so it can register
(`portico/push/register`), subscribe and later answer. At home the direct `ha_url` keeps working
and is tried first. The approach is the intercom app's `tsmobile` (an embedded tsnet node, no
`VpnService`), which has run on phones with this toolchain since 2026-09.

## (a) Dial target

**Finding.** The obvious option, "dial `ha_url`'s host name through tsnet", does not work for
a setup like this. tsnet's own dialer (`tsdial.Dialer.userDialResolve`, tailscale.com v1.102.3)
resolves a name through MagicDNS and the netmap first, then with the ordinary Go resolver. It
never uses the tailnet's split DNS. `ha.example.com` is not a tailnet node, and
public DNS has no answer for it, so the name cannot be dialled away from home.

**Decision.** The app takes an optional new QR field, `ha_dial`. Absent means "dial `ha_url`'s
host and port". That fallback works when `ha_url` is already an IP address, or a MagicDNS name
when HA is itself a tailnet node.

- **The server must add:** `"ha_dial": "192.168.2.5:443"`. It is a string, an IP literal and a port
  (`a.b.c.d:port` or `[v6]:port`). No host name is accepted, because a name needs the DNS that
  is missing away from home.
- The app validates it strictly (`TailnetTarget.parseHaDial`). The host must pass `UrlPolicy`'s
  private-host rule and be in 10/8, 172.16/12, 192.168/16, 100.64/10 or fc00::/7. Loopback,
  link-local, public, IPv4-mapped and non-canonical forms are refused. A present but invalid
  value refuses the whole enrollment (as `enroll_id` does), and nothing is saved.
- `ha_dial` is stored in Settings. A newer enrollment without it removes the stored one.

## (b) TLS

The URL never changes. OkHttp opens `wss://ha.example.com/api/websocket` with three
changes to its client, made in `TailnetConnector`:

- a `Dns` that never resolves (it returns a placeholder 127.0.0.1);
- a `SocketFactory` whose sockets connect to the local forwarder at `127.0.0.1:<port>`
  (`FORWARDER_LOOPBACK`, the explicit IPv4 loopback), whatever address they are given, and send
  the forwarder's token first;
- `Proxy.NO_PROXY`.

OkHttp then does TLS over that socket for the URL's real host. SNI, the certificate chain
(system CAs) and hostname verification are the same as on the direct path. The JVM tests check
the SNI the server sees, a wrong-name failure and an untrusted-certificate failure. The tailnet
carries bytes and is never trusted with the server's identity. Only the TCP connection moves.

**The forwarder** (`gocore/tailnet/forwarder.go`) listens on `127.0.0.1:<ephemeral>` and bridges
each connection to `tsnet.Server.Dial("tcp", target)`, so no `net.Conn` crosses the gomobile
boundary. *Change from the brief:* every app on the phone can reach 127.0.0.1, so each forwarder
mints a 32-byte random token. A connection that does not send that token within 5 s is closed
before anything is dialled. Without the token, any installed app could reach HA's port through
this phone's tailnet identity.

*The loopback is IPv4, explicitly.* The forwarder binds `127.0.0.1` only, so the Kotlin side
connects to `127.0.0.1`, never `InetAddress.getLoopbackAddress()`. On Android that call returns
`::1`, where nothing listens: every connect was refused and Setup said "the local forwarder was
not listening" (2026-10-08, node up and route present). A desktop JDK returns `127.0.0.1`, which
is why the JVM tests passed. `ForwarderLoopbackTest` now runs the socket in a child JVM with
`-Djava.net.preferIPv6Addresses=true` (loopback `::1`, as on Android) against a listener bound
to 127.0.0.1 only. A refused connect is a `ForwarderConnectException`, and Setup's line names
the endpoint tried: "the local forwarder was not listening at 127.0.0.1:41234."

`StartForwarder` reuses the forwarder for the same target only while its listener is alive.
If the `serve()` loop ended (the listener failed underneath), it builds a new one rather than
returning a port where nothing listens. `Stop` (and so the app's `sleep()`) closes the
forwarder, and every connection asks Go for the forwarder again, so no port outlives the node.

**Away-from-home video does not use the forwarder** (DEV-713 S1b). The forwarder bridges ONE fixed
target for OkHttp; the video goes to go2rtc (`192.168.2.6:8554`), so `tailnet.StartRtspVideo` calls the
node's own `Dial("tcp", host:port)` (`gocore/tailnet/rtspvideo.go`, client in `gocore/internal/rtsp`)
and hands H.264 NAL units to Kotlin for `MediaCodec`. It needs the Tailscale grant
`tag:portico-phone` -> `192.168.2.6:tcp:8554`. See [Live video away from home](user-guide/live-video.md).

## (c) Path choice

`FallbackConnector` (in `:core`) works like this:

1. It opens `ha_url` directly.
2. If the direct socket opens within 5 s (`DIRECT_TIMEOUT_MS`), that is the socket. The tailnet node
   is put to sleep.
3. If the direct attempt fails first (for example NXDOMAIN away from home, or refused), it is
   closed and the tailnet connector runs at once on its own worker thread. It wakes the node,
   waits up to 45 s, and opens the same URL through the forwarder.
4. If the direct attempt is only slow (not open at 5 s), the two paths are staggered, not
   exchanged. The tailnet starts, and the direct attempt stays alive for up to 10 s more
   (`DIRECT_GRACE_MS`, so it is cut off at 15 s, the same as OkHttp's connect timeout). Whichever
   opens first is the socket and the other is closed. A direct win puts the node back to sleep.
   Before this, the deadline closed the direct attempt, so a just-woken phone (Wi-Fi radio in
   power save, cold DNS and TLS) could lose to a cold tailnet node and end up with neither
   ("offline", then "lan" on a later retry).
5. Without a usable tailnet (no identity and no pending key, or an APK without the library), the
   socket closes with the direct reason, exactly as before.

**Mobile data starts the tailnet at once (DEV-694 P1c).** Before each connect the app reads which
networks Android lists (`ConnectivityManager`, no new permission). If mobile data is the default,
no validated Wi-Fi/Ethernet network exists and no other app's VPN is involved, the phone is not on
the home LAN, so the tailnet connector starts at once, alongside the direct attempt (the same state
a slow direct attempt is moved to at its deadline). The first to open wins and the other is closed
and shown in Settings as "not needed"; direct keeps its 5 s deadline; if direct wins, the tailnet
node that was started is stopped. It applies only when the tailnet is usable, the phone has a
stored identity and no pending single-use key, and the direct path is not known to work from
outside the house: that is learned (a direct socket that opens on mobile data, a public `ha_url`
with no split horizon) and forgotten after two unreachable results at least ten minutes apart. Wi-Fi
is untouched. Anything unreadable is today's behaviour (direct first). Design:
the maintainer's path-hint design note (not published).

An open socket never switches paths. Setup shows "Connected directly…" or "Connected … through
the tailnet", plus one line for the tailnet state. When the tailnet wins after a direct deadline,
the direct line shows "no answer in time", so a slow LAN is readable on the phone.

### Re-routing home (`PathRechecker`, `:core`)

A healthy tailnet socket never drops, so a phone that connected on mobile data would stay on the
tailnet after it joined the home Wi-Fi (wrong status word, node awake, and the frame-over-socket
call audio at home). `PathRecheckWatcher` reports the default network
(`registerDefaultNetworkCallback`, API 24; `ACCESS_NETWORK_STATE` is already declared) and
`PathRechecker` calls the existing `SignalingClient.reconnect()` (direct first, tailnet fallback as
always) when:

| Open path | Network | Call live | Paused | Result |
|---|---|---|---|---|
| tailnet | Wi-Fi or Ethernet | no | no | recheck, after a 3 s debounce |
| tailnet | Wi-Fi or Ethernet | yes | no | deferred; checked 3 s after the call ends |
| tailnet | Wi-Fi or Ethernet, rechecked < 30 s ago | no | no | one delayed look when 30 s have passed |
| tailnet | mobile data | any | any | never (the phone must stay on the tailnet) |
| tailnet | VPN (another Tailscale app, any VPN) | any | any | never; the VPN's routes decide where `ha_url` goes |
| tailnet | none or other | any | any | never |
| direct, or no open socket | any | any | any | never (the normal retry handles a closed link) |
| tailnet | any | any | yes | never |

A network change means a new network id or kind; repeated callbacks for the same network (signal
strength) are ignored. After a change is evaluated, nothing more is owed whatever the outcome, so a
recheck that lands on the tailnet again does not loop until the next change.

Second trigger: the tailnet won because the direct attempt was slow (it hit the 5 s deadline), as
on app reopen. On Wi-Fi or Ethernet, with no call and not paused, direct is retried after 12 s,
then 24, 48, 96, 192 and 300 s (the cap), and stops after six tries until the next network change.
A direct socket resets the count. This trigger has its own backoff and skips the 30 s flap guard.
It is not used for NXDOMAIN or a refusal (away from home), which fall back at once and are not
retried on a timer.

Not done: a recheck when the app returns to the foreground still on the tailnet. The network
callback and the timed retry cover the cases seen, and the foreground hook would sit in the Home
activity.

## (d) Key lifecycle

- The save stores the key and its save time in the encrypted Settings, in the same commit as the
  rest of the enrollment (`SetupResult.Ok.tailnetWrites`). The join starts right away, on a
  worker thread.
- A new key means a new node. The old identity is wiped before the key is redeemed.
- The key is deleted:
  - as soon as the node is up;
  - when the tailnet refuses it (`key_rejected`, or `needs_login` after a join with a key);
  - when it is older than 55 minutes (state `KeyExpired`, which asks for a new QR). This is
    checked on every look, including at process start, so a key saved before the process died
    is deleted on the first look after 55 minutes.
- Until then, a failed join (a timeout, no internet) is retried on the next connect.
- An enrollment without a key leaves the node alone and starts no join. It does drop an older
  enrollment's unredeemed key.
- The key never appears in a log line, status, `toString` or exception. The Go side also masks
  any `tskey-…` in its log ring and status text.
- After the first join, tsnet's node key in `noBackupFilesDir/tailnet/state` authenticates. That
  directory is app-private, never backed up, and deleted on uninstall. A stored identity that the
  tailnet refuses is not retried on every connect.

## (e) Battery, Doze, wake-on-push

The tailnet code has no foreground service of its own. The optional "Stay connected" service (see
[Stay connected](user-guide/stay-connected.md), 2026-10-15, default on) only keeps the process alive so the socket survives the background; on Wi-Fi or
Ethernet it holds the socket and the path choice below stays in charge, and on mobile data it releases
the socket and puts the node to sleep (so it does not hold the node awake there). The Go runtime (30 MB) loads only on
the first real need: a join, or a direct failure while an identity or key exists. A status
refresh never loads it. The node runs only while the socket uses the tailnet, and is stopped once
the direct path works. A push wakes the process, and `SignalingClient.wake()` reconnects through
the same direct-first path. The node is never kept up for its own sake.

## (f) Failure paths and what Setup shows

| State | Setup line |
|---|---|
| not built | "not built into this APK" (info) |
| no key, no identity | "No tailnet: the enrollment did not include a Tailscale key" (info) |
| key pending | waiting to be used (info) |
| joining | info |
| `needs_approval` (user-owned node, DEV-556 `approve`) | approve it in the admin console (problem) |
| up | the node's tailnet IPv4 (ok) |
| `key_rejected`, `needs_login`, `KeyExpired` | scan a new QR (problem) |
| `timeout` | retried on the next connection (info) |
| library failed or internal error | names the error class (problem) |
| no target | problem |

**Why a connection did not open (2026-10-08).** Setup also shows the last attempt on each path
and its reason, plus the route to the dial IP. [Status and error texts](reference/status-and-error-texts.md) lists every
line. Some background for reading the tailnet ones:

- tsnet's dialer (`tsdial.Dialer.dialOneUser`, v1.102.3) sends a dial through the tailnet only
  when `LocalBackend.PeerForIP` finds a peer for the IP: a node address, or an accepted subnet
  route. Otherwise it falls through to a plain `net.Dialer` on the phone's own network. Away
  from home, a 10.x target then simply times out on mobile data. So "dial timed out" with
  "No tailnet peer offers a route" means the route is missing. "Dial timed out" with "route via
  <peer>" means a policy rule drops the packets.
- Subnet routes are accepted because `ipn.NewPrefs` sets `RouteAll` true on GOOS `android`. A
  Linux host build does not set it, which is why the host tests read `PrimaryRoutes` rather than
  dialing through a route.
- The forwarder closes the local socket when its dial fails, so OkHttp sees only an end of
  stream. The forwarder records the outcome first (`ForwarderStatus`), and `:core`'s
  `LinkFailures.tailnet` uses a record that is newer than the attempt.

Go panics are contained in every exported function and on the join goroutine, and reported as
`panic`. A panic on one of tsnet's own goroutines still kills the process (logcat tag `GoLog`). DEV-762 A4: the next start reads the Go frames (function names only) from `GoLog` and attaches them to the `crash_native` exit record.

## (g) What only a phone can prove

- That the library loads.
- That `Prepare` prevents the `no safe place found to store log state` panic.
- That the Java interface table satisfies netmon on Android 11+, and that netbind marking works.
- A real join with a real single-use key against the real control server, and how the real
  server phrases a spent key.
- That the subnet route and ACLs let a tagged (`auto`) or user-owned (`approve`) node reach
  `192.168.2.5:443`.
- The SNI and certificate check against the real nginx certificate.
- Battery behaviour, and the process woken by a push in Doze.

The host tests prove the rest:

- the join, keyless rejoin, wrong-key, interactive-login and machine-approval paths, with real
  tsnet nodes against a local `testcontrol`;
- the forwarder through a real tailnet, end to end;
- the OkHttp redirect over ws and wss;
- every state transition, and the key lifecycle.
