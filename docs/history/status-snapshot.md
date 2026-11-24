# Status paragraph from the old README

The old README's status paragraph, kept verbatim for the record. It is a snapshot from an earlier alpha and is not kept up to date; see the root README for the current status.

**Status:** P2 ring, registration and Settings work; the tailnet client (DEV-557, see [the tailnet client](../architecture/tailnet-client.md)) is built
and host-tested but not yet proven on a phone. A door call can be ANSWERED from the phone (WebRTC to the transport's SFU, the same exchange as the Lovelace card; see ["Answering a door call"](../user-guide/answering-a-door-call.md)), host-tested only. No direct SIP dialing yet (Phase 2, §7). Its wake path is the persistent WebSocket (§9.5), with FCM as an optional away
wake beside the socket, not a replacement for it (push spec §0.1).
