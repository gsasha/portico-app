package com.meda.portico.core.stay

import com.meda.portico.core.ha.NetKind

/**
 * "Stay connected" (Settings switch, default ON; the owner 2026-10-15, NS-1..NS-3): a quiet foreground
 * service that keeps Android from freezing the process, so the Home Assistant socket survives the
 * app being in the background.
 *
 * THE ANDROID CONSTRAINT SHAPES THE DESIGN: since Android 12 an app cannot START a foreground
 * service from the background (except for a high-priority FCM message, BOOT_COMPLETED /
 * MY_PACKAGE_REPLACED, the app being visible, a tap on its notification ...). A network callback is
 * none of those. So the service is started from an allowed context and, while the switch is on,
 * STAYS started; the network only decides what the service does with the socket
 * ([Connection]), never whether the service exists.
 */
object StayConnectedPolicy {

    /** What the service should be doing with the socket. */
    enum class Connection {
        /**
         * Hold the connection: on Wi-Fi or Ethernet, and on mobile data or a VPN (carried by the tailnet), except that in battery
         * saver a connection that is on the tailnet is let go. The existing direct-first path choice, tailnet fallback and
         * PathRechecker stay in charge of the route.
         */
        HOLD,

        /** No network, or battery saver with the tailnet (or mobile data): let the socket go, so the modem rests and the tailnet node sleeps. A push still wakes it. */
        RELEASE,

        /** Mobile data, but a call is live, a door ring is up, a screen of ours is visible, or a push woke the socket a moment ago: leave the socket alone, look again when that ends. */
        DEFER,

        /** The service is not wanted (switch off, not enrolled, paused, or Android refused it): the app behaves exactly as before this feature. */
        IDLE,
    }

    /** What to do about the service itself. */
    enum class Service { START, KEEP, STOP, NONE }

    /** What the notification says. The Android layer maps each to a string. */
    enum class Text { CONNECTED, CONNECTING, WAITING_FOR_WIFI, PUSH_ONLY }

    data class Inputs(
        /** The connection service is wanted: at least one network class (Wi-Fi, mobile, roaming) is set to "Stay connected" (0.6.58). */
        val switchOn: Boolean,
        val enrolled: Boolean,
        val paused: Boolean,
        val net: NetKind,
        val callLive: Boolean,
        val uiVisible: Boolean,
        /** A push woke the socket within [GRACE_MS]. */
        val inGrace: Boolean,
        val serviceRunning: Boolean,
        /** `startForeground` failed for a permission-class reason ([isPermanentRefusal]) in this process: do not try again until the switch is toggled, a screen opens, boot or update, or the process restarts. A transient "not now" never sets this. */
        val serviceRefused: Boolean,
        /** The socket is authenticated right now (only the notification text reads it). */
        val linkConnected: Boolean = false,
        /** Android's battery saver is on. */
        val powerSave: Boolean = false,
        /** The last connection used the tailnet (kept after the socket is released, so releasing it does not read as "not on the tailnet" and reconnect; cleared by a network change). */
        val onTailnet: Boolean = false,
        /**
         * 0.6.58: the CURRENT network class is set to "Push notification". The service (wanted because another class stays connected) keeps
         * running so a later return to a "Stay connected" network needs no allowed context, but the socket is let go here, like a mobile
         * connection in battery saver: DEFER while busy, else RELEASE. A push still wakes it.
         */
        val pushHere: Boolean = false,
        /**
         * 0.6.92: a door ring is up (and not older than [StayConnected.RING_CAP_MS]). A ring that came by the socket classes the push that follows as
         * stale, so no push ever opened a grace: without this the socket was released a moment after the ring arrived (2026-11-21 10:31) and
         * Answer waited for a new one. Busy like a live call.
         */
        val ringLive: Boolean = false,
    )

    data class Decision(val connection: Connection, val service: Service, val text: Text)

    /** After a push woke the socket on mobile data, how long it is left alone before being released again (the old app's 3 minutes). */
    const val GRACE_MS = 180_000L

    /**
     * Transport flags -> the kind this feature uses. Unlike [NetKind.of] (which lets a VPN hide the
     * transport under it, because for ROUTING the VPN decides where `ha_url` goes), here a Wi-Fi or
     * Ethernet underneath wins over a VPN: this decision is about the radio, and a phone on Wi-Fi
     * behind a VPN is on Wi-Fi. A VPN with only mobile data (or nothing) underneath is [NetKind.VPN].
     */
    fun kindOf(wifi: Boolean, ethernet: Boolean, cellular: Boolean, vpn: Boolean): NetKind = when {
        wifi -> NetKind.WIFI
        ethernet -> NetKind.ETHERNET
        cellular -> NetKind.CELLULAR
        vpn -> NetKind.VPN
        else -> NetKind.OTHER
    }

    /** Exception class names that mean the service can never start (a missing prerequisite permission or a wrong type), not "not now". */
    private val PERMANENT_NAMES = setOf("MissingForegroundServiceTypeException", "InvalidForegroundServiceTypeException")

    /**
     * Was a `startForeground` failure permanent (permission-class) or a transient "not now"?
     * `ForegroundServiceStartNotAllowedException` (Android 12+, thrown inside the service when the
     * start is not allowed in this context) is transient, and so is ANY failure of a null-intent
     * START_STICKY restart, which Android makes from the background: neither may disable the
     * service for the process, the next allowed context retries. Only a [SecurityException] or a
     * foreground-service type exception on a real start is permanent.
     */
    fun isPermanentRefusal(e: Throwable, restartedWithoutIntent: Boolean): Boolean {
        if (restartedWithoutIntent) return false
        return e is SecurityException || e.javaClass.simpleName in PERMANENT_NAMES
    }

    fun decide(i: Inputs): Decision {
        val wanted = i.switchOn && // 0.6.58: switchOn = some network class stays connected
 i.enrolled && !i.paused && !i.serviceRefused
        if (!wanted) {
            return Decision(Connection.IDLE, if (i.serviceRunning) Service.STOP else Service.NONE, Text.CONNECTING)
        }
        val service = if (i.serviceRunning) Service.KEEP else Service.START
        if (i.pushHere) {
            val busyHere = i.callLive || i.ringLive || i.uiVisible || i.inGrace
            return Decision(if (busyHere) Connection.DEFER else Connection.RELEASE, service, Text.PUSH_ONLY)
        }
        // the owner 2026-10-24: hold the connection on the home LAN and on the tailnet (mobile data included); in battery saver
        // let the TAILNET one drop (its node and relay keepalives are the costly part), and keep holding the LAN one.
        val dropTailnet = i.powerSave && i.onTailnet
        if (!dropTailnet && (i.net.canReachHome || (!i.powerSave && i.net != NetKind.NONE))) {
            return Decision(Connection.HOLD, service, if (i.linkConnected) Text.CONNECTED else Text.CONNECTING)
        }
        val busy = i.callLive || i.ringLive || i.uiVisible || i.inGrace
        return Decision(if (busy) Connection.DEFER else Connection.RELEASE, service, if (busy && i.linkConnected) Text.CONNECTED else Text.WAITING_FOR_WIFI)
    }
}
