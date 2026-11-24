package com.meda.portico.signaling

import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.auth.LoginStore
import com.meda.portico.core.auth.RefreshClient
import com.meda.portico.core.ha.Backoff
import com.meda.portico.core.ha.ContractMismatch
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaConnectionConfig
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.RegistrationSource
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DEV-544 through the real [SignalingClient] over a real OkHttp WebSocket and real HTTP, against MockWebServer playing Home
 * Assistant: `/auth/token` for the refresh grant and `/api/websocket` for the socket (the contract's recorded frames after
 * auth, as in [SignalingClientTest]).
 *
 * The first group is the OLD path: a phone with a stored long-lived token and no `login` behaves exactly as before and makes no
 * refresh traffic at all. The rest is the refresh-token login: when it refreshes, what it does on `invalid_grant`, on
 * `auth_invalid`, while a socket outlives its access token, and that no token reaches the log.
 */
class SignalingClientLoginTest {
    companion object {
        const val INSTALLATION = "inst-0f1e2d3c4b5a"
        const val PROJECT = "portico-test"
        const val REG = "FAKE-fcm-registration-0001"
        const val LONG_LIVED = "fake-jwt-header.ha-long-lived-access-token.SIGNATURE-0123456789"
        const val REFRESH = "REFRESH-TOKEN-SECRET-0123456789abcdef"
        const val CLIENT_ID = "https://portico.invalid/phone"
    }

    private val sockets = CopyOnWriteArrayList<FakeHa>()
    private val tokenCalls = CopyOnWriteArrayList<RecordedRequest>()
    private val upgrades = AtomicInteger()
    private val validAccess = CopyOnWriteArrayList<String>()
    private val tokenAnswers = java.util.concurrent.ConcurrentLinkedQueue<MockResponse>()
    private val logs = CopyOnWriteArrayList<String>()
    private val statuses = CopyOnWriteArrayList<RegStatus>()
    private val mono = AtomicLong(1_000_000L)
    private lateinit var server: MockWebServer
    private var client: SignalingClient? = null
    private val store = MemLogin()

    private class MemLogin : LoginStore {
        @Volatile var refresh: String? = REFRESH
        val signedOut = AtomicInteger()
        override fun refreshToken() = refresh
        override fun clientId() = CLIENT_ID
        override fun signOut() {
            signedOut.incrementAndGet()
            refresh = null
        }
    }

    private fun accessAnswer(token: String, expires: Long = 86400) =
        MockResponse().setResponseCode(200).setBody("""{"access_token":"$token","token_type":"Bearer","expires_in":$expires}""")

    inner class FakeHa : WebSocketListener() {
        val received = CopyOnWriteArrayList<JsonObject>()

        @Volatile var socket: WebSocket? = null

        @Volatile var authToken: String? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = Json.parseToJsonElement(text).jsonObject
            received += m
            val type = m["type"]!!.jsonPrimitive.content
            if (type == "auth") {
                val tk = m["access_token"]!!.jsonPrimitive.content
                authToken = tk
                if (tk in validAccess) {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                } else {
                    webSocket.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
                    webSocket.close(1000, null)
                }
                return
            }
            val id = m["id"]!!.jsonPrimitive.int
            val case = when (type) {
                "portico/version" -> "version: phone"
                "portico/doors" -> "doors: enrolled phone"
                "portico/push/register" -> "push register: ok"
                "portico/calls/subscribe" -> "calls subscribe: result, then one snapshot per door"
                "portico/content/subscribe" -> "content subscribe: enrolled phone"
                "portico/content/get" -> "content get: enrolled phone, no profile yet"
                else -> null
            }
            if (case == null) {
                webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")
            } else if (type == "portico/content/subscribe") {
                WsContractFile.frames(case, id).take(1).forEach { webSocket.send(it) }
            } else {
                WsContractFile.frames(case, id).forEach { webSocket.send(it) }
            }
        }

        fun types(): List<String> = received.map { it["type"]!!.jsonPrimitive.content }
    }

    @BeforeEach
    fun up() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/token" -> {
                    tokenCalls += request
                    tokenAnswers.poll() ?: MockResponse().setResponseCode(500)
                }
                "/api/websocket" -> {
                    upgrades.incrementAndGet()
                    val ha = FakeHa()
                    sockets += ha
                    MockResponse().withWebSocketUpgrade(ha)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start(java.net.InetAddress.getLoopbackAddress(), 0)
    }

    @AfterEach
    fun down() {
        client?.let { c ->
            c.shutdown()
            waitFor("client stopped") { c.state == SignalingClient.State.STOPPED }
        }
        sockets.forEach { runCatching { it.socket?.close(1001, null) } }
        server.shutdown()
    }

    private val log = HaLog { level, msg -> logs += "$level $msg" }
    private fun haUrl() = server.url("/").toString()

    private fun tokens() = AccessTokens(store, RefreshClient(OkHttpPost()), { haUrl() }, { mono.get() })

    private fun legacyConfig() = object : HaConnectionConfig {
        override val haUrl = haUrl()
        override val accessToken: String? = LONG_LIVED
    }

    private fun loginConfig(t: AccessTokens) = object : HaConnectionConfig {
        override val haUrl = haUrl()
        override val accessToken: String? = null
        override val login: AccessTokens = t
    }

    private fun newClient(cfg: HaConnectionConfig, blocking: Executor? = null, signedOut: () -> Unit = {}): SignalingClient {
        val link = PorticoLink(
            RegistrationMachine(RegistrationConfig(INSTALLATION, PROJECT, "0.5.11-alpha"), InMemoryRegistrationStore(value = RegValue(RegKind.FID, REG)), { System.currentTimeMillis() }, log),
            DoorsCache(InMemoryDoorsCacheStore()),
            { },
            object : RegistrationSource {
                override fun requestFid() {}
                override fun requestToken() {}
            },
            object : PorticoLink.Listener {
                override fun onRegistrationStatus(status: RegStatus) { statuses += status }
                override fun onContractMismatch(mismatch: ContractMismatch) {}
            },
            log,
        )
        val backoff = Backoff(baseMs = 50, maxMs = 200) { it }
        return (
            if (blocking != null) SignalingClient(cfg, link, OkHttpConnector(), backoff, log, blocking = blocking, onSignedOut = signedOut)
            else SignalingClient(cfg, link, OkHttpConnector(), backoff, log, onSignedOut = signedOut)
            ).also { client = it }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what; state=${client?.state}; logs:\n${logs.joinToString("\n")}")
    }

    // ---- the OLD path ---------------------------------------------------------------------------------

    @Test
    fun `GOLDEN - a phone with only the long-lived token connects as it always did and makes no refresh traffic`() {
        validAccess += LONG_LIVED
        val neverUsed = Executor { throw AssertionError("the refresh executor must not be used on the old path") }
        val c = newClient(legacyConfig(), blocking = neverUsed)
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        waitFor("the connect sequence") { "portico/content/get" in sockets.single().types() }
        assertEquals(
            listOf("auth", "portico/version", "portico/doors", "portico/push/register", "portico/calls/subscribe", "portico/content/subscribe", "portico/content/get"),
            sockets.single().types(),
            "the same frames, in the same order, as before DEV-544",
        )
        assertEquals(LONG_LIVED, sockets.single().authToken, "the auth frame carries the stored token verbatim")
        assertEquals(0, tokenCalls.size, "no request to /auth/token")
        assertEquals(1, server.requestCount, "one request in all: the WebSocket upgrade")
        assertEquals(1, c.attempts)
    }

    @Test
    fun `GOLDEN - the old path still stops for good on auth_invalid, as before`() {
        // LONG_LIVED is not in validAccess: HA refuses it
        val c = newClient(legacyConfig())
        c.start()
        waitFor("auth rejected") { c.state == SignalingClient.State.AUTH_REJECTED }
        Thread.sleep(400)
        assertEquals(1, upgrades.get())
        assertEquals(0, tokenCalls.size, "a refused long-lived token is never 'refreshed'")
        assertEquals(1, c.attempts)
    }

    @Test
    fun `GOLDEN - the old path with no token is NOT_CONFIGURED and sends nothing`() {
        val cfg = object : HaConnectionConfig {
            override val haUrl = haUrl()
            override val accessToken: String? = null
        }
        val c = newClient(cfg)
        c.start()
        waitFor("not configured") { c.state == SignalingClient.State.NOT_CONFIGURED }
        assertEquals(0, server.requestCount)
    }

    // ---- the login ---------------------------------------------------------------------------------------

    @Test
    fun `no access token yet - one refresh, then the socket authenticates with it and registers push as ever`() {
        tokenAnswers += accessAnswer("ACC-1")
        validAccess += "ACC-1"
        val c = newClient(loginConfig(tokens()))
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        waitFor("the connect sequence") { "portico/content/get" in sockets.single().types() }
        assertEquals("ACC-1", sockets.single().authToken, "the websocket auth message uses the access token exactly as ha_token was used")
        assertEquals(
            listOf("auth", "portico/version", "portico/doors", "portico/push/register", "portico/calls/subscribe", "portico/content/subscribe", "portico/content/get"),
            sockets.single().types(),
            "after a re-enrol the existing push/register flow runs on the first auth_ok",
        )
        waitFor("registered") { statuses.lastOrNull() is RegStatus.Registered }
        assertEquals(1, tokenCalls.size)
        val call = tokenCalls.single()
        assertEquals("POST", call.method)
        assertEquals("grant_type=refresh_token&refresh_token=$REFRESH&client_id=https%3A%2F%2Fportico.invalid%2Fphone", call.body.readUtf8())
    }

    @Test
    fun `a fresh access token (the one the redemption returned) connects with no refresh at all`() {
        validAccess += "ACC-SEEDED"
        val t = tokens().also { it.seed("ACC-SEEDED", 86400) }
        val c = newClient(loginConfig(t))
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        assertEquals(0, tokenCalls.size)
        assertEquals("ACC-SEEDED", sockets.single().authToken)
    }

    @Test
    fun `a token with under 5 minutes left is refreshed before the connect, 5 minutes exactly is not`() {
        validAccess += "ACC-NEW"
        validAccess += "ACC-OLD"
        // exactly 5:00 left: used as is
        val t1 = tokens().also { it.seed("ACC-OLD", 300) }
        val c1 = newClient(loginConfig(t1))
        c1.start()
        waitFor("connected") { c1.state == SignalingClient.State.CONNECTED }
        assertEquals("ACC-OLD", sockets.last().authToken)
        assertEquals(0, tokenCalls.size)
        c1.shutdown()
        waitFor("stopped") { c1.state == SignalingClient.State.STOPPED }
        // 4:59.999 left: refreshed first
        mono.addAndGet(1)
        tokenAnswers += accessAnswer("ACC-NEW")
        val c2 = newClient(loginConfig(t1))
        c2.start()
        waitFor("connected again") { c2.state == SignalingClient.State.CONNECTED && sockets.size >= 2 }
        assertEquals(1, tokenCalls.size)
        assertEquals("ACC-NEW", sockets.last().authToken)
    }

    @Test
    fun `invalid_grant is SIGNED_OUT - one request, no socket, no retry, and a push wake does not restart it`() {
        tokenAnswers += MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        val signedOut = AtomicInteger()
        val c = newClient(loginConfig(tokens()), signedOut = { signedOut.incrementAndGet() })
        c.start()
        waitFor("signed out") { c.state == SignalingClient.State.SIGNED_OUT }
        Thread.sleep(500) // ten backoff periods
        c.wake()
        Thread.sleep(200)
        assertEquals(SignalingClient.State.SIGNED_OUT, c.state)
        assertEquals(1, tokenCalls.size, "never a retry storm")
        assertEquals(0, upgrades.get(), "no socket was opened")
        assertEquals(1, signedOut.get())
        assertEquals(1, store.signedOut.get(), "the stored login was forgotten")
        assertNull(store.refresh)
    }

    @Test
    fun `an unreachable refresh is a retry on the ordinary backoff, and the login is kept`() {
        tokenAnswers += MockResponse().setResponseCode(503)
        tokenAnswers += MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request"}""")
        tokenAnswers += accessAnswer("ACC-LATE")
        validAccess += "ACC-LATE"
        val c = newClient(loginConfig(tokens()))
        c.start()
        waitFor("connected after the retries") { c.state == SignalingClient.State.CONNECTED }
        assertEquals(3, tokenCalls.size)
        assertEquals("ACC-LATE", sockets.single().authToken)
        assertEquals(0, store.signedOut.get(), "503 and invalid_request never sign the phone out")
        assertEquals(REFRESH, store.refresh)
    }

    @Test
    fun `auth_invalid on a fresh-looking token - one refresh and one reconnect, then it works`() {
        tokenAnswers += accessAnswer("ACC-REVOKED")
        tokenAnswers += accessAnswer("ACC-GOOD")
        validAccess += "ACC-GOOD" // ACC-REVOKED is refused by the socket
        val c = newClient(loginConfig(tokens()))
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        assertEquals(2, tokenCalls.size)
        assertEquals(2, upgrades.get())
        assertEquals(listOf("ACC-REVOKED", "ACC-GOOD"), sockets.map { it.authToken })
    }

    @Test
    fun `auth_invalid twice is AUTH_REJECTED - exactly two refreshes and two sockets, never a loop`() {
        repeat(6) { tokenAnswers += accessAnswer("ACC-BAD-$it") }
        val c = newClient(loginConfig(tokens()))
        c.start()
        waitFor("auth rejected") { c.state == SignalingClient.State.AUTH_REJECTED }
        Thread.sleep(600)
        assertEquals(SignalingClient.State.AUTH_REJECTED, c.state)
        assertEquals(2, tokenCalls.size)
        assertEquals(2, upgrades.get())
    }

    @Test
    fun `a socket that outlives its access token stays up, and the next connect after it drops refreshes first`() {
        validAccess += "ACC-DAY1"
        validAccess += "ACC-DAY2"
        tokenAnswers += accessAnswer("ACC-DAY2")
        val t = tokens().also { it.seed("ACC-DAY1", 86400) }
        val c = newClient(loginConfig(t))
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        // 24 hours and a minute pass with the socket open: the client does nothing about it
        mono.addAndGet(24 * 3_600_000L + 60_000L)
        Thread.sleep(300)
        assertEquals(SignalingClient.State.CONNECTED, c.state)
        assertEquals(0, tokenCalls.size, "no refresh while the socket is simply open")
        // the connection drops; the reconnect finds an expired token and refreshes before it connects
        sockets.first().socket!!.close(1001, "HA restarting")
        waitFor("reconnected with a new token") { sockets.size == 2 && c.state == SignalingClient.State.CONNECTED }
        assertEquals(1, tokenCalls.size)
        assertEquals("ACC-DAY2", sockets.last().authToken)
    }

    @Test
    fun `a pause while the refresh is out opens no socket`() {
        tokenAnswers += accessAnswer("ACC-1").setBodyDelay(600, TimeUnit.MILLISECONDS)
        validAccess += "ACC-1"
        val c = newClient(loginConfig(tokens()))
        c.start()
        waitFor("refreshing") { tokenCalls.size == 1 }
        c.pause()
        waitFor("paused") { c.state == SignalingClient.State.PAUSED }
        Thread.sleep(900)
        assertEquals(0, upgrades.get(), "the answer arrived after the pause and was ignored")
        assertEquals(SignalingClient.State.PAUSED, c.state)
    }

    @Test
    fun `a Setup save during the refresh makes the older answer harmless`() {
        tokenAnswers += accessAnswer("ACC-OLD").setBodyDelay(500, TimeUnit.MILLISECONDS)
        tokenAnswers += accessAnswer("ACC-NEW")
        validAccess += "ACC-OLD"
        validAccess += "ACC-NEW"
        val t = tokens()
        val c = newClient(loginConfig(t))
        c.start()
        waitFor("first refresh out") { tokenCalls.size == 1 }
        c.reconnect()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        Thread.sleep(800)
        assertEquals(SignalingClient.State.CONNECTED, c.state)
        assertTrue(upgrades.get() <= 2, "at most one socket per generation, got ${upgrades.get()}")
    }

    @Test
    fun `no token, refresh or access, reaches the log`() {
        tokenAnswers += accessAnswer("ACC-LOGSECRET")
        validAccess += "ACC-LOGSECRET"
        val c = newClient(loginConfig(tokens()))
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        waitFor("registered") { statuses.lastOrNull() is RegStatus.Registered }
        tokenAnswers += MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        val text = logs.joinToString("\n") + c.toString()
        for (secret in listOf("ACC-LOGSECRET", REFRESH, CLIENT_ID)) assertFalse(text.contains(secret), "the log leaks $secret")
    }

    @Test
    fun `a login with no address is NOT_CONFIGURED, and asks nobody`() {
        val t = tokens()
        val cfg = object : HaConnectionConfig {
            override val haUrl: String? = null
            override val accessToken: String? = null
            override val login: AccessTokens = t
        }
        val c = newClient(cfg)
        c.start()
        waitFor("not configured") { c.state == SignalingClient.State.NOT_CONFIGURED }
        assertEquals(0, server.requestCount)
    }
}
