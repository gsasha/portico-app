# Contributing to Portico app

Thanks for helping. This is a short guide; the longer one is [docs/development/](docs/development/README.md).

## Before you start

- The app talks to Home Assistant through the **portico-ha** integration and speaks one wire contract (contract 3). A change that alters
  what the app sends or expects must be made on both sides; see [WebSocket contract](docs/reference/websocket-contract.md) for how the
  server's contract files are vendored and pinned here.
- Put logic in `:core` (pure Kotlin, no `android.*` imports) whenever it can live without Android. It is tested on the plain JVM.

## Build and test

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

Go code in `gocore/` has its own tests: `cd gocore && go test ./...` (`-short` skips the tests that start real tsnet nodes).
More in [Build and test](docs/development/build-and-test.md).

## Pull requests

- Branch from `main`, keep the change focused, and add or update a test with it.
- Describe what you changed, how you tested it, and anything you could not test on a real phone. Many behaviours (the lock-screen call UI,
  Doze, the camera, audio routing) can only be proven on a device.
- **Versions:** a change that should ship as a release edits the one `porticoVersionName` line in `app/build.gradle.kts` in the same PR
  (see [Version rule and releases](docs/development/versioning-and-releases.md)). A change that should not ship, including documentation-only
  changes, leaves it alone.
- **Never commit secrets:** keystores, `signing.properties`, tokens, enrolment QR text or `google-services.json`. `.gitignore` already
  excludes keystores and `signing.properties`.
- Documentation: update the page in `docs/` that describes the behaviour you changed. Each page starts with a one-paragraph purpose.

## Security

Please report vulnerabilities privately through GitHub's private vulnerability reporting (the repository's Security tab, then Report a vulnerability), not in a public issue.

Useful in a report: the app version (Settings, About), what you did, what you expected, and what happened. Never include real enrolment
QR text, tokens or keys; redact them first.
