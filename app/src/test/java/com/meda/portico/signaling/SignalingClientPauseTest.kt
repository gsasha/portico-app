package com.meda.portico.signaling

import com.meda.portico.core.ha.Backoff
import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaConnectionConfig
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.RegistrationSource
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pause: while the phone is paused the socket to HA is closed and NOTHING reopens it: not start,
 * not a pushed ring's wake, not a Setup save's reconnect, not the retry timer. No FID request
 * either (the link is not started). A fake connector; no network.
 */
class SignalingClientPauseTest {
    private val opens = AtomicInteger()
    private val closes = AtomicInteger()
    private val fidRequests = AtomicInteger()
    @Volatile private var failOpen = false
    @Volatile private var paused = true
    @Volatile private var pausedThrows = false

    private val config = object : HaConnectionConfig {
        override val haUrl: String? get() = "http://ha.lan:8123"
        override val accessToken: String? get() = "t"
    }
    private val connector = Connector { _, _ ->
        opens.incrementAndGet()
        if (failOpen) throw java.io.IOException("refused")
        object : WsTransport {
            override fun send(text: String) = true
            override fun close(code: Int, reason: String) {
                closes.incrementAndGet()
            }
        }
    }
    private val link = PorticoLink(
        RegistrationMachine(RegistrationConfig("i", "portico-test", "0"), InMemoryRegistrationStore(), { 0L }),
        DoorsCache(InMemoryDoorsCacheStore()),
        {},
        object : RegistrationSource {
            override fun requestFid() {
                fidRequests.incrementAndGet()
            }
            override fun requestToken() {}
        },
    )

    // A 50 ms retry: a retry that should not happen has time to show up.
    private val client = SignalingClient(
        config, link, connector, Backoff(jitter = { 50 }, maxMs = 50), tickMs = 3_600_000,
        paused = { if (pausedThrows) error("unreadable") else paused },
    )

    @AfterEach
    fun tearDown() = client.shutdown()

    private fun settle(ms: Long = 0) {
        if (ms > 0) Thread.sleep(ms)
        val l = CountDownLatch(1)
        client.post { l.countDown() }
        l.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun `start while paused - no socket, no FID request, state PAUSED`() {
        client.start()
        settle()
        assertEquals(0, opens.get())
        assertEquals(0, fidRequests.get())
        assertEquals(SignalingClient.State.PAUSED, client.state)
    }

    @Test
    fun `a pushed ring's wake and a Setup save's reconnect open nothing while paused`() {
        client.start()
        client.wake()
        client.reconnect()
        settle(200)
        assertEquals(0, opens.get())
        assertEquals(SignalingClient.State.PAUSED, client.state)
    }

    @Test
    fun `pause closes the open socket and nothing retries`() {
        paused = false
        client.start()
        settle()
        assertEquals(1, opens.get())
        paused = true
        client.pause()
        settle(300)
        assertEquals(1, closes.get())
        assertEquals(1, opens.get(), "a retry ran while paused")
        assertEquals(SignalingClient.State.PAUSED, client.state)
    }

    @Test
    fun `a retry already scheduled when the pause began does not connect`() {
        paused = false
        failOpen = true
        client.start()
        settle()
        assertEquals(1, opens.get())
        paused = true // the flag is committed before the teardown is posted
        settle(300)
        assertEquals(1, opens.get())
        assertEquals(SignalingClient.State.PAUSED, client.state)
    }

    @Test
    fun `resume - a start after the pause ended connects as a normal start does`() {
        client.start()
        settle()
        paused = false
        client.start()
        settle()
        assertEquals(1, opens.get())
        assertEquals(1, fidRequests.get(), "the link starts (and asks Firebase) only on the resume")
    }

    @Test
    fun `FAIL OPEN - an unreadable pause flag connects`() {
        pausedThrows = true
        client.start()
        settle()
        assertEquals(1, opens.get())
    }
}
