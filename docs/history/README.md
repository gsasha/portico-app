# History

Dated decisions and stage notes that shaped the app. The full text of each one is on the page it points to; this page is the index. Identifiers (`DEV-nnn`, `PR-x`, `APP-nn`, `A-nn`, `M6`, `M7`) are the maintainer's tracker and stage names. Where a page quotes a decision by the project's owner, the quote is kept as written; the owner's separate decision log is not part of this repository.

| When | Id | Decision or change | Where |
| :--- | :--- | :--- | :--- |
| 2026-09-13 | PR-C2 | QR scanner ported from the old intercom app, proven there on real phones | [QR scanner](../architecture/qr-scanner.md) |
| 2026-10-06 | PR-G | Versions that build recorded | [Versions that build](../reference/versions.md) |
| 2026-10-09 | Pause | Owner asked for a pause button; rules ported from the intercom app | [Pause](../user-guide/pause.md) |
| 2026-10-15 | NS-1..3 | Foreground service added; hold the connection on Wi-Fi/Ethernet; on by default with a Settings switch | [Stay connected](../user-guide/stay-connected.md) |
| 2026-10-24 | DEV-705 | Hold the connection on the LAN and the tailnet; away call audio always on (0.6.64 removed the switch) | [Stay connected](../user-guide/stay-connected.md), [Reconnect fixes](../architecture/reconnect-fixes.md) |
| 0.4.x to 0.5.0 | M6 A1 | Contract 3; no fallback to contract 2 | [Contract 3](../reference/contract-3.md) |
| 0.5.11-alpha | DEV-544 | Enrolment by one-time code (QR format 2) | [Enrolment and login](../architecture/enrolment-and-login.md) |
| 0.5.12-alpha | | Push and ring journal | [Push and ring journal](../reference/push-ring-journal.md) |
| 0.6.29-alpha | A-09 | Doorbell sound row | [Notifications](../user-guide/notifications-and-settings-status.md) |
| 0.6.30-alpha | A-10, DEV-702 | Roaming block and the mobile-data meter | [Roaming](../user-guide/roaming-and-data-meter.md) |
| 0.6.33-alpha | APP-03 | A station call is the main screen; the ring screen was removed | [Ring and in-call screens](../user-guide/ring-and-in-call-screens.md) |
| 0.6.57-alpha | | "Reset camera sizes" menu item removed | [Home](../user-guide/home-screen.md) |
| 0.6.68-alpha | | Notification glyph redrawn | [Icon](../development/icon.md) |
| | DEV-557 | Tailnet client (embedded tsnet, no VPN permission) | [Tailnet client](../architecture/tailnet-client.md), [decision record](../tailnet.md) |
| | DEV-705 | Connection log; fast reconnect on network change | [Connection log](../reference/connection-log.md), [Reconnect fixes](../architecture/reconnect-fixes.md) |
| | DEV-713 | Live video S1 (home), S1b (away, RTSP), S2 (ring and in-call; thermal and decoder guards) | [Live video](../user-guide/live-video.md) |
| 0.6.84-alpha | DEV-764 | Link watchdog, tailnet first where the tailnet won, resend lost commands | [Reconnecting and the link watchdog](../architecture/reconnect-and-link-watchdog.md) |

Older status text from the README: [Status paragraph from the old README](status-snapshot.md).

