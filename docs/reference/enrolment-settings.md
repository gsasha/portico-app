# Enrolment fields and the settings store

The enrolment field in Settings (the QR's JSON, which may carry the Firebase values), what is kept from it, and where settings are stored.

The app opens on **Settings** when it is not enrolled. The screen has one enrolment field. Source note: the code still accepts a pasted `google-services.json` in `SetupPlan.evaluate`, but the screen passes it an empty string, so the Firebase values come from the enrolment's `fcm` block only:

- **Enrollment.** The JSON text that Home Assistant's Portico > Configure flow puts in its QR:
  `ha_url`, `ha_token`, `phone_name`, and an optional `tailscale_authkey`. It may also carry an
  optional `fcm` block and a `"portico": 1` format marker. Since PR-C2 the QR can be scanned
  instead (below); the paste field stays as the fallback.
- **Firebase (optional), from the enrolment's `fcm` block or a `google-services.json`.** Only four values are kept:
  `project_id`, `project_number`, and the `mobilesdk_app_id` and `api_key` of the
  `com.meda.portico` client (the applicationId; the Kotlin code namespace stays `com.meda.portico`).

The parsing and validation live in `:core` (`core/.../enroll/`) and are JVM-tested. Settings go in
one `EncryptedSharedPreferences` file (`androidx.security:security-crypto` 1.1.0). It holds the
HA URL and token, the phone name, the Firebase values, and a random `installation_id` that is minted once.

The Tailscale part of the enrollment (`tailscale_authkey`, and the optional `ha_dial`) is used by
the tailnet client: see ["Tailnet client"](../architecture/tailnet-client.md) below. Settings shows the HA host and whether a token is
present. It never shows the token or the Tailscale key.
