package com.meda.portico.core.call

/**
 * This phone's own "Do not disturb" for house calls (HC-4, design 15.1), told to the server so the roster shows it ("Do not
 * disturb") and a caller is told BEFORE dialling instead of listening to ringback.
 *
 * What is reported ([reported]): `true` when this phone is not set to receive house calls in Portico (the local switch, default
 * OFF: then a caller is told "Do not disturb" rather than rung into a phone that will ignore the ring), or when Android's own
 * Do Not Disturb filter is on AND the person has let Portico read it (the second switch, default ON). Android's filter is read
 * only through that switch. A house call never rings through Android's DND (parity with the old app); a DOOR call still does,
 * and nothing here touches the door path.
 */
object HouseDnd {
    fun reported(receiveOn: Boolean, shareOsDnd: Boolean, osDndOn: Boolean): Boolean = !receiveOn || (shareOsDnd && osDndOn)
}

/**
 * Sends `portico/house/presence {dnd}` when, and only when, it should be sent:
 *
 * - NEVER while house calls are off for this phone ([evaluate] with `enabled = false` sends nothing: with the server's switch off
 *   the command answers `not_available`, and an app that sent it anyway would not be silent on the wire);
 * - only on an open, ready connection (the report is dropped with the connection, so [onDisconnected] forgets what was sent and the
 *   next connection reports again: the server's Do Not Disturb is "cleared when the connection drops");
 * - only a CHANGE (the same value is not sent twice on one connection);
 * - one in flight at a time; a reply that is an error (`not_subscribed`: the subscription is not set up yet) leaves it unsent, so the
 *   next [evaluate] tries again.
 *
 * [send] returns false when nothing was sent (no socket); otherwise [onDone] is called once with whether the server took it.
 * Every call comes from one thread (the link's); tests drive it directly.
 */
class HouseDndReporter(private val send: (dnd: Boolean, onDone: (Boolean) -> Unit) -> Boolean) {
    private var lastSent: Boolean? = null
    private var inFlight = false

    /** What the server last accepted on this connection, or null. */
    val reportedToServer: Boolean? get() = lastSent

    private var wantEnabled = false
    private var wantReady = false
    private var want = false

    fun evaluate(enabled: Boolean, connectionReady: Boolean, desired: Boolean) {
        synchronized(this) {
            wantEnabled = enabled
            wantReady = connectionReady
            want = desired
            if (!enabled || !connectionReady || inFlight || lastSent == desired) return
            inFlight = true
        }
        val sent = send(desired) { ok ->
            val again = synchronized(this) {
                inFlight = false
                if (ok) lastSent = desired
                // the answer to what was wanted a moment ago: if the wish changed while it was in flight, say the new one now
                ok && lastSent != want
            }
            if (again) evaluate(wantEnabled, wantReady, want)
        }
        if (!sent) synchronized(this) { inFlight = false }
    }

    /** The socket closed: the server dropped the report with it. */
    fun onDisconnected() {
        synchronized(this) {
            lastSent = null
            inFlight = false
        }
    }
}
