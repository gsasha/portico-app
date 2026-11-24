package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCommands.ErrorKind
import com.meda.portico.core.ha.ContentCommands.Outcome
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentTest {

    private val gate = ContentCamera("gate", "Gate", "Gate camera", available = true)
    private val garage = ContentCamera("garage", "Garage", "Garage", available = false)
    private val open = ContentAction("open", "Front door", "lock_open", icon = "mdi:door", confirm = true, name = "Front door lock", available = true)
    private val scene = ContentAction("evening", "Evening", "scene_activate", icon = null, confirm = false, name = "Evening", available = true)
    private val broken = ContentAction("garage-door", "Garage door", "script_run", icon = null, confirm = true, name = "Open garage", available = false)
    // As parseGet builds it: the flat fields are the first page's. The cache holds the pages (A2), not a phone name.
    private val profile = ContentProfile(
        "", "r1", listOf(gate, garage), listOf(open, scene, broken),
        pages = listOf(ContentPage("main", "Main", listOf(gate, garage), listOf(open, scene, broken))),
    )

    // ---- codec ---------------------------------------------------------------------------

    @Test
    fun `the codec round trips a profile`() {
        assertEquals(profile, ContentCodec.decode(ContentCodec.encode(profile)))
        assertEquals(ContentProfile("", "r", emptyList(), emptyList()), ContentCodec.decode(ContentCodec.encode(ContentProfile("", "r", emptyList(), emptyList()))))
    }

    @Test
    fun `a damaged or unknown cache is nothing, never a crash`() {
        for (bad in listOf(null, "", "   ", "garbage", "[]", "{}", """{"v":4,"rev":"r"}""", """{"v":3}""", """{"v":2,"rev":"r","pages":[]}""", """{"v":1,"rev":"r","cameras":[],"actions":[]}""")) {
            assertNull(ContentCodec.decode(bad), bad.toString())
        }
        // a wrong-typed list reads as empty, the rest loads
        assertEquals(ContentProfile("", "r", emptyList(), emptyList()), ContentCodec.decode("""{"v":3,"rev":"r","pages":"x","callers":[],"roster":{}}"""))
    }

    @Test
    fun `the whole document is cached as one blob - pages, callers and roster come back`() {
        val ring = ContentAction("open-gate", "Open gate", "portico_open", icon = null, confirm = false, name = "Gate", available = true)
        val call = ContentAction("call-kitchen", "Kitchen", "call", icon = null, confirm = false, name = "Kitchen", available = false, target = com.meda.portico.core.ha.CallTarget("dashboard", "kitchen"), reason = "dnd")
        val doc = profile.copy(
            homeName = "Sample Villa", endpoint = "phone-1", kind = "phone",
            pages = profile.pages + ContentPage("p2", "Garden", listOf(gate), listOf(scene)),
            callers = mapOf(
                "portico_fake:gate-1" to com.meda.portico.core.ha.ContentCaller("Gate", "door", listOf(ContentCamera("c-0123456789ab", "Gate", "Gate camera", true)), listOf(ring), listOf(ring, call)),
                "*" to com.meda.portico.core.ha.ContentCaller(null, null, emptyList(), emptyList(), emptyList()),
            ),
            roster = listOf(com.meda.portico.core.ha.RosterEntry("r-1", "kitchen", "dashboard", "Kitchen", true, null)),
        )
        val back = ContentCodec.decode(ContentCodec.encode(doc))!!
        assertEquals(doc, back)
        assertEquals(listOf("main", "p2"), back.pages.map { it.id })
        assertEquals("dnd", back.callers.getValue("portico_fake:gate-1").callButtons[1].reason, "a call button keeps its target and reason")
    }

    @Test
    fun `the cache holds only names, ids and flags`() {
        val text = ContentCodec.encode(profile)
        for (word in listOf("entity_id", "device_id", "token", "password", "secret", "camera.", "lock.")) assertFalse(text.contains(word), word)
    }

    // ---- repository ----------------------------------------------------------------------

    @Test
    fun `the last profile survives a restart and is shown before HA answers`() {
        val store = InMemoryContentStore()
        ContentRepository(store).onContent(Outcome.Profile(profile))
        val after = ContentRepository(store)
        assertEquals(profile, after.profile)
        assertEquals(ContentSupport.UNKNOWN, after.support)
        assertFalse(after.answered, "the cache is not an answer")
    }

    @Test
    fun `an unchanged profile is not saved again`() {
        val store = InMemoryContentStore()
        val repo = ContentRepository(store)
        repo.onContent(Outcome.Profile(profile))
        repo.onContent(Outcome.Profile(profile))
        assertEquals(1, store.saves)
    }

    @Test
    fun `a camera that left the profile loses its picture`() {
        val repo = ContentRepository(InMemoryContentStore())
        repo.onContent(Outcome.Profile(profile))
        repo.onSnapshot("gate", Outcome.Snapshot("image/jpeg", byteArrayOf(1, 2)))
        repo.onSnapshot("garage", Outcome.Snapshot("image/jpeg", byteArrayOf(3)))
        repo.onContent(Outcome.Profile(profile.copy(rev = "r2", cameras = listOf(gate), pages = listOf(ContentPage("main", "Main", listOf(gate), profile.actions)))))
        assertEquals(setOf("gate"), repo.pictureIds())
    }

    @Test
    fun `support follows HA's answer and a transient failure keeps what was known`() {
        val repo = ContentRepository(InMemoryContentStore())
        repo.onContent(Outcome.Failed(ErrorKind.UNKNOWN_COMMAND, "unknown_command", ""))
        assertEquals(ContentSupport.NO, repo.support)
        repo.onContent(Outcome.Failed(ErrorKind.UNAUTHORIZED, "unauthorized", ""))
        assertEquals(ContentSupport.NOT_ENROLLED, repo.support)
        repo.onContent(Outcome.Profile(profile))
        assertEquals(ContentSupport.YES, repo.support)
        repo.onContent(Outcome.Failed(ErrorKind.FAILED, "failed", ""))
        assertEquals(ContentSupport.YES, repo.support, "a hiccup does not downgrade")
        repo.onContent(Outcome.Lost)
        assertEquals(ContentSupport.YES, repo.support)
    }

    @Test
    fun `a refused snapshot removes the picture, a rate limit keeps it`() {
        val repo = ContentRepository(InMemoryContentStore())
        repo.onSnapshot("gate", Outcome.Snapshot("image/jpeg", byteArrayOf(9)))
        repo.onSnapshot("gate", Outcome.Failed(ErrorKind.RATE_LIMITED, "rate_limited", ""))
        assertEquals(setOf("gate"), repo.pictureIds())
        repo.onSnapshot("gate", Outcome.Failed(ErrorKind.UNAVAILABLE, "unavailable", ""))
        assertTrue(repo.pictureIds().isEmpty(), "an unavailable camera must not keep looking live")
        repo.onSnapshot("gate", Outcome.Snapshot("image/jpeg", byteArrayOf(9)))
        repo.onSnapshot("gate", Outcome.Lost)
        assertTrue(repo.pictureIds().isEmpty())
    }

    @Test
    fun `a button is running until its answer, and cannot be started twice`() {
        var t = 1000L
        val repo = ContentRepository(InMemoryContentStore()) { t }
        assertTrue(repo.markRunning("open"))
        assertFalse(repo.markRunning("open"), "a second tap while it runs does nothing")
        assertEquals(setOf("open"), repo.runningIds())
        t = 2000L
        repo.onRun("open", Outcome.Ran)
        assertTrue(repo.runningIds().isEmpty())
        assertEquals(RunReport("open", RunResult.OK, 2000L), repo.lastRun)
    }

    @Test
    fun `every kind of failed run has a result the screen can word`() {
        val repo = ContentRepository(InMemoryContentStore())
        fun result(o: Outcome): RunResult {
            repo.markRunning("x")
            repo.onRun("x", o)
            return repo.lastRun!!.result
        }
        assertEquals(RunResult.NOT_CONNECTED, result(Outcome.Lost))
        assertEquals(RunResult.RATE_LIMITED, result(Outcome.Failed(ErrorKind.RATE_LIMITED, "rate_limited", "")))
        assertEquals(RunResult.UNAVAILABLE, result(Outcome.Failed(ErrorKind.UNAVAILABLE, "unavailable", "")))
        assertEquals(RunResult.NOT_ALLOWED, result(Outcome.Failed(ErrorKind.UNKNOWN_ACTION, "unknown_action", "")))
        assertEquals(RunResult.NOT_ALLOWED, result(Outcome.Failed(ErrorKind.UNAUTHORIZED, "unauthorized", "")))
        assertEquals(RunResult.FAILED, result(Outcome.Failed(ErrorKind.OTHER, "not_supported", "")))
        assertEquals(RunResult.FAILED, result(Outcome.Failed(ErrorKind.FAILED, "failed", "")))
        repo.markRunning("x")
        repo.onRunNotSent("x")
        assertEquals(RunResult.NOT_CONNECTED, repo.lastRun!!.result)
    }

    @Test
    fun `a disconnect drops the pictures and the in-flight presses, and keeps the cached profile`() {
        val repo = ContentRepository(InMemoryContentStore())
        repo.onContent(Outcome.Profile(profile))
        repo.onSnapshot("gate", Outcome.Snapshot("image/jpeg", byteArrayOf(1)))
        repo.markRunning("open")
        repo.onDisconnected()
        assertTrue(repo.pictureIds().isEmpty())
        assertTrue(repo.runningIds().isEmpty())
        assertEquals(profile, repo.profile)
        assertFalse(repo.answered)
        assertEquals(ContentSupport.UNKNOWN, repo.support)
    }

    @Test
    fun `a new enrollment clears everything including the stored copy`() {
        val store = InMemoryContentStore()
        val repo = ContentRepository(store)
        repo.onContent(Outcome.Profile(profile))
        repo.onSnapshot("gate", Outcome.Snapshot("image/jpeg", byteArrayOf(1)))
        repo.reset()
        assertEquals(ContentProfile.EMPTY, repo.profile)
        assertNull(store.saved)
        assertTrue(repo.pictureIds().isEmpty())
        assertNull(repo.lastRun)
    }

    @Test
    fun `a listener that throws does not break the link, and unlisten stops it`() {
        val repo = ContentRepository(InMemoryContentStore())
        var calls = 0
        val boom: () -> Unit = { throw IllegalStateException("screen gone") }
        val count: () -> Unit = { calls++ }
        repo.listen(boom)
        repo.listen(count)
        repo.onContent(Outcome.Profile(profile))
        assertEquals(1, calls)
        repo.unlisten(count)
        repo.onContent(Outcome.Profile(profile.copy(rev = "r2")))
        assertEquals(1, calls)
    }

    @Test
    fun `a store that throws is survived on load and on save`() {
        val bad = object : ContentStore {
            override fun load(): ContentProfile? = throw IllegalStateException("prefs")
            override fun save(profile: ContentProfile) = throw IllegalStateException("prefs")
            override fun clear() = throw IllegalStateException("prefs")
        }
        val repo = ContentRepository(bad)
        assertEquals(ContentProfile.EMPTY, repo.profile)
        repo.onContent(Outcome.Profile(profile))
        assertEquals(profile, repo.profile, "kept in memory")
        repo.reset()
    }

    // ---- HomeModel -----------------------------------------------------------------------

    private fun view(
        link: LinkState = LinkState.CONNECTED,
        support: ContentSupport = ContentSupport.YES,
        answered: Boolean = true,
        running: Set<String> = emptySet(),
        pictures: Set<String> = setOf("gate"),
        p: ContentProfile = profile,
    ) = HomeModel.build(p, link, support, answered, running, pictures)

    @Test
    fun `connected and answered shows live tiles and ready buttons`() {
        val v = view()
        assertEquals(Banner.NONE, v.banner)
        assertEquals(PictureState.PICTURE, v.cameras[0].picture)
        assertEquals(PictureState.CAMERA_UNAVAILABLE, v.cameras[1].picture)
        assertEquals(listOf(ButtonState.READY, ButtonState.READY, ButtonState.UNAVAILABLE), v.buttons.map { it.state })
    }

    @Test
    fun `a camera with no picture yet is loading`() {
        assertEquals(PictureState.LOADING, view(pictures = emptySet()).cameras[0].picture)
    }

    @Test
    fun `not connected keeps the tiles but turns every button off and shows no picture`() {
        for (link in listOf(LinkState.WAITING_TO_RETRY, LinkState.STOPPED)) {
            val v = view(link = link, support = ContentSupport.UNKNOWN, answered = false)
            assertEquals(Banner.OFFLINE, v.banner, link.name)
            assertEquals(profile.cameras.size, v.cameras.size, "cached tiles stay")
            assertTrue(v.buttons.all { it.state == ButtonState.OFFLINE }, link.name)
            assertTrue(v.cameras.all { it.picture == PictureState.OFFLINE }, "an old picture is never shown as live")
        }
    }

    @Test
    fun `each link state has its own banner`() {
        assertEquals(Banner.NOT_CONFIGURED, view(LinkState.NOT_CONFIGURED).banner)
        assertEquals(Banner.PAUSED, view(LinkState.PAUSED).banner)
        assertEquals(Banner.AUTH_REJECTED, view(LinkState.AUTH_REJECTED).banner)
        assertEquals(Banner.URL_REFUSED, view(LinkState.URL_REFUSED).banner)
        assertEquals(Banner.CONNECTING, view(LinkState.CONNECTING).banner)
    }

    @Test
    fun `paused and refused states never leave a working button`() {
        for (link in listOf(LinkState.PAUSED, LinkState.AUTH_REJECTED, LinkState.URL_REFUSED, LinkState.NOT_CONFIGURED, LinkState.CONNECTING)) {
            assertTrue(view(link).buttons.none { it.state == ButtonState.READY || it.state == ButtonState.RUNNING }, link.name)
        }
    }

    @Test
    fun `a server without the commands, or one that does not know this phone, turns the buttons off`() {
        val none = view(support = ContentSupport.NO)
        assertEquals(Banner.NO_SERVER_SUPPORT, none.banner)
        assertTrue(none.buttons.all { it.state == ButtonState.OFFLINE })
        val unknown = view(support = ContentSupport.NOT_ENROLLED)
        assertEquals(Banner.NOT_ENROLLED, unknown.banner)
        assertTrue(unknown.buttons.all { it.state == ButtonState.OFFLINE })
    }

    @Test
    fun `an empty profile is only a fact once HA has answered`() {
        assertEquals(Banner.NO_CONTENT, view(p = ContentProfile.EMPTY).banner)
        assertEquals(Banner.NONE, view(p = ContentProfile.EMPTY, answered = false, support = ContentSupport.UNKNOWN).banner, "not asked yet: no claim")
    }

    @Test
    fun `a running button shows running, but an unavailable one stays unavailable`() {
        val v = view(running = setOf("open", "garage-door"))
        assertEquals(ButtonState.RUNNING, v.buttons[0].state)
        assertEquals(ButtonState.RUNNING, v.buttons[2].state, "running wins while a press is in flight")
    }

    @Test
    fun `the detail line repeats HA's name only when it differs from the label`() {
        val v = view()
        assertEquals("Gate camera", v.cameras[0].detail)
        assertNull(v.cameras[1].detail, "same as the label")
        assertEquals("Front door lock", v.buttons[0].detail)
        assertNull(v.buttons[1].detail)
    }

    @Test
    fun `confirm and icon pass through to the tile`() {
        val v = view()
        assertTrue(v.buttons[0].confirm)
        assertFalse(v.buttons[1].confirm)
        assertEquals("mdi:door", v.buttons[0].icon)
    }
}
