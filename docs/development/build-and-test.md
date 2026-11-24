# Build and test

How to build the debug APK and run the Kotlin and Go tests, with the exact commands used by the project.

The Android build needs a JDK 17, the Android SDK (platform 34 and build-tools 34.0.0) and, optionally, Go and the NDK for `gocore.aar`.
No emulator is needed for the tests. Point the build at your own copies, for example:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$PATH
```

Then, from the repo root:

```sh
./gradlew :core:test :app:assembleDebug            # first run: online, fills ~/.gradle
./gradlew --offline :core:test :app:assembleDebug  # later runs, once everything is cached
```

The wrapper pins Gradle 8.9. A system Gradle 8.9 works the same way.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`, signed with the SDK's
auto-generated debug key. A release APK needs Portico's release key, which is never in this repo:
see ["Release build"](release-build.md) below.

`:core` is pure Kotlin/JVM with no `android.*` imports, so its JUnit 5 tests run on the plain
JVM. Put logic there whenever it can live without Android.

The signaling client (`app/.../signaling/`, OkHttp, no `android.*` imports) has JVM tests too.
They run against OkHttp's `MockWebServer` on loopback, never a real Home Assistant:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

Added by PR-D: `com.squareup.okhttp3:okhttp` 5.3.2 (+ `mockwebserver` 5.3.2 for tests) and
`org.jetbrains.kotlinx:kotlinx-serialization-json` 1.8.1 (tree API only, no compiler plugin).
OkHttp 5.4.0 and later need compileSdk 36+, so 5.3.2 is the newest that builds on compileSdk 34.
OkHttp 5.3.2 raises the app's `kotlin-stdlib` to 2.2.21 at runtime and brings in
`androidx.startup`.

The Go module's tests need a Go toolchain of at least the version in `gocore/go.mod` (`go 1.26.6`). The maintainer runs them in Docker
with a `golang:1.26` image (any image whose Go is 1.26.6 or newer works; not re-run when this page was updated); with Go installed
locally, `cd gocore && go test ./...` does the same:

```sh
docker run --rm -v portico-tsnet-gomodcache:/go/pkg/mod \
  -v portico-tsnet-gobuildcache:/root/.cache/go-build \
  -v "$PWD/gocore":/src:ro -w /src -e GOPROXY=off -e GOFLAGS=-buildvcs=false \
  golang:1.26-alpine go test ./...
```

Don't add `--network none`. With it, `TestLongLivedDialSurvives` fails after 60 s
(`tsnet.Up: context deadline exceeded`), even though the test only talks to an in-process control
server. `:app` depends on `gocore` only when `app/libs/gocore.aar` exists (see [gocore and APK size](gocore-and-apk.md)).
