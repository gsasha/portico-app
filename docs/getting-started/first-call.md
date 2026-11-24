# Your first call

This page shows what happens when the door rings and what to check if it does not ring on your phone.

## When the door rings

1. Home Assistant tells the app over its WebSocket (and, if you set up push, through Firebase). The phone posts an incoming-call notification and, if Android allows,
   a full-screen call over the lock screen. A call from the station opens the **Home** screen, which shows Decline and Answer (green) while it rings.
2. Press **Answer**, in the notification or on screen. Only your press answers; a ring, a re-delivered notification or a rotation never opens the microphone.
3. The first time, Android asks for the microphone. Allow it.
4. The call screen shows the status and a timer, with **Mute** and **Hang up**. A call ends when you hang up, the other side hangs up, or after 300 s.
5. **Decline** stops the ring on this phone only; the call keeps ringing elsewhere.

Details: [Answering a door call](../user-guide/answering-a-door-call.md) and [the ring and in-call screens](../user-guide/ring-and-in-call-screens.md).

## If nothing rings

Open Settings. It lists problems first, each with a fix button:

- notifications refused or switched off;
- the "Door calls" channel lowered or switched off;
- no permission for full-screen calls (Android 14 and later);
- Do Not Disturb, background restriction, battery optimisation;
- the connection to Home Assistant, the push registration, and (when it is off) the local ring switch (the current Settings screen has no such switch; see the [Settings reference](../reference/settings.md));
- paused: a paused phone drops every ring, see [Pause](../user-guide/pause.md).

If the phone cannot reach Home Assistant at all, see [When the phone cannot reach Home Assistant](../user-guide/when-the-phone-cannot-reach-ha.md)
and [Status and error texts](../reference/status-and-error-texts.md).

To keep the connection alive in the background, leave **Stay connected** on: [Stay connected](../user-guide/stay-connected.md).

> Not proven on every phone: the full-screen call over the keyguard, notification behaviour in Doze, and call audio routing depend on the Android version and manufacturer.
