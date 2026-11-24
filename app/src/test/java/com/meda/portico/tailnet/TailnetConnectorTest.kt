package com.meda.portico.tailnet

import com.meda.portico.core.ha.FailureClass
import com.meda.portico.core.ha.LinkFailure
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.tailnet.Forwarder
import com.meda.portico.core.tailnet.ForwarderConnectException
import com.meda.portico.core.tailnet.TailnetUnavailableException
import com.meda.portico.signaling.OkHttpConnector
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The tailnet connector's socket redirect, end to end on loopback (DEV-557): OkHttp opens the
 * real `ha_url` (a host name that does NOT resolve on this machine), its TCP connection goes to a
 * stand-in for gocore's forwarder (which checks the token exactly as Go does, then pipes to
 * MockWebServer playing HA), and TLS is done for the URL's real host name.
 */
class TailnetConnectorTest {

    /** gocore/tailnet's forwarder in miniature: token first, else close; then pipe to [target]. */
    class TestForwarder(private val target: Int) : AutoCloseable {
        val token = ByteArray(Forwarder.TOKEN_BYTES) { (it * 7 + 3).toByte() }
        // Bound like gocore's forwarder: 127.0.0.1 only, never the platform's loopback.
        private val server = ServerSocket(0, 50, FORWARDER_LOOPBACK)
        val port: Int get() = server.localPort
        val accepted = AtomicInteger()
        val refused = AtomicInteger()

        init {
            Thread({
                while (!server.isClosed) {
                    val c = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                }
            }, "test-forwarder").apply { isDaemon = true }.start()
        }

        private fun handle(c: Socket) {
            c.use {
                c.soTimeout = 3_000
                val got = ByteArray(token.size)
                val ok = try {
                    var n = 0
                    while (n < got.size) {
                        val r = c.getInputStream().read(got, n, got.size - n)
                        if (r < 0) break
                        n += r
                    }
                    n == got.size && got.contentEquals(token)
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    refused.incrementAndGet()
                    return
                }
                c.soTimeout = 0
                accepted.incrementAndGet()
                Socket(InetAddress.getLoopbackAddress(), target).use { up ->
                    val a = Thread { pipe(c.getInputStream(), up.getOutputStream()); runCatching { up.shutdownOutput() } }
                    a.isDaemon = true
                    a.start()
                    pipe(up.getInputStream(), c.getOutputStream())
                    a.join(1_000)
                }
            }
        }

        private fun pipe(i: InputStream, o: OutputStream) {
            try {
                i.copyTo(o)
                o.flush()
            } catch (_: Exception) {
            }
        }

        fun forwarder(tok: ByteArray = token) = Forwarder.parse("$port:" + tok.joinToString("") { "%02x".format(it) })!!

        override fun close() = server.close()
    }

    /** Records the WsListener callbacks. */
    class Rec : WsListener {
        val events = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<LinkFailure>()
        override fun onFailure(failure: LinkFailure) {
            failures += failure
        }
        override fun onOpen() {
            events += "open"
        }
        override fun onText(text: String) {
            events += "text:$text"
        }
        override fun onClosed(reason: String) {
            events += "closed:$reason"
        }
    }

    /** HA's side of the socket: says hello on open. */
    class Hello : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required"}""")
        }
    }

    private val server = MockWebServer()
    private val closers = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closers.forEach { runCatching { it.close() } }
        runCatching { server.shutdown() }
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `plain ws to an unresolvable private name goes through the forwarder`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(Hello()))
        server.start(InetAddress.getLoopbackAddress(), 0)
        val fwd = TestForwarder(server.port).also { closers += it }
        val rec = Rec()
        // homeassistant.lan never resolves here: if the connector looked it up, this would fail.
        val t = TailnetConnector({ fwd.forwarder() }, { "n/a" }).open("ws://homeassistant.lan:8123/api/websocket", rec)
        waitFor("open + hello") { rec.events.size >= 2 }
        assertEquals(listOf("open", """text:{"type":"auth_required"}"""), rec.events.take(2))
        assertTrue(t.send("""{"type":"auth"}"""))
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("homeassistant.lan:8123", req.getHeader("Host"), "the request still names the real host")
        assertEquals("/api/websocket", req.path)
        assertEquals(1, fwd.accepted.get())
        t.close(1000, "done")
    }

    @Test
    fun `a wrong token is refused by the forwarder and the socket fails`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(Hello()))
        server.start(InetAddress.getLoopbackAddress(), 0)
        val fwd = TestForwarder(server.port).also { closers += it }
        val rec = Rec()
        TailnetConnector({ fwd.forwarder(ByteArray(32)) }, { "n/a" }).open("ws://homeassistant.lan:8123/api/websocket", rec)
        waitFor("closed") { rec.events.isNotEmpty() }
        assertTrue(rec.events.single().startsWith("closed:failure: "), rec.events.toString())
        assertEquals(1, fwd.refused.get())
        assertEquals(0, server.requestCount)
        // OkHttp alone sees only the forwarder hanging up; the Go side's record names the token.
        assertEquals(listOf(LinkFailure(FailureClass.CLOSED_EARLY)), rec.failures.toList())
    }

    @Test
    fun `no forwarder (node not up) throws with the state, and opens nothing`() {
        val e = assertThrows(TailnetUnavailableException::class.java) {
            TailnetConnector({ null }, { "KeyExpired" }).open("ws://homeassistant.lan:8123/api/websocket", Rec())
        }
        assertEquals("tailnet not up (KeyExpired)", e.message)
    }

    @Test
    fun `the URL policy still applies on the tailnet path`() {
        var asked = false
        assertThrows(OkHttpConnector.RefusedUrlException::class.java) {
            TailnetConnector({ asked = true; null }, { "" }).open("ws://ha.example.com/api/websocket", Rec())
        }
        assertFalse(asked, "a refused URL never wakes the tailnet")
    }

    @Test
    fun `the socket factory ignores the requested address and sends the token first`() {
        val got = CopyOnWriteArrayList<ByteArray>()
        ServerSocket(0, 5, FORWARDER_LOOPBACK).use { ss ->
            val th = Thread {
                ss.accept().use { c ->
                    val b = ByteArray(32)
                    var n = 0
                    while (n < 32) n += c.getInputStream().read(b, n, 32 - n)
                    got += b
                }
            }
            th.start()
            val tok = ByteArray(32) { it.toByte() }
            ForwarderSocketFactory(ss.localPort, tok).createSocket().use {
                it.connect(InetSocketAddress(InetAddress.getByAddress("haos.example", byteArrayOf(10, 2, 0, 5)), 443), 2_000)
            }
            th.join(2_000)
            assertTrue(got.single().contentEquals(tok))
        }
    }

    @Test
    fun `the socket connects to exactly 127 0 0 1, IPv4`() {
        ServerSocket(0, 5, FORWARDER_LOOPBACK).use { ss ->
            val th = Thread { runCatching { ss.accept().use { it.getInputStream().readNBytes(32) } } }.apply { start() }
            ForwarderSocketFactory(ss.localPort, ByteArray(32)).createSocket().use {
                it.connect(InetSocketAddress.createUnresolved("haos.example", 443), 2_000)
                assertTrue(it.inetAddress is java.net.Inet4Address, it.inetAddress.toString())
                assertEquals("127.0.0.1", it.inetAddress.hostAddress)
                assertEquals(ss.localPort, it.port)
            }
            th.join(2_000)
        }
        assertTrue(FORWARDER_LOOPBACK is java.net.Inet4Address && FORWARDER_LOOPBACK.hostAddress == Forwarder.HOST)
    }

    @Test
    fun `a forwarder port with no listener fails as FORWARDER_DOWN naming 127 0 0 1 and the port`() {
        val dead = ServerSocket(0, 1, FORWARDER_LOOPBACK).use { it.localPort }
        val e = assertThrows(ForwarderConnectException::class.java) {
            ForwarderSocketFactory(dead, ByteArray(32)).createSocket().use { it.connect(InetSocketAddress.createUnresolved("haos.example", 443), 2_000) }
        }
        assertEquals("127.0.0.1:$dead", e.endpoint)
        // End to end through OkHttp: the Setup line's class and endpoint, no token or URL.
        val rec = Rec()
        val f = Forwarder.parse("$dead:" + "ab".repeat(32))!!
        TailnetConnector({ f }, { "n/a" }).open("ws://homeassistant.lan:8123/api/websocket", rec)
        waitFor("closed") { rec.events.isNotEmpty() }
        assertEquals(listOf(LinkFailure(FailureClass.FORWARDER_DOWN, endpoint = "127.0.0.1:$dead")), rec.failures.toList())
    }

    @Test
    fun `NoLookupDns never resolves`() {
        val a = NoLookupDns.lookup("this-name-does-not-exist.invalid").single()
        assertEquals("this-name-does-not-exist.invalid", a.hostName)
        assertTrue(a.isLoopbackAddress)
    }

    // ---- TLS: SNI, the certificate and the host name are the URL's, unchanged ------------------

    private class Tls(val serverFactory: SSLSocketFactory, val clientFactory: SSLSocketFactory, val trust: X509TrustManager)

    /** A throwaway self-signed certificate for [name], made with the JDK's keytool (no network). */
    private fun tlsFor(name: String, dir: File): Tls {
        val ks = File(dir, "ha.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "ha", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=$name", "-ext", "SAN=dns:$name", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool failed: $out" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ha", store.getCertificate("ha"))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val clientCtx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        return Tls(serverCtx.socketFactory, clientCtx.socketFactory, tmf.trustManagers.single() as X509TrustManager)
    }

    /** Server-side socket factory that records each handshake's SNI. */
    private class SniRecorder(private val d: SSLSocketFactory, val names: MutableList<String>) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        private fun hook(s: Socket): Socket = s.also {
            (it as SSLSocket).addHandshakeCompletedListener { ev ->
                (ev.session as? ExtendedSSLSession)?.requestedServerNames?.forEach { n ->
                    names += (n as? SNIHostName)?.asciiName ?: n.toString()
                }
            }
        }
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean) = hook(d.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String?, port: Int) = hook(d.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, lh: InetAddress?, lp: Int) = hook(d.createSocket(host, port, lh, lp))
        override fun createSocket(host: InetAddress?, port: Int) = hook(d.createSocket(host, port))
        override fun createSocket(a: InetAddress?, port: Int, la: InetAddress?, lp: Int) = hook(d.createSocket(a, port, la, lp))
    }

    @Test
    fun `wss keeps SNI and certificate checks for the real host name`(@TempDir dir: Path) {
        val tls = tlsFor("haos.portico.test", dir.toFile())
        val sni = CopyOnWriteArrayList<String>()
        server.useHttps(SniRecorder(tls.serverFactory, sni), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(Hello()))
        server.start(InetAddress.getLoopbackAddress(), 0)
        val fwd = TestForwarder(server.port).also { closers += it }
        val base = OkHttpClient.Builder().sslSocketFactory(tls.clientFactory, tls.trust).build()
        val rec = Rec()
        val t = TailnetConnector({ fwd.forwarder() }, { "n/a" }, base).open("wss://haos.portico.test/api/websocket", rec)
        waitFor("open over TLS") { rec.events.size >= 2 }
        assertEquals("open", rec.events.first())
        assertEquals(listOf("haos.portico.test"), sni.toList(), "SNI is the URL's host, not 127.0.0.1")
        t.close(1000, "done")
    }

    @Test
    fun `wss to a host the certificate does not name still fails hostname verification`(@TempDir dir: Path) {
        val tls = tlsFor("haos.portico.test", dir.toFile())
        server.useHttps(tls.serverFactory, false)
        server.enqueue(MockResponse().withWebSocketUpgrade(Hello()))
        server.start(InetAddress.getLoopbackAddress(), 0)
        val fwd = TestForwarder(server.port).also { closers += it }
        val base = OkHttpClient.Builder().sslSocketFactory(tls.clientFactory, tls.trust).build()
        val rec = Rec()
        TailnetConnector({ fwd.forwarder() }, { "n/a" }, base).open("wss://other.portico.test/api/websocket", rec)
        waitFor("closed") { rec.events.isNotEmpty() }
        assertTrue(rec.events.single().startsWith("closed:failure: SSLPeerUnverifiedException"), rec.events.toString())
        assertEquals(listOf(LinkFailure(FailureClass.HOSTNAME)), rec.failures.toList())
    }

    @Test
    fun `wss with an untrusted certificate fails - the tailnet is never trusted with identity`(@TempDir dir: Path) {
        val tls = tlsFor("haos.portico.test", dir.toFile())
        server.useHttps(tls.serverFactory, false)
        server.enqueue(MockResponse().withWebSocketUpgrade(Hello()))
        server.start(InetAddress.getLoopbackAddress(), 0)
        val fwd = TestForwarder(server.port).also { closers += it }
        val rec = Rec()
        // The default client: system CAs only, which do not include the throwaway certificate.
        TailnetConnector({ fwd.forwarder() }, { "n/a" }).open("wss://haos.portico.test/api/websocket", rec)
        waitFor("closed") { rec.events.isNotEmpty() }
        assertTrue(rec.events.single().startsWith("closed:failure: SSL"), rec.events.toString())
        assertEquals(listOf(LinkFailure(FailureClass.CERTIFICATE)), rec.failures.toList())
    }
}
