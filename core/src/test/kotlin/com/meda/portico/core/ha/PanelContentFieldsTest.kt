package com.meda.portico.core.ha

import com.meda.portico.core.content.ContentCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * DEV-807 section 5 items 2 and 3 (R8, R9): `phone_endpoint`, `house_calls` and `screens` in a `content/get` result are read, cached, and
 * absent from an older server's. A result without `phone_endpoint` is what "this server does not support panel house calls" looks like.
 */
class PanelContentFieldsTest {
    private fun profile(top: String): ContentProfile {
        val text = """{"id":1,"type":"result","success":true,"result":{"rev":"r1"$top,"pages":[{"id":"p","label":"P","blocks":[{"id":"b","title":"B","cameras":[],"actions":[]}]}]}}"""
        return (ContentCommands.parseGet(HaProtocol.parse(text).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile
    }

    @Test
    fun `an older server's result has none of them`() {
        val p = profile("")
        assertNull(p.phoneEndpoint)
        assertNull(p.houseCalls)
        assertEquals(emptyList<String>(), p.screens)
    }

    @Test
    fun `the three fields are read from the result`() {
        val p = profile(""","phone_endpoint":"ep-panel","house_calls":true,"screens":["kitchen-panel-app"]""")
        assertEquals("ep-panel", p.phoneEndpoint)
        assertEquals(true, p.houseCalls)
        assertEquals(listOf("kitchen-panel-app"), p.screens)
        assertEquals(false, profile(""","house_calls":false""").houseCalls)
    }

    @Test
    fun `a hostile value reads as absent and never drops the page`() {
        val p = profile(""","phone_endpoint":5,"house_calls":"yes","screens":"x"""")
        assertNull(p.phoneEndpoint)
        assertNull(p.houseCalls)
        assertEquals(emptyList<String>(), p.screens)
        assertEquals(1, p.pages.size)
        assertEquals(listOf("a"), profile(""","screens":["a",5,null]""").screens)
    }

    @Test
    fun `the cache keeps them, and a cache written before has none`() {
        val p = profile(""","phone_endpoint":"ep-panel","house_calls":false,"screens":["s1","s2"]""")
        val back = ContentCodec.decode(ContentCodec.encode(p))!!
        assertEquals("ep-panel", back.phoneEndpoint)
        assertEquals(false, back.houseCalls)
        assertEquals(listOf("s1", "s2"), back.screens)
        val plain = ContentCodec.decode(ContentCodec.encode(profile("")))!!
        assertNull(plain.phoneEndpoint)
        assertNull(plain.houseCalls)
        assertFalse(ContentCodec.encode(profile("")).contains("phone_endpoint"))
    }

    // ---- slice 2: the screen's `wake` ----------------------------------------------------------

    @Test
    fun `wake absent is null, an empty list is empty, doors are read as references`() {
        assertNull(profile("").wake)
        assertEquals(emptyList<String>(), profile(""","wake":{"doors":[],"elements":[],"screen_call":true}""").wake)
        assertEquals(listOf("d-a", "dahua:front"), profile(""","wake":{"doors":["d-a","dahua:front"],"elements":["e1"],"self_elements":[],"screen_call":true}""").wake)
    }

    @Test
    fun `a hostile wake reads as absent or drops only the bad entries`() {
        assertNull(profile(""","wake":"all"""").wake, "not an object: absent, so the panel rings for every door and says so")
        assertEquals(emptyList<String>(), profile(""","wake":{"elements":[]}""").wake, "an object with no doors: empty, not absent")
        assertEquals(listOf("d-a"), profile(""","wake":{"doors":["d-a",5,null,""," "]}""").wake)
    }

    @Test
    fun `the cache keeps wake, including an empty one, and an old cache has none`() {
        val some = ContentCodec.decode(ContentCodec.encode(profile(""","wake":{"doors":["d-a"]}""")))!!
        assertEquals(listOf("d-a"), some.wake)
        val empty = ContentCodec.decode(ContentCodec.encode(profile(""","wake":{"doors":[]}""")))!!
        assertEquals(emptyList<String>(), empty.wake)
        val none = ContentCodec.decode(ContentCodec.encode(profile("")))!!
        assertNull(none.wake)
        assertFalse(ContentCodec.encode(profile("")).contains("wake"))
    }
}
