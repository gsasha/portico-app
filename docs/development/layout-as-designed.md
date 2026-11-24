# Layout as the old README described it

The original layout sketch, kept verbatim. It is written from the unpublished design document's point of view (module names, section numbers like §4.3 refer to it); for what is actually in the repository today see [the repository map](repository-map.md).

```
app/              # Kotlin: Activities, CallStyle notification, foreground service,
                   # MODE_IN_COMMUNICATION audio routing (§4.6, §6's Android checklist)
    signaling/       # WebSocket client for the signaling API (§9.5) — call metadata,
                      # arbitration, the portico/doors fetch that tells gocore/ what to dial

gocore/           # gomobile-compiled Go module, following tsmobile's proven shape (§4.3, §6).
                   # Imports portico-ha's pkg/sip and pkg/driver/dahua as an ordinary cross-repo
                   # Go module dependency (not a copy) once wired up — a SIP/RTP or dahua-quirk
                   # fix lands once, in one place (§9.1, §9.8).
    tsnet/           # embedded tsnet client (§4.8) — the tailnet identity the app dials the
                      # device's LAN IP through
    tailnet/         # DEV-557: the gomobile-bound surface (bind.sh -> app/libs/gocore.aar)
```

Reading note: the `gocore/` description above is the plan. Today `gocore/` holds the tailnet client, the forwarder and an RTSP client (see
[the repository map](repository-map.md)); the portico-ha dependency is not wired up and `gocore.Dial` is a placeholder
(see [Design sources](../architecture/design-sources.md)).

Only `dahua_sip`-family calls ever reach `gocore`'s direct-dial path — `unifi_protect` never gives
the app a direct-dial shortcut; it always relays through `portico-ha`'s `internal/call/relay.go`
instead, using the exact same Go code (§9.6).
