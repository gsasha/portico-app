package com.meda.portico.signaling

import com.meda.portico.core.ha.FailureClass
import com.meda.portico.core.ha.LinkFailure
import com.meda.portico.core.ha.WsListener
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The direct connector says WHY a socket failed (Setup's per-path line), from real OkHttp
 * failures on loopback: an HTTP status, a refused port, a name that does not resolve. Nothing
 * here leaves the machine (the unresolvable name never reaches a resolver).
 */
class OkHttpConnectorFailureTest {

    class Rec : WsListener {
        val events = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<LinkFailure>()
        override fun onOpen() {
            events += "open"
        }
        override fun onText(text: String) {
            events += "text"
        }
        override fun onClosed(reason: String) {
            events += "closed:$reason"
        }
        override fun onFailure(failure: LinkFailure) {
            failures += failure
            events += "failure"
        }
    }

    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun waitClosed(rec: Rec) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end && rec.events.none { it.startsWith("closed:") }) Thread.sleep(10)
        assertTrue(rec.events.any { it.startsWith("closed:") }, rec.events.toString())
    }

    @Test
    fun `an HTTP status instead of the upgrade`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.start(IPV4_LOOPBACK, 0)
        val rec = Rec()
        OkHttpConnector().open("ws://127.0.0.1:${server.port}/api/websocket", rec)
        waitClosed(rec)
        assertEquals(listOf(LinkFailure(FailureClass.HTTP_STATUS, 404)), rec.failures.toList())
        assertEquals("failure", rec.events.first(), "the reason arrives before the close")
    }

    @Test
    fun `a refused port`() {
        val port = ServerSocket(0, 1, IPV4_LOOPBACK).use { it.localPort }
        val rec = Rec()
        OkHttpConnector().open("ws://127.0.0.1:$port/api/websocket", rec)
        waitClosed(rec)
        assertEquals(listOf(LinkFailure(FailureClass.REFUSED)), rec.failures.toList())
    }

    @Test
    fun `a name that does not resolve`() {
        val noDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("Unable to resolve host \"$hostname\"")
        }
        val rec = Rec()
        OkHttpConnector(OkHttpConnector.defaultClient().newBuilder().dns(noDns).build())
            .open("ws://homeassistant.lan:8123/api/websocket", rec)
        waitClosed(rec)
        assertEquals(listOf(LinkFailure(FailureClass.NAME_NOT_RESOLVED)), rec.failures.toList())
    }

    @Test
    fun `an opened socket reports no failure, and a later server close is not one`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "bye")
                }
            }),
        )
        server.start(IPV4_LOOPBACK, 0)
        val rec = Rec()
        OkHttpConnector(OkHttpClient()).open("ws://127.0.0.1:${server.port}/api/websocket", rec)
        waitClosed(rec)
        assertEquals("open", rec.events.first())
        assertTrue(rec.failures.isEmpty(), rec.events.toString())
    }
}

/**
 * These tests dial the literal `ws://127.0.0.1:<port>`, so they bind exactly that: the platform's
 * loopback can be `::1` (Android, or a JVM preferring IPv6), where such a dial would be refused.
 */
private val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
