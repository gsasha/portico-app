# Versions that build

The toolchain and library versions the project builds with (checked against `build.gradle.kts`, `app/build.gradle.kts`, `core/build.gradle.kts`, `gradle-wrapper.properties` and `gocore/go.mod` on 2026-11-18). How the app version is chosen is in [Version rule and releases](../development/versioning-and-releases.md).

| Component | Version |
| :--- | :--- |
| Gradle (wrapper, `-bin`, sha256-pinned) | 8.9 |
| JDK | Temurin 17.0.20.1 |
| Android Gradle plugin | 8.7.3 (the newest line that runs on Gradle 8.9) |
| Kotlin (`android` and `jvm` plugins) | 2.1.21 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| Build tools | 34.0.0 |
| JUnit (`:core`) | Jupiter 5.11.4 (BoM) |
| Firebase BoM (PR-E) | 34.19.0 (the first BoM that pins messaging 25.1.3) |
| `com.google.firebase:firebase-messaging` (PR-E) | 25.1.3; resolves `firebase-common` 22.2.1, `firebase-installations` 19.1.2, `play-services-cloud-messaging` 17.4.0 |
| App `versionName` / `versionCode` (PR-G) | `porticoVersionName` in `app/build.gradle.kts`; `versionCode` derived from it (see ["Release build"](../development/release-build.md)) |
| `com.google.zxing:core` (PR-C2, in `:core`) | 3.5.3 (Maven Central; the version the old intercom app pins by sha256 in `build.sh`); no transitive dependencies |
| OkHttp (`:app`, `mockwebserver` in tests) | 5.3.2 (see [Build and test](../development/build-and-test.md)) |
| kotlinx-serialization-json (`:app`, `:core`) | 1.8.1 |
| WebRTC (`io.github.webrtc-sdk:android`) | 137.7151.05 |
| AndroidX (`core-ktx` / `appcompat` / `security-crypto`) | 1.13.1 / 1.7.0 / 1.1.0 |
| Java source and target level | 17 (`:app` and `:core`) |
| Go (`gocore/go.mod`) | `go 1.26.6`; `bind.sh` sets `GOTOOLCHAIN=local`, so the installed Go must already be at least that |
| Key Go modules | `tailscale.com` v1.102.3, `golang.org/x/mobile` 20260821 snapshot, `golang.org/x/sys` v0.47.0 |
| Android NDK (only for `gocore.aar`) | `bind.sh` uses the newest NDK found under `$ANDROID_HOME/ndk/`; the maintainer's build used 26.3.11579264. `gomobile bind -androidapi 24` |

`firebase-messaging` 25.1.3 is a dependency since PR-E (through the BoM). It builds on this exact
set: compileSdk 34, no `android-35`; the runtime `kotlin-stdlib` is 2.2.21 (raised by OkHttp).
