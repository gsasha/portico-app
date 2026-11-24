# Tailnet client (DEV-557)

How a phone away from home joins the tailnet and reaches Home Assistant through an embedded tsnet node, which QR fields it uses, the Android hazards that were handled, and what has not been proven on a phone. The decision record is [`../tailnet.md`](../tailnet.md); the Settings lines and their texts are in [Status and error texts](../reference/status-and-error-texts.md); building the Go library is in [gocore and APK size](../development/gocore-and-apk.md).

## Tailnet client (DEV-557): what it does, QR fields

**What it does.** Away from home, the phone joins the user's tailnet with the enrollment QR's
single-use key. It then reaches Home Assistant through it, so it can register, subscribe and
later answer. At home nothing changes: `ha_url` is always tried directly first (5 s), and the
tailnet is used when that fails, or runs alongside it when it is only slow (the slow direct
attempt is not cut off, and wins if it opens first). On a network change to Wi-Fi or Ethernet, or
after a slow start, a socket sitting on the tailnet is re-routed direct-first (never on mobile data
or a VPN, never during a call, never while paused). The node is an embedded tsnet (userspace WireGuard), not
Android's `VpnService`. There is no VPN permission, and only Portico's
own traffic uses it. The optional ["Stay connected"](../user-guide/stay-connected.md) foreground service (below) keeps the process alive;
it does not run the node. Decisions and reasons: [`docs/tailnet.md`](../tailnet.md).

**QR fields it uses.**

- `tailscale_authkey`: the server sends it only in its `auto` mode. It is stored encrypted, with
  its save time, and redeemed right after the save. It is deleted as soon as the node is up,
  when the tailnet refuses it, or after 55 minutes.
- `ha_dial` (optional, e.g. `"192.168.2.5:443"`): where to dial through the tailnet, because
  `ha_url`'s internal name does not resolve away from home. It must be a private or tailnet IP
  address with a port; any other value refuses the enrollment. When it is absent, the app dials
  `ha_url`'s host. **The server adds this field in a follow-up.** Until then, an `ha_url` with a
  host name cannot be reached through the tailnet.

TLS is unchanged. OkHttp still opens `ha_url` and checks SNI, the certificate and the host name
for it. Only the TCP connection is redirected, to a token-guarded forwarder on 127.0.0.1 that
dials through the tailnet.

## Tailnet client (DEV-557): Android hazards, not proven here

**Android hazards** (from the intercom app, all handled):

- tsnet **panics** on Android unless `TS_LOGS_DIR` (an existing directory), `TMPDIR` and `HOME`
  are set (`Prepare`).
- Go's `net.Interfaces()` is blocked on Android 11+. Java feeds netmon the interface table, with
  `AltAddrs` filled (`TailnetInterfaces`).
- A Go panic kills the process with no Java exception (logcat tag `GoLog`). Every exported
  function and the join goroutine contain panics.
- The gomobile class must load **off the main thread**, after `go.Seq.setContext`
  (`GoTailnetEngine`). No tailnet call runs on the UI thread, and Settings reads a cached state.
- tsnet sockets are marked to the active network (netbind).

**Not proven here** (needs a phone and the tailnet):

- that the library loads and joins on a real phone, with a real key, against the real control
  server;
- that the routes and ACLs let the node reach `192.168.2.5:443`;
- the real certificate over the tailnet;
- battery use and Doze;
- a push waking the process.

The host tests prove the state machine, the key lifecycle, the path choice, the OkHttp redirect,
and the Go join and forward paths against a local control plane.
