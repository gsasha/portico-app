# Repository map

This page lists what is in the repository, as it is today. (The original design-time sketch is kept in [Layout as the old README described it](layout-as-designed.md).)

```
app/                 Android application module (:app)
  src/main/java/com/meda/portico/
    AppGraph.kt        wires the app: storage, link, connector, handlers, services
    boot/ call/ connlog/ fcm/ home/ live/ pause/ ring/ roaming/ setup/ signaling/ stay/ store/ tailnet/
  src/main/res/        layouts, strings, drawables (icons, generated MDI vector drawables)
  src/test/            host tests that need Android resources or the merged manifest
  libs/                gocore.aar goes here when built (git-ignored)
core/                Pure Kotlin/JVM module (:core): decisions and protocol logic, no android.* imports
  src/main/kotlin/com/meda/portico/core/
    auth/ call/ callbar/ callwindow/ connlog/ content/ enroll/ fcm/ ha/ live/ net/ netmem/ netpolicy/
    pause/ push/ reg/ ring/ roaming/ scan/ stay/ tailnet/ traffic/
  src/test/            JUnit 5 tests and the vendored contract files (core/src/test/resources/)
gocore/              Go module for the tailnet client, built into app/libs/gocore.aar by bind.sh
  tailnet/             gomobile-bound surface: node, forwarder, interfaces, RTSP video
  tsnet/               embedded tsnet wrapper
  internal/rtsp/       RTSP client (stdlib only)
scripts/             release.sh, version-guard-test.sh, lib-version.sh, gitea.py, release-history
tools/               gen_mdi_drawables.py (Material Design Icons to VectorDrawables)
design/icon/         the launcher icon master (portico.svg) and its generator (make_icon.py)
third_party/mdi/     licence and notice for the bundled Material Design Icons
docs/                this documentation
```

Gradle: the root `settings.gradle.kts` includes `:app` and `:core` only. The Go module is built separately. The Gradle wrapper pins Gradle 8.9.

Not wired up: `gocore/go.mod` carries a commented-out dependency on portico-ha's transport module (SIP/RTP). `gocore` today contains the tailnet and RTSP code only, and there is no direct SIP dialling in the app.

Related: [Build and test](build-and-test.md), [gocore and APK size](gocore-and-apk.md), [Architecture overview](../architecture/overview.md).
