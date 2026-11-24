# Push ring notification: Firebase messaging

How Firebase is configured at runtime from four stored values, what the manifest removes and turns on, and how a data message reaches the decision core.

Firebase is configured at **runtime** from the four values Settings stores (P2 plan §3.1, sender spec
§5.3). There is no `google-services.json`, no `google-services` Gradle plugin, and no analytics.

- `fcm/FirebaseBoot` initialises the default `FirebaseApp` once per process (from
  `PorticoApp.onCreate`, the messaging service's `onCreate`, and after a Settings save). A Firebase
  config changed while the app runs is applied at the next start: Settings says "restart Portico".
- The manifest removes `FirebaseInitProvider`, turns auto-init off, and turns on FID
  registration (`firebase_messaging_installation_id_enabled`). In 25.1.3 `register()` fails
  without that flag and `getToken()` fails with it. On Play services older than 26.12 the SDK
  itself falls back to a legacy token and still calls `onRegistered`; `RegValues` sends that as
  kind `token`.
- `fcm/PorticoMessagingService` feeds `onRegistered` to the registration machine (it registers
  on every socket connect) and runs each data message through `Decision.receive` on the shared
  `store/PrefsCallStore` and the persisted devices cache. The outcome goes to an
  `OutcomeHandler`. PR-E shipped a handler that only logs; PR-F replaced it with the ring UI.
- The ring-on-this-phone setting (no longer shown in Settings; see the [Settings reference](settings.md)) is applied after the store write
  (`core/push/RingGate`): off means no door-call alert, but every call is still recorded.

`MergedManifestTest` (part of `:app:testDebugUnitTest`, which depends on `processDebugManifest`)
checks the merged manifest: the provider is gone, the service is present and not exported, the
meta-data flags, the boot receiver, and that nothing else is exported except the launcher and
two system-permission-guarded receivers (FCM's `FirebaseInstanceIdReceiver`, androidx
`ProfileInstallReceiver`).

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew --offline :core:test :app:testDebugUnitTest :app:assembleDebug
```

Only a real phone can prove FID delivery, Doze wake, `onRegistered` on boot and update, and FID
rotation. That is P4.
