# Release build and signing

How a release APK is signed (a keystore kept outside the repository, named by one environment variable), what the release settings are, and what is checked.

**Run `./gocore/bind.sh` first** (DEV-557). Without `app/libs/gocore.aar`, the release APK has
no tailnet client.

The release key is a dedicated PKCS12 keystore kept **outside** the repo (P2 plan §7 Q1). Gradle
finds it through one environment variable, `PORTICO_SIGNING_PROPS`, the path of a Java properties
file, also outside the repo:

```properties
# PKCS12. A relative storeFile is relative to this properties file.
storeFile=portico-release.p12
storePassword=...
keyAlias=...
keyPassword=...
```

```sh
PORTICO_SIGNING_PROPS=/path/outside/the/repo/signing.properties ./gradlew :app:assembleRelease
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

- Without the variable, or with a missing file, a missing key or a missing keystore,
  `assembleDebug` and the tests work as before, and `assembleRelease` / `bundleRelease` fail at
  once in `checkReleaseSigning` with one message naming what is missing. A release is never
  signed with the debug key and never left unsigned.
- No password is printed, logged or put in `BuildConfig` (the app generates no `BuildConfig`).
  `.gitignore` keeps `*.jks`, `*.keystore`, `*.p12` and `signing.properties` out of the repo.
- Signing schemes v2 and v3 (minSdk 26 needs no v1).
- The first release build on a fresh cache must run online once: `lintVitalAnalyzeRelease` fetches
  `com.android.tools.lint:lint-gradle`. Later release builds work with `--offline`.
- **An APK signed with another key does not install over this one.** Keep the keystore and its
  passwords backed up; losing them means uninstalling Portico (and re-enrolling) on every phone.

**Release settings:** `isDebuggable = false`, `allowBackup="false"` in the manifest, no
`testOnly`. **No R8 (`isMinifyEnabled = false`, `isShrinkResources = false`) for the first
release:** every unit test runs on unminified classes, nothing on this host can run a minified
APK, and runtime-configured Firebase (reflection on its component registrars), OkHttp and
kotlinx-serialization would each need keep rules that only a phone can prove. Revisit after P4.
