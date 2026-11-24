package com.meda.portico.core.call

import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.push.MiniJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every refusal code the server defines for a house call has plain English, and `house_call` events are parsed without ever
 * taking a name from them (M7 3b-A). The codes are the server's: `house.py` `ERROR_CODES`, `house_bridge.py`, and every
 * `error.code` recorded in `ws_v3.json` for a house command or for a `content/run` of a roster id.
 */
class HouseRefusalTextsTest {

    /** `custom_components/portico/house.py` ERROR_CODES + `house_bridge.py` CODE_* + the end reasons of `END_REASONS`. */
    private val serverCodes = listOf(
        "not_available", "not_callable", "forbidden", "place_forbidden", "target_offline", "target_dnd", "busy", "unknown_call",
        "already_answered", "not_subscribed", "too_many_calls", "not_supported", "already_open", "no_transport",
    )

    private fun recordedErrorCodes(): Set<String> = WsContract.cases.flatMap { c ->
        c.receive.filter { it["success"] == false }.map { f -> ((f["error"] as Map<*, *>)["code"] as String) }
    }.toSet()

    @Test
    fun `every code the server defines for a house call is worded, never the generic fallback`() {
        for (code in serverCodes) {
            val f = PlaceErrors.fromCode(code, "Bob")
            assertNotEquals(PlaceFailure.Kind.OTHER, f.kind, code)
            assertEquals(code, f.code)
        }
    }

    @Test
    fun `every house-related error code recorded in the contract file is worded too`() {
        val house = WsContract.cases.filter { it.type.startsWith("portico/house/") || "house calls on" in it.name || "gate as a call target" in it.name }
        val codes = house.flatMap { c -> c.receive.filter { it["success"] == false }.map { f -> ((f["error"] as Map<*, *>)["code"] as String) } }.toSet()
        assertTrue(codes.size >= 5, "the file records several house refusals: $codes")
        for (code in codes) {
            // `unauthorized` is an ordinary user's refusal (maps to the FORBIDDEN words); the rest are the house's own
            assertNotEquals(PlaceFailure.Kind.OTHER, PlaceErrors.fromCode(code).kind, "recorded code '$code' has words")
        }
        assertTrue(recordedErrorCodes().containsAll(codes))
    }

    @Test
    fun `the words are plain - a sentence, no code, no underscores, no hso id, with the person's name where it says one`() {
        for (code in serverCodes) {
            val m = PlaceErrors.fromCode(code, "Bob").message
            assertTrue(m.endsWith("."), "$code: '$m' is a sentence")
            assertFalse("_" in m, "$code: '$m' shows no code")
            assertFalse("hso" in m, code)
            assertTrue(m.length in 12..90, "$code: '$m'")
        }
        assertEquals("Bob has Do Not Disturb on.", PlaceErrors.fromCode("target_dnd", "Bob").message)
        assertEquals("Bob is not reachable.", PlaceErrors.fromCode("target_offline", "Bob").message)
        assertEquals("Bob is on a call.", PlaceErrors.fromCode("busy", "Bob").message)
        assertEquals("Only the kitchen screen can call everyone.", PlaceErrors.fromCode("forbidden").message)
        assertEquals("The house is on too many calls at once. Try again in a moment.", PlaceErrors.fromCode("too_many_calls").message)
    }

    @Test
    fun `a name that is blank or only noise is not put in a sentence - They stands in`() {
        assertEquals("They have Do Not Disturb on.", PlaceErrors.fromCode("target_dnd", "  ").message)
        assertEquals("They are not reachable.", PlaceErrors.fromCode("target_offline", null).message)
        assertEquals("They are on a call.", PlaceErrors.fromCode("busy", null).message)
    }

    @Test
    fun `only the refusals a second press can help are retryable`() {
        assertTrue(PlaceErrors.fromCode("busy").retryable)
        assertTrue(PlaceErrors.fromCode("target_offline").retryable)
        assertTrue(PlaceErrors.fromCode("too_many_calls").retryable)
        assertFalse(PlaceErrors.fromCode("target_dnd").retryable, "pressing again does not switch their Do Not Disturb off")
        assertFalse(PlaceErrors.fromCode("forbidden").retryable)
        assertFalse(PlaceErrors.fromCode("not_callable").retryable)
        assertFalse(PlaceErrors.fromCode("already_answered").retryable)
    }

    @Test
    fun `every end reason of a house call is handled - the refusals are worded, the ordinary ends are not failures`() {
        assertEquals("Bob declined.", PlaceErrors.fromEndReason("declined", "Bob")!!.message)
        assertEquals("Bob is on a call.", PlaceErrors.fromEndReason("busy", "Bob")!!.message)
        assertEquals("Bob did not answer.", PlaceErrors.fromEndReason("no_answer", "Bob")!!.message)
        assertEquals("Bob has Do Not Disturb on.", PlaceErrors.fromEndReason("dnd", "Bob")!!.message)
        assertEquals(PlaceFailure.Kind.AUDIO_NOT_CONNECTED, PlaceErrors.fromEndReason("error", "Bob")!!.kind)
        for (ordinary in listOf("cancelled", "hangup", "answered_elsewhere", null, "a_reason_from_a_newer_server")) {
            assertNull(PlaceErrors.fromEndReason(ordinary, "Bob"), "$ordinary")
        }
    }

    // ---- the house_call event --------------------------------------------------------------------------------------

    private fun event(json: String) = (MiniJson.parse(json) as Map<*, *>).let { m -> kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject }

    @Test
    fun `a house_call event keeps its endpoint ids as opaque ids and its reason, and says whether it is a snapshot`() {
        val e = HouseCallEvent.parse(event("""{"type":"house_call","call_id":"hso-1","state":"ended","from":"kitchen","to":"ep-alice","reason":"hangup","snapshot":true}"""))!!
        assertEquals(HouseCallEvent("hso-1", "ended", "hangup", "kitchen", "ep-alice", true), e)
        val plain = HouseCallEvent.parse(event("""{"type":"house_call","call_id":"hso-1","state":"ringing","from":"kitchen","to":"everyone"}"""))!!
        assertFalse(plain.snapshot)
        assertNull(plain.reason)
        assertEquals("everyone", plain.to)
    }

    @Test
    fun `anything that is not a well-formed house_call is nothing`() {
        assertNull(HouseCallEvent.parse(event("""{"type":"call_state_changed","call_id":"hso-1","state":"ringing"}""")))
        assertNull(HouseCallEvent.parse(event("""{"type":"house_call","state":"ringing"}""")), "no call id")
        assertNull(HouseCallEvent.parse(event("""{"type":"house_call","call_id":"","state":"ringing"}""")))
        assertNull(HouseCallEvent.parse(event("""{"type":"house_call","call_id":"hso-1"}""")), "no state")
        assertNull(HouseCallEvent.parse(buildJsonObject { put("type", "talk_state") }))
        assertTrue(HouseCallEvent.isRosterChanged(event("""{"type":"house_roster_changed","rev":"abc"}""")))
        assertFalse(HouseCallEvent.isRosterChanged(event("""{"type":"house_call","call_id":"hso-1","state":"ringing"}""")))
    }
}
