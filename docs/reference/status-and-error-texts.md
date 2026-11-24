# Status and error texts

The reasons Settings shows for a failed connection on the direct and tailnet paths, with what to do about each. Other status texts are on [Home](../user-guide/home-screen.md) (the connection dot) and [When the phone cannot reach Home Assistant](../user-guide/when-the-phone-cannot-reach-ha.md).

**Why it cannot reach Home Assistant: the Settings lines.** Under "Home Assistant and the away
ring", Settings shows the last attempt on each path, when it was made, and why it failed. The
"This phone" block also shows the tailnet dial target: the QR's `ha_dial` (`192.168.2.5:443`), or
"none: dials the address's host name". None of these lines shows a key, token or URL.

- `Direct path: connected to Home Assistant's address 2 s ago.` / `Tailnet path: connected to
  Home Assistant 2 s ago.` That path opened the socket.
- `Last connect: direct failed +120 ms, tailnet started +130 ms, library loaded +1.9 s, node up
  +4.3 s, forwarder ready +4.4 s, tailnet open +5.0 s.` (DEV-694 P0) Where the time of the last
  connect went, as offsets from the moment the connect began. Each stage appears once, when it
  first happened; a stage that did not happen (for example no library load when the node was
  already up) is absent. It is the baseline for the away-connect speed work, and it contains only
  stage names and times: no address, key or token. A new connect replaces it; it is hidden while
  the phone is paused.
- `Direct path, 2 s ago: <reason>` / `Tailnet path, 2 s ago: <reason>`. The reason is one of:
  - `the name did not resolve on this network.` (direct): away from home this is normal for an
    internal name, and the tailnet path is tried next.
  - `the connection was refused (nothing listens on that port).` (direct) / `the target refused
    the connection` (tailnet): HA or its proxy is not listening on that port. Check the port in
    `ha_url` or `ha_dial`.
  - `no answer in time.` (direct): the address does not answer from this network (5 s deadline).
  - `this network has no route to that address.` (direct): the phone's own network cannot reach
    it at all.
  - `the secure (TLS) connection failed.`: the handshake broke for a reason other than the
    certificate. Check the proxy's TLS settings.
  - `the server's certificate is not trusted by this phone.`: renew the certificate, or use one
    from a public CA.
  - `the server's certificate does not match the address's host name.`: the certificate must
    name `ha_url`'s host, on both paths.
  - `the server answered with HTTP 404 instead of opening the connection.`: the proxy does not
    pass `/api/websocket` through, or the path in `ha_url` is wrong.
  - `connected, but Home Assistant refused this phone's access token.`: scan a new QR.
  - `the connection closed before it opened, with no reason given.`: something in between hung
    up. Look at the proxy's log.
  - `the tailnet was not up yet.` (tailnet): see the tailnet line above it for why.
  - `the target is a host name the tailnet cannot resolve: the QR has no ha_dial.` (tailnet, Go
    `no such host`): add `ha_dial` on the server and scan a new QR.
  - `no route to the target through the tailnet.` (tailnet, Go `no route to host`): approve
    the subnet route in the Tailscale console.
  - `the dial through the tailnet timed out: a policy rule blocks it, or no peer routes it.`
    (tailnet, Go `i/o timeout`): if the route line says a peer routes it, a policy rule drops
    this phone's tag on that port. Otherwise approve the route.
  - `the local forwarder refused the connection's token.` (tailnet): an app-internal fault.
    Force-stop Portico and open it again.
  - `the local forwarder was not listening at 127.0.0.1:41234.` (tailnet): the app's own
    connection to its forwarder was refused at that address and port (`the local forwarder was
    not listening.` when the port is not known). The node and the route are fine; nothing reached
    HA. An app-internal fault: force-stop Portico and open it again, and report the line.
  - `it failed for another reason.`: anything else.
- `Tailnet path: not tried, because this APK has no tailnet client.`: the APK was built without
  `gocore.aar`.
- `Tailnet route to 192.168.2.5: via home-router (192.168.2.0/24).`: a peer offers a route to the
  dial IP. This line appears only when the dial target is an IP and the node is up (or was just
  tried).
- `No tailnet peer offers a route to 192.168.2.5 (3 peers visible).`: approve the subnet route in
  the Tailscale admin console, or let the policy show it to this phone's tag.
- `Tailnet route to 192.168.2.5: peers not known yet.`: the node is not up, or has no peer list
  yet.

How the reason is worked out: the direct path classifies OkHttp's exception chain and HTTP code
(`:core` `LinkFailures`, one JVM test per branch). On the tailnet path OkHttp only ever talks to
the local forwarder, so a failed tailnet dial looks like a bare end of stream there. The Go
forwarder therefore records each dial's outcome (`ForwarderStatus()`: ok, no_such_host,
timeout, refused, unreachable, token), and a record newer than the attempt overrides OkHttp's
view. The route line comes from `RouteCheck(target)`, which reads the node's netmap
(`LocalClient().Status()`, each peer's `PrimaryRoutes`, `AllowedIPs` and own addresses). It
never dials.
