# Portico app

An Android client for **Portico**, a Home Assistant integration for door and gate calling. When someone rings
at the door or gate, the phone rings like an incoming call, you answer with two-way audio, and the Home screen
shows the cameras and buttons you set up in Home Assistant. It works at home and, through an embedded
tailnet client, away from home.

> **Status: alpha.** Releases are published as prereleases. Much of the behaviour is covered by host tests
> (JVM unit tests and recorded server contracts) and has not been proven on every phone. The docs say
> "not proven" where that is the case.

The server half is the Portico Home Assistant integration, **portico-ha**. This app does nothing useful without it.

Both repositories are developed together. A public link to portico-ha is added here when it is published (PORTICO_HA_URL_TODO).

## What it does

- **Rings for door calls.** A call arrives over a persistent WebSocket to Home Assistant, and optionally also as a
  Firebase push. The phone shows an incoming-call notification that can take over the lock screen.
- **Answer with two-way audio.** WebRTC audio to the door, with Mute and Hang up on the call screen.
- **Home screen.** A tile per camera (a still picture that refreshes while Home is visible, resizable, pinch to zoom)
  and a button per action (door, lock, script, scene, call, talk), all defined in Home Assistant, not in the app.
- **Live video.** Live camera tiles over WebRTC at home and over RTSP through the tailnet away from home, switchable per tile.
- **Away from home.** An embedded tailnet (Tailscale/tsnet) node carries the connection when Home Assistant is not reachable directly.
  It needs the `gocore.aar` library, which the release APKs include.
- **Sign-in by one-time code.** Scan a QR code shown by Home Assistant; no long-lived token is stored on the phone.
- **Pause, stay connected, roaming block.** Pause the app for an hour or more, keep the connection alive with a quiet foreground
  service, and hold back pictures and video on a roaming plan.
- **House calls (off by default).** Ring and call other phones of the house through Home Assistant, if the server enables it.
- **Diagnostics.** A short on-phone journal and, optionally, a redacted connection log sent to your own Home Assistant only.

Not in this app: direct SIP dialling from the phone (planned, not built); calls always go through your Home Assistant.

## Requirements

| What | Requirement |
| :--- | :--- |
| Phone | Android 8.0 or newer (`minSdk 26`), 64-bit ARM (`arm64-v8a`). Phones without arm64 libraries are refused at install. |
| Server | Home Assistant with the **portico-ha** integration, at **contract 3** (the only contract this app speaks). |
| Network | Reach Home Assistant directly (home Wi-Fi, or an address you can reach) or through a tailnet when away. |
| Push (optional) | A Firebase project; the server puts four of its values in the enrolment QR (`fcm` block). Without it the socket still rings while the app is alive. |
| Away (optional) | A tailnet and a Tailscale key the integration puts in the enrolment QR. |

## Install

1. Download `portico-<version>.apk` from the **Releases** page of this repository.
2. On the phone, allow your browser or file manager to install unknown apps, then open the APK. Or from a computer:
   `adb install -r portico-<version>.apk`.
3. Updates install over the old version, and keep your settings, only when signed with the same key. A debug build you compile yourself
   is signed with a different key; uninstall Portico before switching between the two.

Release APKs are signed with the project's release key. The certificate SHA-256 that `scripts/release.sh` expects is
`5f8cb715448565fd63d907a0fd4780726d8150f708b49178afbb86f0811b3451` (a public fingerprint, not a secret); check it with
`apksigner verify --print-certs`. Details: [Release build and signing](docs/development/release-build.md).

The Releases page of the repository that hosts this code (GitHub) carries the APKs; download `portico-<version>.apk` from there.
`scripts/release.sh` builds and checks a release and uploads it to a Gitea server you name through environment variables
(see [Version rule and releases](docs/development/versioning-and-releases.md)); for GitHub, attach the checked APK to the release by hand
(`gh release create v<version> portico-<version>.apk --prerelease`) or adapt the upload step.

## First run

1. In Home Assistant, open the Portico panel and show the enrolment QR for a new phone.
2. Open Portico on the phone. Until it is enrolled it opens **Settings**. Tap **Scan the QR code** and allow the camera
   (or paste the enrolment text into the field instead).
3. The app redeems the one-time code (valid for 10 minutes, once), signs in, and opens **Home**.
4. Open Settings once more and read the status lines. Anything that stops a ring (notifications off, no full-screen permission,
   Do Not Disturb) is listed there with a button to fix it. Allow notifications when asked.

Step-by-step with what to expect: [Getting started](docs/getting-started/README.md).

## Using it

- **When the door rings**, the phone shows the incoming call. Tap **Answer** (the notification's button or the one on screen),
  allow the microphone the first time, talk, then **Hang up**. **Decline** stops the ring on this phone only.
- **Home** shows cameras and buttons. Tap a camera to switch between big and small, pinch to zoom, swipe between pages.
  A button for a lock or gate asks first.
- **The top bar** shows the connection: a green dot with `LAN` or `Tailscale` when Home Assistant is answering, amber while
  connecting, red when offline. Tap it for Settings. The menu has Settings, Status and About.
- **Settings** holds **Pause this phone**, a connection row and a video row for each network type (Wi-Fi, mobile, roaming), the
  buttons that open Android's notification and call pages, enrolment and the connection-log switch. **Status** holds the connection
  and push checks, the mobile-data meter and diagnostics sharing. Details: [Settings reference](docs/reference/settings.md).

More in the [user guide](docs/user-guide/README.md).

## Screens

- **Settings**: enrolment (scan or paste), Firebase values, status checks with fix buttons, pause, switches, data meter, journal.
- **Home**: title and connection status, optional page tabs, camera tiles, button rows. A call from the door is shown here too.
- **In-call**: status, timer, Mute, Hang up, and the caller's cameras and buttons while the call is live.
- **Scanner**: a full-screen camera view for the enrolment QR.
- **About** (from the Home menu): version, build type, and whether the tailnet client is built in.

## Build from source

```sh
git clone <this repository> && cd portico-app
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

You need a JDK 17, the Android SDK with platform 34 and build tools 34.0.0 (`ANDROID_HOME` set), and Gradle through the wrapper (8.9).
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. `:core` is pure Kotlin and its tests run on the JVM.

The **tailnet client is optional at build time**. `gocore/` is a separate Go module, compiled with `gomobile` into `app/libs/gocore.aar`
by `./gocore/bind.sh` (needs Go, the Android NDK and gomobile). Without the `.aar` everything builds and tests, but the APK has no
tailnet client and Settings says so. `gocore/go.mod` declares a recent Go version; check it before installing a toolchain. The signed release build
always runs `bind.sh` first. The full commands, versions and caveats: [Development](docs/development/README.md).

## Documentation

The documentation lives in [`docs/`](docs/README.md): getting started, a user guide, architecture, reference,
development notes and a history of decisions. The server side is documented in portico-ha.

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). A change that should ship as a new release
carries the one-line version bump in `app/build.gradle.kts`; a documentation-only change does not.

## Licence

MIT, see [LICENSE](LICENSE). Bundled Material Design Icons are Apache-2.0, see `third_party/mdi/`.
