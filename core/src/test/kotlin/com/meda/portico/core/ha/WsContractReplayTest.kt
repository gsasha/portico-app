package com.meda.portico.core.ha

import com.meda.portico.core.call.AnswerCommands
import com.meda.portico.core.call.AnswerFailure
import com.meda.portico.core.call.AudioProfile
import com.meda.portico.core.call.HouseCallEvent
import com.meda.portico.core.call.HouseCommands
import com.meda.portico.core.call.HouseEventSink
import com.meda.portico.core.call.PlaceFailure
import com.meda.portico.core.connlog.ConnLogCommands
import com.meda.portico.core.connlog.ConnLogReplies
import com.meda.portico.core.connlog.ConnLogReply
import com.meda.portico.core.content.CallScreen
import com.meda.portico.core.content.CallerModel
import com.meda.portico.core.content.CameraTile
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.content.GateCall
import com.meda.portico.core.live.CallerLive
import com.meda.portico.core.panel.FrontReason
import com.meda.portico.core.panel.HeartbeatOutcome
import com.meda.portico.core.panel.PanelCallWire
import com.meda.portico.core.panel.PanelFacts
import com.meda.portico.core.panel.PanelHeartbeatCommands
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LivePlan
import com.meda.portico.core.ring.LinkState
import com.meda.portico.core.content.HouseGate
import com.meda.portico.core.call.MediaCommands
import com.meda.portico.core.call.PlaceCommands
import com.meda.portico.core.ha.PushCommands.ErrorKind
import com.meda.portico.core.ha.PushCommands.Outcome
import com.meda.portico.core.ha.WsContract.APP_VERSION
import com.meda.portico.core.ha.WsContract.DOORS
import com.meda.portico.core.ha.WsContract.FRONT
import com.meda.portico.core.ha.WsContract.FRONT_CALL
import com.meda.portico.core.ha.WsContract.FRONT_ENDPOINT
import com.meda.portico.core.ha.WsContract.FRONT_REF
import com.meda.portico.core.ha.WsContract.GATE
import com.meda.portico.core.ha.WsContract.GATE_ENDPOINT
import com.meda.portico.core.ha.WsContract.GATE_REF
import com.meda.portico.core.ha.WsContract.HA_TOKEN
import com.meda.portico.core.ha.WsContract.INSTALLATION
import com.meda.portico.core.ha.WsContract.PHONE_ENDPOINT
import com.meda.portico.core.ha.WsContract.PROJECT
import com.meda.portico.core.ha.WsContract.REGISTRATION
import com.meda.portico.core.ha.WsContract.REGISTRATION_ROTATED
import com.meda.portico.core.ha.WsContract.REG_ID
import com.meda.portico.core.push.MiniJson
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Replays EVERY case of the vendored `ws_v3.json` (frames recorded from portico-ha's real handlers, contract 3)
 * through the app's protocol core: [PorticoLink] over [HaProtocol], the [RegistrationMachine], [PushCommands],
 * [Handshake], the [DoorsCache], [ContentCommands] and the answer path's builders and parsers.
 *
 * For each case: the request the app builds must equal the file's request field by field (ids normalised: `_meta`
 * says they are per connection, so the `id` is dropped from the comparison and the file's reply frames are renumbered
 * to the id the app used), and the file's server frames must give the app's expected typed result. Expectations are
 * written out here, not derived from the file, so a drifted file turns this red.
 *
 * Cases the app deliberately never sends (a blank, placeholder or schema-invalid request; another caller's command) are
 * checked both ways: the app refuses them before the wire, or never builds them, and the server's recorded answer still
 * maps to the right status if HA ever said it. Where the file's request differs from the app's on purpose, the
 * expectation names the field: `answer` records `client_kind: "app"` (the app sends `"browser"`: it carries its audio
 * over WebRTC to the SFU, like the card), `hangup`, `media_open` and `push/register` records leave out an optional field the
 * app always sends (`reason`, `codec`, `app_version`).
 */
class WsContractReplayTest {

    // ---- harness ---------------------------------------------------------------------------

    private class Wire : WsTransport {
        val sent = mutableListOf<String>()
        var closed: String? = null
        override fun send(text: String): Boolean = sent.add(text)
        override fun close(code: Int, reason: String) { closed = "$code $reason" }

        @Suppress("UNCHECKED_CAST")
        fun frames(): List<Map<String, Any?>> = sent.map { MiniJson.parse(it) as Map<String, Any?> }
        fun ofType(type: String) = frames().filter { it["type"] == type }
    }

    /** One phone: a fresh link, machine, stores and a scripted socket. */
    private class Phone(
        config: RegistrationConfig = config(),
        value: RegValue? = RegValue(RegKind.FID, REGISTRATION),
        initialDoors: List<Door> = emptyList(),
    ) {
        val regStore = InMemoryRegistrationStore(value = value)
        val doorsStore = InMemoryDoorsCacheStore(initialDoors)
        val calls = mutableListOf<CallEvent>()
        val talks = mutableListOf<TalkStateEvent>()
        val houses = mutableListOf<HouseCallEvent>()
        var pushStatus: Outcome? = null
        var content: ContentCommands.Outcome? = null
        val runs = mutableListOf<Pair<String, ContentCommands.Outcome>>()
        val snaps = mutableListOf<Pair<String, ContentCommands.Outcome>>()
        val mismatches = mutableListOf<ContractMismatch>()
        var fidRequests = 0
        val logs = mutableListOf<String>()
        val wire = Wire()
        val link = PorticoLink(
            RegistrationMachine(config, regStore, { 0L }, { l, m -> logs += "$l $m" }),
            DoorsCache(doorsStore),
            { calls += it },
            object : RegistrationSource {
                override fun requestFid() { fidRequests++ }
                override fun requestToken() {}
            },
            object : PorticoLink.Listener {
                override fun onPushStatus(outcome: Outcome) { pushStatus = outcome }
                override fun onContent(outcome: ContentCommands.Outcome) { content = outcome }
                override fun onContentRun(actionId: String, outcome: ContentCommands.Outcome) { runs += actionId to outcome }
                override fun onSnapshot(cameraId: String, outcome: ContentCommands.Outcome) { snaps += cameraId to outcome }
                override fun onContractMismatch(mismatch: ContractMismatch) { mismatches += mismatch }
            },
            { l, m -> logs += "$l $m" },
            TalkStateSink { talks += it },
            HouseEventSink { houses += it },
        )

        /** HA's handshake, then the app's `portico/version` and `portico/doors` answered from the file. */
        fun connect(doors: WsContract.Case = WsContract.DOORS_PHONE, version: WsContract.Case = WsContract.VERSION_PHONE): Phone {
            link.onSocketOpen(wire, HA_TOKEN)
            link.onSocketText(WsContract.AUTH_REQUIRED)
            link.onSocketText(WsContract.AUTH_OK)
            replay(version)
            if (version.receive.first()["success"] == true) replay(doors)
            return this
        }

        /** The app's latest request of [case]'s type must equal the file's; then HA's frames are fed. */
        fun replay(case: WsContract.Case) {
            val mine = wire.ofType(case.type).lastOrNull() ?: throw AssertionError("$case: the app sent no ${case.type}; sent ${wire.sent.size} frames")
            val id = (mine["id"] as Long).toInt()
            assertTrue(id >= 1, "$case: a positive command id")
            assertEquals(case.request.keys, mine.keys - "id", "$case: request fields")
            for ((k, v) in case.request) assertEquals(v, mine[k], "$case: request field '$k'")
            answer(case, id)
        }

        /** Feeds [case]'s server frames as the reply to the app's command [id] (no request check). */
        fun answer(case: WsContract.Case, id: Int) = case.receiveFor(id).forEach(link::onSocketText)

        /**
         * Feeds a case that mixes streams: its `event` frames go to the subscription [eventId] (the id of the
         * `calls/subscribe` they belong to), its `result` to the command [resultId].
         */
        fun answerSplit(case: WsContract.Case, eventId: Int, resultId: Int) = case.receive.forEach {
            link.onSocketText(WsContract.encode(it + ("id" to (if (it["type"] == "event") eventId else resultId).toLong())))
        }

        /** Answers the app's latest command of [case]'s type with [case]'s frames, whatever it sent. */
        fun answerLast(case: WsContract.Case) = answer(case, lastId(case.type))

        /** Answers the app's latest register with [case]'s frames, whatever it sent. */
        fun answerRegister(case: WsContract.Case) = answer(case, lastId(PushCommands.REGISTER))

        fun lastId(type: String) = (wire.ofType(type).last()["id"] as Long).toInt()

        /** What the app's latest command of [type] put on the wire, without its connection-local `id`. */
        fun sent(type: String): Map<String, Any?> = wire.ofType(type).last() - "id"

        /** Sends [body] as a call-path command through the link and answers it with [case]'s frames; returns HA's result. */
        fun callReply(case: WsContract.Case, body: JsonObject): HaInbound.Result {
            var got: HaInbound.Result? = null
            assertTrue(link.sendCall(case.type, body) { got = it }, "$case: sent")
            answerLast(case)
            return got ?: throw AssertionError("$case: no reply reached the call")
        }

        val status get() = link.registrationStatus
    }

    companion object {
        fun config(
            installationId: String = INSTALLATION,
            project: String? = PROJECT,
            packageName: String = PushCommands.PACKAGE,
        ) = RegistrationConfig(installationId, project, APP_VERSION, packageName)

        private fun case(name: String) = WsContract.case(name)

        private const val CALL = "gate-call-1"
        private val HOUSE_TYPES = setOf(HouseCommands.ANSWER, HouseCommands.DECLINE, HouseCommands.HANGUP, HouseCommands.PRESENCE, HouseCommands.MEDIA_OPEN)
        private const val TALK_CALL_ID = "out-0123456789abcdef"

        // DEV-713: the admin commands the app never sends (S0). The live commands are the app's own since S1 (`LiveCommands`).
        private const val HOST_CANDIDATE = "candidate:1 1 UDP 2130706431 192.168.1.5 8555 typ host"
        private val SERVER_ONLY_TYPES = setOf(
            "portico/door_status", "portico/door_settings/get", "portico/door_settings/set", "portico/ui_settings/get", "portico/ui_settings/set",
            "portico/claims/get", "portico/claims/set", "portico/house_calls/set",
        )

        private fun result(r: Map<String, Any?>): HaInbound.Result = HaProtocol.parse(WsContract.encode(r)).single() as HaInbound.Result
        private fun result(c: WsContract.Case): HaInbound.Result = result(c.receive.first())
    }

    // ---- the per-case expectations: every case in the file must be listed here -----------

    private fun registerSent(name: String, phone: Phone, expected: RegStatus) {
        phone.connect()
        phone.replay(case(name))
        assertEquals(expected, phone.status, name)
        assertEquals(1, phone.wire.ofType(PushCommands.REGISTER).size, "$name: no retry on the same connection")
    }

    /** The app never sends [name]'s request; HA's recorded answer to it still maps to [expected]. */
    private fun replyMapsTo(name: String, expected: RegStatus): Phone {
        val p = Phone().connect()
        p.answerRegister(case(name))
        assertEquals(expected, p.status, "$name: status from the server's answer")
        return p
    }

    private fun unregister(name: String): Phone {
        val p = Phone().connect()
        p.answerRegister(case("push register: ok"))
        p.link.setPushEnabled(false)
        p.replay(case(name))
        return p
    }

    /** A command another caller sends (a kiosk card, the panel, a test client): the app's code never builds it. */
    private fun serverOnly(name: String) {
        val c = case(name)
        assertFalse(c.type in WsContract.appCommands, "${c.type} is in app_commands_v3.txt, so the app sends it and '$name' needs a real expectation")
    }

    private fun versionOk(name: String) {
        assertEquals(Handshake.Version.Ok(1), Handshake.parseVersion(result(case(name))), name)
    }

    private fun doorsParse(name: String) {
        assertEquals(DOORS, Handshake.parseDoors(result(case(name))), name)
    }

    private fun answerRefused(name: String, kind: AnswerFailure.Kind, code: String, dismissRing: Boolean, retryable: Boolean) {
        val p = Phone().connect()
        val r = p.callReply(case(name), AnswerCommands.answer(case(name).str("door_id"), case(name).str("call_id")))
        val claim = AnswerCommands.parseClaim(r) as AnswerCommands.Claim.Refused
        assertEquals(kind, claim.failure.kind, name)
        assertEquals(code, claim.failure.code, name)
        assertEquals(dismissRing, claim.failure.dismissRing, "$name: dismissRing")
        assertEquals(retryable, claim.failure.retryable, "$name: retryable")
        // the request: the app's, except for client_kind (the file's client is a test client of kind "app")
        assertEquals(case(name).request - "client_kind", p.sent(AnswerCommands.ANSWER) - "client_kind", name)
        assertEquals(AnswerCommands.CLIENT_KIND, p.sent(AnswerCommands.ANSWER)["client_kind"])
    }

    private fun offerRefused(name: String, kind: AnswerFailure.Kind, code: String) {
        val p = Phone().connect()
        val c = case(name)
        val r = p.callReply(c, AnswerCommands.webrtcOffer(c.str("call_id"), c.send["door_id"] as? String ?: GATE, c.str("sdp")))
        val offer = AnswerCommands.parseOffer(r) as AnswerCommands.Offer.Refused
        assertEquals(kind, offer.failure.kind, name)
        assertEquals(code, offer.failure.code, name)
    }

    private fun mediaOpenRefused(name: String, kind: AnswerFailure.Kind, code: String) {
        val p = Phone().connect()
        val c = case(name)
        val r = p.callReply(c, MediaCommands.open(c.send["door_id"] as? String ?: GATE, c.str("call_id")))
        val opened = MediaCommands.parseOpen(r, AudioProfile.PCMU) as MediaCommands.Opened.Refused
        assertEquals(kind, opened.failure.kind, name)
        assertEquals(code, opened.failure.code, name)
        // the app always names the codec; the file's request leaves the optional key out
        assertEquals(c.request, p.sent(MediaCommands.OPEN) - "codec", name)
        assertEquals(AudioProfile.PCMU.codec, p.sent(MediaCommands.OPEN)["codec"])
    }

    /** A quiet command (uplink frame, best-effort close): HA answers it and the app drops the answer without a fuss. */
    private fun quiet(name: String, body: JsonObject) {
        val p = Phone().connect()
        val c = case(name)
        assertTrue(p.link.sendQuiet(c.type, body))
        assertEquals(c.request, p.sent(c.type), name)
        val before = p.calls.size
        p.answerLast(c)
        assertEquals(before, p.calls.size)
        assertTrue(p.logs.none { it.contains("unknown id") }, "the answer of a quiet command is not an unknown id: ${p.logs}")
    }

    private fun contentGet(name: String): Phone {
        val p = Phone().connect()
        p.replay(case(name))
        return p
    }

    private fun hangupReply(name: String, reason: String?) {
        val p = Phone().connect()
        val c = case(name)
        val r = p.callReply(c, AnswerCommands.hangup(c.str("call_id"), reason ?: "x"))
        assertEquals(c.request, p.sent(AnswerCommands.HANGUP).let { if (reason == null) it - "reason" else it }, name)
        assertEquals(c.receive.first()["success"], r.success, "$name: any reply, even an error, settles a hang-up")
    }

    private val expectations: Map<String, () -> Unit> = linkedMapOf<String, () -> Unit>().also { m ->
        // ---- the handshake ----
        m["version: phone"] = {
            val p = Phone().connect()
            assertTrue(p.link.isReady)
            assertNull(p.link.contractMismatch)
            assertEquals(
                listOf(Handshake.VERSION, Handshake.DOORS, PushCommands.REGISTER, Handshake.CALLS_SUBSCRIBE, Handshake.CONTENT_SUBSCRIBE, ContentCommands.GET),
                p.wire.frames().drop(1).map { it["type"] },
                "version first, then doors, register, the two subscriptions and the phone's own document",
            )
        }
        for (n in listOf("version: screen", "version: user", "version: admin")) m[n] = { versionOk(n) }
        m["doors: enrolled phone"] = {
            val p = Phone().connect(case("doors: enrolled phone"))
            assertEquals(DOORS, p.link.doors.all)
            assertEquals(DOORS, p.doorsStore.saved, "persisted")
            assertEquals("Vehicle gate", p.link.doors.nameFor(GATE_REF), "the door name reaches the cache (ring text source)")
            assertEquals("Front door", p.link.doors.nameFor(FRONT_REF))
            assertEquals(setOf(GATE_REF, FRONT_REF), p.link.doors.knownDoorRefs())
            assertEquals(GATE, p.link.doors.doorIdFor(GATE_REF))
            assertEquals(FRONT_REF, p.link.doors.doorRefFor(FRONT))
            assertEquals(GATE_ENDPOINT, p.link.doors.endpointIdFor(GATE_REF))
            assertEquals(FRONT_ENDPOINT, p.link.doors.endpointIdFor(FRONT_REF))
            assertEquals(DOORS, DoorCodec.decode(DoorCodec.encode(p.doorsStore.saved)))
        }
        m["doors: declared screen"] = { doorsParse("doors: declared screen") }
        m["doors: admin"] = { doorsParse("doors: admin") }
        // O8 (portico-ha #139): an ordinary HA user is answered like anyone else, not refused as unauthorized
        m["doors: ordinary user"] = { doorsParse("doors: ordinary user") }
        for (n in listOf("doors/secrets: not a command", "devices: contract 2's name is gone", "content list: contract 2's command is gone")) {
            m[n] = {
                val c = case(n)
                assertFalse(c.type in WsContract.appCommands, c.type)
                val r = result(c)
                assertEquals("unknown_command", r.error!!.code)
                assertEquals(ErrorKind.UNKNOWN_COMMAND, ErrorKind.of(r.error!!.code))
                assertEquals(ContentCommands.ErrorKind.UNKNOWN_COMMAND, (ContentCommands.parseGet(r) as ContentCommands.Outcome.Failed).kind)
            }
        }

        // ---- commands another caller sends: the app never builds them ----
        for (n in listOf(
            "dial: ordinary user", "dial: declared screen", "dial: admin", "dial: enrolled phone, unknown door", "dial: contract 2's device_id is refused by the schema",
            "open_relay: ordinary user", "open_relay: declared screen", "open_relay: enrolled phone, unknown door",
            "keepalive: the answered call", "keepalive: ordinary user",
            "webrtc_answer: the answered call", "webrtc_answer: ordinary user", "webrtc_ice: the answered call", "webrtc_ice: ordinary user",
            "content document: ordinary user", "content document: declared screen", "content document: admin",
            "content preview: ordinary user", "content preview: admin", "content save: ordinary user", "content save: admin, a stale revision",
            "kiosk hello: a declared screen", "kiosk hello: ordinary user",
            // portico-ha #138+: the editor's camera-info command; no app screen sends it
            "cameras info: admin (attributes, a still's header, an unavailable camera)", "cameras info: admin, a non-camera id is refused",
            "cameras info: admin, more than 20 ids (invalid_format)", "cameras info: enrolled phone", "cameras info: declared screen",
            "cameras info: ordinary user",
        )) m[n] = { serverOnly(n) }

        // ---- hang-up: any reply settles it ----
        m["hangup: ordinary user"] = { hangupReply("hangup: ordinary user", null) }
        m["hangup: nothing owns the call"] = { hangupReply("hangup: nothing owns the call", null) }
        m["hangup: the answered call"] = {
            val c = case("hangup: the answered call")
            // the only record that carries a reason: "hangup", what a call that was answered ends with
            assertEquals(PlaceCommands.REASON_HANGUP, c.str("reason"))
            assertEquals(c.request, mapOf("type" to AnswerCommands.HANGUP) + MiniJson.parse(PlaceCommands.hangup(CALL, PlaceCommands.REASON_HANGUP).toString()) as Map<String, Any?>)
            hangupReply("hangup: the answered call", PlaceCommands.REASON_HANGUP)
        }

        // ---- answer ----
        m["answer: granted"] = {
            val p = Phone().connect()
            val c = case("answer: granted")
            val r = p.callReply(c, AnswerCommands.answer(GATE, CALL))
            // the file's test client is kind "app" and is told `direct`; the phone is kind "browser" and wants `relay`
            assertEquals("app", c.str("client_kind"))
            assertEquals(AnswerCommands.Claim.Granted("direct"), AnswerCommands.parseClaim(r), "a `direct` mode parses; the session refuses anything but relay")
            assertEquals(c.request - "client_kind", p.sent(AnswerCommands.ANSWER) - "client_kind")
        }
        m["answer: nothing is ringing (not_available)"] = {
            answerRefused("answer: nothing is ringing (not_available)", AnswerFailure.Kind.CALL_ENDED, "not_available", dismissRing = true, retryable = false)
        }
        m["answer: ordinary user"] = {
            // O8 (portico-ha #139): the call commands are open to any HA user, so this is granted, not unauthorized
            val p = Phone().connect()
            val c = case("answer: ordinary user")
            val r = p.callReply(c, AnswerCommands.answer(c.str("door_id"), c.str("call_id")))
            assertEquals(AnswerCommands.Claim.Granted("direct"), AnswerCommands.parseClaim(r), "a `direct` mode parses; the session refuses anything but relay")
            assertEquals(c.request - "client_kind", p.sent(AnswerCommands.ANSWER) - "client_kind")
        }
        m["answer: unknown door"] = {
            answerRefused("answer: unknown door", AnswerFailure.Kind.UNKNOWN_DOOR, "unknown_door", dismissRing = false, retryable = false)
        }
        m["answer: the call is taken (already_answered)"] = {
            answerRefused("answer: the call is taken (already_answered)", AnswerFailure.Kind.ALREADY_ANSWERED, "already_answered", dismissRing = true, retryable = false)
        }
        m["answer: the door refuses (refused)"] = {
            answerRefused("answer: the door refuses (refused)", AnswerFailure.Kind.NOT_SUPPORTED, "refused", dismissRing = false, retryable = true)
        }

        // ---- webrtc_offer names the door ----
        m["webrtc_offer: the answered call, the door named"] = {
            val p = Phone().connect()
            val c = case("webrtc_offer: the answered call, the door named")
            val r = p.callReply(c, AnswerCommands.webrtcOffer(CALL, GATE, "FAKE-offer"))
            assertEquals(c.request, p.sent(AnswerCommands.WEBRTC_OFFER))
            assertEquals(AnswerCommands.Offer.Answer("fake-answer-sdp"), AnswerCommands.parseOffer(r))
        }
        m["webrtc_offer: a door that does not own the call"] = { offerRefused("webrtc_offer: a door that does not own the call", AnswerFailure.Kind.OTHER, "unknown_call") }
        m["webrtc_offer: unknown door"] = { offerRefused("webrtc_offer: unknown door", AnswerFailure.Kind.UNKNOWN_DOOR, "unknown_door") }
        m["webrtc_offer: ordinary user"] = {
            // O8 (portico-ha #139): answered, not unauthorized
            val p = Phone().connect()
            val c = case("webrtc_offer: ordinary user")
            val r = p.callReply(c, AnswerCommands.webrtcOffer(c.str("call_id"), c.send["door_id"] as? String ?: GATE, c.str("sdp")))
            assertEquals(AnswerCommands.Offer.Answer("fake-answer-sdp"), AnswerCommands.parseOffer(r))
        }
        m["webrtc_offer: no door (contract 2's shape)"] = {
            val c = case("webrtc_offer: no door (contract 2's shape)")
            assertFalse("door_id" in c.send)
            assertTrue("door_id" in AnswerCommands.webrtcOffer(CALL, GATE, "FAKE-offer"), "the app always names the door")
            assertThrows<IllegalArgumentException> { AnswerCommands.webrtcOffer(CALL, "", "FAKE-offer") }
            val refused = AnswerCommands.parseOffer(result(c)) as AnswerCommands.Offer.Refused
            assertEquals(AnswerFailure.Kind.SERVER_TOO_OLD, refused.failure.kind, "invalid_format reads as a server that does not take the app's shape")
        }

        // ---- away media ----
        m["media_open: enrolled phone, away media off"] = {
            mediaOpenRefused("media_open: enrolled phone, away media off", AnswerFailure.Kind.MEDIA, "away_media_disabled")
        }
        m["media_open: ordinary user"] = { mediaOpenRefused("media_open: ordinary user", AnswerFailure.Kind.OTHER, "not_enrolled") }
        m["media_open: contract 2's device_id is refused by the schema"] = {
            val c = case("media_open: contract 2's device_id is refused by the schema")
            assertTrue("device_id" in c.send && "door_id" !in c.send)
            assertTrue("device_id" !in MediaCommands.open(GATE, CALL) && "door_id" in MediaCommands.open(GATE, CALL))
            assertEquals(AnswerFailure.Kind.SERVER_TOO_OLD, (MediaCommands.parseOpen(result(c), AudioProfile.PCMU) as MediaCommands.Opened.Refused).failure.kind)
        }
        m["media_frame: ordinary user"] = { quiet("media_frame: ordinary user", MediaCommands.frame(CALL, "QUJD")) }
        m["media_frame: enrolled phone, no channel"] = { quiet("media_frame: enrolled phone, no channel", MediaCommands.frame(CALL, "QUJD")) }
        m["media_close: ordinary user"] = { quiet("media_close: ordinary user", MediaCommands.close(CALL)) }
        m["media_close: enrolled phone"] = { quiet("media_close: enrolled phone", MediaCommands.close(CALL)) }

        // ---- push ----
        m["push register: push not configured"] = {
            registerSent("push register: push not configured", Phone(), RegStatus.ServerWithoutPush)
        }
        m["push register: app 0.4.3 is too old"] = {
            // the app never sends it (a 0.5.0 build reports 0.5.0-alpha); if a server ever said it, the phone says so and stops
            val c = case("push register: app 0.4.3 is too old")
            assertEquals("0.4.3-alpha", c.str("app_version"))
            val p = replyMapsTo(c.name, appTooOld(c))
            assertEquals(1, p.wire.ofType(PushCommands.REGISTER).size)
            p.link.onRegistrationValue(RegValue(RegKind.FID, REGISTRATION_ROTATED))
            assertEquals(1, p.wire.ofType(PushCommands.REGISTER).size, "no register storm against a server that refuses the app")
        }
        m["push register: no app version is too old"] = {
            val c = case("push register: no app version is too old")
            assertFalse("app_version" in c.send)
            assertTrue("app_version" in PushCommands.register(INSTALLATION, "fid", REGISTRATION, PROJECT, APP_VERSION), "the app always sends it")
            replyMapsTo(c.name, appTooOld(c))
        }
        m["push register: not an enrolled phone"] = {
            registerSent(
                "push register: not an enrolled phone", Phone(),
                RegStatus.Refused("unauthorized", "only an enrolled Portico phone may do this", RegistrationMachine.TEXT_NOT_ENROLLED),
            )
        }
        m["push register: ok"] = {
            val p = Phone()
            registerSent("push register: ok", p, RegStatus.Registered(REG_ID, false))
            assertEquals(
                listOf(Handshake.VERSION, Handshake.DOORS, PushCommands.REGISTER, Handshake.CALLS_SUBSCRIBE, Handshake.CONTENT_SUBSCRIBE, ContentCommands.GET),
                p.wire.frames().drop(1).map { it["type"] },
            )
        }
        m["push register: rotation (same installation_id, new registration)"] = {
            // A phone in token mode (the FID fallback): its first token registers, then Firebase rotates it.
            val p = Phone(value = RegValue(RegKind.TOKEN, REGISTRATION)).connect()
            p.answerRegister(case("push register: ok"))
            p.link.onRegistrationValue(RegValue(RegKind.TOKEN, REGISTRATION_ROTATED))
            p.replay(case("push register: rotation (same installation_id, new registration)"))
            assertEquals(RegStatus.Registered(REG_ID, true), p.status, "same reg_id, the server kept the switch")
            assertEquals(RegValue(RegKind.TOKEN, REGISTRATION_ROTATED), p.regStore.value)
            assertEquals(2, p.wire.ofType(PushCommands.REGISTER).size)
        }
        m["push register: blank registration"] = { placeholder("push register: blank registration", "   ") }
        m["push register: placeholder registration"] = { placeholder("push register: placeholder registration", "unknown") }
        m["push register: another Firebase project"] = {
            val msg = "this phone uses a different Firebase project than Home Assistant; re-scan the enrollment QR"
            registerSent(
                "push register: another Firebase project", Phone(config(project = "another-project")),
                RegStatus.Refused("project_mismatch", msg, msg),
            )
        }
        m["push register: another package"] = {
            registerSent(
                "push register: another package", Phone(config(packageName = "com.meda.ha_intercom")),
                RegStatus.Refused("package_mismatch", "registrations are accepted for the Portico app only", RegistrationMachine.TEXT_PACKAGE),
            )
        }
        m["push register: invalid_format (unknown kind)"] = {
            assertTrue(RegKind.entries.none { it.wire == "topic" }, "the app has no kind 'topic'")
            assertThrows<IllegalArgumentException> { PushCommands.register(INSTALLATION, "topic", REGISTRATION, PROJECT, APP_VERSION) }
            replyMapsTo("push register: invalid_format (unknown kind)", RegStatus.RetryNextConnect("invalid_format"))
        }
        m["push register: invalid_format (empty installation_id)"] = {
            assertThrows<IllegalArgumentException> { PushCommands.register("", "fid", REGISTRATION, PROJECT, APP_VERSION) }
            val p = Phone(config(installationId = "")).connect()
            assertEquals(emptyList<Any>(), p.wire.ofType(PushCommands.REGISTER), "refused before the wire")
            assertEquals(RegStatus.RetryNextConnect("refused_locally"), p.status)
            replyMapsTo("push register: invalid_format (empty installation_id)", RegStatus.RetryNextConnect("invalid_format"))
        }
        m["push register: invalid_format (missing field)"] = {
            val c = case("push register: invalid_format (missing field)")
            assertFalse("fcm_project_id" in c.send)
            assertTrue("fcm_project_id" in PushCommands.register(INSTALLATION, "fid", REGISTRATION, PROJECT, APP_VERSION), "the app always sends it")
            val p = Phone(config(project = null)).connect()
            assertEquals(emptyList<Any>(), p.wire.ofType(PushCommands.REGISTER), "no fcm block: never registers")
            assertEquals(RegStatus.NoFirebaseConfig, p.status)
            replyMapsTo("push register: invalid_format (missing field)", RegStatus.RetryNextConnect("invalid_format"))
        }
        m["push status: the caller's own rows"] = {
            val p = Phone().connect()
            assertTrue(p.link.requestPushStatus())
            p.replay(case("push status: the caller's own rows"))
            val row = PushCommands.StatusRow(
                regId = REG_ID, installationId = INSTALLATION, name = "Alice's Pixel", kind = "token", state = "never_sent",
                pushEnabled = true, lastResult = null, lastOkAt = null, lastSeenAt = "2026-10-06T10:00:00+00:00",
                registrationUpdatedAt = "2026-10-06T10:00:00+00:00", stale = false,
            )
            assertEquals(Outcome.Status("ok", listOf(row)), p.pushStatus)
        }
        m["push status: ordinary user"] = {
            val p = Phone().connect()
            p.link.requestPushStatus()
            p.replay(case("push status: ordinary user"))
            assertEquals(Outcome.Failed(ErrorKind.UNAUTHORIZED, "unauthorized", "only an enrolled Portico phone may do this"), p.pushStatus)
        }
        m["push status: no rows"] = {
            val p = Phone().connect()
            p.link.requestPushStatus()
            p.replay(case("push status: no rows"))
            assertEquals(Outcome.Status("ok", emptyList()), p.pushStatus)
        }
        m["push unregister: ok"] = {
            val p = unregister("push unregister: ok")
            assertEquals(RegStatus.PushOff, p.status)
            assertFalse(p.regStore.pendingUnregister)
        }
        m["push unregister: not_found"] = {
            val p = unregister("push unregister: not_found")
            assertEquals(RegStatus.PushOff, p.status)
            assertFalse(p.regStore.pendingUnregister, "not_found is success: nothing left to remove")
            assertEquals(1, p.wire.ofType(PushCommands.UNREGISTER).size, "and it is not retried")
        }
        m["push unregister: ordinary user"] = {
            val p = unregister("push unregister: ordinary user")
            assertFalse(p.regStore.pendingUnregister, "an unenrolled phone has no row to remove")
        }

        // ---- the two subscriptions ----
        m["calls subscribe: result, then one snapshot per door"] = {
            val p = Phone().connect()
            p.replay(case("calls subscribe: result, then one snapshot per door"))
            assertEquals(
                listOf(
                    CallEvent.NoLiveCall(GATE, GATE_REF),
                    CallEvent.NoLiveCall(FRONT, FRONT_REF),
                    CallEvent.Ringing(FRONT, FRONT_REF, FRONT_CALL),
                    CallEvent.Ended(FRONT, FRONT_REF, FRONT_CALL, inferred = false),
                    // no `idle` echo follows the end: contract 3 dropped it, and nothing infers an end from one
                ),
                p.calls,
            )
            assertNull(p.link.tracker.followedCall(FRONT))
            assertEquals(1, p.wire.ofType(ContentCommands.GET).size, "a call event never makes the app fetch the document")
            assertEquals(1, p.wire.ofType(Handshake.DOORS).size, "and every door is known: no doors refresh")
        }
        m["calls subscribe: ordinary user"] = {
            val p = Phone().connect()
            p.replay(case("calls subscribe: ordinary user"))
            // O8 (portico-ha #139): subscribed like anyone else; the snapshots arrive
            assertEquals(listOf(CallEvent.NoLiveCall(GATE, GATE_REF), CallEvent.NoLiveCall(FRONT, FRONT_REF)), p.calls)
        }
        m["content subscribe: enrolled phone"] = {
            val p = Phone().connect()
            p.replay(case("content get: enrolled phone, no profile yet")) // the document it holds: rev 665dacb1a218 of ep-alice
            assertEquals(1, p.wire.ofType(ContentCommands.GET).size)
            p.replay(case("content subscribe: enrolled phone"))
            // two events, both with a rev the phone does not hold: it fetches its document after each
            assertEquals(1 + 2, p.wire.ofType(ContentCommands.GET).size)
        }
        m["content subscribe: declared screen"] = {
            val p = Phone().connect()
            p.replay(case("content get: enrolled phone, no profile yet"))
            val before = p.wire.ofType(ContentCommands.GET).size
            p.answerLast(case("content subscribe: declared screen"))
            assertEquals(before, p.wire.ofType(ContentCommands.GET).size, "another endpoint's change is not this phone's")
        }
        m["content subscribe: admin names none (every change)"] = {
            val p = Phone().connect()
            p.replay(case("content get: enrolled phone, no profile yet"))
            val before = p.wire.ofType(ContentCommands.GET).size
            p.answerLast(case("content subscribe: admin names none (every change)"))
            assertEquals(before + 2, p.wire.ofType(ContentCommands.GET).size, "a change with no rev, or for no endpoint, is fetched to be safe")
        }
        for (n in listOf("content subscribe: a phone names another's endpoint", "content subscribe: admin names an unknown endpoint", "content subscribe: ordinary user")) {
            m[n] = {
                val p = Phone().connect()
                val c = case(n)
                assertEquals(Handshake.CONTENT_SUBSCRIBE, c.type)
                assertEquals(ContentCommands.subscribe().toString(), "{}", "the app names no endpoint")
                p.answerLast(c)
                assertTrue(p.logs.any { it.contains("portico/content/subscribe refused: ${(c.receive.first()["error"] as Map<*, *>)["code"]}") })
                assertEquals(1, p.wire.ofType(ContentCommands.GET).size)
            }
        }

        // ---- content ----
        m["content get: enrolled phone, no profile yet"] = {
            val p = contentGet("content get: enrolled phone, no profile yet")
            val profile = (p.content as ContentCommands.Outcome.Profile).profile
            assertEquals("665dacb1a218", profile.rev)
            assertEquals(PHONE_ENDPOINT, profile.endpoint)
            assertEquals("phone", profile.kind)
            assertEquals(listOf("home"), profile.pages.map { it.id })
            assertEquals(listOf(ContentCamera("porch", "Porch", "Gate camera", true, CameraSize.BIG)), profile.cameras, "Home draws the first page")
            assertEquals(listOf("home"), profile.pages.single().blocks!!.map { it.id }, "the server's blocks, one for a migrated page")
            assertEquals(listOf("evening"), profile.actions.map { it.id })
            assertEquals(profile.pages.first().cameras, profile.cameras)
            assertEquals(setOf("kitchen", GATE_ENDPOINT, FRONT_ENDPOINT, "*"), profile.callers.keys)
            assertEquals(listOf("r-3171d89ad005", "r-355c9550e870", "r-34e65cd09db1"), profile.roster.map { it.actionId })
            assertEquals("", profile.phone, "contract 3 names no phone")
        }
        m["content get: ordinary user"] = {
            val p = contentGet("content get: ordinary user")
            assertEquals(ContentCommands.ErrorKind.UNAUTHORIZED, (p.content as ContentCommands.Outcome.Failed).kind)
        }
        m["content get: enrolled phone, a profile"] = {
            val p = contentGet("content get: enrolled phone, a profile")
            val profile = (p.content as ContentCommands.Outcome.Profile).profile
            assertEquals("4822cd6098d2", profile.rev)
            assertEquals(RECORDED_PAGE, profile.pages.single())
            assertEquals(RECORDED_PAGE.cameras, profile.cameras)
            assertEquals(RECORDED_PAGE.actions, profile.actions)
            val gate = profile.callers.getValue(GATE_ENDPOINT)
            assertEquals("Vehicle gate", gate.name)
            assertEquals("door", gate.kind)
            assertEquals(listOf(ContentCamera("c-30102768d913", "Vehicle gate", "Gate camera", true)), gate.cameras, "a caller's camera ids are c-...")
            assertEquals(listOf("open-front", "gate-relay"), gate.ringButtons.map { it.id })
            assertEquals(listOf("open-front", "gate-relay"), gate.callButtons.map { it.id })
            assertEquals(listOf("open-front"), profile.callers.getValue(FRONT_ENDPOINT).ringButtons.map { it.id })
            assertEquals(ContentCaller(null, null, emptyList(), emptyList(), emptyList()), profile.callers.getValue("*"))
            assertEquals(
                RosterEntry("r-3171d89ad005", "kitchen", "dashboard", "Kitchen screen", available = false, reason = "not_available"),
                profile.roster.first(),
            )
        }
        m["content get: a declared screen"] = { screenDocument("content get: a declared screen") }
        m["content get: a declared screen names its own endpoint"] = {
            val c = case("content get: a declared screen names its own endpoint")
            assertEquals(c.request, mapOf("type" to ContentCommands.GET) + (MiniJson.parse(ContentCommands.get("kitchen").toString()) as Map<String, Any?>))
            screenDocument("content get: a declared screen names its own endpoint")
        }
        m["content get: a declared screen names another's endpoint"] = { namedEndpointRefused("content get: a declared screen names another's endpoint") }
        m["content get: a phone names another's endpoint"] = { namedEndpointRefused("content get: a phone names another's endpoint") }
        m["content get: an admin names the phone's endpoint"] = {
            val c = case("content get: an admin names the phone's endpoint")
            assertEquals(c.request, mapOf("type" to ContentCommands.GET) + (MiniJson.parse(ContentCommands.get(PHONE_ENDPOINT).toString()) as Map<String, Any?>))
            val p = Phone().connect()
            p.answer(c, p.lastId(ContentCommands.GET))
            assertEquals(PHONE_ENDPOINT, (p.content as ContentCommands.Outcome.Profile).profile.endpoint)
        }
        m["content get: an admin names an unknown endpoint"] = {
            val p = Phone().connect()
            p.answer(case("content get: an admin names an unknown endpoint"), p.lastId(ContentCommands.GET))
            val failed = p.content as ContentCommands.Outcome.Failed
            assertEquals(ContentCommands.ErrorKind.UNKNOWN_ENDPOINT, failed.kind)
            assertEquals("unknown_endpoint", failed.code)
        }
        m["content get: an admin names none"] = {
            val p = Phone().connect()
            p.answer(case("content get: an admin names none"), p.lastId(ContentCommands.GET))
            assertEquals(ContentCommands.ErrorKind.UNAUTHORIZED, (p.content as ContentCommands.Outcome.Failed).kind)
        }
        m["content get: contract 2's profile key is refused by the schema"] = { oldKeyRefused("content get: contract 2's profile key is refused by the schema", "profile") }
        m["content get: contract 2's pages flag is refused by the schema"] = { oldKeyRefused("content get: contract 2's pages flag is refused by the schema", "pages") }
        m["content run: ok"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("open-front"))
            p.replay(case("content run: ok"))
            assertEquals(listOf("open-front" to ContentCommands.Outcome.Ran), p.runs)
        }
        m["content run: the same action again at once"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("open-front"))
            p.replay(case("content run: the same action again at once"))
            assertEquals(ContentCommands.ErrorKind.RATE_LIMITED, (p.runs.single().second as ContentCommands.Outcome.Failed).kind)
        }
        m["content run: not in the profile"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("lock.unlock"))
            p.replay(case("content run: not in the profile"))
            assertEquals("lock.unlock", p.runs.single().first)
            assertEquals(ContentCommands.ErrorKind.UNKNOWN_ACTION, (p.runs.single().second as ContentCommands.Outcome.Failed).kind)
        }
        m["content run: a declared screen"] = {
            val c = case("content run: a declared screen")
            // a screen with one declared endpoint names none; the builder takes one for a login that serves several
            assertEquals(c.request, mapOf("type" to ContentCommands.RUN) + (MiniJson.parse(ContentCommands.run("evening").toString()) as Map<String, Any?>))
            assertEquals(c.request + ("endpoint" to "kitchen"), mapOf("type" to ContentCommands.RUN) + (MiniJson.parse(ContentCommands.run("evening", "kitchen").toString()) as Map<String, Any?>))
            assertEquals(ContentCommands.Outcome.Ran, ContentCommands.parseRun(result(c)))
        }
        m["content run: ordinary user"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("evening"))
            p.replay(case("content run: ordinary user"))
            assertEquals(ContentCommands.ErrorKind.UNAUTHORIZED, (p.runs.single().second as ContentCommands.Outcome.Failed).kind)
        }
        m["content snapshot: ok"] = {
            val p = Phone().connect()
            assertTrue(p.link.requestSnapshot("gate"))
            p.replay(case("content snapshot: ok"))
            val (id, outcome) = p.snaps.single()
            assertEquals("gate", id)
            outcome as ContentCommands.Outcome.Snapshot
            assertEquals("image/jpeg", outcome.contentType)
            assertEquals("FAKE-JPEG-0001", String(outcome.bytes, Charsets.US_ASCII))
        }
        m["content snapshot: a camera that is unavailable"] = {
            val p = Phone().connect()
            assertTrue(p.link.requestSnapshot("garage"))
            p.replay(case("content snapshot: a camera that is unavailable"))
            assertEquals(ContentCommands.ErrorKind.UNAVAILABLE, (p.snaps.single().second as ContentCommands.Outcome.Failed).kind)
        }
        m["content snapshot: not in the profile"] = {
            val p = Phone().connect()
            assertTrue(p.link.requestSnapshot("front"))
            p.replay(case("content snapshot: not in the profile"))
            assertEquals(ContentCommands.ErrorKind.UNKNOWN_CAMERA, (p.snaps.single().second as ContentCommands.Outcome.Failed).kind)
        }
        m["content snapshot: width out of range (invalid_format)"] = {
            // The app refuses a width outside 64-1280 before the wire; HA's recorded answer still maps.
            assertThrows<IllegalArgumentException> { ContentCommands.snapshot("gate", 10) }
            val p = Phone().connect()
            assertTrue(p.link.requestSnapshot("gate"))
            p.answer(case("content snapshot: width out of range (invalid_format)"), p.lastId(ContentCommands.SNAPSHOT))
            assertEquals(ContentCommands.ErrorKind.INVALID_FORMAT, (p.snaps.single().second as ContentCommands.Outcome.Failed).kind)
        }
        // ---- blocks (DEV-658, B2): what the app reads of them, and what it never sends ----
        m["blocks, content get: a phone with two blocks (door, sizes, colour, Talk off, flat form beside them)"] = {
            val p = contentGet("blocks, content get: a phone with two blocks (door, sizes, colour, Talk off, flat form beside them)")
            val page = (p.content as ContentCommands.Outcome.Profile).profile.pages.single()
            val (entrance, garden) = page.blocks!!
            assertEquals(listOf("Entrance", "Garden"), page.blocks!!.map { it.title })
            assertEquals("http://192.168.1.7:8091" to true, entrance.door to entrance.doorAvailable)
            assertEquals(null to null, garden.door to garden.doorAvailable)
            assertEquals(listOf(CameraSize.BIG, CameraSize.BIG, CameraSize.SMALL), entrance.cameras.map { it.size })
            assertEquals(listOf(CameraSize.SMALL), garden.cameras.map { it.size })
            assertEquals(listOf("open-door", "unlock-front", "talk", "evening"), entrance.actions.map { it.id })
            val unlock = entrance.actions[1]
            assertEquals(Triple("lock_unlock", "red", false), Triple(unlock.kind, unlock.color, unlock.showLabel))
            val talk = entrance.actions[2]
            assertEquals(Triple("talk", false, "talk_off"), Triple(talk.kind, talk.available, talk.reason))
            assertFalse(talk.confirm)
            assertEquals("green", garden.actions.single().color)
            // the flat form beside the blocks is not read: the page holds every block item, the new kinds included
            assertEquals(listOf("gate", "garage", "porch", "lawn"), page.cameras.map { it.id })
            assertEquals(listOf("open-door", "unlock-front", "talk", "evening", "gate-cover"), page.actions.map { it.id })
        }
        for (n in listOf(
            "blocks, content document: admin (schema 4: blocks and the flat mirror)",
            "blocks, content preview: a draft whose door does not exist (door_available false)",
            "blocks, content preview: a flat edit from the panel on disk on a one-block page",
            "blocks, content preview: a flat edit on a page of several blocks is refused",
            "blocks, content preview: a schema 3 document is refused",
            "blocks, content preview: a Talk button on a screen's page is off, phone_only",
            "blocks, content save: a schema 3 document with a stale revision conflicts first",
        )) m[n] = { serverOnly(n) }
        for (id in listOf("unlock-front" to "blocks, content run: lock_unlock", "gate-cover" to "blocks, content run: cover_open in the second block")) {
            m[id.second] = {
                val p = Phone().connect()
                assertTrue(p.link.runAction(id.first))
                p.replay(case(id.second))
                assertEquals(listOf(id.first to ContentCommands.Outcome.Ran), p.runs)
            }
        }
        m["blocks, content run: talk is in the grant and refused (switched off)"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("talk"))
            p.replay(case("blocks, content run: talk is in the grant and refused (switched off)"))
            val failed = p.runs.single().second as ContentCommands.Outcome.Failed
            assertEquals("not_available", failed.code)
            assertEquals(ContentCommands.ErrorKind.NOT_AVAILABLE, failed.kind, "B7b: not_available has a kind of its own (B5 read it as OTHER)")
        }
        m["blocks, content run: talk by an administrator naming the phone is not_allowed"] = {
            // The phone never names an endpoint (ContentCommands.run takes none from the app's link), so it never gets this answer; the reply still parses to its own kind.
            val c = case("blocks, content run: talk by an administrator naming the phone is not_allowed")
            val failed = ContentCommands.parseRun(result(c)) as ContentCommands.Outcome.Failed
            assertEquals(ContentCommands.ErrorKind.NOT_ALLOWED, failed.kind)
            assertEquals("not_allowed", failed.code)
            assertEquals("ep-alice", c.send["endpoint"], "the request names an endpoint, which the app's link never does")
            assertEquals(null, ContentCommands.run("talk").get("endpoint"))
        }
        m["blocks, content snapshot: width 1920"] = {
            // The server allows 1920 and, since B6, so does the builder: the link sends exactly the case's request.
            val c = case("blocks, content snapshot: width 1920")
            assertEquals(1920L, c.send["width"])
            val sent = ContentCommands.snapshot("lawn", 1920)
            assertEquals("lawn", (sent["camera_id"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(1920, (sent["width"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
            val p = Phone().connect()
            assertTrue(p.link.requestSnapshot("lawn", 1920))
            p.replay(c)
            val outcome = p.snaps.single().second as ContentCommands.Outcome.Snapshot
            assertEquals("image/jpeg", outcome.contentType)
        }
        m["blocks, content snapshot: width 1921 (invalid_format)"] = {
            val c = case("blocks, content snapshot: width 1921 (invalid_format)")
            assertThrows<IllegalArgumentException> { ContentCommands.snapshot("lawn", 1921) }
            assertEquals(ContentCommands.ErrorKind.INVALID_FORMAT, (ContentCommands.parseSnapshot(result(c)) as ContentCommands.Outcome.Failed).kind)
        }
        m["content snapshot: ordinary user"] = {
            val p = Phone().connect()
            assertTrue(p.link.requestSnapshot("gate"))
            p.replay(case("content snapshot: ordinary user"))
            assertEquals(ContentCommands.ErrorKind.UNAUTHORIZED, (p.snaps.single().second as ContentCommands.Outcome.Failed).kind)
        }

        // ---- the server's recorded `talk_state` frames (M7; the session side is TalkStateServerFramesTest) ----
        m["calls subscribe: phone (talk to the gate)"] = {
            val p = Phone().connect()
            p.replay(case("calls subscribe: phone (talk to the gate)"))
            assertEquals(listOf(CallEvent.NoLiveCall(GATE, GATE_REF), CallEvent.NoLiveCall(FRONT, FRONT_REF)), p.calls, "a talk_state is not a call event: it never rings")
            assertEquals(listOf(TalkStateEvent(GATE, TALK_CALL_ID, TalkWireState.ENDED, null)), p.talks)
        }
        m["calls subscribe: screen (talk to the gate)"] = {
            val p = Phone().connect()
            p.replay(case("calls subscribe: screen (talk to the gate)"))
            assertEquals(listOf(CallEvent.NoLiveCall(GATE, GATE_REF), CallEvent.NoLiveCall(FRONT, FRONT_REF)), p.calls)
            assertEquals(emptyList<TalkStateEvent>(), p.talks, "the screen did not place the call, so it is sent no talk_state")
        }
        m["content run: talk starts, the phone hears dialing and in_call before the reply (talk to the gate)"] = {
            val p = Phone().connect()
            val sub = p.lastId(Handshake.CALLS_SUBSCRIBE)
            p.answer(case("calls subscribe: screen (talk to the gate)"), sub) // the subscription is live; no talk_state in it
            assertTrue(p.link.runAction("talk"))
            val c = case("content run: talk starts, the phone hears dialing and in_call before the reply (talk to the gate)")
            assertEquals(c.request, p.sent(ContentCommands.RUN))
            p.answerSplit(c, sub, p.lastId(ContentCommands.RUN))
            // `dialing` carries call_id null (the id is minted after the grant): the parser drops it; `in_call` reaches the sink
            assertEquals(listOf(TalkStateEvent(GATE, TALK_CALL_ID, TalkWireState.IN_CALL, null)), p.talks)
            assertEquals(listOf("talk" to ContentCommands.Outcome.Ran), p.runs)
            assertEquals(2, p.calls.size, "only the two idle snapshots: nothing rang")
        }
        m["version: the screen heard nothing of it, its next frame is its own answer (talk to the gate)"] = {
            versionOk("version: the screen heard nothing of it, its next frame is its own answer (talk to the gate)")
        }
        m["content run: talk refused by the gate, dialing then ended with the reason (talk to the gate)"] = {
            val p = Phone().connect()
            val sub = p.lastId(Handshake.CALLS_SUBSCRIBE)
            p.answer(case("calls subscribe: screen (talk to the gate)"), sub)
            assertTrue(p.link.runAction("talk"))
            val c = case("content run: talk refused by the gate, dialing then ended with the reason (talk to the gate)")
            p.answerSplit(c, sub, p.lastId(ContentCommands.RUN))
            // both talk_state frames carry call_id null, so the app never sees them; the error reply is what ends the Talk
            assertEquals(emptyList<TalkStateEvent>(), p.talks)
            val failed = p.runs.single().second as ContentCommands.Outcome.Failed
            assertEquals("busy", failed.code)
        }

        // ---- house calls (M7 3b-A): the five `portico/house/...` commands are in app_commands_v3.txt now, so every recorded case of
        // one is replayed through the app's builders and parsers (a body the server's schema would refuse, or a reply the app
        // maps to the wrong words, turns this red). The cases that ride on commands the app already sends follow.
        for (c in WsContract.cases.filter { it.type in HOUSE_TYPES }) m[c.name] = { houseCommand(c) }
        m["calls subscribe: phone, with house calls on (house calls on)"] = {
            val p = Phone().connect()
            p.answer(case("content get: enrolled phone, a profile"), p.lastId(ContentCommands.GET)) // the session start's read is answered
            p.replay(case("calls subscribe: phone, with house calls on (house calls on)"))
            assertEquals(listOf(CallEvent.NoLiveCall(GATE, GATE_REF), CallEvent.NoLiveCall(FRONT, FRONT_REF)), p.calls, "house_call and house_roster_changed events ring nothing and break nothing")
            assertEquals(emptyList<TalkStateEvent>(), p.talks)
            assertTrue(p.mismatches.isEmpty())
            // 3b-A: the two house_call events reach the house sink with their endpoint ids (names are never read from them)
            assertEquals(
                listOf(
                    HouseCallEvent("hso-1", "ringing", null, "kitchen", "ep-alice"),
                    HouseCallEvent("hso-1", "ended", "hangup", "kitchen", "ep-alice"),
                ),
                p.houses,
            )
            // house_roster_changed asks for the document again (the roster lives in it): one more content/get than the session start's
            assertEquals(2, p.wire.ofType(ContentCommands.GET).size, "the roster change re-reads the document")
        }
        m["calls subscribe: screen, with house calls on (house calls on)"] = {
            val p = Phone().connect()
            p.replay(case("calls subscribe: screen, with house calls on (house calls on)"))
            assertEquals(listOf(CallEvent.NoLiveCall(GATE, GATE_REF), CallEvent.NoLiveCall(FRONT, FRONT_REF)), p.calls)
            assertEquals(listOf(HouseCallEvent("hso-1", "answered", null, "kitchen", "ep-alice")), p.houses)
        }
        m["content get: the screen's roster leads with Everyone (house calls on)"] = {
            val p = Phone().connect()
            p.answer(case("content get: the screen's roster leads with Everyone (house calls on)"), p.lastId(ContentCommands.GET))
            assertEquals("kitchen", (p.content as ContentCommands.Outcome.Profile).profile.endpoint, "the new roster and rev keys do not stop the app reading the document")
        }
        m["content run: Everyone from the screen (house calls on)"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("r-99c746982df6"))
            val c = case("content run: Everyone from the screen (house calls on)")
            p.answerSplit(c, p.lastId(Handshake.CALLS_SUBSCRIBE), p.lastId(ContentCommands.RUN))
            assertEquals(listOf("r-99c746982df6" to ContentCommands.Outcome.Ran), p.runs)
            assertEquals(listOf(HouseCallEvent("hso-1", "ringing", null, "kitchen", "everyone")), p.houses)
        }
        m["content run: Everyone again (house calls on)"] = {
            val p = Phone().connect()
            assertTrue(p.link.runAction("r-99c746982df6"))
            val c = case("content run: Everyone again (house calls on)")
            p.answerSplit(c, p.lastId(Handshake.CALLS_SUBSCRIBE), p.lastId(ContentCommands.RUN))
            assertEquals(listOf("r-99c746982df6" to ContentCommands.Outcome.Ran), p.runs)
            assertEquals(listOf(HouseCallEvent("hso-2", "ringing", null, "kitchen", "everyone"), HouseCallEvent("hso-2", "ringing", null, "kitchen", "ep-alice")), p.houses)
        }

        // ---- the gate as a roster endpoint (M7 3f, recorded): a door row is never a house call and never turns the page on ----
        m["content get: the gate is in the roster, callable, with can_dial (gate as a call target)"] = {
            val p = Phone().connect()
            p.answer(case("content get: the gate is in the roster, callable, with can_dial (gate as a call target)"), p.lastId(ContentCommands.GET))
            val roster = (p.content as ContentCommands.Outcome.Profile).profile.roster
            assertEquals(3, roster.size)
            assertTrue(roster.any { it.kind == "door" && it.available }, "the gate is callable")
            assertFalse(HouseGate.enabled(roster), "a gate row alone is not house calls: the roster page stays out")
        }
        m["content get: a screen sees the gate off with phone_only (gate as a call target)"] = {
            val p = Phone().connect()
            p.answer(case("content get: a screen sees the gate off with phone_only (gate as a call target)"), p.lastId(ContentCommands.GET))
            val roster = (p.content as ContentCommands.Outcome.Profile).profile.roster
            assertEquals("phone_only", roster.single { it.kind == "door" && it.id.contains("192.168.1.7") }.reason)
            assertFalse(HouseGate.enabled(roster))
        }
        m["content document: the gate's endpoint row carries can_dial (gate as a call target)"] = { serverOnly("content document: the gate's endpoint row carries can_dial (gate as a call target)") }
        // ---- DEV-695: a call button aimed at the gate carries `gate_call: true` itself, roster or no roster ----
        // (a preview's result is the same document a `content get` returns; the app reads it with the same parser)
        val markedName = "content preview: a call button aimed at the gate carries gate_call (gate as a call target)"
        m[markedName] = {
            val profile = (ContentCommands.parseGet(result(case(markedName))) as ContentCommands.Outcome.Profile).profile
            val button = profile.pages.single().actions.single()
            assertEquals(CallTarget("door", GATE_ENDPOINT), button.target)
            assertTrue(button.gateCall, "the action carries the marker")
            assertTrue(profile.roster.any { it.id == GATE_ENDPOINT && it.canDial }, "the roster shows the gate here")
            assertEquals(GateCall(GATE), GateCall.of(button, profile.roster, DOORS))
            // the marker is what decides: the same button without it, on the same roster, is still found through the roster
            assertEquals(GateCall(GATE), GateCall.of(button.copy(gateCall = false), profile.roster, DOORS))
        }
        val hiddenName = "content preview: the gate hidden from the roster, the call button still carries gate_call (gate as a call target)"
        m[hiddenName] = {
            val profile = (ContentCommands.parseGet(result(case(hiddenName))) as ContentCommands.Outcome.Profile).profile
            val button = profile.pages.single().actions.single()
            assertTrue(button.gateCall, "the action carries the marker")
            assertTrue(profile.roster.none { it.id == GATE_ENDPOINT }, "the roster hides the gate: nothing to detect there")
            assertEquals(GateCall(GATE), GateCall.of(button, profile.roster, DOORS), "the marker alone makes it a gate call")
            // an older server sends no marker: with the gate hidden the roster cannot tell (the case this change exists for)
            assertNull(GateCall.of(button.copy(gateCall = false), profile.roster, DOORS))
        }
        // ---- DEV-807 slice 2: the panel's heartbeat (phone class) and the commands only the editor or another caller sends ----
        for (c in WsContract.cases) {
            if (c.name.startsWith("wall heartbeat")) continue // real expectations below
            if (c.name !in m && c.type in setOf(
                    "portico/camera/webrtc_offer", "portico/phones/make_panel_screen", "portico/phones/set", "portico/ui_settings/set",
                    "portico/elements/list", "portico/elements/set", "portico/elements/delete", "portico/content/pages_migrate",
                )) {
                m[c.name] = { serverOnly(c.name) }
            }
        }
        /** The heartbeat's body as the app builds it; [overrides] stand in for a value the case's request pins. */
        fun heartbeatBody(): JsonObject = PanelHeartbeatCommands.heartbeat(
            PanelFacts(
                appVersion = "0.6.0-alpha", panelMode = true, endpointOk = true, foreground = false, frontReason = FrontReason.NONE, overdue = false,
                overlayOk = true, fsiOk = true, microphoneOk = true, notificationsOk = true, ringAudible = true, batteryExempt = true,
                stayConnected = true, ringLevelOk = true, wakeDoors = 2, phoneEndpoint = "ep-panel", houseSwitch = true, houseGate = true, autoRevokeExempt = true,
            ),
        )
        for (n in listOf("wall heartbeat: ordinary user", "wall heartbeat: declared screen", "wall heartbeat: administrator (no phone)")) {
            m[n] = {
                // only an enrolled phone may send it: any other login is `unauthorized`, which is not "an older server" (`unknown_command`)
                val c = case(n)
                val p = Phone().connect()
                val r = p.callReply(c, heartbeatBody())
                assertEquals(HeartbeatOutcome.Error("unauthorized"), PanelHeartbeatCommands.outcome(r), n)
            }
        }
        m["wall heartbeat: phone, not a panel (not_a_panel)"] = {
            val c = case("wall heartbeat: phone, not a panel (not_a_panel)")
            val p = Phone().connect()
            val r = p.callReply(c, heartbeatBody())
            val built = p.sent(PanelHeartbeatCommands.HEARTBEAT).keys
            assertTrue(built.containsAll(c.request.keys - "type"), "the app's heartbeat carries every field the recorded request does")
            assertEquals(HeartbeatOutcome.Error("not_a_panel"), PanelHeartbeatCommands.outcome(r))
        }
        m["wall heartbeat: phone, a field out of range (invalid_format)"] = {
            // the app only ever sends the four front reasons: the recorded "sideways" is a server-side check the app cannot trigger
            val c = case("wall heartbeat: phone, a field out of range (invalid_format)")
            assertFalse(FrontReason.entries.any { it.wire == c.str("front_reason") })
            val p = Phone().connect()
            val r = p.callReply(c, heartbeatBody())
            assertEquals(HeartbeatOutcome.Error("invalid_format"), PanelHeartbeatCommands.outcome(r))
        }
        // ---- D-20261121-c98a: the panel app's answer to `portico.call_everyone` ----
        for (n in listOf("panel call result: ordinary user", "panel call result: declared screen", "panel call result: administrator (no phone)")) {
            m[n] = {
                val r = Phone().connect().callReply(case(n), PanelCallWire.result("0123456789abcdef", true))
                assertEquals("unauthorized", r.error?.code, n)
            }
        }
        m["panel call result: phone, not a panel (not_a_panel)"] = {
            val c = case("panel call result: phone, not a panel (not_a_panel)")
            val p = Phone().connect()
            val r = p.callReply(c, PanelCallWire.result("0123456789abcdef", true))
            assertTrue(p.sent(PanelCallWire.RESULT).keys.containsAll(c.request.keys - "type"), "the app's answer carries every field the recorded request does")
            assertEquals("not_a_panel", r.error?.code)
        }
        m["panel call result: phone, a bad request id (invalid_format)"] = {
            // the app only ever answers with the 16-hex id it was sent: its builder refuses anything else, so the recorded bad id is a server-side check
            val c = case("panel call result: phone, a bad request id (invalid_format)")
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { PanelCallWire.result(c.str("request_id")!!, true) }
        }
        // ---- the connection log (DEV-705): `report` is the phone's, `get` and `clear` the administrator's ----
        for (c in WsContract.cases) {
            if (c.name.startsWith("connlog get") || c.name.startsWith("connlog clear") || c.name.startsWith("connlog journal get") || c.name.startsWith("connlog journal clear") ||
                c.name.startsWith("connlog diag get") || c.name.startsWith("connlog diag clear")) {
                m[c.name] = { serverOnly(c.name) }
            }
        }
        fun connlogSent(name: String, expected: ConnLogReply) {
            val c = case(name)
            val reports = (c.request["reports"] as List<*>).map { WsContract.encode(it as Map<String, Any?>) }
            val p = Phone().connect()
            val reply = p.callReply(c, ConnLogCommands.report(reports))
            assertEquals(c.request, p.sent(c.type), "$name: the request the app builds")
            assertEquals(expected, ConnLogReplies.of(reply), name)
        }
        m["connlog report: phone, one report"] = { connlogSent("connlog report: phone, one report", ConnLogReply.Ok(1, 0, 0)) }
        m["connlog report: phone, a duplicate, a new one and a v2 report (rejected)"] =
            { connlogSent("connlog report: phone, a duplicate, a new one and a v2 report (rejected)", ConnLogReply.Ok(1, 1, 1)) }
        // another caller's refusal, and a frame the app never builds (no `reports`): HA's answer still maps to the right reply
        for (name in listOf("connlog report: ordinary user", "connlog report: declared screen", "connlog report: administrator (no phone)")) {
            m[name] = { assertEquals(ConnLogReply.Error("unauthorized"), ConnLogReplies.of(result(case(name))), name) }
        }
        // ---- the Journal items (DEV-713 / DEV-705): the same command, items with `kind: journal`; the reply adds `journal: {lines, dropped}` ----
        // (JournalReporterContractTest builds these items with the app's own reporter and sends them through the real queue and sender)
        for (name in listOf("connlog journal report: ordinary user", "connlog journal report: declared screen", "connlog journal report: administrator (no phone)")) {
            m[name] = { assertEquals(ConnLogReply.Error("unauthorized"), ConnLogReplies.of(result(case(name))), name) }
        }
        val journalReplies = mapOf(
            "connlog journal report: phone, three lines" to ConnLogReply.Ok(1, 0, 0, journalLines = 3, journalDropped = 0),
            "connlog journal report: phone, the same item again (a duplicate)" to ConnLogReply.Ok(0, 1, 0, journalLines = 0, journalDropped = 0),
            "connlog journal report: phone, hostile lines dropped and counted" to ConnLogReply.Ok(1, 0, 0, journalLines = 2, journalDropped = 5),
            "connlog journal report: phone, nothing acceptable in it (rejected)" to ConnLogReply.Ok(0, 0, 2, journalLines = 0, journalDropped = 2),
            "connlog journal report: phone, a connection report and an item in one message" to ConnLogReply.Ok(2, 0, 0, journalLines = 1, journalDropped = 0),
            "connlog journal report: phone, a `kind` of connection is a connection report" to ConnLogReply.Ok(1, 0, 0),
        )
        for ((name, expected) in journalReplies) m[name] = { connlogSent(name, expected) }
        // ---- DEV-762 H1 (portico-ha #197): ring-decision Journal lines, and `crash` / `exit` items the app does not build yet (A2 to A4) ----
        m["connlog diag report: phone, ring-decision Journal lines are accepted"] = {
            connlogSent("connlog diag report: phone, ring-decision Journal lines are accepted", ConnLogReply.Ok(1, 0, 0, journalLines = 4, journalDropped = 1))
        }
        for (name in listOf("connlog diag report: ordinary user", "connlog diag report: declared screen", "connlog diag report: administrator (no phone)")) {
            m[name] = { assertEquals(ConnLogReply.Error("unauthorized"), ConnLogReplies.of(result(case(name))), name) }
        }
        // the additive `diag` block of the reply is ignored: the stored / duplicates / rejected counts still map
        for ((name, expected) in mapOf(
            "connlog diag report: phone, a crash and an exit" to ConnLogReply.Ok(2, 0, 0),
            "connlog diag report: phone, the same crash again (a duplicate)" to ConnLogReply.Ok(0, 1, 0),
            "connlog diag report: phone, the same signature is merged, a hostile one is refused" to ConnLogReply.Ok(1, 0, 1),
        )) m[name] = { assertEquals(expected, ConnLogReplies.of(result(case(name))), name) }
        m["connlog report: phone, no reports field (invalid_format)"] = {
            val c = case("connlog report: phone, no reports field (invalid_format)")
            assertFalse("reports" in c.request, "the app always sends reports")
            assertEquals(ConnLogReply.Error("invalid_format"), ConnLogReplies.of(result(c)))
        }
        // ---- the admin-only Phones tab and house-calls switch (portico-ha UI split P2/P6b): another caller's, the app never builds them ----
        for (c in WsContract.cases) if (c.name.startsWith("phones ") || c.name.startsWith("house calls ")) m[c.name] = { serverOnly(c.name) }
        m["content run: the gate's roster key dials through Talk's path (gate as a call target)"] = {
            val p = Phone().connect()
            val sub = p.lastId(Handshake.CALLS_SUBSCRIBE)
            p.answer(case("calls subscribe: screen (talk to the gate)"), sub)
            assertTrue(p.link.runAction("r-355c9550e870"))
            p.answerSplit(case("content run: the gate's roster key dials through Talk's path (gate as a call target)"), sub, p.lastId(ContentCommands.RUN))
            assertEquals(listOf("r-355c9550e870" to ContentCommands.Outcome.Ran), p.runs)
            assertEquals(listOf(TalkStateEvent(GATE, TALK_CALL_ID, TalkWireState.IN_CALL, null), TalkStateEvent(GATE, TALK_CALL_ID, TalkWireState.ENDED, null)), p.talks)
            assertEquals(emptyList<HouseCallEvent>(), p.houses, "a dialled call is not a house call")
        }
        // ---- DEV-713 stage S0 (dark): the contract gained the live commands and the server's other additions ----
        // Admin commands the app never sends (door status and settings, UI settings, claims, the house-calls switch).
        for (c in WsContract.cases) if (c.type in SERVER_ONLY_TYPES) m[c.name] = { serverOnly(c.name) }
        // DEV-713 S1: the three live commands, built by `LiveCommands` and sent through `PorticoLink.openStream` / `sendCall`.
        liveExpectations(m)
        // A later session's calls subscription, after the house-calls switch went off and on: the same snapshots, the roster events ring nothing.
        for (n in listOf("house reattach: phone subscribes (house calls off)", "house reattach: screen subscribes (house calls off)")) {
            m[n] = {
                val p = Phone().connect()
                p.replay(case(n))
                assertEquals(listOf(CallEvent.NoLiveCall(GATE, GATE_REF), CallEvent.NoLiveCall(FRONT, FRONT_REF)), p.calls, n)
                assertEquals(emptyList<TalkStateEvent>(), p.talks, n)
                assertEquals(emptyList<HouseCallEvent>(), p.houses, "$n: house calls are off, so no house_call event")
                assertTrue(p.mismatches.isEmpty(), n)
            }
        }
        // The two new document fields, read from the server's own previews (the admin sends them; the parser is the phone's).
        m["live flag: preview of a draft whose first camera is live"] = {
            val profile = (ContentCommands.parseGet(result(case("live flag: preview of a draft whose first camera is live"))) as ContentCommands.Outcome.Profile).profile
            assertEquals(
                mapOf("gate" to true, "garage" to false, "porch" to false, "lawn" to false),
                profile.pageList.single().cameras.associate { it.id to it.live },
                "the server writes live only on the camera that has it",
            )
            assertFalse(profile.showFps, "no show_fps key in this preview")
        }
        m["live flag: preview with a live that is not a boolean is refused"] = {
            val failed = ContentCommands.parseGet(result(case("live flag: preview with a live that is not a boolean is refused")))
            assertTrue(failed is ContentCommands.Outcome.Failed, "an invalid live is the server's refusal, no profile")
        }
        m["show_fps: preview of a draft with the frame rate on"] = {
            val profile = (ContentCommands.parseGet(result(case("show_fps: preview of a draft with the frame rate on"))) as ContentCommands.Outcome.Profile).profile
            assertTrue(profile.showFps)
            assertTrue(profile.pageList.single().cameras.none { it.live }, "no camera is live in this draft")
        }
        m["show_fps: preview of a draft with it off carries nothing"] = {
            val c = case("show_fps: preview of a draft with it off carries nothing")
            assertFalse("show_fps" in (c.receive.first()["result"] as Map<*, *>), "the server writes no show_fps key when it is off")
            assertFalse((ContentCommands.parseGet(result(c)) as ContentCommands.Outcome.Profile).profile.showFps)
        }
        m["show_fps: preview with a show_fps that is not a boolean is refused"] = {
            val failed = ContentCommands.parseGet(result(case("show_fps: preview with a show_fps that is not a boolean is refused")))
            assertTrue(failed is ContentCommands.Outcome.Failed)
        }
        // DEV-713 S1b: the camera tile's additive `live_rtsp`, read from the server's own previews by the phone's parser; the admin's
        // ui_settings cases (live_rtsp_base, live_sub_streams) are the server's alone, listed by their type below.
        fun rtspOf(name: String): Map<String, LiveRtsp?> =
            (ContentCommands.parseGet(result(case(name))) as ContentCommands.Outcome.Profile).profile.pageList.single().cameras.associate { it.id to it.liveRtsp }
        val noRtsp = mapOf<String, LiveRtsp?>("gate" to null, "yard" to null, "still" to null, "tablet" to null)
        m["live_rtsp: preview with no address set carries none"] = { assertEquals(noRtsp, rtspOf("live_rtsp: preview with no address set carries none")) }
        m["live_rtsp: preview after clearing carries none again"] = { assertEquals(noRtsp, rtspOf("live_rtsp: preview after clearing carries none again")) }
        m["live_rtsp: preview, a live Frigate tile has main and (when listed) sub; a tile that is not live, or not Frigate, none"] = {
            assertEquals(
                mapOf(
                    "gate" to LiveRtsp("rtsp://192.168.1.6:8554/gate_intercom", "rtsp://192.168.1.6:8554/gate_intercom_sub"),
                    "yard" to LiveRtsp("rtsp://192.168.1.6:8554/yard"),
                    "still" to null,
                    "tablet" to null,
                ),
                rtspOf("live_rtsp: preview, a live Frigate tile has main and (when listed) sub; a tile that is not live, or not Frigate, none"),
            )
        }
        m["live_rtsp: preview with no sub-stream list has main only"] = {
            assertEquals(
                mapOf(
                    "gate" to LiveRtsp("rtsp://192.168.1.6:8554/gate_intercom"),
                    "yard" to LiveRtsp("rtsp://192.168.1.6:8554/yard"),
                    "still" to null,
                    "tablet" to null,
                ),
                rtspOf("live_rtsp: preview with no sub-stream list has main only"),
            )
        }
        // DEV-713 S2: the CALLER's cameras (`callers.<id>.cameras[]`, ids `c-...`) carry the same `live_rtsp`, never a `live` key. The ring and
        // in-call screens read them through the phone's own parser and CallerModel; the admin's three ui_settings cases are the server's alone.
        val callerCam = "c-30102768d913"
        fun callerCamera(name: String): ContentCamera = (ContentCommands.parseGet(result(case(name))) as ContentCommands.Outcome.Profile).profile.callers.getValue(GATE_ENDPOINT).cameras.single()
        fun callerTile(name: String): CameraTile {
            val profile = (ContentCommands.parseGet(result(case(name))) as ContentCommands.Outcome.Profile).profile
            return CallerModel.build(profile, GATE_ENDPOINT, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), setOf(callerCam))!!.cameras.single()
        }
        fun callerNone(name: String) {
            val cam = callerCamera(name)
            assertEquals(ContentCamera(callerCam, "Vehicle gate", "Gate camera", true), cam, "no live_rtsp, and no live")
            assertNull(callerTile(name).liveRtsp)
        }
        m["caller live_rtsp: preview with no address set, the caller's camera carries none"] = { callerNone("caller live_rtsp: preview with no address set, the caller's camera carries none") }
        m["caller live_rtsp: preview, a caller camera that is not a Frigate camera carries none"] = { callerNone("caller live_rtsp: preview, a caller camera that is not a Frigate camera carries none") }
        m["caller live_rtsp: preview after clearing carries none again"] = { callerNone("caller live_rtsp: preview after clearing carries none again") }
        m["caller live_rtsp: preview, the caller's Frigate camera has main and sub (no live flag needed)"] = {
            val name = "caller live_rtsp: preview, the caller's Frigate camera has main and sub (no live flag needed)"
            val want = LiveRtsp("rtsp://192.168.1.6:8554/gate_intercom", "rtsp://192.168.1.6:8554/gate_intercom_sub")
            assertEquals(ContentCamera(callerCam, "Vehicle gate", "Gate camera", true, null, false, want), callerCamera(name))
            val tile = callerTile(name)
            assertEquals(want, tile.liveRtsp)
            assertTrue(tile.live, "the caller's first available camera is the live one on the ring screen")
            assertEquals(CameraSize.BIG, tile.size, "a full-width tile: the main stream away")
            val plan = LivePlan.plan(
                LiveGlobal(switchOn = true, shown = true, paused = false, connected = true, path = LinkPath.TAILNET),
                CallerLive.tiles(listOf(tile), { it.live }, setOf(callerCam)),
            )
            assertEquals(setOf(LiveKey(callerCam, CameraSize.BIG, "gate_intercom")), plan.wanted)
            assertEquals(want.main, plan.urls.values.single())
        }
        m["caller live_rtsp: preview, the caller's camera has main only"] = {
            val name = "caller live_rtsp: preview, the caller's camera has main only"
            val want = LiveRtsp("rtsp://192.168.1.6:8554/gate_intercom")
            assertEquals(ContentCamera(callerCam, "Vehicle gate", "Gate camera", true, null, false, want), callerCamera(name))
            assertEquals(want, callerTile(name).liveRtsp)
        }
    }

    /** The recorded answer of [name] has the first frame [code] as its error (null: it succeeded); for callers the app never is. */
    private fun recordedReply(name: String, code: String?) {
        val r = result(case(name))
        assertEquals(code == null, r.success, name)
        assertEquals(code, r.error?.code, name)
    }

    /**
     * One phone `portico/content/live`, opened through [PorticoLink.openStream] as the app does: the request it puts on the wire equals
     * the file's field by field (the offer is the test's literal), and the recorded result and events are read into [reply] and [events].
     */
    private fun liveOpen(name: String, camera: String, size: CameraSize?, reply: LiveCommands.Reply, events: List<LiveCommands.Event>, endpoint: String? = null) {
        val p = Phone().connect()
        var got: HaInbound.Result? = null
        var replied = false
        val seen = mutableListOf<LiveCommands.Event>()
        val id = p.link.openStream(LiveCommands.LIVE, LiveCommands.live(camera, "v=0 FAKE-offer", size, endpoint), { got = it; replied = true }, { seen += LiveCommands.parseEvent(it) }, { throw AssertionError("$name: lost") })
        assertTrue(id != null, "$name: sent")
        p.replay(case(name))
        assertTrue(replied, "$name: the result reached the caller")
        assertEquals(reply, LiveCommands.parseReply(got), name)
        assertEquals(events, seen, name)
        assertTrue(p.mismatches.isEmpty(), name)
    }

    private fun liveSent(name: String, body: JsonObject, type: String, code: String?) {
        val p = Phone().connect()
        var got: HaInbound.Result? = null
        assertTrue(p.link.sendCall(type, body) { got = it }, "$name: sent")
        p.replay(case(name))
        assertEquals(code == null, got!!.success, name)
        assertEquals(code, got!!.error?.code, name)
    }

    private fun liveExpectations(m: MutableMap<String, () -> Unit>) {
        val session1 = listOf<LiveCommands.Event>(LiveCommands.Event.Session("live-1", "main"), LiveCommands.Event.Answer("v=0 FAKE-ANSWER-1"), LiveCommands.Event.Candidate(LiveCandidate(HOST_CANDIDATE, "0")))
        m["live: phone, its own camera (the result, the session event, the answer and a candidate)"] = {
            liveOpen("live: phone, its own camera (the result, the session event, the answer and a candidate)", "gate", null, LiveCommands.Reply.Opened("live-1", "main"), session1)
        }
        m["live: phone, a small tile with no sub stream mapped uses the main stream"] = {
            liveOpen(
                "live: phone, a small tile with no sub stream mapped uses the main stream", "gate", CameraSize.SMALL, LiveCommands.Reply.Opened("live-2", "main"),
                listOf(LiveCommands.Event.Session("live-2", "main"), LiveCommands.Event.Answer("v=0 FAKE-ANSWER-2"), LiveCommands.Event.Candidate(LiveCandidate(HOST_CANDIDATE, "0"))),
            )
        }
        m["live: phone, a camera that is not in its profile (unknown_camera)"] = {
            liveOpen("live: phone, a camera that is not in its profile (unknown_camera)", "front", null, LiveCommands.Reply.Refused("unknown_camera"), emptyList())
        }
        m["live: phone, a camera that is unavailable"] = {
            liveOpen("live: phone, a camera that is unavailable", "garage", null, LiveCommands.Reply.Refused("unavailable"), emptyList())
        }
        m["live: phone, a camera Home Assistant cannot stream (live_unavailable)"] = {
            liveOpen("live: phone, a camera Home Assistant cannot stream (live_unavailable)", "lawn", null, LiveCommands.Reply.Refused("live_unavailable"), emptyList())
        }
        m["live: phone, a size that is not big or small (invalid_format)"] = {
            // the app's size is an enum: it cannot send "huge"; the server's refusal still reads as a refusal
            val n = "live: phone, a size that is not big or small (invalid_format)"
            assertNull(CameraSize.of(case(n).str("size")))
            assertEquals(LiveCommands.Reply.Refused("invalid_format"), LiveCommands.parseReply(result(case(n))))
        }
        m["live: phone names another's endpoint"] = {
            liveOpen("live: phone names another's endpoint", "porch", null, LiveCommands.Reply.Refused("unauthorized"), emptyList(), endpoint = "kitchen")
        }
        m["live: phone, too many live streams (too_many_live)"] = {
            liveOpen("live: phone, too many live streams (too_many_live)", "gate", null, LiveCommands.Reply.Refused("too_many_live"), emptyList())
        }
        m["live: phone, the camera's handler fails (the result, the session event, then an error event)"] = {
            liveOpen(
                "live: phone, the camera's handler fails (the result, the session event, then an error event)", "gate", null, LiveCommands.Reply.Opened("live-5", "main"),
                listOf(LiveCommands.Event.Session("live-5", "main"), LiveCommands.Event.Error("live_unavailable")),
            )
        }
        // other callers' live: the recorded answers read the same way; the app is never those callers
        m["live: a declared screen, its own camera"] = { assertEquals(LiveCommands.Reply.Opened("live-3", "main"), LiveCommands.parseReply(result(case("live: a declared screen, its own camera")))) }
        m["live: ordinary user"] = { recordedReply("live: ordinary user", "unauthorized") }
        m["live: an admin names none"] = { recordedReply("live: an admin names none", "unauthorized") }
        m["live: admin names the screen's endpoint"] = { assertEquals(LiveCommands.Reply.Opened("live-4", "main"), LiveCommands.parseReply(result(case("live: admin names the screen's endpoint")))) }
        m["live: admin names the phone's endpoint, a camera it does not have (unknown_camera)"] = { recordedReply("live: admin names the phone's endpoint, a camera it does not have (unknown_camera)", "unknown_camera") }

        val ice = LiveCandidate("candidate:7 1 UDP 2122260223 192.168.2.28 52000 typ host", "0", 0)
        m["live_ice: phone, a candidate"] = { liveSent("live_ice: phone, a candidate", LiveCommands.ice("live-1", ice), LiveCommands.LIVE_ICE, null) }
        m["live_ice: phone, the end-of-candidates mark"] = { liveSent("live_ice: phone, the end-of-candidates mark", LiveCommands.ice("live-1", LiveCandidate.END), LiveCommands.LIVE_ICE, null) }
        m["live_ice: phone, a session that does not exist (unknown_session)"] = { liveSent("live_ice: phone, a session that does not exist (unknown_session)", LiveCommands.ice("nope", ice), LiveCommands.LIVE_ICE, "unknown_session") }
        m["live_ice: phone, not a candidate (invalid_format)"] = {
            // the app cannot build a candidate object without its `candidate` line; the server's refusal still reads
            val n = "live_ice: phone, not a candidate (invalid_format)"
            assertFalse("candidate" in (case(n).send["candidate"] as Map<*, *>))
            recordedReply(n, "invalid_format")
        }
        m["live_ice: the screen cannot use the phone's session"] = { recordedReply("live_ice: the screen cannot use the phone's session", "unknown_session") }
        m["live_ice: ordinary user"] = { recordedReply("live_ice: ordinary user", "unauthorized") }

        m["live_close: phone"] = { liveSent("live_close: phone", LiveCommands.close("live-1"), LiveCommands.LIVE_CLOSE, null) }
        m["live_close: phone, again (nothing is open: closed false)"] = { liveSent("live_close: phone, again (nothing is open: closed false)", LiveCommands.close("live-1"), LiveCommands.LIVE_CLOSE, null) }
        m["live_close: the screen cannot close the phone's session"] = { recordedReply("live_close: the screen cannot close the phone's session", null) }
        m["live_close: ordinary user"] = { recordedReply("live_close: ordinary user", "unauthorized") }
    }

    /** The app's failure kind for each error code the server gives a house command. */
    private val HOUSE_ERROR_KINDS = mapOf(
        "not_available" to PlaceFailure.Kind.NOT_AVAILABLE,
        "unauthorized" to PlaceFailure.Kind.FORBIDDEN,
        "unknown_call" to PlaceFailure.Kind.CALL_OVER,
        "already_answered" to PlaceFailure.Kind.ALREADY_ANSWERED,
        "not_subscribed" to PlaceFailure.Kind.NOT_SUBSCRIBED,
        "not_supported" to PlaceFailure.Kind.NO_AUDIO_BRIDGE,
    )

    /**
     * One recorded case of a `portico/house/...` command: the app builds the request the server's schema takes (the record leaves
     * out `media_open`'s optional `codec`, which the app always sends), the link puts it on the wire, and the recorded reply maps
     * to the right words. Frames that belong to the calls subscription (the `house_call` events a command causes) go to it.
     */
    private fun houseCommand(c: WsContract.Case) {
        val built = when (c.type) {
            HouseCommands.ANSWER -> HouseCommands.answer(c.str("call_id"))
            HouseCommands.DECLINE -> HouseCommands.decline(c.str("call_id"))
            HouseCommands.HANGUP -> HouseCommands.hangup(c.str("call_id"))
            HouseCommands.PRESENCE -> HouseCommands.presence(c.send["dnd"] as Boolean)
            else -> HouseCommands.mediaOpen(c.str("call_id"))
        }
        val p = Phone().connect()
        var reply: HaInbound.Result? = null
        assertTrue(p.link.sendCall(c.type, built) { reply = it }, "$c: sent")
        val sent = p.sent(c.type)
        val wanted = c.request - "type"
        assertEquals(wanted.keys, sent.keys - "type" - (if (c.type == HouseCommands.MEDIA_OPEN) setOf("codec") else emptySet()), "$c: request fields")
        for ((k, v) in wanted) assertEquals(v, sent[k], "$c: request field '$k'")
        p.answerSplit(c, p.lastId(Handshake.CALLS_SUBSCRIBE), p.lastId(c.type))
        val r = reply ?: throw AssertionError("$c: no reply reached the call")
        if (r.success) {
            when (c.type) {
                HouseCommands.ANSWER -> assertEquals(HouseCommands.Answered.Granted(c.str("call_id"), null), HouseCommands.parseAnswer(r), "$c")
                HouseCommands.MEDIA_OPEN -> throw AssertionError("$c: no successful media_open is recorded (the bridge is the server's 3b-Go); add its expectation")
                else -> assertEquals(HouseCommands.Plain.Ok, HouseCommands.parsePlain(r), "$c")
            }
            return
        }
        val code = r.error!!.code
        val failure = when (c.type) {
            HouseCommands.ANSWER -> (HouseCommands.parseAnswer(r) as HouseCommands.Answered.Refused).failure
            HouseCommands.MEDIA_OPEN -> (HouseCommands.parseMediaOpen(r) as HouseCommands.Opened.Refused).failure
            else -> (HouseCommands.parsePlain(r) as HouseCommands.Plain.Refused).failure
        }
        assertEquals(code, failure.code, "$c: the failure carries the server's code")
        assertEquals(HOUSE_ERROR_KINDS.getValue(code), failure.kind, "$c: $code is worded")
    }

    private fun appTooOld(c: WsContract.Case): RegStatus {
        val e = result(c).error!!
        assertEquals("app_too_old", e.code)
        return RegStatus.Refused("app_too_old", e.message, RegistrationMachine.TEXT_APP_TOO_OLD)
    }

    private fun screenDocument(name: String) {
        val p = Phone().connect()
        p.answer(case(name), p.lastId(ContentCommands.GET))
        val profile = (p.content as ContentCommands.Outcome.Profile).profile
        assertEquals("kitchen", profile.endpoint)
        assertEquals("dashboard", profile.kind)
        assertEquals(listOf("porch"), profile.cameras.map { it.id })
        assertEquals(setOf(PHONE_ENDPOINT, GATE_ENDPOINT, FRONT_ENDPOINT, "*"), profile.callers.keys)
        assertEquals("phone", profile.callers.getValue(PHONE_ENDPOINT).kind)
    }

    private fun namedEndpointRefused(name: String) {
        val p = Phone().connect()
        p.answer(case(name), p.lastId(ContentCommands.GET))
        assertEquals(ContentCommands.ErrorKind.UNAUTHORIZED, (p.content as ContentCommands.Outcome.Failed).kind, name)
        assertEquals("{}", ContentCommands.get().toString(), "the app names no endpoint, so it is never refused for one")
    }

    private fun oldKeyRefused(name: String, key: String) {
        val c = case(name)
        assertTrue(key in c.send, "the file's request carries contract 2's '$key'")
        val p = Phone().connect()
        assertFalse(key in p.sent(ContentCommands.GET), "the app never sends '$key'")
        p.answer(c, p.lastId(ContentCommands.GET))
        assertEquals(ContentCommands.ErrorKind.INVALID_FORMAT, (p.content as ContentCommands.Outcome.Failed).kind)
    }

    /** The page of the file's "content get: enrolled phone, a profile": what the app must make of it (one block, the page's own id and label). */
    private val RECORDED_PAGE = ContentPage.ofBlocks(
        "home", "Home",
        listOf(
            ContentBlock(
                "home", "Home",
                cameras = listOf(
                    ContentCamera("gate", "Gate", "Gate camera", available = true, size = CameraSize.BIG),
                    ContentCamera("garage", "Garage", "Garage camera", available = false, size = CameraSize.SMALL),
                ),
                actions = listOf(
                    ContentAction("open-front", "Front door", "lock_open", icon = null, confirm = true, name = "Front door lock", available = true),
                    ContentAction("evening", "Evening", "scene_activate", icon = null, confirm = false, name = "Evening", available = true),
                    ContentAction("gate-relay", "Gate", "portico_open", icon = null, confirm = true, name = "Vehicle gate", available = true),
                ),
            ),
        ),
    )

    private fun placeholder(name: String, value: String) {
        assertEquals(value, case(name).str("registration"))
        assertFalse(PushCommands.isUsableRegistration(value))
        assertThrows<IllegalArgumentException> { PushCommands.register(INSTALLATION, "fid", value, PROJECT, APP_VERSION) }
        val held = Phone(value = RegValue(RegKind.FID, value)).connect()
        assertEquals(emptyList<Any>(), held.wire.ofType(PushCommands.REGISTER), "a cached placeholder never reaches the wire")
        assertNull(held.regStore.value, "it is dropped")
        assertEquals(1, held.fidRequests, "and Firebase is asked again")
        // HA's answer: drop the value, ask Firebase once more (plan §3.2).
        val p = replyMapsTo(name, RegStatus.WaitingForRegistration)
        assertNull(p.regStore.value)
        assertEquals(1, p.fidRequests)
    }

    @TestFactory
    fun everyCase(): List<DynamicTest> = WsContract.cases.map { c ->
        DynamicTest.dynamicTest(c.name) {
            val run = expectations[c.name] ?: throw AssertionError("case '${c.name}' is in ws_v3.json but not replayed: add it to expectations")
            run()
        }
    }

    @Test
    fun `every expectation names a case in the file`() {
        assertEquals(WsContract.cases.map { it.name }.toSet(), expectations.keys)
    }

    /** Each command in `app_commands_v3.txt` has at least one recorded case whose request the app builds (or sends through the link) exactly. */
    @Test
    fun `every command the app sends has a client call checked against the file`() {
        val builders: Map<String, JsonObject?> = mapOf(
            Handshake.VERSION to null,
            Handshake.DOORS to null,
            Handshake.CALLS_SUBSCRIBE to null,
            Handshake.CONTENT_SUBSCRIBE to ContentCommands.subscribe(),
            ContentCommands.GET to ContentCommands.get(),
            ContentCommands.RUN to ContentCommands.run("open-front"),
            ContentCommands.SNAPSHOT to ContentCommands.snapshot("gate"),
            PushCommands.REGISTER to PushCommands.register(INSTALLATION, "fid", REGISTRATION, PROJECT, APP_VERSION),
            PushCommands.UNREGISTER to PushCommands.unregister(INSTALLATION),
            PushCommands.STATUS to null,
            AnswerCommands.ANSWER to AnswerCommands.answer(GATE, CALL),
            AnswerCommands.WEBRTC_OFFER to AnswerCommands.webrtcOffer(CALL, GATE, "FAKE-offer"),
            AnswerCommands.HANGUP to AnswerCommands.hangup(CALL, "x"),
            MediaCommands.OPEN to MediaCommands.open(GATE, CALL),
            MediaCommands.FRAME to MediaCommands.frame(CALL, "QUJD"),
            MediaCommands.CLOSE to MediaCommands.close(CALL),
            HouseCommands.ANSWER to HouseCommands.answer("hso-1"),
            HouseCommands.DECLINE to HouseCommands.decline("hso-1"),
            HouseCommands.HANGUP to HouseCommands.hangup("hso-1"),
            HouseCommands.PRESENCE to HouseCommands.presence(true),
            HouseCommands.MEDIA_OPEN to HouseCommands.mediaOpen("hso-1"),
            ConnLogCommands.REPORT to ConnLogCommands.report(listOf("""{"v":1,"id":"x"}""")),
            PanelHeartbeatCommands.HEARTBEAT to PanelHeartbeatCommands.heartbeat(
                PanelFacts(
                    appVersion = "0.6.0-alpha", panelMode = true, endpointOk = true, foreground = false, frontReason = FrontReason.NONE, overdue = false,
                    overlayOk = true, fsiOk = true, microphoneOk = true, notificationsOk = true, ringAudible = true, batteryExempt = true,
                    stayConnected = true, ringLevelOk = true, houseSwitch = true, houseGate = true, autoRevokeExempt = true,
                ),
            ),
            PanelCallWire.RESULT to PanelCallWire.result("0123456789abcdef", true),
            LiveCommands.LIVE to LiveCommands.live("gate", "v=0 FAKE-offer", CameraSize.SMALL),
            LiveCommands.LIVE_ICE to LiveCommands.ice("live-1", LiveCandidate("candidate:7 1 UDP 2122260223 192.168.2.28 52000 typ host", "0", 0)),
            LiveCommands.LIVE_CLOSE to LiveCommands.close("live-1"),
        )
        assertEquals(emptySet<String>(), WsContract.NOT_YET_SENT, "DEV-713 S1 built the live commands: no vendored command is left unbuilt")
        assertEquals((WsContract.appCommands - WsContract.NOT_YET_SENT).toSortedSet(), builders.keys.toSortedSet(), "a client call for every command in the list, and no more")
        for ((type, body) in builders) {
            val built = if (body == null) emptySet() else (MiniJson.parse(body.toString()) as Map<*, *>).keys.map { it as String }.toSet()
            val recorded = WsContract.cases.filter { it.type == type && it.caller in setOf("phone") }
            assertTrue(recorded.isNotEmpty(), "$type: no recorded case from the enrolled phone")
            // the app's fields are a superset of the required ones in at least one phone case (it adds optional fields only)
            assertTrue(recorded.any { c -> built.containsAll(c.request.keys - "type") }, "$type: the app's fields $built miss a field of every recorded request")
        }
    }

    // ---- matching is on the error code, never the message ---------------------------------

    /** What a failed register leaves, minus the server's own text (shown to the user as-is). */
    private fun withoutMessage(s: RegStatus): Any = when (s) {
        is RegStatus.Refused -> s.code to (if (s.code == "project_mismatch") "<server text>" else s.userText)
        else -> s
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `no behaviour depends on an error's message`() {
        val errors = WsContract.cases.filter { it.receive.first()["success"] == false }
        assertTrue(errors.size >= 10)
        for (c in errors) {
            val reference = c.receive.first()
            val code = (reference["error"] as Map<String, Any?>)["code"] as String
            for (msg in listOf("", "a completely different text", "value must be one of ['fid', 'token'] for dictionary value @ data['kind']")) {
                val variant = WsContract.Case(
                    c.name, c.caller, c.send,
                    listOf(reference + ("error" to mapOf("code" to code, "message" to msg))),
                )
                val parsed = HaProtocol.parse(variant.receiveFor(1).single()).single() as HaInbound.Result
                assertEquals(ErrorKind.of(code), (PushCommands.parseRegister(parsed) as Outcome.Failed).kind, "${c.name} / '$msg'")
                if (c.type == PushCommands.REGISTER) {
                    val original = Phone().connect().also { it.answerRegister(c) }.status
                    val altered = Phone().connect().also { it.answerRegister(variant) }.status
                    assertEquals(withoutMessage(original), withoutMessage(altered), "${c.name} / '$msg'")
                }
                if (c.type == PushCommands.UNREGISTER) {
                    assertEquals(PushCommands.parseUnregister(parsed)::class, PushCommands.parseUnregister(
                        HaProtocol.parse(c.receiveFor(1).single()).single() as HaInbound.Result,
                    )::class, "${c.name} / '$msg'")
                }
            }
        }
    }
}
