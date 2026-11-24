# Stay connected (foreground service)

The optional foreground service that keeps the app process, and so the Home Assistant connection, alive; the policy table of when it holds, releases or defers the socket.

**Why.** Portico's connection to Home Assistant is a WebSocket in the app process. With no foreground
service Android freezes or kills a background process, the socket drops, and reopening the app showed
"offline" for a few seconds before it reconnected. The old intercom app avoids this with a foreground
service (`Watch`, type `connectedDevice`, START_STICKY) and a permanent notification. Decided on
2026-10-15 (NS-1 add it, modelled on `Watch` with a quiet low-priority notification; NS-2 hold the
connection only on Wi-Fi/Ethernet; NS-3 on by default with a Settings switch).

**What it is.** `stay/ConnectionService`: type `connectedDevice`, not exported, `stopWithTask=false`,
START_STICKY. It holds no socket and runs no timer; it keeps the process running. Its notification is on
its own LOW channel (`portico_connection_v1`, no sound, no vibration), ongoing, tap opens Home, and says
"Connected to <home name>" / "Connecting to <home name>..." / "Waiting for Wi-Fi". It is never a ring or a
CallStyle. No wake lock and no Wi-Fi lock (the old app takes neither). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
is not requested; the Settings help text says battery optimisation can still limit it.

**The Android rule that shapes it.** Since Android 12 an app cannot start a foreground service from the
background, except in listed cases. A network callback is not one, so the service cannot be started
when Wi-Fi appears. It is started from an allowed context and, while the switch is on, stays started;
the network only decides what it does with the socket. Allowed contexts used: the app being visible
(Home or Settings opening, the switch changing), `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (through the
existing `BootReceiver`; `connectedDevice` is not among the types Android 15 bars from BOOT_COMPLETED
receivers: dataSync, camera, mediaPlayback, phoneCall, mediaProjection, microphone), and a high-priority FCM
message (the push's wake). From anywhere else the start is attempted, refused by Android, logged as
"not now" (whether it surfaces in `startForegroundService()` or inside the service's `startForeground()`)
and retried at the next allowed context; only a permission-class refusal is remembered, and an app opening or boot clears it. Android 14+ also needs a prerequisite permission for the
type; `CHANGE_NETWORK_STATE` (a normal permission, no prompt) is the one claimed, as in the old app.
`connectedDevice` has no Android 15 timeout (only `dataSync` and `mediaProcessing` do).

Sources: developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (exemptions),
.../fgs/service-types (connectedDevice prerequisites), about/versions/15/behavior-changes-15 (BOOT_COMPLETED
barred types), .../fgs/timeout (Android 15 timeouts).

**Policy** (`:core` `StayConnectedPolicy`, one test per row; `StayConnected` applies it):

| Switch | Enrolled | Paused | Network | Call live / screen open / push < 3 min ago | Service | Socket |
|---|---|---|---|---|---|---|
| on | yes | no | Wi-Fi or Ethernet (also under a VPN) | any, battery saver off, or on and the last connection was direct | start or keep | HOLD: connect if closed; route stays with `FallbackConnector` and `PathRechecker` (direct first, node asleep when direct works) |
| on | yes | no | mobile data, VPN with no Wi-Fi, other (decided 2026-10-24) | battery saver off | start or keep | HOLD: the tailnet carries it; a connection that dropped is retried (8 s for 5 minutes, then 60 s) |
| on | yes | no | any network, **battery saver on**, the last connection was on the tailnet (or the network is mobile data) | no | keep | RELEASE: socket closed, no retry, tailnet node put to sleep; a push or opening the app brings it back. A connection on the home LAN is never released by battery saver. |
| on | yes | no | no network (or any of the RELEASE rows above) | yes | keep | DEFER: left alone; released when the call ends, the screen closes or the 3 minutes pass |
| on | yes | no | no network | no | keep | RELEASE (nothing to connect to; the return of a network is a change that connects) |
| off | any | any | any | any | stop | IDLE: exactly as before this feature (socket while the process lives) |
| any | no | any | any | any | stop | IDLE |
| on | yes | yes | any | any | stop, quiet | IDLE (Pause owns the socket) |
| on | yes | no | any | any | Android refused it for a permission-class reason (`SecurityException`, foreground-service type exception) | IDLE, not tried again until the switch is toggled, a screen opens, boot or update, or the process restarts |
| on | yes | no | any | any | Android said "not now" (`ForegroundServiceStartNotAllowedException`, or the null-intent START_STICKY restart from the background) | not remembered: the service stops itself and the next allowed context starts it |

**Deviation from NS-2's literal reading.** Android cannot start the service from a network callback, so
once started it stays started on mobile data too, and so does its notification, which then says "Waiting for
Wi-Fi". What NS-2 asked for (not holding the connection, node asleep, battery) is done; the notification
being absent on mobile data is not possible without losing the ability to come back on Wi-Fi.

**Pause** stops the service and removes its notification; Resume starts it again when Android allows it
(the notification's Resume button and Settings are allowed contexts; a timed pause that ends on its own in
the background is not, so the service returns at the next push or app opening).

**Hold on the home LAN and on the tailnet (decided 2026-10-24).** The first version held only on Wi-Fi and let mobile data go.
The connection log showed what that costs: a join onto Wi-Fi with the phone locked reached the app only at unlock. Holding
everywhere gives a ready connection; battery saver (`PowerManager.isPowerSaveMode`, re-evaluated on
`ACTION_POWER_SAVE_MODE_CHANGED`) lets the tailnet one drop, because the embedded node's keepalives and relay connection are
the costly part, while a home-LAN connection stays. "The last connection was on the tailnet" is remembered after the release
(otherwise the release would read as "not on the tailnet" and reconnect in a loop) and cleared by a network change, which tries
the home LAN first again.

**Only a phone can show**: battery over a day on Wi-Fi and on mobile data, Doze, OEM task killers,
whether the notification looks right, and that the first start after an update from the background is
refused or allowed on the real Android version.
