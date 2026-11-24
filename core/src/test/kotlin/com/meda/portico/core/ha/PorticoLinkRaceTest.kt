package com.meda.portico.core.ha

import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-811: the link race inside [PorticoLink]: a second connection is built beside the working one and swapped in, or thrown away. */
class PorticoLinkRaceTest {
    private class Fake : WsTransport {
        val sent = mutableListOf<String>()
        var closedWith: String? = null
        var promoted = false
        override fun send(text: String): Boolean = if (closedWith == null) sent.add(text) else false
        override fun close(code: Int, reason: String) { closedWith = reason }
        override fun promote() { promoted = true }
        fun json(i: Int): JsonObject = Json.parseToJsonElement(sent[i]).jsonObject
        fun types(): List<String> = sent.indices.map { json(it)["type"]!!.jsonPrimitive.content }
        fun idOf(type: String): Int = sent.indices.map { json(it) }.first { it["type"]!!.jsonPrimitive.content == type }["id"]!!.jsonPrimitive.content.toInt()
        fun count(type: String) = types().count { it == type }
    }

    private val calls = mutableListOf<CallEvent>()
    private var authenticated = 0
    private var disconnected = 0
    private var rejected = 0
    private val contentRefusals = mutableListOf<ContentCommands.ErrorKind>()
    private val journal = mutableListOf<String>()
    private val listener = object : PorticoLink.Listener {
        override fun onAuthenticated(haVersion: String?) { authenticated++ }
        override fun onDisconnected() { disconnected++ }
        override fun onAuthRejected(message: String?) { rejected++ }
        override fun onContentSubscribeRefused(kind: ContentCommands.ErrorKind) { contentRefusals += kind }
    }
    private val log = HaLog { _, _ -> }
    private val link = PorticoLink(
        RegistrationMachine(
            RegistrationConfig(WsContract.INSTALLATION, WsContract.PROJECT, WsContract.APP_VERSION),
            InMemoryRegistrationStore(value = RegValue(RegKind.FID, WsContract.REGISTRATION)),
            { 0L },
            log,
        ),
        DoorsCache(InMemoryDoorsCacheStore()),
        { calls += it },
        object : RegistrationSource {
            override fun requestFid() {}
            override fun requestToken() {}
        },
        listener,
        log,
    )

    private fun ok(id: Int) = """{"id":$id,"type":"result","success":true,"result":null}"""
    private fun version(id: Int) = """{"id":$id,"type":"result","success":true,"result":{"contract":3,"ui_version":1}}"""
    private fun doors(id: Int) = """{"id":$id,"type":"result","success":true,"result":{"doors":[{"door_id":"d1","door_ref":"r1"},{"door_id":"d2","door_ref":"r2"}]}}"""
    private fun event(id: Int, door: String, call: String?, state: String, snapshot: Boolean = false): String {
        val e = buildJsonObject {
            put("type", "call_state_changed"); put("door_id", door); put("state", state)
            if (call != null) put("call_id", call) else put("call_id", kotlinx.serialization.json.JsonNull)
            if (snapshot) put("snapshot", true)
        }
        return """{"id":$id,"type":"event","event":$e}"""
    }

    /** Runs a handshake over [t]: [feed] gets each server frame; [open]/[text] are the primary or candidate entry points. */
    private fun handshake(t: Fake, text: (String) -> Unit, snapshots: List<String> = emptyList()) {
        text(WsContract.AUTH_REQUIRED)
        text(WsContract.AUTH_OK)
        text(version(t.idOf("portico/version")))
        text(doors(t.idOf("portico/doors")))
        text(ok(t.idOf("portico/calls/subscribe")))
        text(ok(t.idOf("portico/content/subscribe")))
        for (s in snapshots) text(s)
    }

    private val old = Fake()
    private val cand = Fake()

    private fun connectOld(snapshots: List<(Int) -> String> = listOf({ id -> event(id, "d1", null, "idle", true) })) {
        link.onSocketOpen(old, WsContract.HA_TOKEN)
        handshake(old, link::onSocketText)
        val id = old.idOf("portico/calls/subscribe")
        snapshots.forEach { link.onSocketText(it(id)) }
        assertTrue(link.isReady)
    }

    private fun openCandidate(snapshots: List<(Int) -> String> = emptyList()) {
        link.openCandidate(cand, WsContract.HA_TOKEN)
        handshake(cand, link::onCandidateText)
        val id = cand.idOf("portico/calls/subscribe")
        snapshots.forEach { link.onCandidateText(it(id)) }
    }

    @Test
    fun `a candidate does the handshake, registers nothing, reads no profile and delivers nothing`() {
        connectOld()
        val oldAuth = authenticated
        val callsBefore = calls.size
        openCandidate(listOf({ id -> event(id, "d1", "c1", "ringing", true) }))
        assertTrue(link.candidateReady)
        assertEquals(listOf("auth", "portico/version", "portico/doors", "portico/calls/subscribe", "portico/content/subscribe"), cand.types())
        assertEquals(0, cand.count("portico/push/register"))
        assertEquals(0, cand.count("portico/content/get"))
        assertEquals(oldAuth, authenticated, "the listener hears nothing from a candidate")
        assertEquals(callsBefore, calls.size, "a candidate's ring waits for the swap")
        assertTrue(link.isReady, "the old link is still the working one")
        assertTrue(old.closedWith == null)
    }

    @Test
    fun `a candidate that is not subscribed yet is not ready`() {
        connectOld()
        link.openCandidate(cand, WsContract.HA_TOKEN)
        link.onCandidateText(WsContract.AUTH_REQUIRED)
        link.onCandidateText(WsContract.AUTH_OK)
        assertFalse(link.candidateReady)
        assertFalse(link.promoteCandidate())
    }

    @Test
    fun `the new link wins - a command in flight on the old one is Lost, the next goes out on the new one, the profile and push are redone`() {
        connectOld()
        var reply: Any? = "unset"
        assertTrue(link.sendCall("portico/answer", buildJsonObject { put("call_id", "c1") }) { reply = it })
        openCandidate()
        val authBefore = authenticated
        val discBefore = disconnected
        assertTrue(link.promoteCandidate())
        assertNull(reply, "the command in flight on the old link is answered Lost")
        assertEquals(discBefore + 1, disconnected)
        assertEquals(authBefore + 1, authenticated)
        assertTrue(link.isReady)
        assertFalse(link.hasCandidate)
        val oldCount = old.sent.size
        assertTrue(link.sendCall("portico/hangup", buildJsonObject { put("call_id", "c1") }) { })
        assertEquals(oldCount, old.sent.size, "nothing more goes to the old transport")
        assertEquals(1, cand.count("portico/hangup"))
        assertEquals(1, cand.count("portico/push/register"))
        assertEquals(1, cand.count("portico/content/get"))
        assertTrue(link.facts().open)
    }

    @Test
    fun `a command not yet sent when the swap happens goes out on the winner`() {
        connectOld()
        openCandidate()
        assertTrue(link.promoteCandidate())
        var got: HaInbound.Result? = null
        assertTrue(link.sendCall("portico/answer", buildJsonObject { put("call_id", "c1") }) { got = it })
        assertEquals(1, cand.count("portico/answer"))
        assertEquals(0, old.count("portico/answer"))
        link.onSocketText("""{"id":${cand.idOf("portico/answer")},"type":"result","success":true,"result":null}""")
        assertTrue(got?.success == true)
    }

    @Test
    fun `a hangup sent during the overlap goes out on the working link only, and a swap never sends it again by itself`() {
        connectOld()
        openCandidate()
        assertTrue(link.sendCall("portico/hangup", buildJsonObject { put("call_id", "c1") }) { })
        assertEquals(1, old.count("portico/hangup"))
        assertEquals(0, cand.count("portico/hangup"))
        link.promoteCandidate()
        assertEquals(0, cand.count("portico/hangup"), "a swap never sends a command again by itself")
    }

    @Test
    fun `a ring both links heard is delivered once, a ring only the candidate heard is delivered at the swap`() {
        connectOld()
        link.onSocketText(event(old.idOf("portico/calls/subscribe"), "d1", "c1", "ringing"))
        assertEquals(1, calls.count { it is CallEvent.Ringing })
        openCandidate(listOf({ id -> event(id, "d1", "c1", "ringing", true) }, { id -> event(id, "d2", "c2", "ringing", true) }))
        assertEquals(1, calls.count { it is CallEvent.Ringing })
        assertTrue(link.promoteCandidate())
        val rings = calls.filterIsInstance<CallEvent.Ringing>()
        assertEquals(listOf("c1", "c2"), rings.map { it.callId }, "c1 once, c2 at the swap")
        link.onSocketText(event(cand.idOf("portico/calls/subscribe"), "d1", "c1", "ended"))
        assertEquals(1, calls.count { it is CallEvent.Ended }, "events after the swap arrive on the new link")
    }

    @Test
    fun `a snapshot that says the call is over is still delivered at the swap`() {
        connectOld()
        link.onSocketText(event(old.idOf("portico/calls/subscribe"), "d1", "c1", "ringing"))
        openCandidate(listOf({ id -> event(id, "d1", null, "idle", true) }))
        link.promoteCandidate()
        assertTrue(calls.any { it is CallEvent.Ended && it.callId == "c1" })
    }

    @Test
    fun `an old link that answered first - the candidate is dropped and nothing changes`() {
        connectOld()
        openCandidate(listOf({ id -> event(id, "d1", "c1", "ringing", true) }))
        link.dropCandidate()
        assertFalse(link.hasCandidate)
        assertFalse(link.promoteCandidate())
        assertEquals(0, disconnected)
        assertEquals(0, calls.count { it is CallEvent.Ringing }, "the dropped candidate's ring never reaches a sink")
        assertTrue(link.isReady)
    }

    @Test
    fun `a candidate the server refuses is failed without touching the working link or the auth state`() {
        connectOld()
        link.openCandidate(cand, WsContract.HA_TOKEN)
        link.onCandidateText(WsContract.AUTH_REQUIRED)
        link.onCandidateText("""{"type":"auth_invalid","message":"x"}""")
        assertTrue(link.candidateFailed)
        assertEquals("auth_invalid", cand.closedWith)
        assertEquals(0, rejected)
        assertFalse(link.authRejected)
        assertTrue(link.isReady)
    }

    @Test
    fun `a candidate on another contract is failed, the listener is not told`() {
        connectOld()
        link.openCandidate(cand, WsContract.HA_TOKEN)
        link.onCandidateText(WsContract.AUTH_REQUIRED)
        link.onCandidateText(WsContract.AUTH_OK)
        link.onCandidateText("""{"id":${cand.idOf("portico/version")},"type":"result","success":true,"result":{"contract":2}}""")
        assertTrue(link.candidateFailed)
        assertNull(link.contractMismatch)
    }

    @Test
    fun `the old link already dropped - the candidate becomes the link with no second disconnect`() {
        connectOld()
        openCandidate()
        link.onSocketClosed("link watchdog") // the watchdog's DEAD while the candidate is on the way
        assertEquals(1, disconnected)
        assertTrue(link.hasCandidate)
        assertTrue(link.promoteCandidate())
        assertEquals(1, disconnected)
        assertTrue(link.isReady)
        assertEquals(2, authenticated)
    }

    @Test
    fun `a stream open on the working link is reported, so no race is started over a live media socket`() {
        connectOld()
        assertFalse(link.hasOpenStreams())
        link.openStream("portico/media_open", buildJsonObject { put("call_id", "c1") }, { }, { }, { })
        assertTrue(link.hasOpenStreams())
    }

    @Test
    fun `facts follow the working link, not the candidate`() {
        connectOld()
        val before = link.facts()
        link.openCandidate(cand, WsContract.HA_TOKEN)
        link.onCandidateText(WsContract.AUTH_REQUIRED)
        val mid = link.facts()
        assertTrue(mid.open)
        assertEquals(before.callsMs != null, mid.callsMs != null)
        assertTrue(mid.callsMs != null)
    }

    @Test
    fun `while a candidate exists the working link takes no command but a hangup, and a command pressed then is sent once on the winner`() {
        connectOld()
        openCandidate()
        assertFalse(link.sendCall("portico/answer", buildJsonObject { put("call_id", "c1") }) { }, "not sent: the answer path polls")
        assertFalse(link.runAction("act-1"))
        assertNull(link.openStream("portico/media_open", buildJsonObject { }, { }, { }, { }))
        assertEquals(0, old.count("portico/answer"))
        assertTrue(link.sendCall("portico/hangup", buildJsonObject { put("call_id", "c1") }) { })
        assertEquals(1, old.count("portico/hangup"))
        assertTrue(link.promoteCandidate())
        assertTrue(link.sendCall("portico/answer", buildJsonObject { put("call_id", "c1") }) { })
        assertEquals(1, cand.count("portico/answer"))
        assertEquals(0, old.count("portico/answer"))
    }

    @Test
    fun `a hangup in flight on the old link at the swap is told lost exactly once, and a late result from the old socket reaches nobody`() {
        connectOld()
        val replies = mutableListOf<HaInbound.Result?>()
        assertTrue(link.sendCall("portico/hangup", buildJsonObject { put("call_id", "c1") }) { replies += it })
        val id = old.idOf("portico/hangup")
        openCandidate()
        assertTrue(link.promoteCandidate())
        assertEquals(listOf<HaInbound.Result?>(null), replies)
        link.onSocketText("""{"id":$id,"type":"result","success":true,"result":null}""") // the new link's own id space: unknown here
        assertEquals(1, replies.size)
    }

    @Test
    fun `the ring window of the old connection is the new one's after the swap`() {
        connectOld()
        link.ringFastWatch()
        assertTrue(link.fastWatching())
        openCandidate()
        assertTrue(link.promoteCandidate())
        assertTrue(link.fastWatching(), "the fast rule must still run on the connection that took over")
    }

    @Test
    fun `the candidate's doors list is written at the swap, not before`() {
        connectOld()
        val before = link.doors.all.map { it.doorId }
        link.openCandidate(cand, WsContract.HA_TOKEN)
        link.onCandidateText(WsContract.AUTH_REQUIRED)
        link.onCandidateText(WsContract.AUTH_OK)
        link.onCandidateText(version(cand.idOf("portico/version")))
        link.onCandidateText("""{"id":${cand.idOf("portico/doors")},"type":"result","success":true,"result":{"doors":[{"door_id":"d9","door_ref":"r9"}]}}""")
        assertEquals(before, link.doors.all.map { it.doorId }, "nothing shared is written by a candidate")
        link.onCandidateText(ok(cand.idOf("portico/calls/subscribe")))
        link.onCandidateText(ok(cand.idOf("portico/content/subscribe")))
        assertTrue(link.promoteCandidate())
        assertEquals(listOf("d9"), link.doors.all.map { it.doorId })
    }

    @Test
    fun `a content subscribe the server refused is reported when the candidate takes over`() {
        connectOld()
        link.openCandidate(cand, WsContract.HA_TOKEN)
        link.onCandidateText(WsContract.AUTH_REQUIRED)
        link.onCandidateText(WsContract.AUTH_OK)
        link.onCandidateText(version(cand.idOf("portico/version")))
        link.onCandidateText(doors(cand.idOf("portico/doors")))
        link.onCandidateText(ok(cand.idOf("portico/calls/subscribe")))
        link.onCandidateText("""{"id":${cand.idOf("portico/content/subscribe")},"type":"result","success":false,"error":{"code":"unauthorized","message":"x"}}""")
        assertTrue(contentRefusals.isEmpty())
        assertTrue(link.promoteCandidate())
        assertEquals(1, contentRefusals.size)
    }

    @Test
    fun `a ring that came and went on the candidate only is nothing at the swap, and ring then answered is just answered`() {
        connectOld()
        openCandidate(
            listOf(
                { id -> event(id, "d1", "c1", "ringing", true) },
                { id -> event(id, "d1", "c1", "ended") },
                { id -> event(id, "d2", "c2", "ringing", true) },
                { id -> event(id, "d2", "c2", "answered") },
            ),
        )
        assertTrue(link.promoteCandidate())
        assertEquals(0, calls.count { it is CallEvent.Ringing && it.callId == "c1" })
        assertEquals(0, calls.count { it is CallEvent.Ended && it.callId == "c1" })
        assertEquals(0, calls.count { it is CallEvent.Ringing && it.callId == "c2" })
        assertEquals(1, calls.count { it is CallEvent.Answered && it.callId == "c2" })
    }
}
