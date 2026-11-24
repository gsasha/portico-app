# Conventions

This page collects the working rules that are stated in the project's code and scripts, so a contributor does not have to rediscover them.

## Code

- **Logic in `:core`.** Put it there whenever it can live without Android; `:core` has no `android.*` imports and its tests run on the plain JVM.
- **Every new connect path calls `UrlPolicy.check`.** Android's platform policy permits cleartext for the whole app, so the restriction to private plain-http addresses is the app's own code. See [Network policy](../reference/network-policy.md).
- **The phone never names a Home Assistant service or an entity.** It sends action and camera ids from its own profile; a test fails the build if the app builds a `call_service`. See [Home](../user-guide/home-screen.md).
- **Diagnostics are redacted by construction.** Journal and connection-log lines carry fixed words, numbers and shortened ids, never addresses, tokens or names. See [Connection log](../reference/connection-log.md) and [Push and ring journal](../reference/push-ring-journal.md).
- **Decoration never breaks the call path.** Anything drawn or fetched on the ring and in-call screens runs inside a guard that swallows its failure. See [Live video](../user-guide/live-video.md).

## Contracts

The server's contract files are copied byte for byte into `core/src/test/resources/` and pinned by sha256. A contract change is always a version bump on both sides. Commands: [WebSocket contract](../reference/websocket-contract.md) and [Push decision core](../architecture/push-decision-core.md).

## Tests

Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` (details in [Build and test](build-and-test.md)). Some tests read source or manifest text (for example `MergedManifestTest`, `NativeAbiTest`) and pin facts such as the permission set and the packaged ABIs; changing those facts means changing the test with them.
One test, `SizesZoomResourcesTest`, reads the top-level `README.md` and requires that it does not repeat a retired banner phrase; keep the README free of text that a test says is stale.

## Versions and releases

One line, `porticoVersionName` in `app/build.gradle.kts`, is the whole version. A change PR that ships carries the bump; a documentation-only change does not. See [Version rule and releases](versioning-and-releases.md) and [Release build and signing](release-build.md).

## Secrets

Never commit a keystore, a `signing.properties`, a token, enrolment QR text or `google-services.json`. The signing properties file is named by the environment variable `PORTICO_SIGNING_PROPS` and lives outside the repository.

## Documentation

Pages in `docs/` start with a one-paragraph purpose, stay short, and link to each other with relative paths. When behaviour changes, update the page that describes it. Use documentation addresses (`192.168.2.x`, `example.com`) in examples, never a real home network, host name or person.
