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

/** PR-E: `wake()`, the socket connect a pushed ring triggers. A fake connector; no network. */
class SignalingClientWakeTest {
    private val opens = AtomicInteger()
    @Volatile private var failOpen = true
    // PR-J: a private (.lan) host; plain http to a public-looking name is refused by UrlPolicy.
    @Volatile private var url: String? = "http://ha.lan:8123"
    private val config = object : HaConnectionConfig {
        override val haUrl: String? get() = url
        override val accessToken: String? get() = "t"
    }
    private val connector = Connector { _, _ ->
        opens.incrementAndGet()
        if (failOpen) throw java.io.IOException("refused")
        object : WsTransport {
            override fun send(text: String) = true
            override fun close(code: Int, reason: String) {}
        }
    }
    private val link = PorticoLink(
        RegistrationMachine(RegistrationConfig("i", null, "0"), InMemoryRegistrationStore(), { 0L }),
        DoorsCache(InMemoryDoorsCacheStore()),
        {},
        object : RegistrationSource {
            override fun requestFid() {}
            override fun requestToken() {}
        },
    )
    // A one-hour retry delay: only wake() can make a second attempt inside the test.
    private val client = SignalingClient(config, link, connector, Backoff(jitter = { 3_600_000 }, maxMs = 3_600_000), tickMs = 3_600_000)

    @AfterEach
    fun tearDown() = client.shutdown()

    private fun settle() {
        val l = CountDownLatch(1)
        client.post { l.countDown() }
        l.await(5, TimeUnit.SECONDS)
    }

    @Test
    fun `wake on a stopped client starts it`() {
        client.wake()
        settle()
        assertEquals(1, opens.get())
    }

    @Test
    fun `wake while waiting to retry connects at once`() {
        client.start()
        settle()
        assertEquals(SignalingClient.State.WAITING_TO_RETRY, client.state)
        client.wake()
        settle()
        assertEquals(2, opens.get())
    }

    @Test
    fun `wake while connecting leaves the socket alone`() {
        failOpen = false
        client.start()
        settle()
        assertEquals(SignalingClient.State.CONNECTING, client.state)
        client.wake()
        settle()
        assertEquals(1, opens.get())
    }

    @Test
    fun `wake after Setup supplied the URL connects (was NOT_CONFIGURED)`() {
        url = null
        client.start()
        settle()
        assertEquals(SignalingClient.State.NOT_CONFIGURED, client.state)
        url = "http://ha.lan:8123"
        client.wake()
        settle()
        assertEquals(1, opens.get())
    }
}
