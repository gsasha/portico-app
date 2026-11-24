# Design sources and the wire contract

The old README's opening paragraphs: what the app is for, which design document it follows, and which server contract it speaks. For the current picture of the modules see [the architecture overview](overview.md).

The native Android client half of **Portico** — a public, config-driven HA calling plugin. Thin
compared to a typical VoIP app: its job is call UX (foreground service, `CallStyle` notification,
lock-screen full-screen intent, `MODE_IN_COMMUNICATION` audio routing), not protocol logic.

**What is real today.** The design planned to share the SIP/RTP code with `portico-ha`'s transport module through a cross-repo Go module
dependency. That dependency is **not wired up**: `gocore/go.mod` has the `require` line commented out, nothing in `gocore/` imports
portico-ha, and the root package's `Dial` function is a placeholder that panics ("Phase 2: not implemented"). It is also not part of the built
library: `gocore/bind.sh` binds only `./tailnet`. Calls are answered with WebRTC through Home Assistant (see
[Answering a door call](../user-guide/answering-a-door-call.md)); direct SIP dialling from the phone is planned, not built.

**Design doc:** the project follows the maintainer's design document, which is not published. The sections cited in source comments and
older notes (§4.6 and §4.8 for the client and Tailscale architecture, §9.8 for this repository's layout) are summarised by the
[architecture overview](overview.md) and the [repository map](../development/repository-map.md).

**Wire contract:** the code speaks **contract 3** of `portico-ha` (M6 A1, see ["Contract 3"](../reference/contract-3.md) below) and nothing else: there is
no fallback to contract 2. The app's `versionName` lives only in `app/build.gradle.kts` (`porticoVersionName`; A3 cut 0.5.0-alpha): **app 0.5.x needs a server at contract 3**, and
**app 0.4.x stays on contract 2** (a contract-3 server answers a 0.4.x `portico/push/register` with `app_too_old`).
Every later version has a larger derived `versionCode` (see ["Release build"](../development/release-build.md)), so it updates an installed 0.4.x or 0.5.x in place (same release key).
