# Requirements and install

This page lists what Portico app needs and how to put the APK on a phone.

## What you need

| Item | Detail |
| :--- | :--- |
| Phone | Android 8.0 or newer (`minSdk 26`; the app targets Android 14, API 34). Only the 64-bit ARM libraries (`arm64-v8a`) are packaged, so a 32-bit ARM or x86 phone is refused at install (`INSTALL_FAILED_NO_MATCHING_ABIS`). See [gocore and APK size](../development/gocore-and-apk.md). |
| Home Assistant | A Home Assistant with the **portico-ha** integration installed, speaking **contract 3**. An app of the 0.5 line or later needs a contract-3 server; the 0.4 line speaks the older contract 2 and is not compatible with a contract-3 server. See [Contract 3](../reference/contract-3.md). |
| Reachability | Either a direct address for Home Assistant that the phone can reach, or a tailnet for use away from home. Plain `http://` is accepted only for private addresses; see [Network policy](../reference/network-policy.md). |
| Firebase (optional) | A Firebase project; the server puts four of its values in the enrolment QR (an `fcm` block) and the app keeps them. Push is a second way to wake the phone; the WebSocket alone rings while the app process is alive. See [Push ring notification: Firebase](../reference/push-fcm.md). |
| Tailnet (optional) | The integration can put a single-use Tailscale key in the enrolment QR so the phone joins your tailnet by itself. See [Tailnet client](../architecture/tailnet-client.md). |

## Install the APK

1. Download `portico-<version>.apk` from the repository's **Releases** page. Releases are prereleases named `v<version>` (for example `v0.6.84-alpha`) with one APK asset.
2. Open the file on the phone and allow your browser or file manager to install unknown apps when asked. Or use `adb install -r portico-<version>.apk` from a computer.
3. Open Portico. Until it is enrolled it opens Settings.

## Updates and signing

- A new version installs over the old one and keeps settings and enrolment, as long as both are signed with the same key and the new
  `versionCode` is higher. The release key's certificate SHA-256 is `5f8cb715448565fd63d907a0fd4780726d8150f708b49178afbb86f0811b3451`
  (the value `scripts/release.sh` checks; a public fingerprint). Verify an APK with `apksigner verify --print-certs portico-<version>.apk`.
- A debug build compiled from source is signed with a different (debug) key. It does not install over a release build, and vice versa;
  uninstall one before installing the other and enrol again.
- If the release key were ever lost, every phone would have to uninstall and re-enrol, see [Release build and signing](../development/release-build.md).

Releases are published as prereleases by `scripts/release.sh`, on the Releases page of the repository that hosts this code.

Next: [Enrolling a phone](enrolling-a-phone.md).
