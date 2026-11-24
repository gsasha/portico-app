# gocore, the tailnet library and APK size

How to build `app/libs/gocore.aar` with gomobile, what builds without it, and why only arm64-v8a is packaged.

**Building it.** `app/libs/gocore.aar` is git-ignored: it is derived, about 15 MB, and needs Go
and the NDK. Build it with:

```sh
./gocore/bind.sh     # gomobile bind -target=android/arm64 -androidapi 24, ~15 s warm
```

The script defaults to `GOPROXY=off`. On a fresh module cache, run it once with
`GOPROXY=https://proxy.golang.org`, because gomobile's own `go mod tidy` needs x/mobile's
dependencies.

- **Without the `.aar`** every build and test still works. The APK has no tailnet client, and
  Settings says "The tailnet client is not built into this APK".
- **With it**, the debug APK grows from about 7.7 MB to about 37.9 MB. `libgojni.so` (30 MB,
  arm64-v8a only) is stored uncompressed.
- **The signed release build must run `gocore/bind.sh` first.**

**APK size and native ABIs.** `app/build.gradle.kts` sets `ndk { abiFilters += "arm64-v8a" }`, so
only arm64-v8a native code is packaged. Reason: release 0.4.0-alpha was 82,754,237 bytes against
36 MB for 0.2.2-alpha, because the WebRTC library (`io.github.webrtc-sdk:android`, added with
answering a door call) ships four ABIs (arm64-v8a 11.8 MB, armeabi-v7a 6.7 MB, x86 12.3 MB,
x86_64 15.8 MB uncompressed), while `gocore.aar` is arm64-only by design ("every phone this app
targets is arm64"). Measured on debug APKs with `gocore.aar` built in: 84,474,456 bytes without the
filter, 49,715,898 with it (`lib/` is `arm64-v8a` only: `libgojni.so`, `libjingle_peerconnection_so.so`,
`libdatastore_shared_counter.so`). armeabi-v7a is dropped too: the project targets 64-bit phones only (a project choice, not an Android requirement).
- **Consequence for other devices.** A phone whose ABIs include none of the packaged ones (a pure
  32-bit ARM or x86 device) is refused at install (`INSTALL_FAILED_NO_MATCHING_ABIS`; Android's ABI
  page documents the scan for `lib/<abi>/` but not this error). An x86_64 emulator that translates
  ARM may install it and run it. Before the filter such a device installed, but already showed
  "library failed" for the tailnet because `libgojni.so` was arm64-only.
- **Pinned by** `NativeAbiTest`, which lists `lib/` in the built debug APK and fails if any
  ABI other than arm64-v8a appears. To support another ABI, change the filter, `bind.sh`'s target
  and that test together.
- Gradle prints which kind of APK it is building ("Portico tailnet client: built in" or
  "NOT built in").

Go tests: `cd gocore && go test ./...`. This includes real tsnet nodes against a local
`testcontrol`, on loopback only; `-short` skips those.
