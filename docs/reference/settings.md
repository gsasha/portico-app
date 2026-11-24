# Settings

This page lists what the two Settings screens contain and where each item is explained. It was checked against `activity_setup.xml`, `strings.xml`, `SetupActivity` and `Settings.kt` in the source on 2026-11-18; it has not been checked on a running phone.

There are two screens in one activity, both opened from the menu at the top right of Home (the menu has **Settings**, **Status** and **About**). Tapping the connection status on Home opens one of them too. When the phone is not enrolled, Home is not shown and **Settings** opens by itself.

## Settings screen ("Portico settings")

| Item (as labelled) | What it does | Details |
| :--- | :--- | :--- |
| Pause this phone | Pause for 1 hour, 4 hours or until resumed; while paused it shows "Paused until ..." and Resume | [Pause](../user-guide/pause.md) |
| On Wi-Fi, On mobile, On roaming: connection row | Per network type: **Stay connected** (keep the connection alive in the background) or **Push notification** (rely on push) | [Stay connected](../user-guide/stay-connected.md) |
| On Wi-Fi, On mobile, On roaming: video row | Per network type, what camera tiles may use: **Big video**, **Small video**, **Stills** or **Off** | [Live video](../user-guide/live-video.md), [Roaming and the data meter](../user-guide/roaming-and-data-meter.md) |
| Ring control (Android) | Buttons that open Android's own pages: App settings, Notifications, Full-screen calls, Status notification, Over other apps | [Notifications and Settings status](../user-guide/notifications-and-settings-status.md) |
| House calls: Receive house calls in Portico; Tell the house when Do Not Disturb is on | Shown only when Home Assistant says house calls are on for this phone. Receiving is off by default; the second is on by default | [Talk and house calls](../user-guide/talk-and-house-calls.md) |
| Enrollment from Home Assistant: Scan the QR code, text field, Save | Enrol by scanning or pasting the QR's text. Firebase values arrive in the QR's `fcm` block | [Enrolling a phone](../getting-started/enrolling-a-phone.md), [fields](enrolment-settings.md) |
| Connection log: Send diagnostics to Home Assistant | A short report per connection, sent only to your own Home Assistant | [Connection log](connection-log.md) |

## Status screen ("Portico status")

| Item | What it does | Details |
| :--- | :--- | :--- |
| Home Assistant, and Push ring notification | Connection checks and push registration, with the per-path reason when a path fails; "Copy registration string" when Home Assistant cannot be reached | [Status and error texts](status-and-error-texts.md), [When the phone cannot reach HA](../user-guide/when-the-phone-cannot-reach-ha.md) |
| Status lines (notifications, Door calls channel, full-screen calls, Do Not Disturb, background restriction, battery) | Problems first, each pointing at the matching button on the Settings screen | [Notifications and Settings status](../user-guide/notifications-and-settings-status.md) |
| Data used by this app | Mobile, Wi-Fi, video, calls and other counters (today, this month, since reset) with a Reset button | [Roaming and the data meter](../user-guide/roaming-and-data-meter.md) |
| Send diagnostics | Hands the push and ring journal and the last connect timeline to Android's share sheet | [Push and ring journal](push-ring-journal.md) |

The tailnet dial target ("This phone") appears in the Home Assistant checks; if the APK was built without `gocore.aar`, the status says "The tailnet client is not built into this APK". See [Tailnet client](../architecture/tailnet-client.md).

## Changes found while checking

Older versions of this page, and other pages, described items that the source no longer has. They are gone from the layout: the single "Stay connected" and "Live video in this app" switches (replaced in 0.6.58 by the per-network rows; the old values only set the starting choice after an upgrade), the "Doorbell sound" row, the "Ring for door calls on this phone" switch (its status line string still exists in the source), the separate Firebase paste field, and "Refresh" in the Home menu.

Not verified: the exact visible wording on a phone (for example `home_menu_description` still says "Menu: refresh, settings, status, about"), and whether any of the removed items still exist in a build older than the current source.
