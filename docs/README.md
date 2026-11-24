# Portico app documentation

Portico app is the Android client for the Portico Home Assistant integration (portico-ha). This folder holds everything that is not in the
top-level [README](../README.md): how to get going, how each screen behaves, how the app is built, the details a contributor needs, and the decisions that shaped it.

## Reading order

1. **New here?** Start with the [README](../README.md), then [Getting started](getting-started/README.md).
2. **Using the app?** [User guide](user-guide/README.md).
3. **Want to understand how it works?** [Architecture](architecture/README.md), starting with the [overview](architecture/overview.md).
4. **Looking something up?** [Reference](reference/README.md): settings, the connection log, push setup, network policy, contracts, versions, status texts.
5. **Changing the code?** [Development](development/README.md) and [CONTRIBUTING](../CONTRIBUTING.md).
6. **Why was it done this way?** [History](history/README.md).

## Pages by folder

| Folder | What is in it |
| :--- | :--- |
| [getting-started/](getting-started/README.md) | Requirements and install, enrolling a phone, the first call |
| [user-guide/](user-guide/README.md) | Home, answering calls, ring and in-call screens, live video, pause, stay connected, roaming, notifications, trouble reaching Home Assistant |
| [architecture/](architecture/README.md) | Overview and diagram, reconnecting and the link watchdog, tailnet client, enrolment and login, QR scanner, push decision core |
| [reference/](reference/README.md) | Settings, connection log, push and ring journal, Firebase push, network policy, contracts, versions, permissions, status and error texts |
| [development/](development/README.md) | Repository map, build and test, gocore and APK size, release build, version rule, conventions, icon |
| [history/](history/README.md) | Dated decision index and old status notes |

[`tailnet.md`](tailnet.md) is the decision record for the tailnet client and stays at this path (source comments point to it).

## Looking for a section of the old README?

The README used to be a 1,383-line page. Every section moved here without losing text; where each one went:

| Old README section | Now |
| :--- | :--- |
| Intro (design doc, wire contract) | [architecture/design-sources.md](architecture/design-sources.md) |
| Intro (Status) | [history/status-snapshot.md](history/status-snapshot.md) |
| Layout | [development/layout-as-designed.md](development/layout-as-designed.md), plus the new [repository map](development/repository-map.md) |
| Build and test | [development/build-and-test.md](development/build-and-test.md) |
| Versions that build | [reference/versions.md](reference/versions.md) |
| Enrollment and settings | [reference/enrolment-settings.md](reference/enrolment-settings.md) |
| Connection log | [reference/connection-log.md](reference/connection-log.md) |
| Reconnecting when the network changes | [architecture/reconnect-fixes.md](architecture/reconnect-fixes.md) |
| Push and ring journal | [reference/push-ring-journal.md](reference/push-ring-journal.md) |
| Enrolment by one-time code | [architecture/enrolment-and-login.md](architecture/enrolment-and-login.md) |
| Network policy | [reference/network-policy.md](reference/network-policy.md) |
| Scanning the enrollment QR | [architecture/qr-scanner.md](architecture/qr-scanner.md) |
| Push decision core | [architecture/push-decision-core.md](architecture/push-decision-core.md) |
| WebSocket contract | [reference/websocket-contract.md](reference/websocket-contract.md) |
| Contract 3 | [reference/contract-3.md](reference/contract-3.md) |
| Answering a door call | [user-guide/answering-a-door-call.md](user-guide/answering-a-door-call.md) |
| Push ring notification: Firebase messaging | [reference/push-fcm.md](reference/push-fcm.md) |
| Ring UI and Settings status | [user-guide/notifications-and-settings-status.md](user-guide/notifications-and-settings-status.md) |
| When the phone cannot reach Home Assistant | [user-guide/when-the-phone-cannot-reach-ha.md](user-guide/when-the-phone-cannot-reach-ha.md) |
| Home: cameras and buttons | [user-guide/home-screen.md](user-guide/home-screen.md) and [user-guide/talk-and-house-calls.md](user-guide/talk-and-house-calls.md) |
| Live video on Home / away from home / ring and in-call | [user-guide/live-video.md](user-guide/live-video.md) |
| Roaming block and the mobile-data meter | [user-guide/roaming-and-data-meter.md](user-guide/roaming-and-data-meter.md) |
| The ring and in-call screens draw the caller | [user-guide/ring-and-in-call-screens.md](user-guide/ring-and-in-call-screens.md) |
| Pause | [user-guide/pause.md](user-guide/pause.md) |
| Stay connected | [user-guide/stay-connected.md](user-guide/stay-connected.md) |
| Tailnet client | [architecture/tailnet-client.md](architecture/tailnet-client.md); Settings lines in [reference/status-and-error-texts.md](reference/status-and-error-texts.md); building in [development/gocore-and-apk.md](development/gocore-and-apk.md) |
| Release build | [development/release-build.md](development/release-build.md), [development/versioning-and-releases.md](development/versioning-and-releases.md), [reference/permissions.md](reference/permissions.md), and the refused-CallStyle note in [user-guide/notifications-and-settings-status.md](user-guide/notifications-and-settings-status.md) |
| Icon | [development/icon.md](development/icon.md) |
| A station call is the main screen | [user-guide/ring-and-in-call-screens.md](user-guide/ring-and-in-call-screens.md) |
| License | [README](../README.md#licence) |

## Conventions in these pages

- Identifiers such as `DEV-705` or `PR-J` are the maintainer's tracker and stage names, kept so older notes and commits can be matched.
- Addresses and names in examples (`192.168.2.x`, `ha.example.com`, `home-router`, `tag:portico-phone`) are placeholders; use your own.
  Pages that quote the project's early decisions keep the dated record without personal names.
- "Not proven" means host tests cover it but nobody has confirmed it on a real phone.
