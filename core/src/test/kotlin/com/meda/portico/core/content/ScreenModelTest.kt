package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCaller
import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentCommands.Outcome
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.Label
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A2: the pure rules behind the pager, the ring and in-call screens and the still refresh. Sample names only. */
class ScreenModelTest {

    private fun cam(id: String, label: String = id, available: Boolean = true, name: String? = null) = ContentCamera(id, label, name, available)
    private fun act(id: String, kind: String = "script_run", label: String = id, available: Boolean = true, confirm: Boolean = false) =
        ContentAction(id, label, kind, null, confirm, null, available)

    private val doorEndpoint = "portico_fake:gate-1"
    private val open = act("a-open", "portico_open", "Open gate")
    private val light = act("a-light", "script_run", "Porch light")
    private val gateCam = cam("c-000000000001", "Gate", name = "Gate camera")
    private val yardCam = cam("c-000000000002", "Gate 2")

    private fun profile(
        pages: List<ContentPage> = listOf(ContentPage("p1", "Home", listOf(cam("k1")), listOf(act("a1")))),
        callers: Map<String, ContentCaller> = emptyMap(),
    ) = ContentProfile(
        "", "r1", pages.firstOrNull()?.cameras ?: emptyList(), pages.firstOrNull()?.actions ?: emptyList(),
        pages = pages, callers = callers,
    )

    private fun gateCaller(ring: List<ContentAction> = listOf(open, light), call: List<ContentAction> = listOf(open)) =
        ContentCaller("Front gate", "door", listOf(gateCam, yardCam), ring, call)

    // ---- the pager -----------------------------------------------------------------------

    private val three = listOf(
        ContentPage("p1", "Home", emptyList(), emptyList()),
        ContentPage("p2", "Garden", emptyList(), emptyList()),
        ContentPage("p3", "Garage", emptyList(), emptyList()),
    )

    @Test
    fun `the selected page is kept by id, and an unknown or absent id is the first page`() {
        assertEquals(1, Pager.select(three, "p2"))
        assertEquals(2, Pager.select(three, "p3"))
        assertEquals(0, Pager.select(three, "gone"), "a page the editor removed falls back to the first")
        assertEquals(0, Pager.select(three, null))
        assertEquals(0, Pager.select(emptyList(), "p2"))
    }

    @Test
    fun `tabs exist only for two pages or more, and a blank label is named by its position`() {
        assertTrue(Pager.tabs(emptyList()).isEmpty())
        assertTrue(Pager.tabs(three.take(1)).isEmpty())
        assertEquals(listOf("Home", "Garden", "Garage"), Pager.tabs(three).map { it.label })
        val blank = listOf(ContentPage("a", "  \n ", emptyList(), emptyList()), ContentPage("b", "Garden", emptyList(), emptyList()))
        assertEquals(listOf("1", "Garden"), Pager.tabs(blank).map { it.label })
        val long = listOf(ContentPage("a", "L".repeat(80), emptyList(), emptyList()), ContentPage("b", "B", emptyList(), emptyList()))
        assertEquals(Label.TAB_MAX, Pager.tabs(long)[0].label.length)
    }

    @Test
    fun `stepping holds inside the pages and never wraps`() {
        assertEquals(1, Pager.step(3, 0, 1))
        assertEquals(2, Pager.step(3, 2, 1), "no wrap past the last page")
        assertEquals(0, Pager.step(3, 0, -1), "no wrap before the first page")
        assertEquals(0, Pager.step(0, 0, 1))
    }

    @Test
    fun `a swipe is a long, mostly horizontal drag - left is next, right is previous`() {
        assertEquals(1, Swipe.direction(-300f, 20f, 100f))
        assertEquals(-1, Swipe.direction(300f, -20f, 100f))
        assertEquals(0, Swipe.direction(-50f, 0f, 100f), "too short")
        assertEquals(0, Swipe.direction(-300f, 400f, 100f), "a vertical scroll is never a page change")
        assertEquals(0, Swipe.direction(-300f, 200f, 100f), "not horizontal enough: dx must be at least twice dy")
        assertEquals(1, Swipe.direction(-300f, 150f, 100f))
    }

    @Test
    fun `Home draws the selected page only, with its tabs`() {
        val pages = listOf(
            ContentPage("p1", "Home", listOf(cam("k1")), listOf(act("a1"))),
            ContentPage("p2", "Garden", listOf(cam("k2"), cam("k3")), listOf(act("a2"), act("a3"))),
        )
        val p = profile(pages)
        fun view(sel: String?) = HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), null, sel)
        assertEquals(listOf("k1"), view(null).cameras.map { it.id })
        assertEquals(listOf("k2", "k3"), view("p2").cameras.map { it.id })
        assertEquals(listOf("a2", "a3"), view("p2").buttons.map { it.id })
        assertEquals(listOf("Home", "Garden"), view("p2").tabs.map { it.label })
        assertEquals(1, view("p2").selected)
        assertEquals("p2", view("p2").selectedId)
        assertEquals(listOf("k1"), view("gone").cameras.map { it.id }, "an unknown page is the first")
        assertEquals("p1", view("gone").selectedId)
    }

    @Test
    fun `a one-page document has no tabs, and a flat profile is one page`() {
        val v = HomeModel.build(profile(), LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet())
        assertTrue(v.tabs.isEmpty())
        assertEquals(listOf("k1"), v.cameras.map { it.id })
        val flat = ContentProfile("", "r", listOf(cam("f1")), listOf(act("fa")))
        val fv = HomeModel.build(flat, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet())
        assertEquals(listOf("f1"), fv.cameras.map { it.id })
        assertEquals(listOf("fa"), fv.buttons.map { it.id })
    }

    @Test
    fun `no content only when every page is empty`() {
        fun banner(p: ContentProfile) = HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet()).banner
        assertEquals(Banner.NO_CONTENT, banner(profile(pages = emptyList())), "no page at all")
        assertEquals(Banner.NO_CONTENT, banner(profile(pages = listOf(ContentPage("p1", "A", emptyList(), emptyList()), ContentPage("p2", "B", emptyList(), emptyList())))))
        assertEquals(Banner.NONE, banner(profile(pages = listOf(ContentPage("p1", "A", emptyList(), emptyList()), ContentPage("p2", "B", listOf(cam("k")), emptyList())))), "a second page with a camera is content")
        assertEquals(Banner.NONE, banner(profile()))
    }

    @Test
    fun `a label is plain text of bounded length, and a blank one shows the id`() {
        val p = profile(listOf(ContentPage("p1", "Home", listOf(cam("k1", "Gate‮\ncam" + "x".repeat(100))), listOf(act("a1", label = "  ")))))
        val v = HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet())
        val label = v.cameras.single().label
        assertTrue(label.startsWith("Gate cam"), label)
        assertFalse(label.contains('‮') || label.contains('\n'))
        assertEquals(Label.MAX, label.codePointCount(0, label.length))
        assertEquals("a1", v.buttons.single().label)
        assertEquals("<b>x</b>", Label.clean("<b>x</b>", 60), "markup is not interpreted by a TextView and is shown as typed, not stripped")
    }

    // ---- callers lookup ------------------------------------------------------------------

    @Test
    fun `a door's own entry wins, then the star entry, then nothing`() {
        val own = gateCaller()
        val star = ContentCaller(null, null, listOf(cam("c-star")), listOf(light), emptyList())
        val both = profile(callers = mapOf(doorEndpoint to own, "*" to star))
        assertEquals(own, Callers.lookup(both, doorEndpoint))
        assertEquals(star, Callers.lookup(both, "portico_fake:other"), "an unknown door falls back to *")
        assertEquals(star, Callers.lookup(both, null), "an unknown endpoint id (a stale doors cache) falls back to * too")
        assertNull(Callers.lookup(profile(callers = mapOf(doorEndpoint to own)), "portico_fake:other"), "no star entry: today's screen")
        assertNull(Callers.lookup(profile(), doorEndpoint))
    }

    @Test
    fun `an empty entry for the door is an answer - it does not fall through to star`() {
        val empty = ContentCaller("Front gate", "door", emptyList(), emptyList(), emptyList())
        val star = gateCaller()
        val p = profile(callers = mapOf(doorEndpoint to empty, "*" to star))
        assertEquals(empty, Callers.lookup(p, doorEndpoint))
    }

    @Test
    fun `the ring screen gets ring_buttons and the in-call screen gets call_buttons`() {
        val p = profile(callers = mapOf(doorEndpoint to gateCaller(ring = listOf(open, light), call = listOf(light))))
        fun view(s: CallScreen) = CallerModel.build(p, doorEndpoint, s, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!
        assertEquals(listOf("a-open", "a-light"), view(CallScreen.RING).buttons.map { it.id })
        assertEquals(listOf("a-light"), view(CallScreen.IN_CALL).buttons.map { it.id })
        assertEquals("Front gate", view(CallScreen.RING).name)
        assertEquals(listOf("c-000000000001", "c-000000000002"), view(CallScreen.RING).cameras.map { it.id })
        assertNull(CallerModel.build(profile(), doorEndpoint, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet()), "no entry, no star: nothing new is drawn")
    }

    // ---- button order and grouping -------------------------------------------------------

    @Test
    fun `custom buttons keep the server's order, drop call buttons and draw a repeated id once`() {
        val call = act("a-call", "call")
        val list = listOf(light, call, open, light, act("a-scene", "scene_activate"))
        assertEquals(listOf("a-light", "a-open", "a-scene"), Callers.customButtons(list).map { it.id })
        assertEquals(emptyList<ContentAction>(), Callers.customButtons(listOf(call)))
    }

    @Test
    fun `a call-screen button follows the same rules as Home - off when unavailable, off offline, running while pressed`() {
        val dead = act("a-dead", available = false)
        val p = profile(callers = mapOf(doorEndpoint to gateCaller(ring = listOf(open, dead, light))))
        fun states(link: LinkState, running: Set<String> = emptySet()) =
            CallerModel.build(p, doorEndpoint, CallScreen.RING, link, ContentSupport.YES, running, emptySet())!!.buttons.map { it.state }
        assertEquals(listOf(ButtonState.READY, ButtonState.UNAVAILABLE, ButtonState.READY), states(LinkState.CONNECTED))
        assertEquals(List(3) { ButtonState.OFFLINE }, states(LinkState.WAITING_TO_RETRY), "nothing is queued for later")
        assertEquals(ButtonState.RUNNING, states(LinkState.CONNECTED, setOf("a-open"))[0])
        val notYours = CallerModel.build(p, doorEndpoint, CallScreen.RING, LinkState.CONNECTED, ContentSupport.NOT_ENROLLED, emptySet(), emptySet())!!
        assertTrue(notYours.buttons.all { it.state == ButtonState.OFFLINE })
    }

    @Test
    fun `the caller's pictures follow the link - none shown offline, a word while loading`() {
        val p = profile(callers = mapOf(doorEndpoint to gateCaller()))
        fun pics(link: LinkState, have: Set<String>) =
            CallerModel.build(p, doorEndpoint, CallScreen.RING, link, ContentSupport.YES, emptySet(), have)!!.cameras.map { it.picture }
        assertEquals(listOf(PictureState.PICTURE, PictureState.LOADING), pics(LinkState.CONNECTED, setOf("c-000000000001")))
        assertEquals(listOf(PictureState.OFFLINE, PictureState.OFFLINE), pics(LinkState.WAITING_TO_RETRY, setOf("c-000000000001")), "an old picture is never shown as live")
        val unavailable = profile(callers = mapOf(doorEndpoint to ContentCaller("G", "door", listOf(cam("c-1", available = false)), emptyList(), emptyList())))
        assertEquals(PictureState.CAMERA_UNAVAILABLE, CallerModel.build(unavailable, doorEndpoint, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!.cameras.single().picture)
    }

    @Test
    fun `a door name is plain text of bounded length`() {
        val p = profile(callers = mapOf(doorEndpoint to ContentCaller("Gate\u0007‮" + "n".repeat(90), "door", emptyList(), emptyList(), emptyList())))
        val name = CallerModel.build(p, doorEndpoint, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!.name!!
        assertEquals(Label.MAX, name.length)
        assertTrue(name.startsWith("Gate n"))
        val blank = profile(callers = mapOf(doorEndpoint to ContentCaller("  ", "door", emptyList(), emptyList(), emptyList())))
        assertNull(CallerModel.build(blank, doorEndpoint, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!.name)
    }

    // ---- the wire reply, end to end -------------------------------------------------------

    @Test
    fun `a contract-3 content reply draws the right screens`() {
        val frame = """{"id":1,"type":"result","success":true,"result":{"endpoint":"p-1","kind":"phone","rev":"r7","home_name":"Sample Villa",
          "pages":[{"id":"pg-1","label":"Home","cameras":[{"id":"k-1","label":"Porch","entity_id":"camera.x","available":true,"name":"Porch cam"}],"actions":[]},
                   {"id":"pg-2","label":"Garden","cameras":[],"actions":[{"id":"a-1","label":"Sprinkler","kind":"script_run","confirm":true,"available":true,"name":"Sprinkler script"}]}],
          "callers":{"$doorEndpoint":{"name":"Front gate","kind":"door","cameras":[{"id":"c-0123456789ab","label":"Front gate","name":"Gate cam","available":true}],
                      "ring_buttons":[{"id":"a-open","label":"Open","kind":"portico_open","confirm":false,"available":true,"name":"Gate"}],
                      "call_buttons":[{"id":"a-open","label":"Open","kind":"portico_open","confirm":false,"available":true,"name":"Gate"},{"id":"a-x","label":"Light","kind":"script_run","confirm":false,"available":true,"name":"Light"}]},
                     "*":{"name":null,"kind":null,"cameras":[],"ring_buttons":[],"call_buttons":[]}},
          "roster":[]}}"""
        val r = HaProtocol.parse(frame).single() as HaInbound.Result
        val doc = (ContentCommands.parseGet(r) as Outcome.Profile).profile
        val home = HomeModel.build(doc, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet(), null, "pg-2")
        assertEquals(listOf("Home", "Garden"), home.tabs.map { it.label })
        assertEquals(listOf("a-1"), home.buttons.map { it.id })
        val ring = CallerModel.build(doc, doorEndpoint, CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!
        assertEquals(listOf("Open"), ring.buttons.map { it.label })
        val inCall = CallerModel.build(doc, doorEndpoint, CallScreen.IN_CALL, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!
        assertEquals(listOf("Open", "Light"), inCall.buttons.map { it.label })
        val other = CallerModel.build(doc, "portico_fake:other", CallScreen.RING, LinkState.CONNECTED, ContentSupport.YES, emptySet(), emptySet())!!
        assertTrue(other.cameras.isEmpty() && other.buttons.isEmpty(), "the * entry says: nothing extra")
        assertNotNull(doc.findAction("a-x"), "a caller's button is found by id for its result line")
        assertEquals("Gate cam", doc.findCamera("c-0123456789ab")!!.name)
    }

    // ---- the still refresh ----------------------------------------------------------------

    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke() = now
    }

    @Test
    fun `one request in flight per camera - a second is refused until the answer`() {
        val clock = Clock()
        val gate = StillGate(clock)
        assertTrue(gate.tryBegin("c1"))
        assertFalse(gate.tryBegin("c1"), "already on its way")
        assertTrue(gate.tryBegin("c2"), "another camera is independent")
        gate.end("c1")
        assertTrue(gate.tryBegin("c1"), "the answer frees the camera")
    }

    @Test
    fun `a request whose answer never comes is forgotten, so a camera cannot stay stuck`() {
        val clock = Clock()
        val gate = StillGate(clock, staleAfterMs = 10_000L)
        assertTrue(gate.tryBegin("c1"))
        clock.now += 9_999
        assertFalse(gate.tryBegin("c1"))
        clock.now += 1
        assertTrue(gate.tryBegin("c1"), "10 s without an answer")
        gate.clear()
        assertTrue(gate.tryBegin("c1"))
        assertEquals(setOf("c1"), gate.inFlight())
    }

    @Test
    fun `the schedule asks each camera once per interval`() {
        val s = StillSchedule(1_500L)
        val asked = mutableListOf<String>()
        fun tick(now: Long) = s.tick(listOf("c1", "c2"), now) { asked += "$it@$now"; true }
        tick(0)
        tick(250)
        tick(1_499)
        assertEquals(listOf("c1@0", "c2@0"), asked)
        tick(1_500)
        assertEquals(listOf("c1@0", "c2@0", "c1@1500", "c2@1500"), asked)
    }

    @Test
    fun `a request the guard refused is offered again at the next tick, not a whole interval later`() {
        val s = StillSchedule(1_500L)
        val asked = mutableListOf<Long>()
        var accept = false
        fun tick(now: Long) = s.tick(listOf("c1"), now) { asked += now; accept }
        tick(0)
        tick(250)
        accept = true
        tick(500)
        tick(750)
        assertEquals(listOf(0L, 250L, 500L), asked)
    }

    @Test
    fun `reset asks at once, and a camera that left the screen is forgotten`() {
        val s = StillSchedule(1_500L)
        val asked = mutableListOf<String>()
        s.tick(listOf("c1"), 0) { asked += it; true }
        s.reset()
        s.tick(listOf("c1"), 10) { asked += it; true }
        assertEquals(listOf("c1", "c1"), asked)
        s.tick(listOf("c2"), 20) { asked += it; true } // c1 left
        s.tick(listOf("c1"), 30) { asked += it; true }
        assertEquals(listOf("c1", "c1", "c2", "c1"), asked, "c1 was forgotten when it left, so it is asked at once on return")
    }

    @Test
    fun `the repository lets one request per camera through, for every screen`() {
        val clock = Clock()
        val repo = ContentRepository(InMemoryContentStore(), clock)
        assertTrue(repo.beginSnapshot("c1"))
        assertFalse(repo.beginSnapshot("c1"), "the ring screen and Home share the guard")
        repo.onSnapshot("c1", Outcome.Snapshot("image/jpeg", byteArrayOf(1)))
        assertTrue(repo.beginSnapshot("c1"))
        repo.onSnapshot("c1", Outcome.Failed(ContentCommands.ErrorKind.RATE_LIMITED, "rate_limited", ""))
        assertTrue(repo.beginSnapshot("c1"), "a refusal is an answer too")
        repo.onSnapshot("c1", Outcome.Lost)
        assertTrue(repo.beginSnapshot("c1"))
        repo.onDisconnected()
        assertTrue(repo.beginSnapshot("c1"), "a closed socket drops everything in flight")
        repo.endSnapshot("c1")
        assertTrue(repo.beginSnapshot("c1"), "a request that was never sent frees the camera")
    }

    @Test
    fun `a caller's camera keeps its picture and a removed one loses it`() {
        val repo = ContentRepository(InMemoryContentStore())
        val withCaller = profile(callers = mapOf(doorEndpoint to gateCaller()))
        repo.onContent(Outcome.Profile(withCaller))
        repo.onSnapshot("c-000000000001", Outcome.Snapshot("image/jpeg", byteArrayOf(1)))
        repo.onSnapshot("k1", Outcome.Snapshot("image/jpeg", byteArrayOf(2)))
        repo.onSnapshot("stranger", Outcome.Snapshot("image/jpeg", byteArrayOf(3)))
        repo.onContent(Outcome.Profile(withCaller.copy(rev = "r2")))
        assertEquals(setOf("c-000000000001", "k1"), repo.pictureIds(), "a page camera and a caller camera stay; one the document does not name goes")
        repo.onContent(Outcome.Profile(profile().copy(rev = "r3")))
        assertEquals(setOf("k1"), repo.pictureIds(), "the caller left the document: its picture goes with it")
        assertEquals("Open gate", ContentRepository(InMemoryContentStore(withCaller)).action("a-open")?.label)
    }
}
