package com.meda.portico.core.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Go side's diagnostic lines, the target's IP, and the controller's route and forwarder reads. */
class TailnetDiagTest {

    @Test
    fun `forwarder status lines`() {
        assertNull(ForwarderDial.parse(null))
        assertNull(ForwarderDial.parse("none"))
        assertNull(ForwarderDial.parse(""))
        assertNull(ForwarderDial.parse("timeout"))
        assertNull(ForwarderDial.parse("timeout\tsoon"))
        assertNull(ForwarderDial.parse("Time Out\t1"))
        assertEquals(ForwarderDial("ok", 1_700_000_000_000), ForwarderDial.parse("ok\t1700000000000"))
        assertEquals(
            ForwarderDial("timeout", 5, "dial tcp 192.168.1.5:443: i/o timeout"),
            ForwarderDial.parse("timeout\t5\tdial tcp 192.168.1.5:443: i/o timeout\n"),
        )
        val long = ForwarderDial.parse("error\t5\t" + "x".repeat(500))!!
        assertEquals(ForwarderDial.MAX_DETAIL, long.detail.length)
    }

    @Test
    fun `route check lines`() {
        assertEquals(RouteCheck.Via("example-router", "192.168.0.0/16"), RouteCheck.parse("via\texample-router\t192.168.0.0/16"))
        assertEquals(RouteCheck.None("192.168.1.5", 3), RouteCheck.parse("none\t192.168.1.5\t3"))
        assertEquals(RouteCheck.NotIp, RouteCheck.parse("not_ip"))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse("unknown"))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse(null))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse("via\t\t192.168.0.0/16"))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse("via\tx"))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse("none\t192.168.1.5\tmany"))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse("none\t\t1"))
        assertEquals(RouteCheck.Unknown, RouteCheck.parse("something newer"))
        val v = RouteCheck.parse("via\t" + "n".repeat(300) + "\t192.168.0.0/16") as RouteCheck.Via
        assertEquals(RouteCheck.MAX_NAME, v.peer.length)
    }

    @Test
    fun `the target's IP`() {
        assertEquals("192.168.1.5", TailnetTarget.ipOf("192.168.1.5:443"))
        assertEquals("fd7a:115c:a1e0::5", TailnetTarget.ipOf("[FD7A:115C:A1E0::5]:443"))
        assertNull(TailnetTarget.ipOf("haos.example.org:443"))
        assertNull(TailnetTarget.ipOf("[not-v6]:443"))
        assertNull(TailnetTarget.ipOf("[::1"))
        assertNull(TailnetTarget.ipOf("192.168.1.5"))
        assertNull(TailnetTarget.ipOf(":443"))
        assertNull(TailnetTarget.ipOf(null))
    }

    @Test
    fun `tailnet unavailable names the state, not a key`() {
        assertEquals("tailnet not up (Joining)", TailnetUnavailableException("Joining").message)
    }

    private var target: String? = "192.168.1.5:443"

    private fun up(e: FakeEngine): TailnetController {
        e.status = "up\t100.70.97.30"
        e.identity = true
        return TailnetController(e, FakeKeys(), hostname = { "portico-pixel" }, target = { target })
    }

    @Test
    fun `the controller refreshes the route while up, for an IP target`() {
        val checked = mutableListOf<String>()
        val e = object : FakeEngine() {
            override fun routeCheck(target: String): String {
                checked.add(target)
                return "via\texample-router\t192.168.0.0/16"
            }
        }
        val c = up(e)
        assertEquals(RouteCheck.Unknown, c.route)
        c.refresh()
        assertEquals(RouteCheck.Via("example-router", "192.168.0.0/16"), c.route)
        assertEquals(listOf("192.168.1.5:443"), checked)
        e.status = "idle"
        c.refresh()
        assertEquals(RouteCheck.Unknown, c.route, "not up: the peers are not known")
    }

    @Test
    fun `a name target has no route to look up, and an engine that throws is unknown`() {
        target = "haos.example.org:443"
        val e = object : FakeEngine() {
            override fun routeCheck(target: String): String = throw IllegalStateException("old library")
        }
        val c = up(e)
        c.refresh()
        assertEquals(RouteCheck.NotIp, c.route)
        target = "192.168.1.5:443"
        c.refresh()
        assertEquals(RouteCheck.Unknown, c.route)
        target = null
        c.refresh()
        assertEquals(RouteCheck.NotIp, c.route)
    }

    @Test
    fun `an engine without the new calls answers unknown and none`() {
        val c = up(FakeEngine())
        c.refresh()
        assertEquals(RouteCheck.Unknown, c.route)
        assertNull(c.forwarderDial())
    }

    @Test
    fun `forwarder dial is read only when the library is loaded`() {
        var asked = 0
        val e = object : FakeEngine() {
            override fun forwarderStatus(): String {
                asked++
                return "timeout\t42\tdial tcp 192.168.1.5:443: i/o timeout"
            }
        }
        e.started = false
        val c = TailnetController(e, FakeKeys(), hostname = { "p" }, target = { target })
        assertNull(c.forwarderDial())
        assertEquals(0, asked, "must not wake the library")
        e.started = true
        assertEquals(ForwarderDial("timeout", 42, "dial tcp 192.168.1.5:443: i/o timeout"), c.forwarderDial())
        val throwing = object : FakeEngine() {
            override fun forwarderStatus(): String = throw IllegalStateException("x")
        }
        assertNull(TailnetController(throwing, FakeKeys(), hostname = { "p" }, target = { target }).forwarderDial())
        assertNull(TailnetController(TailnetEngine.NOT_BUILT, FakeKeys(), hostname = { "p" }, target = { target }).forwarderDial())
        assertTrue(TailnetEngine.NOT_BUILT.routeCheck("192.168.1.5:443") == "unknown")
        assertTrue(TailnetEngine.NOT_BUILT.forwarderStatus() == "none")
    }
}
