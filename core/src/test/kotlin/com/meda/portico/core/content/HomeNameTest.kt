package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.HomeName
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The home's name (an optional top-level string `home_name` in the result of `portico/content/get`,
 * trimmed, at most 40 characters): how it is read, cached and handed to the Home screen. Sample names are
 * arbitrary; nothing in the app knows a real one.
 */
class HomeNameTest {
    private fun parse(extra: String): ContentProfile {
        val frame = """{"id":1,"type":"result","success":true,"result":{"rev":"r1","cameras":[],"actions":[]$extra}}"""
        val r = HaProtocol.parse(frame).single() as HaInbound.Result
        return (ContentCommands.parseGet(r) as ContentCommands.Outcome.Profile).profile
    }

    // ---- parsing -------------------------------------------------------------------------

    @Test
    fun `a home name is read as sent`() {
        assertEquals("Sample Villa", parse(""","home_name":"Sample Villa"""").homeName)
    }

    @Test
    fun `an absent, null, non-string or blank name is no name`() {
        assertNull(parse("").homeName)
        assertNull(parse(""","home_name":null""").homeName)
        assertNull(parse(""","home_name":42""").homeName)
        assertNull(parse(""","home_name":true""").homeName)
        assertNull(parse(""","home_name":["x"]""").homeName)
        assertNull(parse(""","home_name":{"a":"b"}""").homeName)
        assertNull(parse(""","home_name":""""").homeName)
        assertNull(parse(""","home_name":"   \t\n """").homeName)
    }

    @Test
    fun `a name is trimmed and cut to 40 characters, never rejected`() {
        assertEquals("Sample", parse(""","home_name":"  Sample  """").homeName)
        val long = "A".repeat(55)
        assertEquals("A".repeat(40), parse(""","home_name":"$long"""").homeName)
        // the cut never leaves a trailing space
        assertEquals("B".repeat(39), HomeName.clean("B".repeat(39) + " " + "C".repeat(10)))
    }

    @Test
    fun `control characters never reach the screen`() {
        assertEquals("Two Lines", HomeName.clean("Two\nLines"))
        assertEquals("Tab Bed", HomeName.clean("Tab\u0000\tBed"))
        assertEquals("AB", HomeName.clean("A\u200E\u202EB"), "bidi marks are dropped, not turned into spaces")
        assertNull(HomeName.clean("\u0000\u0007\u001B"))
        assertFalse(HomeName.clean("x\u2028y\u2029z\u0085w")!!.any { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' })
    }

    @Test
    fun `the cut never splits an emoji`() {
        val name = "H".repeat(39) + "\uD83C\uDFE0" // 39 letters + a 2-char house emoji = 40 code points, 41 chars
        assertEquals(name, HomeName.clean(name))
        val cut = HomeName.clean("H".repeat(39) + "\uD83C\uDFE0\uD83C\uDFE0")!!
        assertEquals(40, cut.codePointCount(0, cut.length))
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun `the name is plain text, markup stays markup`() {
        assertEquals("<b>Home</b> &amp;", HomeName.clean("<b>Home</b> &amp;"))
    }

    // ---- the cache -----------------------------------------------------------------------

    private fun profile(name: String?) = ContentProfile("", "r1", emptyList(), emptyList(), name)

    @Test
    fun `the codec round trips the name and leaves it out when unset`() {
        assertEquals(profile("Sample Villa"), ContentCodec.decode(ContentCodec.encode(profile("Sample Villa"))))
        assertEquals(profile(null), ContentCodec.decode(ContentCodec.encode(profile(null))))
        assertFalse(ContentCodec.encode(profile(null)).contains("home_name"))
    }

    @Test
    fun `a profile stored before the name existed still loads, without one`() {
        val old = """{"v":3,"rev":"r9","pages":[]}"""
        assertEquals(ContentProfile("", "r9", emptyList(), emptyList()), ContentCodec.decode(old))
        assertNull(ContentCodec.decode(old)!!.homeName)
        assertNull(ContentCodec.decode("""{"v":2,"rev":"r9","pages":[]}"""), "the cache before blocks is dropped")
        // contract 2's flat form is dropped, never half-read: its ids do not exist on a contract-3 server
        assertNull(ContentCodec.decode("""{"v":1,"phone":"P","rev":"r9","cameras":[],"actions":[]}"""))
    }

    @Test
    fun `a damaged stored name is ignored and the rest loads`() {
        for (bad in listOf("""123""", """null""", """["x"]""", """"   """", "\"\\u0007\"")) {
            val p = ContentCodec.decode("""{"v":3,"rev":"r","pages":[],"home_name":$bad}""")
            assertEquals("r", p!!.rev, bad)
            assertNull(p.homeName, bad)
        }
    }

    @Test
    fun `a changed name is a changed profile, so the repository saves it`() {
        assertTrue(profile("A") != profile("B"))
        assertTrue(profile("A") != profile(null))
    }

    // ---- the model -----------------------------------------------------------------------

    private fun view(p: ContentProfile, link: LinkState = LinkState.CONNECTED) =
        HomeModel.build(p, link, ContentSupport.YES, true, emptySet(), emptySet())

    @Test
    fun `the view carries the name, in every link state, or null`() {
        assertEquals("Sample Villa", view(profile("Sample Villa")).homeName)
        assertNull(view(profile(null)).homeName)
        for (l in LinkState.entries) assertEquals("Sample Villa", view(profile("Sample Villa"), l).homeName, l.name)
    }

    @Test
    fun `the view never trusts a name that bypassed the parser`() {
        assertNull(view(profile("   ")).homeName)
        assertEquals("Ab", view(profile("A\u0000b")).homeName?.replace(" ", ""))
        assertEquals(40, view(profile("Z".repeat(90))).homeName!!.length)
    }

    // ---- set, changed, cleared (the plugin folds the name into rev and sends content_changed) ----

    @Test
    fun `name set then cleared - the title goes back to Portico, and each step is a new profile that is saved`() {
        val store = InMemoryContentStore()
        val repo = ContentRepository(store)
        repo.onContent(ContentCommands.Outcome.Profile(parse("")))
        assertNull(view(repo.profile).homeName, "unset: the screen keeps 'Portico'")
        repo.onContent(ContentCommands.Outcome.Profile(parse(""","home_name":"Sample Villa"""").copy(rev = "r2")))
        assertEquals("Sample Villa", view(repo.profile).homeName)
        assertEquals("Sample Villa", ContentRepository(store).profile.homeName, "and it survives a restart")
        repo.onContent(ContentCommands.Outcome.Profile(parse("").copy(rev = "r3")))
        assertNull(view(repo.profile).homeName, "cleared: back to 'Portico'")
        assertNull(ContentRepository(store).profile.homeName, "the cache forgets it too")
        assertEquals(3, store.saves)
    }

    @Test
    fun `a name-only change is a new rev, so the app refetches on the event`() {
        val a = parse(""","home_name":"One"""")
        val b = a.copy(rev = "r-with-two", homeName = "Two")
        assertTrue(a.rev != b.rev && a != b)
        val ev = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"content_changed","endpoint":"ep","rev":"${b.rev}"}""") as kotlinx.serialization.json.JsonObject
        assertEquals(b.rev, ContentCommands.parseChanged(ev)?.rev, "the event carries the new rev; PorticoLink refetches whenever it differs from the held one")
    }
}
