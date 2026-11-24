# Enrolling a phone

Enrolment tells the app which Home Assistant to use and signs the phone in. You do it once per phone, by scanning a QR code shown by Home Assistant, or by pasting the text it contains.

Tip: the portico-ha [AI setup prompt](https://github.com/gsasha/portico-ha/blob/main/docs/getting-started/ai-setup-prompt.md) walks you through this whole page and the server side. Paste to an AI assistant: "Read https://raw.githubusercontent.com/gsasha/portico-ha/main/docs/getting-started/ai-setup-prompt.md and follow it to help me install Portico and its Android app."

## Steps

1. In Home Assistant, open the Portico panel (or the integration's Configure flow, depending on your portico-ha version) and show the enrolment QR for a new phone.
2. Open Portico. A phone that is not enrolled opens **Settings**, which has one enrolment field:
   - **Enrollment from Home Assistant**: the QR's JSON text. Tap **Scan the QR code** to scan it instead of pasting. Firebase values for push, if the server uses push, are part of that text (its `fcm` block).
3. Allow the camera when asked. If you refuse, the app explains how to allow it later, and pasting the text always works.
4. The app saves the settings and redeems the QR's **one-time code** (good for 10 minutes and one use). A code that is expired or already used is
   refused with "This QR has expired or was already used"; show a new QR.
5. Home opens. The top bar's dot turns green with `LAN` or `Tailscale` when Home Assistant answers.

What is stored: the Home Assistant address, a refresh token, the phone's name, the Firebase values, and a random installation id, in an encrypted preferences file.
Settings shows the Home Assistant host and whether a login is present; it never shows a token or a Tailscale key.

## Re-enrolling

Scan a new QR in Settings at any time. A phone that has not connected for 90 days must be enrolled again (Home Assistant expires an unused refresh token),
and a phone that Home Assistant has signed out says "signed out, enroll again". An older format-1 QR (with a long-lived token) is still accepted.

## Where this is described in detail

- [Enrolment fields and the settings store](../reference/enrolment-settings.md)
- [Enrolment by one-time code and the login behind it](../architecture/enrolment-and-login.md)
- [Scanning the enrolment QR](../architecture/qr-scanner.md)
- [Network policy](../reference/network-policy.md) (which addresses are accepted)

Next: [Your first call](first-call.md).
