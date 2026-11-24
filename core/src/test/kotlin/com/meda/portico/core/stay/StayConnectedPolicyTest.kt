package com.meda.portico.core.stay

import com.meda.portico.core.ha.NetKind
import com.meda.portico.core.stay.StayConnectedPolicy.Connection
import com.meda.portico.core.stay.StayConnectedPolicy.Inputs
import com.meda.portico.core.stay.StayConnectedPolicy.Service
import com.meda.portico.core.stay.StayConnectedPolicy.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** One test per row of the policy table in docs/user-guide/stay-connected.md. */
class StayConnectedPolicyTest {
    private fun inputs(
        switchOn: Boolean = true, enrolled: Boolean = true, paused: Boolean = false, net: NetKind = NetKind.WIFI,
        callLive: Boolean = false, uiVisible: Boolean = false, inGrace: Boolean = false,
        running: Boolean = true, refused: Boolean = false, connected: Boolean = true,
        powerSave: Boolean = false, onTailnet: Boolean = false,
    ) = Inputs(switchOn, enrolled, paused, net, callLive, uiVisible, inGrace, running, refused, connected, powerSave, onTailnet)

    private fun d(i: Inputs) = StayConnectedPolicy.decide(i)

    @Test fun `wifi - hold, service kept, says connected`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.HOLD, Service.KEEP, Text.CONNECTED), d(inputs()))

    @Test fun `ethernet - hold`() = assertEquals(Connection.HOLD, d(inputs(net = NetKind.ETHERNET)).connection)

    @Test fun `wifi but the socket is not up yet - hold, says connecting`() =
        assertEquals(Text.CONNECTING, d(inputs(connected = false)).text)

    @Test fun `wifi and the service is not running - start it (from an allowed context)`() =
        assertEquals(Service.START, d(inputs(running = false)).service)

    @Test fun `mobile data in battery saver - release, service kept, says waiting for wifi`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.RELEASE, Service.KEEP, Text.WAITING_FOR_WIFI), d(inputs(net = NetKind.CELLULAR, powerSave = true, connected = false)))

    @Test fun `vpn with no wifi under it, in battery saver - release`() = assertEquals(Connection.RELEASE, d(inputs(net = NetKind.VPN, powerSave = true)).connection)

    @Test fun `no network - release`() = assertEquals(Connection.RELEASE, d(inputs(net = NetKind.NONE)).connection)

    @Test fun `other transport, in battery saver - release`() = assertEquals(Connection.RELEASE, d(inputs(net = NetKind.OTHER, powerSave = true)).connection)

    @Test fun `mobile data during a call - defer, the call is never cut`() =
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.CELLULAR, powerSave = true, callLive = true)).connection)

    @Test fun `mobile data with a screen of ours visible - defer`() =
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.CELLULAR, powerSave = true, uiVisible = true)).connection)

    @Test fun `mobile data just after a push woke the socket - defer`() =
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.CELLULAR, powerSave = true, inGrace = true)).connection)

    @Test fun `mobile data, deferred, socket up - still says connected, not waiting`() =
        assertEquals(Text.CONNECTED, d(inputs(net = NetKind.CELLULAR, powerSave = true, uiVisible = true)).text)

    @Test fun `wifi during a call - hold`() = assertEquals(Connection.HOLD, d(inputs(callLive = true)).connection)

    @Test fun `switch off - idle, a running service is stopped`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.IDLE, Service.STOP, Text.CONNECTING), d(inputs(switchOn = false)))

    @Test fun `switch off on mobile data - idle, the socket is left as before this feature`() =
        assertEquals(Connection.IDLE, d(inputs(switchOn = false, net = NetKind.CELLULAR, powerSave = true)).connection)

    @Test fun `switch off and no service - nothing to do`() = assertEquals(Service.NONE, d(inputs(switchOn = false, running = false)).service)

    @Test fun `not enrolled - idle and stopped`() = assertEquals(Service.STOP, d(inputs(enrolled = false)).service)

    @Test fun `paused on wifi - idle, service stopped, quiet`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.IDLE, Service.STOP, Text.CONNECTING), d(inputs(paused = true)))

    @Test fun `paused beats everything, a live call included`() =
        assertEquals(Connection.IDLE, d(inputs(paused = true, callLive = true, net = NetKind.CELLULAR, powerSave = true)).connection)

    @Test fun `android refused the service - idle, not started again`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.IDLE, Service.NONE, Text.CONNECTING), d(inputs(refused = true, running = false)))

    // ---- transport classification ------------------------------------------------------------

    @Test fun `kind - wifi under a vpn is wifi (the radio decides, not the vpn)`() =
        assertEquals(NetKind.WIFI, StayConnectedPolicy.kindOf(wifi = true, ethernet = false, cellular = false, vpn = true))

    @Test fun `kind - ethernet`() = assertEquals(NetKind.ETHERNET, StayConnectedPolicy.kindOf(false, true, false, false))

    @Test fun `kind - cellular under a vpn is cellular`() = assertEquals(NetKind.CELLULAR, StayConnectedPolicy.kindOf(false, false, true, true))

    @Test fun `kind - a vpn alone is vpn`() = assertEquals(NetKind.VPN, StayConnectedPolicy.kindOf(false, false, false, true))

    @Test fun `kind - nothing known is other`() = assertEquals(NetKind.OTHER, StayConnectedPolicy.kindOf(false, false, false, false))

    // Stand-ins with the framework exceptions' simple names (the classes are Android-only, API 31+/34+).
    private class ForegroundServiceStartNotAllowedException : IllegalStateException("not allowed now")
    private class MissingForegroundServiceTypeException : RuntimeException("no type")

    @Test fun `start not allowed now - transient, not remembered`() =
        assertEquals(false, StayConnectedPolicy.isPermanentRefusal(ForegroundServiceStartNotAllowedException(), restartedWithoutIntent = false))

    @Test fun `missing prerequisite permission - permanent`() =
        assertEquals(true, StayConnectedPolicy.isPermanentRefusal(SecurityException("CHANGE_NETWORK_STATE"), restartedWithoutIntent = false))

    @Test fun `missing service type - permanent`() =
        assertEquals(true, StayConnectedPolicy.isPermanentRefusal(MissingForegroundServiceTypeException(), restartedWithoutIntent = false))

    @Test fun `any failure of the null-intent sticky restart - transient`() {
        assertEquals(false, StayConnectedPolicy.isPermanentRefusal(SecurityException("x"), restartedWithoutIntent = true))
        assertEquals(false, StayConnectedPolicy.isPermanentRefusal(ForegroundServiceStartNotAllowedException(), restartedWithoutIntent = true))
    }

    @Test fun `an unknown exception is transient, never a silent disable`() =
        assertEquals(false, StayConnectedPolicy.isPermanentRefusal(RuntimeException("?"), restartedWithoutIntent = false))

    // ---- the owner 2026-10-24: hold on the home LAN and on the tailnet; battery saver lets the tailnet go ----

    @Test fun `mobile data holds - the tailnet carries it`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.HOLD, Service.KEEP, Text.CONNECTED), d(inputs(net = NetKind.CELLULAR)))

    @Test fun `vpn and other transports hold too, no network does not`() {
        assertEquals(Connection.HOLD, d(inputs(net = NetKind.VPN)).connection)
        assertEquals(Connection.HOLD, d(inputs(net = NetKind.OTHER)).connection)
        assertEquals(Connection.RELEASE, d(inputs(net = NetKind.NONE)).connection)
    }

    @Test fun `battery saver on the home LAN still holds`() =
        assertEquals(Connection.HOLD, d(inputs(net = NetKind.WIFI, powerSave = true)).connection)

    @Test fun `battery saver on wifi over the tailnet lets it go`() =
        assertEquals(Connection.RELEASE, d(inputs(net = NetKind.WIFI, powerSave = true, onTailnet = true)).connection)

    @Test fun `tailnet without battery saver holds on wifi and on mobile data`() {
        assertEquals(Connection.HOLD, d(inputs(net = NetKind.WIFI, onTailnet = true)).connection)
        assertEquals(Connection.HOLD, d(inputs(net = NetKind.CELLULAR, onTailnet = true)).connection)
    }

    @Test fun `battery saver on mobile data lets go`() =
        assertEquals(Connection.RELEASE, d(inputs(net = NetKind.CELLULAR, powerSave = true)).connection)

    @Test fun `battery saver never cuts a call or hides a visible screen`() {
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.WIFI, powerSave = true, onTailnet = true, callLive = true)).connection)
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.CELLULAR, powerSave = true, onTailnet = true, uiVisible = true)).connection)
    }

    // 0.6.58: per network class. switchOn = some class stays connected; pushHere = the current class is "Push notification".
    @Test fun `push on this network - release, the service kept for the other networks, says push only`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.RELEASE, Service.KEEP, Text.PUSH_ONLY), d(inputs().copy(pushHere = true)))

    @Test fun `push on this network but a screen is up or a call is live - defer, never cut the socket under the user`() {
        assertEquals(Connection.DEFER, d(inputs(uiVisible = true).copy(pushHere = true)).connection)
        assertEquals(Connection.DEFER, d(inputs(callLive = true).copy(pushHere = true)).connection)
        assertEquals(Connection.DEFER, d(inputs(inGrace = true).copy(pushHere = true)).connection)
    }

    @Test fun `push on this network and the service not running - it still starts, so a return to a stay network needs no allowed context`() =
        assertEquals(Service.START, d(inputs(running = false).copy(pushHere = true)).service)

    @Test fun `push on every network - the service is not wanted, idle and stopped`() =
        assertEquals(StayConnectedPolicy.Decision(Connection.IDLE, Service.STOP, Text.CONNECTING), d(inputs(switchOn = false).copy(pushHere = true)))

    @Test fun `stay on this network - unchanged by the other networks`() =
        assertEquals(Connection.HOLD, d(inputs().copy(pushHere = false)).connection)

    @Test fun `a released tailnet stays released - not-on-tailnet is only true after a network change`() {
        // the app keeps onTailnet after the release; if it read false the policy would HOLD and reconnect in a loop
        assertEquals(Connection.RELEASE, d(inputs(net = NetKind.WIFI, powerSave = true, onTailnet = true, connected = false)).connection)
    }

    // 0.6.92: a ring that is up is busy, like a live call (the 2026-11-21 10:31 ring released the socket 6 s in).
    @Test fun `mobile data, battery saver, a ring is up - defer, not release`() =
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.CELLULAR, powerSave = true).copy(ringLive = true)).connection)

    @Test fun `push-mode network, a ring is up - defer`() =
        assertEquals(Connection.DEFER, d(inputs(net = NetKind.CELLULAR).copy(pushHere = true, ringLive = true)).connection)

    @Test fun `no ring - the same inputs release`() {
        assertEquals(Connection.RELEASE, d(inputs(net = NetKind.CELLULAR, powerSave = true)).connection)
        assertEquals(Connection.RELEASE, d(inputs(net = NetKind.CELLULAR).copy(pushHere = true)).connection)
    }

    @Test fun `a ring does not change a held connection or an idle one`() {
        assertEquals(Connection.HOLD, d(inputs().copy(ringLive = true)).connection)
        assertEquals(Connection.IDLE, d(inputs(paused = true).copy(ringLive = true)).connection)
    }
}
