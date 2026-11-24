# Architecture

How the app is put together and why. Start with the overview; the other pages go deep on one subsystem each.

| Page | Subject |
| :--- | :--- |
| [Overview](overview.md) | Modules, a diagram, and how signalling, media, push and the tailnet connect |
| [Reconnecting and the link watchdog](reconnect-and-link-watchdog.md) | Path choice, the 0.6.84 link watchdog, resending lost commands |
| [Reconnecting when the network changes](reconnect-fixes.md) | The DEV-705 fixes: reconnect at once, parallel dials, Doze wake lock |
| [Tailnet client](tailnet-client.md) and [decision record](../tailnet.md) | The embedded node that carries the connection away from home |
| [Enrolment by one-time code](enrolment-and-login.md) | Scan, redeem, refresh-token login |
| [Scanning the enrolment QR](qr-scanner.md) | Why Camera2 plus ZXing, QR size, permission |
| [Push decision core](push-decision-core.md) | What the phone does with a push message |
| [Design sources and the wire contract](design-sources.md) | The old README's introduction |

Contracts and rules are in [Reference](../reference/README.md).
