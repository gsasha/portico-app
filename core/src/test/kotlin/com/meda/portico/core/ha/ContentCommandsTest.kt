package com.meda.portico.core.ha

import com.meda.portico.core.ha.WsContract.HA_TOKEN
import com.meda.portico.core.push.MiniJson
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The content commands' builders and parsers, and how [PorticoLink] drives them. Frames here are hand-made edge cases; the recorded ones are in [WsContractReplayTest]. */
class ContentCommandsTest {

    private fun result(json: String): HaInbound.Result = HaProtocol.parse(json).single() as HaInbound.Result
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ---- builders ------------------------------------------------------------------------

    @Test
    fun `run and snapshot carry ids only, never a service or an entity`() {
        assertEquals(setOf("action_id"), ContentCommands.run("open-gate").keys)
        assertEquals(setOf("camera_id"), ContentCommands.snapshot("gate").keys)
        assertEquals(setOf("camera_id", "width"), ContentCommands.snapshot("gate", 480).keys)
    }

    @Test
    fun `the phone's own calls name no endpoint, and a named one is the kiosk card's and carries only the id`() {
        assertEquals(emptySet<String>(), ContentCommands.get().keys, "the endpoint-less call is the phone's own")
        assertEquals(emptySet<String>(), ContentCommands.subscribe().keys)
        assertEquals(setOf("endpoint"), ContentCommands.get("kitchen").keys)
        assertEquals(setOf("action_id", "endpoint"), ContentCommands.run("evening", "kitchen").keys)
        assertEquals(setOf("camera_id", "width", "endpoint"), ContentCommands.snapshot("c-30102768d913", 320, "kitchen").keys)
        // contract 2's `profile` and `pages` parameters are refused by the server; no builder can produce them
        for (b in listOf(ContentCommands.get("x"), ContentCommands.run("a", "x"), ContentCommands.snapshot("a", null, "x"))) {
            assertTrue("profile" !in b.keys && "pages" !in b.keys)
        }
        assertThrows<IllegalArgumentException> { ContentCommands.get("") }
        assertThrows<IllegalArgumentException> { ContentCommands.run("a", "x".repeat(65)) }
    }

    @Test
    fun `builders refuse an unusable id or width before the wire`() {
        assertThrows<IllegalArgumentException> { ContentCommands.run("") }
        assertThrows<IllegalArgumentException> { ContentCommands.run("x".repeat(65)) }
        assertThrows<IllegalArgumentException> { ContentCommands.snapshot("") }
        assertThrows<IllegalArgumentException> { ContentCommands.snapshot("gate", 63) }
        assertThrows<IllegalArgumentException> { ContentCommands.snapshot("gate", 1921) }
    }

    @Test
    fun `the snapshot width guard is the server's 64 to 1920 since question 7, edges included`() {
        assertEquals(64, ContentCommands.SNAPSHOT_WIDTH_MIN)
        assertEquals(1920, ContentCommands.SNAPSHOT_WIDTH_MAX)
        for (w in listOf(64, 640, 960, 1280, 1281, 1920)) assertEquals(setOf("camera_id", "width"), ContentCommands.snapshot("gate", w).keys, "$w")
        for (w in listOf(0, 63, 1921, 4096, -1)) assertThrows<IllegalArgumentException> { ContentCommands.snapshot("gate", w) }
    }

    // ---- parsing -------------------------------------------------------------------------

    @Test
    fun `a profile without the optional fields reads with safe defaults`() {
        val outcome = ContentCommands.parseGet(
            result("""{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[{"id":"p","label":"P","cameras":[],"actions":[{"id":"a","label":"A","kind":"lock_open"}]}]}}"""),
        ) as ContentCommands.Outcome.Profile
        val action = outcome.profile.actions.single()
        assertTrue(action.confirm, "an unreadable confirm flag asks first")
        assertFalse(action.available, "availability unknown is not available")
        assertNull(action.icon)
        assertEquals("", outcome.profile.phone)
    }

    @Test
    fun `a call action reads its target and reason, and never asks first`() {
        val outcome = ContentCommands.parseGet(
            result("""{"id":1,"type":"result","success":true,"result":{"rev":"r1","pages":[{"id":"p","label":"P","cameras":[],"actions":[
                {"id":"talk","label":"Kitchen","kind":"call","confirm":true,"available":false,"reason":"dnd","name":"Kitchen tablet","target":{"type":"dashboard","id":"ep-1"}},
                {"id":"bad","label":"Bad","kind":"call","available":true,"target":{"type":"phone"}},
                {"id":"lock","label":"Gate","kind":"lock_open","confirm":true,"target":{"type":"phone","id":"x"},"reason":"dnd","available":true}]}]}}"""),
        ) as ContentCommands.Outcome.Profile
        val (talk, bad, lock) = outcome.profile.actions
        assertTrue(talk.isCall)
        assertEquals(CallTarget("dashboard", "ep-1"), talk.target)
        assertEquals("dnd", talk.reason)
        assertFalse(talk.confirm, "CB-9: a call button never asks first, whatever the flag says")
        assertNull(bad.target, "a half target is no target")
        assertTrue(lock.confirm, "other kinds keep their flag")
        assertNull(lock.target, "target and reason belong to call buttons only")
        assertNull(lock.reason)
    }

    @Test
    fun `an entry without an id or a label is dropped, the rest kept`() {
        val outcome = ContentCommands.parseGet(
            result(
                """{"id":1,"type":"result","success":true,"result":{"rev":"r","pages":[{"label":"no id"},{"id":"p","label":"P","cameras":[{"label":"no id"},{"id":"ok","label":"OK","available":true}],"actions":[{"id":"x"},42]}]}}""",
            ),
        ) as ContentCommands.Outcome.Profile
        assertEquals(listOf("ok"), outcome.profile.cameras.map { it.id })
        assertTrue(outcome.profile.actions.isEmpty())
    }

    @Test
    fun `malformed results are failures, not crashes`() {
        for (body in listOf(
            """{"id":1,"type":"result","success":true,"result":null}""",
            """{"id":1,"type":"result","success":true,"result":{"pages":[]}}""",
            """{"id":1,"type":"result","success":true,"result":"text"}""",
        )) {
            val f = ContentCommands.parseGet(result(body)) as ContentCommands.Outcome.Failed
            assertEquals("malformed_result", f.code, body)
        }
        assertEquals("malformed_result", (ContentCommands.parseRun(result("""{"id":1,"type":"result","success":true,"result":{"ran":false}}""")) as ContentCommands.Outcome.Failed).code)
        assertEquals("malformed_result", (ContentCommands.parseSnapshot(result("""{"id":1,"type":"result","success":true,"result":{"content_type":"image/jpeg","content":"!!not base64!!"}}""")) as ContentCommands.Outcome.Failed).code)
        assertEquals("malformed_result", (ContentCommands.parseSnapshot(result("""{"id":1,"type":"result","success":true,"result":{"content":"AAAA"}}""")) as ContentCommands.Outcome.Failed).code)
    }

    @Test
    fun `an older server without the commands is UNKNOWN_COMMAND`() {
        val f = ContentCommands.parseGet(result("""{"id":1,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")) as ContentCommands.Outcome.Failed
        assertEquals(ContentCommands.ErrorKind.UNKNOWN_COMMAND, f.kind)
    }

    @Test
    fun `every wire code the contract records for the content commands has its own kind`() {
        @Suppress("UNCHECKED_CAST")
        val codes = WsContract.cases.filter { it.type in setOf(ContentCommands.GET, ContentCommands.RUN, ContentCommands.SNAPSHOT) }.map { it.receive.first() }
            .filter { it["success"] == false }.map { (it["error"] as Map<String, Any?>)["code"] as String }.toSet()
        // "not_available" is a Talk button run while its gate is off, "not_allowed" a Talk run by a login that is not the phone's own (blocks B7a): each has a kind of its own since B7b.
        assertEquals(setOf("unauthorized", "unknown_endpoint", "invalid_format", "rate_limited", "unknown_action", "unavailable", "unknown_camera", "not_available", "not_allowed"), codes)
        for (code in codes) assertEquals(code, ContentCommands.ErrorKind.of(code).wire, code)
        for (code in listOf("no_door", "not_connected", "busy")) assertEquals(code, ContentCommands.ErrorKind.of(code).wire, "a Talk refusal the server can give: $code")
        assertEquals(ContentCommands.ErrorKind.OTHER, ContentCommands.ErrorKind.of("not_supported"), "a device's own code is OTHER")
    }

    @Test
    fun `a snapshot's toString never prints the picture`() {
        val s = ContentCommands.Outcome.Snapshot("image/jpeg", ByteArray(5000) { 7 })
        assertEquals("Snapshot(image/jpeg, 5000 bytes)", s.toString())
    }

    @Test
    fun `content_changed is recognised, with its endpoint and rev or without them`() {
        assertEquals(ContentChange("ep-alice", "abc"), ContentCommands.parseChanged(obj("""{"type":"content_changed","endpoint":"ep-alice","rev":"abc"}""")))
        assertEquals(ContentChange("ep-alice", null), ContentCommands.parseChanged(obj("""{"type":"content_changed","endpoint":"ep-alice","rev":null}""")))
        assertEquals(ContentChange(null, null), ContentCommands.parseChanged(obj("""{"type":"content_changed","endpoint":null,"rev":null}""")), "an admin's every-change event")
        assertEquals(ContentChange(null, null), ContentCommands.parseChanged(obj("""{"type":"content_changed"}""")))
        assertEquals(ContentChange(null, "r"), ContentCommands.parseChanged(obj("""{"type":"content_changed","endpoint":"","rev":"r"}""")), "a blank endpoint names none")
        assertNull(ContentCommands.parseChanged(obj("""{"type":"call_state_changed","state":"idle"}""")))
    }

    @Test
    fun `a contract 3 document keeps the first page flat for Home and the rest for A2`() {
        val outcome = ContentCommands.parseGet(
            result(
                """{"id":1,"type":"result","success":true,"result":{"endpoint":"ep-1","kind":"phone","rev":"r9","home_name":"Villa","future_key":{"x":1},
                "pages":[{"id":"home","label":"Home","unknown":1,"cameras":[{"id":"a","label":"A","entity_id":"camera.a","available":true}],"actions":[{"id":"x","label":"X","kind":"scene_activate","confirm":false,"available":true}]},
                         {"id":"two","label":"Two","cameras":[],"actions":[{"id":"y","label":"Y","kind":"script_run"}]}],
                "callers":{"fake:g":{"name":"Gate","kind":"door","cameras":[{"id":"c-1","label":"Gate"}],"ring_buttons":[{"id":"x","label":"X","kind":"scene_activate"}],"call_buttons":[]},"*":{"name":null,"kind":null}, "bad":7},
                "roster":[{"action_id":"r-1","id":"kitchen","kind":"dashboard","name":"Kitchen","available":true},{"id":"no action id"}]}}""",
            ),
        ) as ContentCommands.Outcome.Profile
        val p = outcome.profile
        assertEquals(listOf("home", "two"), p.pages.map { it.id })
        assertEquals(listOf("a"), p.cameras.map { it.id }, "the flat cameras are the first page's")
        assertEquals(listOf("x"), p.actions.map { it.id })
        assertEquals("Villa", p.homeName)
        assertEquals(setOf("fake:g", "*"), p.callers.keys, "a caller that is not an object is dropped, the rest kept")
        assertEquals(listOf("c-1"), p.callers.getValue("fake:g").cameras.map { it.id })
        assertEquals(listOf("x"), p.callers.getValue("fake:g").ringButtons.map { it.id })
        assertEquals(listOf(RosterEntry("r-1", "kitchen", "dashboard", "Kitchen", true, null)), p.roster)
    }

    @Test
    fun `a document without pages, callers or roster is an empty profile, not a failure`() {
        val p = (ContentCommands.parseGet(result("""{"id":1,"type":"result","success":true,"result":{"rev":"r"}}""")) as ContentCommands.Outcome.Profile).profile
        assertTrue(p.isEmpty)
        assertTrue(p.pages.isEmpty() && p.callers.isEmpty() && p.roster.isEmpty())
        assertNull(p.endpoint)
    }

    // ---- the link ------------------------------------------------------------------------

    private class Wire : WsTransport {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean = sent.add(text)
        override fun close(code: Int, reason: String) {}

        @Suppress("UNCHECKED_CAST")
        fun ofType(type: String) = sent.map { MiniJson.parse(it) as Map<String, Any?> }.filter { it["type"] == type }
    }

    private class Harness {
        val wire = Wire()
        var content: ContentCommands.Outcome? = null
        val runs = mutableListOf<Pair<String, ContentCommands.Outcome>>()
        val snaps = mutableListOf<Pair<String, ContentCommands.Outcome>>()
        val link = PorticoLink(
            RegistrationMachine(RegistrationConfig("inst", "proj", "0.1", "com.meda.portico"), InMemoryRegistrationStore(value = RegValue(RegKind.FID, "FAKE-fid-1")), { 0L }, { _, _ -> }),
            DoorsCache(InMemoryDoorsCacheStore()),
            { },
            object : RegistrationSource {
                override fun requestFid() {}
                override fun requestToken() {}
            },
            object : PorticoLink.Listener {
                override fun onContent(outcome: ContentCommands.Outcome) { content = outcome }
                override fun onContentRun(actionId: String, outcome: ContentCommands.Outcome) { runs += actionId to outcome }
                override fun onSnapshot(cameraId: String, outcome: ContentCommands.Outcome) { snaps += cameraId to outcome }
            },
        )

        /** Connects, answers version and doors; the link then sends the two subscriptions and the first content get. */
        fun connect() {
            link.onSocketOpen(wire, HA_TOKEN)
            link.onSocketText(WsContract.AUTH_REQUIRED)
            link.onSocketText(WsContract.AUTH_OK)
            link.onSocketText(WsContract.reply("version: phone", id(Handshake.VERSION)))
            link.onSocketText(WsContract.reply("doors: enrolled phone", id(Handshake.DOORS)))
        }

        fun id(type: String): Int = (wire.ofType(type).last()["id"] as Long).toInt()
        fun gets(): Int = wire.ofType(ContentCommands.GET).size

        fun answerGet(rev: String) =
            link.onSocketText("""{"id":${id(ContentCommands.GET)},"type":"result","success":true,"result":{"endpoint":"ep-1","kind":"phone","rev":"$rev","pages":[]}}""")

        fun subscribeId(): Int = id(Handshake.CONTENT_SUBSCRIBE)
        fun changed(rev: String?, endpoint: String? = "ep-1") =
            link.onSocketText(
                """{"id":${subscribeId()},"type":"event","event":{"type":"content_changed","endpoint":${endpoint?.let { "\"$it\"" } ?: "null"},"rev":${rev?.let { "\"$it\"" } ?: "null"}}}""",
            )
    }

    @Test
    fun `the content is fetched once the session starts`() {
        val h = Harness()
        h.connect()
        assertEquals(1, h.gets())
        h.answerGet("r1")
        assertEquals("r1", (h.content as ContentCommands.Outcome.Profile).profile.rev)
    }

    @Test
    fun `content_changed with the rev already held is not fetched again, a new or missing one is`() {
        val h = Harness()
        h.connect()
        h.answerGet("r1")
        h.changed("r1")
        assertEquals(1, h.gets(), "same rev: nothing new")
        h.changed("r2")
        assertEquals(2, h.gets(), "new rev: fetch")
        h.answerGet("r2")
        h.changed(null)
        assertEquals(3, h.gets(), "no rev: fetch to be safe")
    }

    @Test
    fun `a content_changed for another endpoint is not this phone's, one for no endpoint is`() {
        val h = Harness()
        h.connect()
        h.answerGet("r1")
        h.changed("r2", endpoint = "kitchen")
        assertEquals(1, h.gets(), "a phone hears its own endpoint only; another's change is ignored")
        h.changed("r2", endpoint = null)
        assertEquals(2, h.gets(), "an unnamed endpoint is read as ours")
        h.answerGet("r2")
        h.changed("r2", endpoint = "ep-1")
        assertEquals(2, h.gets(), "our endpoint, the rev we hold: nothing new")
    }

    @Test
    fun `a content_changed on the calls subscription is not a content change, and the other way round`() {
        val h = Harness()
        h.connect()
        h.answerGet("r1")
        // an event on the calls subscription's id that looks like a content change must not refetch
        h.link.onSocketText("""{"id":${h.id(Handshake.CALLS_SUBSCRIBE)},"type":"event","event":{"type":"content_changed","endpoint":"ep-1","rev":"zzz"}}""")
        assertEquals(1, h.gets())
        // and a call event on the content subscription's id is not a call
        h.link.onSocketText("""{"id":${h.subscribeId()},"type":"event","event":{"type":"call_state_changed","door_id":"d","call_id":"c","state":"ringing"}}""")
        assertEquals(1, h.gets())
    }

    @Test
    fun `a run or snapshot in flight when the socket closes is reported lost, and nothing is queued`() {
        val h = Harness()
        h.connect()
        assertTrue(h.link.runAction("open-gate"))
        assertTrue(h.link.requestSnapshot("gate"))
        h.link.onSocketClosed("test")
        assertEquals(listOf("open-gate" to ContentCommands.Outcome.Lost), h.runs)
        assertEquals(listOf("gate" to ContentCommands.Outcome.Lost), h.snaps)
        assertFalse(h.link.runAction("open-gate"), "not connected: false, and nothing is queued for later")
        assertFalse(h.link.requestSnapshot("gate"))
        assertFalse(h.link.requestContent())
    }

    @Test
    fun `an unusable id is refused locally and sends nothing`() {
        val h = Harness()
        h.connect()
        assertFalse(h.link.runAction(""))
        assertFalse(h.link.requestSnapshot("x".repeat(65)))
        assertTrue(h.wire.ofType(ContentCommands.RUN).isEmpty() && h.wire.ofType(ContentCommands.SNAPSHOT).isEmpty())
    }

    @Test
    fun `a server without the commands leaves the app working`() {
        val h = Harness()
        h.connect()
        h.link.onSocketText("""{"id":${h.id(ContentCommands.GET)},"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")
        assertEquals(ContentCommands.ErrorKind.UNKNOWN_COMMAND, (h.content as ContentCommands.Outcome.Failed).kind)
        assertTrue(h.link.isReady)
    }

    @Test
    fun `a result for an unknown id is ignored`() {
        val h = Harness()
        h.connect()
        h.link.onSocketText("""{"id":999,"type":"result","success":true,"result":{"ran":true}}""")
        assertTrue(h.runs.isEmpty())
    }

    @Test
    fun `results are matched to their own request when several are in flight`() {
        val h = Harness()
        h.connect()
        h.link.runAction("a")
        val idA = h.id(ContentCommands.RUN)
        h.link.runAction("b")
        val idB = h.id(ContentCommands.RUN)
        // answered out of order
        h.link.onSocketText("""{"id":$idB,"type":"result","success":false,"error":{"code":"unavailable","message":"x"}}""")
        h.link.onSocketText("""{"id":$idA,"type":"result","success":true,"result":{"ran":true}}""")
        assertEquals(ContentCommands.Outcome.Ran, h.runs.first { it.first == "a" }.second)
        assertEquals(ContentCommands.ErrorKind.UNAVAILABLE, (h.runs.first { it.first == "b" }.second as ContentCommands.Outcome.Failed).kind)
    }
}
