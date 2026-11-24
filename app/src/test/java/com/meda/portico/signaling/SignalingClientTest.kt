package com.meda.portico.signaling

import com.meda.portico.core.ha.Backoff
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.ContractMismatch
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.fcm.OutcomeDispatcher
import com.meda.portico.fcm.PushReceiver
import com.meda.portico.ring.AndroidOutcomeHandler
import com.meda.portico.ring.RingPoster
import com.meda.portico.ring.RingState
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * SignalingClient over a real OkHttp WebSocket against MockWebServer on loopback, playing HA.
 * After HA's auth handshake (not part of the contract), every Portico frame the fake sends is
 * RECORDED: core's vendored `ws_v3.json` ([WsContractFile]), renumbered to the id the app
 * used. Core's `WsContractReplayTest` replays every case; this drives the real socket client
 * through register-on-connect, an error code, unregister, subscribe and the door name.
 */
class SignalingClientTest {

    companion object {
        // The phone's own configuration: literals, checked against the file's request.
        const val INSTALLATION = "inst-0f1e2d3c4b5a"
        const val PROJECT = "portico-test"
        const val REG = "FAKE-fcm-registration-0001"
        const val REG_ROTATED = "FAKE-fcm-registration-0002"
        const val HA_TOKEN = "fake-jwt-header.ha-long-lived-access-token.SIGNATURE-0123456789"
        const val REG_ID = "00000000000000000000000000000001"
        const val GATE = "http://192.168.1.7:8091"
        const val GATE_REF = "d-355c9550e870bcbabb4daac53cb4320f"
        const val FRONT = "vto-front.example"
        const val FRONT_REF = "d-34e65cd09db1499f5c992b6b77346362"
        const val FRONT_CALL = "front-call-1"
        const val CALLS_CASE = "calls subscribe: result, then one snapshot per door"
    }

    /** One fake HA connection: HA's handshake, then the contract's recorded frames ([registerCase] for register). */
    inner class FakeHa(
        private val registerCase: String = "push register: ok",
        /** The contract this fake server speaks; anything but 3 makes `portico/version` answer that number. */
        private val contract: Int = 3,
    ) : WebSocketListener() {
        init {
            fakes += this
        }

        val received = CopyOnWriteArrayList<JsonObject>()

        @Volatile
        var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = Json.parseToJsonElement(text).jsonObject
            received += m
            val type = m["type"]!!.jsonPrimitive.content
            if (type == "auth") {
                if (m["access_token"]!!.jsonPrimitive.content == HA_TOKEN) {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                } else {
                    webSocket.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
                    webSocket.close(1000, null)
                }
                return
            }
            val id = m["id"]!!.jsonPrimitive.int
            val case = when (type) {
                "portico/version" -> if (contract == 3) "version: phone" else null
                "portico/doors" -> "doors: enrolled phone"
                "portico/push/register" -> registerCase
                "portico/calls/subscribe" -> CALLS_CASE
                "portico/content/subscribe" -> "content subscribe: enrolled phone"
                "portico/content/get" -> "content get: enrolled phone, no profile yet"
                "portico/push/unregister" -> "push unregister: not_found"
                "portico/push/status" -> "push status: the caller's own rows"
                else -> null
            }
            if (type == "portico/version" && contract != 3) {
                webSocket.send("""{"id":$id,"type":"result","success":true,"result":{"contract":$contract,"ui_version":1,"providers":[]}}""")
            } else if (case == null) {
                webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")
            } else if (type == "portico/content/subscribe") {
                // only the result: its recorded events make the app fetch its document again, which the tests below do not want here
                WsContractFile.frames(case, id).take(1).forEach { webSocket.send(it) }
            } else {
                WsContractFile.frames(case, id).forEach { webSocket.send(it) }
            }
        }

        fun types(): List<String> = received.map { it["type"]!!.jsonPrimitive.content }
        fun registrations(): List<String> =
            received.filter { it["type"]!!.jsonPrimitive.content == "portico/push/register" }.map { it["registration"]!!.jsonPrimitive.content }
    }

    private val fakes = CopyOnWriteArrayList<FakeHa>()
    private lateinit var server: MockWebServer
    private var client: SignalingClient? = null
    private val logs = CopyOnWriteArrayList<String>()
    private val statuses = CopyOnWriteArrayList<RegStatus>()
    private val calls = CopyOnWriteArrayList<CallEvent>()
    private val mismatches = CopyOnWriteArrayList<ContractMismatch>()
    private val regStore = InMemoryRegistrationStore(value = RegValue(RegKind.FID, REG))
    private val log = HaLog { level, msg -> logs += "$level $msg" }
    private lateinit var link: PorticoLink

    /** The recorded subscribe frames, as the app's call sink must see them. */
    private val recordedCalls = listOf(
        CallEvent.NoLiveCall(GATE, GATE_REF),
        CallEvent.NoLiveCall(FRONT, FRONT_REF),
        CallEvent.Ringing(FRONT, FRONT_REF, FRONT_CALL),
        CallEvent.Ended(FRONT, FRONT_REF, FRONT_CALL, inferred = false),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(java.net.InetAddress.getLoopbackAddress(), 0)
    }

    @AfterEach
    fun tearDown() {
        client?.let { c ->
            c.shutdown()
            waitFor("client stopped") { c.state == SignalingClient.State.STOPPED }
        }
        fakes.forEach { runCatching { it.socket?.close(1001, null) } } // the server side, so MockWebServer can shut down
        server.shutdown()
    }

    private fun config(token: String? = HA_TOKEN, url: String? = server.url("/").toString()) = object : HaConnectionConfig {
        override val haUrl = url
        override val accessToken = token
        override fun toString() = "Config(url=$haUrl)"
    }

    private fun newClient(cfg: HaConnectionConfig = config(), clockMs: () -> Long = { System.nanoTime() / 1_000_000 }): SignalingClient {
        link = PorticoLink(
            RegistrationMachine(RegistrationConfig(INSTALLATION, PROJECT, "0.5.0-alpha"), regStore, { System.currentTimeMillis() }, log),
            DoorsCache(InMemoryDoorsCacheStore()),
            { calls += it },
            object : RegistrationSource {
                override fun requestFid() {}
                override fun requestToken() {}
            },
            object : PorticoLink.Listener {
                override fun onRegistrationStatus(status: RegStatus) { statuses += status }
                override fun onContractMismatch(mismatch: ContractMismatch) { mismatches += mismatch }
            },
            log,
        )
        return SignalingClient(cfg, link, OkHttpConnector(), Backoff(baseMs = 50, maxMs = 200) { it }, log, clockMs = clockMs).also { client = it }
    }

    private fun upgrade(ha: FakeHa) = server.enqueue(MockResponse().withWebSocketUpgrade(ha))

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what; logs:\n${logs.joinToString("\n")}")
    }

    @Test
    fun `connect, auth, version, doors, register, subscribe over a real WebSocket`() {
        val ha = FakeHa()
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("registered") { statuses.lastOrNull() == RegStatus.Registered(REG_ID, false) }
        assertEquals("/api/websocket", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        waitFor("subscribe and the content fetch") { "portico/content/get" in ha.types() }
        assertEquals(
            listOf("auth", "portico/version", "portico/doors", "portico/push/register", "portico/calls/subscribe", "portico/content/subscribe", "portico/content/get"),
            ha.types(),
        )
        assertEquals(WsContractFile.request("content get: enrolled phone, no profile yet"), JsonObject(ha.received[6] - "id"), "the content request the server recorded")
        val reg = ha.received[3]
        assertEquals(3, reg["id"]!!.jsonPrimitive.int)
        assertEquals(WsContractFile.request("push register: ok"), JsonObject(reg - "id"), "the request the server recorded, field by field")
        assertEquals(WsContractFile.request("version: phone"), JsonObject(ha.received[1] - "id"))
        assertEquals(WsContractFile.request("doors: enrolled phone"), JsonObject(ha.received[2] - "id"))
        assertEquals(WsContractFile.request(CALLS_CASE), JsonObject(ha.received[4] - "id"))
        assertEquals(WsContractFile.request("content subscribe: enrolled phone"), JsonObject(ha.received[5] - "id"))
        waitFor("the call events") { calls.size == recordedCalls.size }
        assertEquals(recordedCalls, calls.toList())
        assertEquals(SignalingClient.State.CONNECTED, c.state)
    }

    @Test
    fun `not-connected time counts from start until auth_ok, and from a drop through the retries`() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000)
        val first = FakeHa()
        upgrade(first)
        val c = newClient(clockMs = { now.get() })
        now.set(21_000)
        assertEquals(20_000, c.notConnectedForMs(), "never connected yet: counts from creation")
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        assertEquals(0, c.notConnectedForMs())
        // Every retry fails (HTTP 503, no upgrade): CONNECTING <-> WAITING_TO_RETRY.
        repeat(20) { server.enqueue(MockResponse().setResponseCode(503)) }
        now.set(30_000)
        first.socket!!.close(1001, "HA unreachable")
        waitFor("a few retries") { c.attempts >= 3 }
        now.set(46_000)
        assertTrue(c.state != SignalingClient.State.CONNECTED)
        assertEquals(16_000, c.notConnectedForMs(), "counted from the drop, not restarted by each retry")
    }

    @Test
    fun `auth_invalid stops reconnecting`() {
        upgrade(FakeHa())
        upgrade(FakeHa())
        val c = newClient(config(token = "a-wrong-token"))
        c.start()
        waitFor("auth rejected") { c.state == SignalingClient.State.AUTH_REJECTED }
        Thread.sleep(400) // several backoff periods
        assertEquals(1, server.requestCount)
        assertEquals(1, c.attempts)
    }

    @Test
    fun `a dropped socket reconnects with backoff and registers again`() {
        val first = FakeHa()
        val second = FakeHa()
        upgrade(first)
        upgrade(second)
        val c = newClient()
        c.start()
        waitFor("first register") { first.registrations().size == 1 }
        waitFor("first subscribe") { "portico/calls/subscribe" in first.types() }
        first.socket!!.close(1001, "HA restarting")
        waitFor("second register") { second.registrations().size == 1 }
        assertEquals(listOf(REG), second.registrations())
        assertEquals(2, c.attempts)
    }

    @Test
    fun `a failed upgrade is retried`() {
        server.enqueue(MockResponse().setResponseCode(502))
        val ha = FakeHa()
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("register after a retry") { ha.registrations().size == 1 }
        assertTrue(logs.any { it.contains("HTTP 502") })
    }

    @Test
    fun `rotation re-registers on the live socket`() {
        val ha = FakeHa()
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("registered") { statuses.lastOrNull() is RegStatus.Registered }
        c.post { it.onRegistrationValue(RegValue(RegKind.FID, REG_ROTATED)) }
        waitFor("rotated register") { ha.registrations() == listOf(REG, REG_ROTATED) }
        assertEquals(REG_ROTATED, regStore.value!!.value)
    }

    @Test
    fun `push off unregisters and not_found is success`() {
        val ha = FakeHa()
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("registered") { statuses.lastOrNull() is RegStatus.Registered }
        c.post { it.setPushEnabled(false) }
        waitFor("unregister") { "portico/push/unregister" in ha.types() }
        waitFor("pending cleared") { !regStore.pendingUnregister && statuses.lastOrNull() == RegStatus.PushOff }
    }

    @Test
    fun `a terminal register error is shown and not retried`() {
        val ha = FakeHa("push register: another Firebase project")
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("refused") { statuses.lastOrNull() is RegStatus.Refused }
        assertEquals("project_mismatch", (statuses.last() as RegStatus.Refused).code)
        Thread.sleep(200)
        assertEquals(1, ha.registrations().size)
        assertEquals(SignalingClient.State.CONNECTED, c.state)
    }

    @Test
    fun `no ha_url or token - no connection at all`() {
        val c = newClient(config(token = null))
        c.start()
        waitFor("not configured") { c.state == SignalingClient.State.NOT_CONFIGURED }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nothing logged or rendered holds the registration, the HA token or a device secret`() {
        val ha = FakeHa()
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("registered") { statuses.lastOrNull() is RegStatus.Registered }
        c.post { it.onRegistrationValue(RegValue(RegKind.FID, REG_ROTATED)) }
        waitFor("rotated") { ha.registrations().size == 2 }
        c.stop()
        waitFor("stopped") { c.state == SignalingClient.State.STOPPED }
        val rendered = (logs + statuses.map { it.toString() } + calls.map { it.toString() } + c.toString() + regStore.value.toString()).joinToString("\n")
        assertTrue(logs.size > 5)
        for (secret in listOf(REG, HA_TOKEN)) assertFalse(rendered.contains(secret), "leaked into:\n$rendered")
    }

    @Test
    fun `the door name HA records reaches the cache and the ring notification`() {
        val ha = FakeHa()
        upgrade(ha)
        val c = newClient()
        c.start()
        waitFor("doors cached") { link.doors.all.size == 2 }
        assertEquals(WsContractFile.doorIds, link.doors.all.map { it.doorId })
        assertEquals("Vehicle gate", link.doors.nameFor(GATE_REF))
        assertEquals("Front door", link.doors.nameFor(FRONT_REF))

        // The notification's text source is AppGraph's `doorName = { doors.nameFor(it) }`.
        val posted = CopyOnWriteArrayList<String>()
        val poster = object : RingPoster {
            override fun post(spec: NotificationSpec, slot: Int?, doorName: String?) { posted += "${spec.kind} $doorName" }
            override fun cancelRing(slot: Int) {}
            override fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long) {}
            override fun cancelBackstop(slot: Int) {}
        }
        val t = 1_760_000_000_000L
        val ringState = RingState(FakeSharedPreferences())
        val handler = AndroidOutcomeHandler(
            poster, ringState, DoorSlots(ringState), { PermissionState() }, { true }, { link.doors.nameFor(it) }, { t }, log,
        )
        val receiver = PushReceiver(PrefsCallStore(FakeSharedPreferences()), link.doors::knownDoorRefs, OutcomeDispatcher(handler, {}, { true }, log), log)
        receiver.onMessage(
            mapOf("pv" to "2", "kind" to "ring", "door_ref" to GATE_REF, "call_id" to "gate-call-9", "seq" to "1", "t" to "$t", "exp" to "${t + 45_000}"),
            t,
        )
        assertEquals(listOf("RING Vehicle gate"), posted.toList())
    }

    // ---- R7-05: a server on another contract is a retrying state of the connection ----------------

    @Test
    fun `a server on contract 2 keeps the client retrying - never connected, nothing registered, never auth-rejected`() {
        val old1 = FakeHa(contract = 2)
        val old2 = FakeHa(contract = 2)
        upgrade(old1)
        upgrade(old2)
        val c = newClient()
        c.start()
        waitFor("a second attempt: the client retries") { c.attempts >= 2 }
        waitFor("the mismatch") { mismatches.size >= 2 }
        assertEquals(listOf("auth", "portico/version"), old1.types(), "version, and nothing after it")
        assertEquals(listOf("auth", "portico/version"), old2.types(), "the retry asks again")
        assertEquals(ContractMismatch(2, "contract"), mismatches.first())
        assertTrue(c.state != SignalingClient.State.CONNECTED && c.state != SignalingClient.State.AUTH_REJECTED, "state ${c.state}")
        assertTrue(old1.registrations().isEmpty() && old2.registrations().isEmpty())
        assertTrue(statuses.none { it is RegStatus.Registered })
    }

    @Test
    fun `the app installed before the restart heals - the retry finds contract 3 and registers`() {
        val old = FakeHa(contract = 2)
        val upgraded = FakeHa()
        upgrade(old)
        upgrade(upgraded)
        val c = newClient()
        c.start()
        waitFor("registered after the server's restart") { statuses.lastOrNull() == RegStatus.Registered(REG_ID, false) }
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
        assertEquals(1, mismatches.size)
        assertEquals(listOf(REG), upgraded.registrations())
        assertNull(link.contractMismatch, "cleared with the connection that found it")
    }

    // ---- PR-J: the network URL policy is re-checked before every connect ---------------------

    @Test
    fun `PR-J - plain http to the loopback MockWebServer is a private host and connects`() {
        assertTrue(server.url("/").toString().startsWith("http://"))
        upgrade(FakeHa())
        val c = newClient()
        c.start()
        waitFor("connected") { c.state == SignalingClient.State.CONNECTED }
    }

    @Test
    fun `PR-J - a refused stored URL is never opened and the log holds no URL, user-info or token`() {
        val port = server.port
        for (bad in listOf(
            "http://portico-user:hunter2-pw@127.0.0.1:$port/", // user-info, even to a private host
            "http://ha.example.com:$port/", // plain http to a public name: never resolved, never opened
            "http://127.1:$port/", // a non-canonical IPv4 form
        )) {
            logs.clear()
            val c = newClient(config(url = bad))
            c.start()
            waitFor("url refused: $bad") { c.state == SignalingClient.State.URL_REFUSED }
            Thread.sleep(100)
            assertEquals(0, c.attempts, "no connection attempt for $bad")
            val rendered = (logs + c.toString()).joinToString("\n")
            assertTrue(logs.any { "refused by the network policy" in it }, rendered)
            for (leak in listOf("hunter2", "portico-user", "example.com", "127.1", HA_TOKEN, "http://")) {
                assertFalse(rendered.contains(leak), "leaked $leak into:\n$rendered")
            }
            c.shutdown()
            waitFor("stopped") { c.state == SignalingClient.State.STOPPED }
            client = null
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `PR-J - the connector refuses a policy-refused URL itself, without the URL in the error`() {
        val listener = object : com.meda.portico.core.ha.WsListener {
            override fun onOpen() = throw AssertionError("opened")
            override fun onText(text: String) = Unit
            override fun onClosed(reason: String) = Unit
        }
        for (bad in listOf("ws://ha.example.com:${server.port}/api/websocket", "ws://u:hunter2@127.0.0.1:${server.port}/api/websocket")) {
            val e = org.junit.jupiter.api.assertThrows<OkHttpConnector.RefusedUrlException> { OkHttpConnector().open(bad, listener) }
            assertFalse("example" in e.message!! || "hunter2" in e.message!!, e.message)
        }
        assertEquals(0, server.requestCount)
    }
}
