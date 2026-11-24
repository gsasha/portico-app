package com.meda.portico.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.NetworkInterface

/**
 * The table format is the contract with gocore/tailnet's parseInterfaces (interfaces.go). Run on
 * the build host's real interfaces: the same java.net API Android answers.
 */
class TailnetInterfacesTest {

    @Test
    fun `one tab-separated line per interface, five fields`() {
        val lines = TailnetInterfaces.table().trimEnd('\n').split('\n')
        assertTrue(lines.isNotEmpty())
        for (l in lines) {
            val f = l.split('\t')
            assertEquals(5, f.size, l)
            assertTrue(f[0].isNotEmpty(), l)
            f[1].toInt()
            assertTrue(f[2].toInt() > 0, l)
            f[3].toInt()
        }
    }

    @Test
    fun `loopback is flagged up, running and loopback, with 127_0_0_1 slash 8`() {
        val lo = java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).first { it.isLoopback }
        val f = TailnetInterfaces.line(lo).split('\t')
        val flags = f[3].toInt()
        assertTrue(flags and TailnetInterfaces.FLAG_UP != 0)
        assertTrue(flags and TailnetInterfaces.FLAG_RUNNING != 0)
        assertTrue(flags and TailnetInterfaces.FLAG_LOOPBACK != 0)
        assertEquals(0, flags and TailnetInterfaces.FLAG_BROADCAST, "loopback is not broadcast")
        assertTrue("127.0.0.1/8" in f[4].split(','), f[4])
    }

    @Test
    fun `no address carries a percent-scope suffix (Go's ParseCIDR would drop it)`() {
        assertFalse(TailnetInterfaces.table().contains('%'))
    }

    @Test
    fun `Go's flag bits match net_Flags`() {
        // tailnet/interfaces.go reads these as Go's net.Flags: up=1 broadcast=2 loopback=4 p2p=8 multicast=16 running=32.
        assertEquals(listOf(1, 2, 4, 8, 16, 32), listOf(
            TailnetInterfaces.FLAG_UP, TailnetInterfaces.FLAG_BROADCAST, TailnetInterfaces.FLAG_LOOPBACK,
            TailnetInterfaces.FLAG_POINTTOPOINT, TailnetInterfaces.FLAG_MULTICAST, TailnetInterfaces.FLAG_RUNNING,
        ))
    }

    @Test
    fun `the gomobile and no-gomobile engines are the only two, one per source set`() {
        // app/build.gradle.kts compiles exactly one of these; both must define the same entry point.
        val a = File("src/gomobile/java/com/meda/portico/tailnet/TailnetEngines.kt").readText()
        val b = File("src/nogomobile/java/com/meda/portico/tailnet/TailnetEngines.kt").readText()
        val sig = "fun create(app: Context, log: HaLog): TailnetEngine"
        assertTrue(sig in a && sig in b)
        assertTrue("TailnetEngine.NOT_BUILT" in b)
        assertTrue("GoTailnetEngine" in a)
    }

    // ---- A-08 (DEV-761): the real tables the old app's Pixel produced on a work Wi-Fi, eleven seconds apart ----

    private val t1 = "dummy0\t2\t1500\t69\tfe80::7cee:71ff:feb6:9b86/64\n" +
        "wlan0\t3\t1500\t71\tfe80::3037:5cff:fe3c:b0b3/64,2a00:79e1:2a00:700:3037:5cff:fe3c:b0b3/64," +
        "2a00:79e1:2a00:700:c048:b6a7:94ec:3f2d/64\n" +
        "rmnet1\t4\t1500\t69\t192.168.2.145/32\n" +
        "rmnet2\t5\t1500\t69\tfe80::200:ff:fe00:0/64,2a02:6680:2001:6cb3:efe6:45f9:8edd:7a88/64\n" +
        "v4-wlan0\t6\t1472\t69\t192.0.0.4/32\n"
    private val t2 = t1.replace("c048:b6a7:94ec:3f2d", "ab23:e0f4:9f21:b3fe")

    @Test
    fun `a rotating IPv6 privacy address is not a network change`() {
        assertEquals(TailnetInterfaces.signature(t1), TailnetInterfaces.signature(t2))
    }

    @Test
    fun `real network changes still change the signature`() {
        val base = TailnetInterfaces.signature(t1)
        assertNotEquals(base, TailnetInterfaces.signature(t1.replace("2a00:79e1:2a00:700:", "2a00:79e1:2a00:999:")))
        assertNotEquals(base, TailnetInterfaces.signature(t1.replace("wlan0\t3\t1500\t71\tfe80", "wlan0\t3\t1500\t71\t192.168.2.24/24,fe80")))
        assertNotEquals(base, TailnetInterfaces.signature(t1.replace(Regex("(?m)^wlan0.*\\n"), "")))
        assertNotEquals(base, TailnetInterfaces.signature(t1.replace("wlan0\t3\t1500\t71", "wlan0\t3\t1500\t0")))
        assertNotEquals(base, TailnetInterfaces.signature(t1.replace("wlan0\t3\t1500", "wlan0\t3\t1280")))
        assertNotEquals(base, TailnetInterfaces.signature(t1.replace("192.168.2.145/32", "192.168.2.200/32")))
    }

    @Test
    fun `the default network changing changes the restricted signature`() {
        val wifi = TailnetInterfaces.restrict(t1, setOf("wlan0", "v4-wlan0"))
        val cell = TailnetInterfaces.restrict(t1, setOf("rmnet1", "v4-rmnet1"))
        assertNotEquals(TailnetInterfaces.signature(wifi), TailnetInterfaces.signature(cell))
        // and the privacy rotation on the default network still does not
        assertEquals(
            TailnetInterfaces.signature(wifi),
            TailnetInterfaces.signature(TailnetInterfaces.restrict(t2, setOf("wlan0", "v4-wlan0"))),
        )
    }

    @Test
    fun `restrict keeps only the default network, its translator and loopback`() {
        val r = TailnetInterfaces.restrict("lo\t1\t65536\t73\t127.0.0.1/8\n$t1", setOf("wlan0", "v4-wlan0"))
        assertEquals(setOf("lo", "wlan0", "v4-wlan0"), TailnetInterfaces.names(r))
        assertFalse(r.contains("192.168.2.145"))
        assertFalse(r.contains("rmnet2"))
    }

    @Test
    fun `restrict changes nothing when the default is unknown or leaves no global address`() {
        assertEquals(t1, TailnetInterfaces.restrict(t1, emptySet()))
        assertEquals(t1, TailnetInterfaces.restrict(t1, setOf("dummy0"))) // only a link-local
        assertEquals(t1, TailnetInterfaces.restrict(t1, setOf("nope")))
    }

    @Test
    fun `vanished sees a missing interface and ignores a new one`() {
        assertTrue(TailnetInterfaces.vanished(t1, t1.replace(Regex("(?m)^wlan0.*\\n"), "")))
        assertFalse(TailnetInterfaces.vanished(t1, t1 + "eth0\t7\t1500\t69\t10.0.0.2/24\n"))
        assertFalse(TailnetInterfaces.vanished(null, t1))
        assertFalse(TailnetInterfaces.vanished("", t1))
        assertFalse(TailnetInterfaces.vanished(t1, t2))
    }
}
