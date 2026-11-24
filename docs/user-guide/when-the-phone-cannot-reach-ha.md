# When the phone cannot reach Home Assistant

What Settings says when the phone cannot reach Home Assistant to register for push, and the registration string you can paste into Home Assistant by hand.

The phone registers its Firebase id with Home Assistant over the WebSocket
(`portico/push/register`), so it has to reach HA at least once. When it cannot, Settings says so
under "Home Assistant and the push ring notification":

- **Shown only when** Home Assistant is configured, the socket is trying (`CONNECTING` /
  `WAITING_TO_RETRY`) and has not been `CONNECTED` for 15 seconds, and Firebase has already given
  this phone its registration value. When the phone can reach HA, nothing extra shows. If the
  value is not there yet, the usual "Waiting for Firebase" line is all there is.
- If HA is only reachable from your home network, connect to it (for example on your Wi-Fi) and
  Portico registers by itself.
- Otherwise tap **Copy registration string** and paste it in Home Assistant:
  **Portico > Push ring notification > Configure > Add a phone's registration.** HA then registers the phone
  just as the phone's own register would.
- The string is one opaque base64url token. It holds this phone's enrollment id, installation id,
  Firebase id or token, package, Firebase project and app version. It holds no HA token, no
  Tailscale key and no URL. The app never shows it on screen and never logs it. The format is
  shared with portico-ha (golden vector in `RegistrationStringTest`).
- It needs the `enroll_id` that newer HA versions put in the enrollment QR. After enrolling from
  an older QR, Settings asks you to scan the QR again.

The decision is `:core`'s `OfflineRegistration` (JVM-tested). The clipboard and the 15-second
timing on a real phone are not tested here.
