package com.meda.portico.tailnet

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The forwarder socket must reach gocore's listener, which binds `127.0.0.1` ONLY (IPv4).
 *
 * The 2026-10-08 field failure ("the local forwarder was not listening"): the socket connected
 * to `InetAddress.getLoopbackAddress()`. On Android that is `::1` (libcore's loopback list puts
 * IPv6 first), so every connect was refused. On a desktop JDK it is `127.0.0.1`, which is why
 * every JVM test passed.
 *
 * How this test catches that on the JVM: it runs [ForwarderLoopbackProbe] in a CHILD JVM started
 * with `-Djava.net.preferIPv6Addresses=true`. That flag makes the JDK's `getLoopbackAddress()`
 * return `::1`, just as Android does. In the child, a listener bound only to 127.0.0.1 (as Go
 * binds it) must receive the token through [ForwarderSocketFactory]. With the old
 * `getLoopbackAddress()` connect, the child reports `refused`. The test first checks that the
 * child's platform loopback really is `::1`. On a host without IPv6 the simulation is not
 * possible, so the test is skipped there instead of passing for no reason.
 */
class ForwarderLoopbackTest {

    @Test
    fun `the forwarder socket reaches an IPv4-only listener where the platform loopback is IPv6`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = System.getProperty("java.class.path")
        val p = ProcessBuilder(java, "-Djava.net.preferIPv6Addresses=true", "-cp", cp, ForwarderLoopbackProbe::class.java.name)
            .redirectErrorStream(true)
            .start()
        val finished = p.waitFor(60, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(finished, "the probe did not finish: $out")
        assumeTrue("platform-loopback=ipv6" in out, "this host cannot simulate an IPv6 platform loopback: $out")
        assertTrue("result=ok" in out, out)
    }
}

/** Run in a child JVM by [ForwarderLoopbackTest]; prints `platform-loopback=...` and `result=...`. */
object ForwarderLoopbackProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val platform = InetAddress.getLoopbackAddress()
        println("platform-loopback=" + if (platform is Inet6Address) "ipv6" else "ipv4")
        val token = ByteArray(32) { (it + 1).toByte() }
        // Bound exactly as gocore/tailnet/forwarder.go binds: 127.0.0.1, IPv4 only.
        ServerSocket(0, 5, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { ss ->
            var got: ByteArray? = null
            val th = Thread {
                runCatching {
                    ss.accept().use { c ->
                        val b = ByteArray(32)
                        var n = 0
                        while (n < 32) {
                            val r = c.getInputStream().read(b, n, 32 - n)
                            if (r < 0) break
                            n += r
                        }
                        got = b
                    }
                }
            }.apply { isDaemon = true; start() }
            val result = try {
                ForwarderSocketFactory(ss.localPort, token).createSocket().use {
                    it.connect(InetSocketAddress.createUnresolved("haos.portico.test", 443), 5_000)
                }
                th.join(5_000)
                if (got?.contentEquals(token) == true) "ok" else "no-token"
            } catch (e: Exception) {
                "refused: ${e.javaClass.simpleName}: ${e.message}"
            }
            println("result=$result")
        }
        System.out.flush()
    }
}
