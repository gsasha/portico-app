package com.meda.portico.signaling

import com.meda.portico.core.auth.PathedHttp
import com.meda.portico.core.auth.PathsFailedException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** DEV-544: the HTTP transport for the redemption and the refresh - what it sends, what it refuses, how it fails. */
class OkHttpPostTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer

    @BeforeEach
    fun up() {
        server = MockWebServer().also { it.start(java.net.InetAddress.getLoopbackAddress(), 0) }
        other = MockWebServer().also { it.start(java.net.InetAddress.getLoopbackAddress(), 0) }
    }

    @AfterEach
    fun down() {
        runCatching { server.shutdown() }
        runCatching { other.shutdown() }
    }

    private fun url(s: MockWebServer = server, path: String = "/x") = s.url(path).toString()

    @Test
    fun `it posts the body with the given content type, no Authorization header, and returns status, body and Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9").setBody("""{"error":"rate_limited"}"""))
        val r = OkHttpPost().post(url(), "application/json", """{"a":1}""")
        assertEquals(429, r.status)
        assertEquals("""{"error":"rate_limited"}""", r.body)
        assertEquals("9", r.retryAfter)
        val seen = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", seen.method)
        assertEquals("""{"a":1}""", seen.body.readUtf8())
        assertTrue(seen.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(seen.getHeader("Authorization"))
        assertNull(seen.getHeader("Cookie"))
    }

    @Test
    fun `a 4xx or 5xx is an answer, not an exception`() {
        for (st in listOf(400, 401, 403, 404, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(st).setBody("e"))
            assertEquals(st, OkHttpPost().post(url(), "application/json", "{}").status)
        }
    }

    @Test
    fun `a redirect is never followed - the body never goes anywhere the address did not name`() {
        for (code in listOf(301, 302, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", url(other, "/steal")))
            other.enqueue(MockResponse().setResponseCode(200).setBody("stolen")) // answers at once if a redirect IS followed
            val e = assertThrows(IOException::class.java) { OkHttpPost().post(url(), "application/json", """{"code":"SECRET"}""") }
            assertFalse(e.message!!.contains("SECRET"))
        }
        assertEquals(0, other.requestCount, "the redirect target saw nothing")
    }

    @Test
    fun `a server that never answers fails with an IOException when the timeout runs out`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fast = OkHttpClient.Builder().followRedirects(false).readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(2, TimeUnit.SECONDS).build()
        val t0 = System.nanoTime()
        assertThrows(IOException::class.java) { OkHttpPost(fast).post(url(), "application/json", "{}") }
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L)
    }

    @Test
    fun `a dropped connection and a refused connection are IOExceptions`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(IOException::class.java) { OkHttpPost().post(url(), "application/json", "{}") }
        val dead = url()
        server.shutdown()
        assertThrows(IOException::class.java) { OkHttpPost().post(dead, "application/json", "{}") }
    }

    @Test
    fun `the default client is the careful one - no redirects, no silent retry, bounded waits`() {
        val c = OkHttpPost.defaultClient()
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
        assertFalse(c.retryOnConnectionFailure)
        assertTrue(c.connectTimeoutMillis in 1..15_000)
        assertTrue(c.readTimeoutMillis in 1..30_000)
        assertTrue(c.callTimeoutMillis in 1..60_000)
    }

    @Test
    fun `plain http to a public name is refused before anything is sent`() {
        val e = assertThrows(IOException::class.java) { OkHttpPost().post("http://ha.example.com/auth/token", "application/json", "{}") }
        assertFalse(e.message!!.contains("ha.example.com"))
        assertThrows(IOException::class.java) { OkHttpPost().post("ftp://ha.example.com/x", "application/json", "{}") }
    }

    @Test
    fun `an enormous answer is cut, not read into memory`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(200_000)))
        val r = OkHttpPost().post(url(), "application/json", "{}")
        assertTrue(r.body.length <= 16 * 1024)
    }

    @Test
    fun `through the path chooser - a dead direct path and no tailnet is one error naming no address`() {
        val dead = url()
        server.shutdown()
        val e = assertThrows(PathsFailedException::class.java) { PathedHttp(OkHttpPost(), null, { false }).post(dead, "application/json", "{}") }
        assertFalse(e.message!!.contains("127.0.0.1"))
    }
}
