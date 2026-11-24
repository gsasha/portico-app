package com.meda.portico.core.ha

import com.meda.portico.core.ha.WsContract.FRONT
import com.meda.portico.core.ha.WsContract.FRONT_REF
import com.meda.portico.core.ha.WsContract.GATE
import com.meda.portico.core.ha.WsContract.GATE_REF
import com.meda.portico.core.ha.WsContract.HA_TOKEN
import com.meda.portico.core.ha.WsContract.ID_CALLS
import com.meda.portico.core.ha.WsContract.ID_CONTENT_GET
import com.meda.portico.core.ha.WsContract.ID_CONTENT_SUBSCRIBE
import com.meda.portico.core.ha.WsContract.ID_DOORS
import com.meda.portico.core.ha.WsContract.ID_REGISTER
import com.meda.portico.core.ha.WsContract.ID_VERSION
import com.meda.portico.core.ha.WsContract.REGISTRATION
import com.meda.portico.core.ha.WsContract.REGISTRATION_ROTATED
import com.meda.portico.core.ha.WsContract.REG_ID
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The protocol core end to end over a scripted fake transport: what the recorded contract does
 * NOT contain (HA's own handshake and errors, the contract mismatch, reconnects, malformed and
 * interleaved frames, a door appearing or leaving while connected, the no-leak rule). Every case
 * the contract does contain is replayed by [WsContractReplayTest]; frames here come from the file
 * where it has them ([WsContract.reply]) and are built inline where it does not.
 *
 * A connection's command ids: 1 `portico/version`, 2 `portico/doors`, 3 `portico/push/register`,
 * 4 `portico/calls/subscribe`, 5 `portico/content/subscribe`, 6 `portico/content/get`.
 */
class PorticoLinkTest {

    class FakeTransport : WsTransport {
        val sent = mutableListOf<String>()
        var closedWith: String? = null
        override fun send(text: String): Boolean = if (closedWith == null) sent.add(text) else false
        override fun close(code: Int, reason: String) { closedWith = reason }
        fun json(i: Int): JsonObject = Json.parseToJsonElement(sent[i]).jsonObject
        fun types(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content }
    }

    private val cfg = RegistrationConfig(WsContract.INSTALLATION, WsContract.PROJECT, WsContract.APP_VERSION)
    private val regStore = InMemoryRegistrationStore(value = RegValue(RegKind.FID, REGISTRATION))
    private val doorStore = InMemoryDoorsCacheStore()
    private val logs = mutableListOf<String>()
    private val log = HaLog { level, msg -> logs += "$level $msg" }
    private val calls = mutableListOf<CallEvent>()
    private val statuses = mutableListOf<RegStatus>()
    private val mismatches = mutableListOf<ContractMismatch>()
    private var rejected: String? = null
    private var now = 0L

    private val listener = object : PorticoLink.Listener {
        override fun onAuthRejected(message: String?) { rejected = message }
        override fun onRegistrationStatus(status: RegStatus) { statuses += status }
        override fun onContractMismatch(mismatch: ContractMismatch) { mismatches += mismatch }
    }
    private val source = object : RegistrationSource {
        override fun requestFid() {}
        override fun requestToken() {}
    }
    private var sink = CallStateSink { calls += it }
    private val talks = mutableListOf<TalkStateEvent>()
    private var talkHook: (TalkStateEvent) -> Unit = { talks += it }

    private val link by lazy {
        PorticoLink(RegistrationMachine(cfg, regStore, { now }, log), DoorsCache(doorStore), { sink.onCallEvent(it) }, source, listener, log, talkSink = { talkHook(it) })
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    // Inline frames: shapes the recorded contract does not hold.
    private fun err(id: Int, code: String, message: String) =
        """{"id":$id,"type":"result","success":false,"error":{"code":"$code","message":"$message"}}"""

    private fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"

    /** A contract-3 live `call_state_changed` event on subscription [id]. */
    private fun live(id: Int, doorId: String, callId: String?, state: String, prev: String = "idle") =
        """{"id":$id,"type":"event","event":{"type":"call_state_changed","door_id":"$doorId","call_id":${q(callId)},"state":"$state","prev_state":"$prev"}}"""

    private fun snapshot(id: Int, doorId: String, callId: String?, state: String) =
        """{"id":$id,"type":"event","event":{"type":"call_state_changed","snapshot":true,"door_id":"$doorId","call_id":${q(callId)},"state":"$state"}}"""

    private fun registerOk(id: Int) = WsContract.reply("push register: ok", id)
    private fun callsSubscribeOk(id: Int) = WsContract.reply("calls subscribe: result, then one snapshot per door", id)

    private fun versionResult(id: Int, contract: Int) =
        """{"id":$id,"type":"result","success":true,"result":{"contract":$contract,"ui_version":1,"providers":[]}}"""

    private fun doorsResult(id: Int, vararg doors: Pair<String, String>) =
        """{"id":$id,"type":"result","success":true,"result":{"doors":[${doors.joinToString(",") { """{"door_id":"${it.first}","door_ref":"${it.second}","endpoint_id":"fake:${it.first}","name":"N","driver":"fake","capabilities":{},"camera_entity_id":null,"available":true,"away_media":false}""" }}]}}"""

    /** Opens a socket and answers HA's auth; returns the transport, `portico/version` just sent. */
    private fun authenticate(): FakeTransport {
        val t = FakeTransport()
        link.onSocketOpen(t, HA_TOKEN)
        assertTrue(t.sent.isEmpty(), "the client waits for auth_required")
        link.onSocketText(WsContract.AUTH_REQUIRED)
        assertEquals(json("""{"type":"auth","access_token":"$HA_TOKEN"}"""), t.json(0))
        link.onSocketText(WsContract.AUTH_OK)
        assertEquals(json("""{"id":1,"type":"portico/version"}"""), t.json(1), "the version is the first thing a connection says")
        return t
    }

    /** connect -> auth_required -> auth -> auth_ok -> portico/version -> the recorded result -> portico/doors -> the recorded result. */
    private fun connect(): FakeTransport {
        val t = authenticate()
        link.onSocketText(WsContract.reply("version: phone", ID_VERSION))
        assertEquals(json("""{"id":2,"type":"portico/doors"}"""), t.json(2))
        WsContract.frames("doors: enrolled phone", ID_DOORS).forEach(link::onSocketText)
        return t
    }

    private val sessionTypes = listOf(
        "auth", Handshake.VERSION, Handshake.DOORS, PushCommands.REGISTER, Handshake.CALLS_SUBSCRIBE, Handshake.CONTENT_SUBSCRIBE, ContentCommands.GET,
    )

    @Test
    fun `a connection says version, doors, register, the two subscriptions and the document, in that order`() {
        val t = connect()
        assertEquals(sessionTypes, t.types())
        assertEquals(ID_REGISTER, t.json(3)["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(ID_CALLS, t.json(4)["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(ID_CONTENT_SUBSCRIBE, t.json(5)["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(ID_CONTENT_GET, t.json(6)["id"]!!.jsonPrimitive.content.toInt())
        assertTrue(link.isReady)
    }

    @Test
    fun `auth_invalid - rejected, socket closed, nothing else sent`() {
        val t = FakeTransport()
        link.onSocketOpen(t, "wrong-token")
        link.onSocketText(WsContract.AUTH_REQUIRED)
        link.onSocketText(WsContract.AUTH_INVALID)
        assertEquals("Invalid access token or password", rejected)
        assertTrue(link.authRejected)
        assertEquals("auth_invalid", t.closedWith)
        assertEquals(listOf("auth"), t.types())
        assertFalse(link.isReady)
    }

    // ---- the contract mismatch (R7-05): a retrying state of ONE connection -------------------

    @Test
    fun `a server on contract 2 is a mismatch - nothing registered, socket closed to retry, the app says why`() {
        val t = authenticate()
        link.onSocketText(versionResult(1, 2))
        assertEquals(listOf("auth", "portico/version"), t.types(), "no doors, no register, no subscription")
        assertEquals("contract_mismatch", t.closedWith)
        assertEquals(listOf(ContractMismatch(2, "contract")), mismatches)
        assertEquals(ContractMismatch(2, "contract"), link.contractMismatch)
        assertFalse(link.isReady, "never CONNECTED: the client stays in its backoff loop")
        assertFalse(link.authRejected, "and it is not an auth rejection, which is terminal")
        assertEquals(
            "This Home Assistant runs a different Portico version (server contract 2, app needs 3); retrying.",
            mismatches.single().userText,
        )
        assertTrue(statuses.none { it is RegStatus.Registered || it is RegStatus.Sent })
        assertTrue(logs.any { it.contains("contract mismatch: server 2") })
    }

    @Test
    fun `a server that cannot answer portico version is a mismatch with no number`() {
        val t = authenticate()
        link.onSocketText(err(1, "unknown_command", "Unknown command."))
        assertEquals(ContractMismatch(null, "unknown_command"), link.contractMismatch)
        assertEquals("contract_mismatch", t.closedWith)
        assertEquals(
            "This Home Assistant runs a different Portico version (server contract unknown, app needs 3); retrying.",
            mismatches.single().userText,
        )
    }

    @Test
    fun `a malformed or failed version result is a mismatch too - the app cannot confirm contract 3`() {
        for (frame in listOf(
            """{"id":1,"type":"result","success":true,"result":null}""",
            """{"id":1,"type":"result","success":true,"result":{"contract":"3"}}""",
            """{"id":1,"type":"result","success":true,"result":{"ui_version":1}}""",
            err(1, "home_assistant_error", "boom"),
        )) {
            val own = PorticoLinkTest()
            val t = own.authenticate()
            own.link.onSocketText(frame)
            assertEquals("contract_mismatch", t.closedWith, frame)
            assertEquals(1, t.sent.size - 1, frame)
            assertTrue(own.mismatches.isNotEmpty(), frame)
        }
    }

    @Test
    fun `a newer contract is a mismatch as well - only 3 is spoken`() {
        authenticate()
        link.onSocketText(versionResult(1, 4))
        assertEquals(ContractMismatch(4, "contract"), link.contractMismatch)
    }

    @Test
    fun `the mismatch is cleared when the socket closes - the next connect asks again and heals`() {
        val t1 = authenticate()
        link.onSocketText(versionResult(1, 2))
        link.onSocketClosed("closed by us")
        assertNull(link.contractMismatch, "per connection: onSocketClosed clears it")
        assertFalse(link.authRejected)
        val t2 = connect() // the restarted server now speaks contract 3
        assertEquals(sessionTypes, t2.types(), "the next connection re-sent portico/version and went on")
        assertNull(link.contractMismatch)
        link.onSocketText(registerOk(ID_REGISTER))
        assertEquals(RegStatus.Registered(REG_ID, false), link.registrationStatus)
        assertEquals(2, t1.sent.size)
    }

    @Test
    fun `a mismatch registers nothing even when Firebase hands over a value meanwhile`() {
        val t = authenticate()
        link.onSocketText(versionResult(1, 2))
        link.onRegistrationValue(RegValue(RegKind.FID, REGISTRATION_ROTATED))
        link.onSocketClosed("closed")
        link.onRegistrationValue(RegValue(RegKind.FID, REGISTRATION))
        assertTrue(t.types().none { it == PushCommands.REGISTER }, t.types().toString())
    }

    @Test
    fun `the version reply is the connection's ready point - between auth_ok and the reply it is not ready`() {
        val t = authenticate()
        assertFalse(link.isReady, "auth_ok alone is not CONNECTED: a mismatch must never flicker through it")
        link.onSocketText(WsContract.reply("version: phone", 1))
        assertTrue(link.isReady, "contract 3 confirmed")
        assertFalse(t.types().contains(PushCommands.REGISTER), "but nothing is registered before the doors are in")
        WsContract.frames("doors: enrolled phone", 2).forEach(link::onSocketText)
        assertTrue(t.types().contains(PushCommands.REGISTER))
    }

    @Test
    fun `the mismatch is reported once per connection`() {
        authenticate()
        link.onSocketText(versionResult(1, 2))
        link.onSocketText(versionResult(1, 2)) // a duplicate of the same answer: the command is no longer pending
        assertEquals(1, mismatches.size)
    }

    // ---- registration, ids and frames ---------------------------------------------------------

    @Test
    fun `unknown_command (an integration without push) is a server without push, not retried`() {
        val t = connect()
        link.onSocketText(err(ID_REGISTER, "unknown_command", "Unknown command."))
        assertEquals(RegStatus.ServerWithoutPush, link.registrationStatus)
        assertEquals(7, t.sent.size, "no retry on the same connection (auth, version, doors, register, two subscriptions, content get)")
    }

    @Test
    fun `app_too_old on register is shown as an update the app, and is not retried on this connection`() {
        val t = connect()
        link.onSocketText(WsContract.reply("push register: app 0.4.3 is too old", ID_REGISTER))
        val s = link.registrationStatus as RegStatus.Refused
        assertEquals("app_too_old", s.code)
        assertEquals("This Home Assistant needs a newer Portico app. Update the app.", s.userText)
        assertEquals(7, t.sent.size)
        link.onRegistrationValue(RegValue(RegKind.FID, REGISTRATION_ROTATED))
        assertEquals(7, t.sent.size, "a refused app does not hammer the server")
    }

    @Test
    fun `reconnect re-registers, ids restart at 1`() {
        val t1 = connect()
        link.onSocketText(registerOk(ID_REGISTER))
        link.onSocketClosed("server went away")
        assertFalse(link.isReady)
        val t2 = connect()
        assertEquals("portico/push/register", t2.json(3)["type"]!!.jsonPrimitive.content)
        assertEquals(3, t2.json(3)["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(REGISTRATION, t2.json(3)["registration"]!!.jsonPrimitive.content)
        assertEquals(7, t1.sent.size)
    }

    @Test
    fun `a live FID rotation re-registers with the new value`() {
        val t = connect()
        link.onSocketText(registerOk(ID_REGISTER))
        link.onRegistrationValue(RegValue(RegKind.FID, REGISTRATION_ROTATED))
        assertEquals("portico/content/get", t.json(6)["type"]!!.jsonPrimitive.content)
        assertEquals("fid", t.json(7)["kind"]!!.jsonPrimitive.content)
        assertEquals(REGISTRATION_ROTATED, t.json(7)["registration"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed frames anywhere do not derail the session`() {
        val t = FakeTransport()
        link.onSocketOpen(t, HA_TOKEN)
        val junk = listOf("", "garbage", "{}", """{"type":"result","id":"x"}""", "[]", """{"id":99,"type":"result","success":true}""")
        junk.forEach(link::onSocketText)
        link.onSocketText(WsContract.AUTH_REQUIRED)
        junk.forEach(link::onSocketText)
        link.onSocketText(WsContract.AUTH_OK)
        junk.forEach(link::onSocketText)
        link.onSocketText(WsContract.reply("version: phone", ID_VERSION))
        junk.forEach(link::onSocketText)
        WsContract.frames("doors: enrolled phone", ID_DOORS).forEach(link::onSocketText)
        junk.forEach(link::onSocketText)
        link.onSocketText(registerOk(ID_REGISTER))
        assertEquals(RegStatus.Registered(REG_ID, false), link.registrationStatus)
        assertEquals(sessionTypes, t.types())
    }

    @Test
    fun `id correlation under interleaved events and out-of-order results`() {
        val t = connect()
        // The calls subscription's result and an event arrive BEFORE register's result; an unknown id is ignored.
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ringing"))
        link.onSocketText(registerOk(99))
        link.onSocketText(live(77, GATE, "zzz", "ringing"))
        assertEquals(RegStatus.Sent, link.registrationStatus)
        link.onSocketText("[${live(ID_CALLS, GATE, "c1", "answered", "ringing")},${registerOk(ID_REGISTER)}]")
        assertEquals(RegStatus.Registered(REG_ID, false), link.registrationStatus)
        assertEquals(listOf(CallEvent.Ringing(GATE, GATE_REF, "c1"), CallEvent.Answered(GATE, GATE_REF, "c1")), calls)
        assertEquals(7, t.sent.size)
    }

    @Test
    fun `a call event on the wrong subscription is not a call`() {
        connect()
        link.onSocketText(live(ID_CONTENT_SUBSCRIBE, GATE, "c1", "ringing"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a refused calls subscription is logged and its id forgotten`() {
        connect()
        link.onSocketText(err(ID_CALLS, "unauthorized", "no"))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ringing"))
        assertTrue(calls.isEmpty())
        assertTrue(logs.any { it.contains("portico/calls/subscribe refused: unauthorized") })
    }

    // ---- the doors cache -------------------------------------------------------------------

    @Test
    fun `a door that shows itself in a snapshot while connected makes the app ask for the doors again`() {
        val t = connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        assertEquals(7, t.sent.size)
        link.onSocketText(snapshot(ID_CALLS, "new.example", null, "idle"))
        assertEquals(json("""{"id":7,"type":"portico/doors"}"""), t.json(7))
        assertEquals(CallEvent.NoLiveCall("new.example", null), calls.last(), "its first event was before the cache knew it")
        link.onSocketText(doorsResult(7, GATE to GATE_REF, FRONT to FRONT_REF, "new.example" to "d-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals(setOf(GATE_REF, FRONT_REF, "d-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), link.doors.knownDoorRefs())
        assertEquals(8, t.sent.size, "a refresh does not re-register")
        link.onSocketText(live(ID_CALLS, "new.example", "n1", "ringing"))
        assertEquals(CallEvent.Ringing("new.example", "d-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "n1"), calls.last(), "now translated")
    }

    @Test
    fun `an unknown door is asked about once per connection and never while a refresh is in flight`() {
        val t = connect()
        link.onSocketText(live(ID_CALLS, "ghost", "g1", "ringing"))
        link.onSocketText(live(ID_CALLS, "ghost", "g1", "answered", "ringing"))
        link.onSocketText(live(ID_CALLS, "ghost2", "g2", "ringing"))
        assertEquals(1, t.types().count { it == Handshake.DOORS } - 1, "one refresh, the second unknown door waits for it")
        link.onSocketText(doorsResult(7, GATE to GATE_REF))
        link.onSocketText(live(ID_CALLS, "ghost", "g3", "ringing"))
        assertEquals(1, t.types().count { it == Handshake.DOORS } - 1, "ghost was already asked about on this connection")
        link.onSocketText(live(ID_CALLS, "ghost2", "g4", "ringing"))
        assertEquals(2, t.types().count { it == Handshake.DOORS } - 1, "a different unknown door is asked about")
    }

    @Test
    fun `a door asked about too often stops making refreshes`() {
        val t = connect()
        var next = 7
        repeat(20) { i ->
            link.onSocketText(snapshot(ID_CALLS, "ghost-$i", null, "idle"))
            link.onSocketText(doorsResult(next++, GATE to GATE_REF))
        }
        assertEquals(8, t.types().count { it == Handshake.DOORS } - 1, "bounded per connection")
    }

    @Test
    fun `a door that left asks for the doors once, so the cache forgets it`() {
        val t = connect()
        link.onSocketText(snapshot(ID_CALLS, FRONT, null, "unavailable"))
        assertEquals(CallEvent.NoLiveCall(FRONT, FRONT_REF), calls.last())
        assertEquals(json("""{"id":7,"type":"portico/doors"}"""), t.json(7))
        link.onSocketText(doorsResult(7, GATE to GATE_REF))
        assertEquals(setOf(GATE_REF), link.doors.knownDoorRefs())
        link.onSocketText(snapshot(ID_CALLS, FRONT, null, "unavailable"))
        assertEquals(8, t.sent.size, "it is gone from the cache already: nothing to ask")
    }

    @Test
    fun `a known door's events never ask for the doors`() {
        val t = connect()
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ringing"))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ended", "ringing"))
        link.onSocketText(snapshot(ID_CALLS, FRONT, null, "idle"))
        assertEquals(7, t.sent.size)
    }

    @Test
    fun `devices_changed is gone - the event does nothing`() {
        val t = connect()
        link.onSocketText("""{"id":$ID_CALLS,"type":"event","event":{"type":"devices_changed"}}""")
        assertEquals(7, t.sent.size)
    }

    @Test
    fun `doors failing keeps the persisted cache and still registers and subscribes`() {
        doorStore.save(listOf(Door(GATE, GATE_REF)))
        val t = authenticate()
        link.onSocketText(WsContract.reply("version: phone", ID_VERSION))
        link.onSocketText(err(ID_DOORS, "unauthorized", "no"))
        assertEquals(sessionTypes, t.types())
        assertEquals(setOf(GATE_REF), link.doors.knownDoorRefs())
    }

    @Test
    fun `a throwing sink never breaks the link`() {
        sink = CallStateSink { throw IllegalStateException("boom") }
        connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ringing"))
        link.onSocketText(registerOk(ID_REGISTER))
        assertEquals(RegStatus.Registered(REG_ID, false), link.registrationStatus)
    }

    @Test
    fun `push status needs a connection`() {
        assertFalse(link.requestPushStatus(), "not connected")
        connect()
        assertTrue(link.requestPushStatus())
    }

    @Test
    fun `no log line or toString ever holds a registration or the HA token, and the doors cache holds six fields at most`() {
        val t = connect()
        link.onSocketText(registerOk(ID_REGISTER))
        WsContract.frames("calls subscribe: result, then one snapshot per door", ID_CALLS).forEach(link::onSocketText)
        link.onRegistrationValue(RegValue(RegKind.FID, REGISTRATION_ROTATED))
        link.onSocketText(WsContract.reply("push register: placeholder registration", 7))
        link.setPushEnabled(false)
        link.onSocketClosed("test")
        val rendered = buildList {
            addAll(logs)
            add(link.toString())
            addAll(statuses.map { it.toString() })
            addAll(calls.map { it.toString() })
            add(regStore.value.toString())
            addAll(t.sent.drop(1).flatMap { HaProtocol.parse(it) }.map { it.toString() })
            addAll(WsContract.frames("doors: enrolled phone", ID_DOORS).flatMap { HaProtocol.parse(it) }.map { it.toString() })
            add(link.doors.all.toString())
            add(DoorCodec.encode(doorStore.saved))
            add(HaProtocol(HA_TOKEN).toString())
        }.joinToString("\n")
        assertTrue(logs.isNotEmpty())
        for (secret in listOf(REGISTRATION, REGISTRATION_ROTATED, HA_TOKEN)) {
            assertFalse(rendered.contains(secret), "leaked a secret into:\n$rendered")
        }
        assertTrue(rendered.contains(Mask.short(REGISTRATION)), "the short form is what is logged")
        // what the cache keeps: ids, driver, name; not the capabilities, the camera entity or the away-media flag
        val saved = DoorCodec.encode(doorStore.saved)
        for (notKept in listOf("capabilities", "camera_entity_id", "away_media", "available", "camera.gate")) {
            assertFalse(saved.contains(notKept), notKept)
        }
        assertNull(rejected)
    }

    // ---- P3-C: talk_state on the calls stream ------------------------------------------------------------------

    private fun talkFrame(id: Int, body: String) = """{"id":$id,"type":"event","event":{"type":"talk_state",$body}}"""

    @Test
    fun `a talk_state event reaches the talk sink only, never the call sink`() {
        connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"in_call""""))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"ended","reason":"busy""""))
        assertEquals(
            listOf(
                TalkStateEvent(GATE, "out-0123456789abcdef", TalkWireState.IN_CALL, null),
                TalkStateEvent(GATE, "out-0123456789abcdef", TalkWireState.ENDED, "busy"),
            ),
            talks,
        )
        assertTrue(calls.isEmpty(), "a talk_state is not call state: $calls")
    }

    @Test
    fun `a talk_state on another subscription is not a talk state`() {
        connect()
        link.onSocketText(talkFrame(ID_CONTENT_SUBSCRIBE, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"in_call""""))
        assertTrue(talks.isEmpty())
    }

    @Test
    fun `a malformed talk_state is ignored - unknown state, no door, no call id, an id that is not out-`() {
        connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"ringing""""))
        link.onSocketText(talkFrame(ID_CALLS, """"call_id":"out-0123456789abcdef","state":"ended""""))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","state":"ended""""))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"gso-1","state":"ended""""))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":null,"state":"ended""""))
        assertTrue(talks.isEmpty(), "$talks")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a dialled call's call_state_changed through the link reaches no sink`() {
        connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        link.onSocketText(live(ID_CALLS, GATE, "out-0123456789abcdef", "ringing"))
        link.onSocketText(snapshot(ID_CALLS, GATE, "out-0123456789abcdef", "ringing"))
        assertTrue(calls.isEmpty(), "$calls")
        assertTrue(talks.isEmpty())
    }

    @Test
    fun `talk_state events between real call events change nothing the call sink sees`() {
        connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ringing"))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"dialing""""))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "answered", "ringing"))
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"ended""""))
        assertEquals(listOf(CallEvent.Ringing(GATE, GATE_REF, "c1"), CallEvent.Answered(GATE, GATE_REF, "c1")), calls)
        assertEquals(2, talks.size)
    }

    @Test
    fun `a talk sink that throws does not stop the stream`() {
        connect()
        link.onSocketText(callsSubscribeOk(ID_CALLS))
        talkHook = { error("boom") }
        link.onSocketText(talkFrame(ID_CALLS, """"door_id":"$GATE","call_id":"out-0123456789abcdef","state":"in_call""""))
        link.onSocketText(live(ID_CALLS, GATE, "c1", "ringing"))
        assertEquals(listOf<CallEvent>(CallEvent.Ringing(GATE, GATE_REF, "c1")), calls)
        assertTrue(logs.any { it.contains("talk sink failed: IllegalStateException") }, "$logs")
    }
}
