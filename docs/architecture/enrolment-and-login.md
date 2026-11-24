# Enrolment by one-time code and the login behind it

How the app turns a format-2 QR (a one-time code, no long-lived token) into a Home Assistant login: scan, redeem, refresh-token login, and the old format-1 path that still works.

The server (portico-ha, DEV-544) no longer puts a 10-year Home Assistant token in the enrolment QR. The QR is **format 2**
(`"portico": 2`): it carries `code`, a one-time code good for 10 minutes and one redemption, and no `ha_token`. The contract
both sides follow is `contract/enroll_v1.json` in portico-ha, vendored byte for byte in `core/src/test/resources/enroll_v1.json`
(sha256 pinned in `EnrollFixture` and `EnrollContractFile`; to re-vendor, copy the file, change both pins together).

**Pasting instead of scanning.** The Portico panel's Phones tab has a **Copy enrolment text** button (portico-ha, D-20261120-e060). It copies exactly the text the QR encodes. Settings' enrolment paste field takes it through the same path as a scan (`SetupPlan.evaluate`), so a wall tablet that cannot scan can be enrolled by paste (typed, clipboard, or `adb shell input text`). The text is as secret as the QR: one use, valid 10 minutes.

What the app does (`core/.../auth/`, JVM-tested):

1. **Scan.** A format-2 QR is saved like any other (address, name, Firebase, tailnet key) but its `code` is parked, with a fresh
   `nonce`, in the encrypted settings store (`pending_*`). The phone's existing login is not touched. A QR with a format number the
   app does not know ("3") is refused with "Update the app" and saves nothing. A format-2 QR that also carries `ha_token`, or has no
   `code`, is refused.
2. **Redeem.** `POST {code, enroll_id, installation_id, nonce}` to `<ha_url>/api/portico/enroll/redeem`, over the same path as
   the WebSocket: `ha_url` directly first, then the tailnet if that cannot complete (`PathedHttp`). 200: the refresh token and the
   client id replace the old long-lived token in ONE commit and the code is dropped. 400: "This QR has expired or was already
   used."; the old login (if any) is kept. 429 (the server throttles a hot source: one request in 2 s gets through): wait `Retry-After`, send the same request again up to 3 times, then "Home Assistant asked this phone to wait a minute" (the code is kept; never "expired"). The body is always `Content-Type: application/json` (a 415 is this app's bug and is never retried) and no `X-Forwarded-For`-style header is sent. A Tailscale key in the QR lives 10 minutes in Portico's own mode, an hour in Approve mode. A lost answer or an unreachable HA: the SAME request
   (same nonce) is sent again, with growing pauses, until the record is 12 minutes old (10 minutes of code plus the 2-minute replay
   window). The nonce is stored, so a restarted process continues with the same one.
3. **Log in.** Before each WebSocket connect, if the in-memory access token has less than 5 minutes left (or none), the app
   POSTs `grant_type=refresh_token`, `refresh_token`, `client_id` (form-encoded) to `<ha_url>/auth/token`. The access token is
   kept in memory only; the WebSocket `auth` message carries it exactly as it carried the old token. `invalid_grant` (or
   `access_denied`) is **SIGNED_OUT**: the login is forgotten, nothing is retried, Home and Settings say "signed out, enroll
   again". `auth_invalid` on a connect refreshes and reconnects once; a second one is the old terminal "token refused".
4. **The old path.** A phone enrolled with a format-1 QR (a stored `ha_token`, no refresh token) never touches any of this: the
   connection config returns no login, the stored token goes into the `auth` message as before, and no request is made to
   `/auth/token`. A format-1 QR is still accepted (it works against a server that has not been updated). A re-enrolment by code
   replaces the old token once its code has been redeemed.

HA expires a refresh token that is not used for 90 days: a phone idle that long must be enrolled again.
