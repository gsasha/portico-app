package com.meda.portico.signaling

import com.meda.portico.call.LinkCommandPort
import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.call.Cancellable
import com.meda.portico.core.call.CommandReply
import com.meda.portico.core.call.PatientCommandPort
import com.meda.portico.core.auth.HttpReply
import com.meda.portico.core.auth.LoginStore
import com.meda.portico.core.auth.RefreshClient
import com.meda.portico.core.ha.Backoff
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaConnectionConfig
import com.meda.portico.core.ha.InMemoryDoorsCacheStore
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.RegistrationSource
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushJournalStore
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** DEV-811: the link race. A fake Home Assistant whose sockets can be made silent; the link's clock is moved by hand. */
class SignalingClientRaceTest {
    /** One fake HA socket. [silent]: it swallows everything (a dead tunnel). [quietPings]: it answers all but `ping`. */
    private inner class Sock(val listener: WsListener, val candidate: Boolean, val ringOnSnapshot: Boolean) : WsTransport {
        @Volatile var silent = false
        @Volatile var quietPings = false
        @Volatile var closedWith: String? = null
        @Volatile var promoted = false
        val sent = CopyOnWriteArrayList<String>()

        fun say(text: String) = listener.onText(text)

        override fun send(text: String): Boolean {
            if (closedWith != null) return false
            sent += text
            if (silent) return true
            val o = Json.parseToJsonElement(text).jsonObject
            val type = o["type"]!!.jsonPrimitive.content
            val id = o["id"]?.jsonPrimitive?.content?.toInt()
            when (type) {
                "auth" -> say("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                "ping" -> if (!quietPings) say("""{"id":$id,"type":"pong"}""")
                "portico/version" -> say("""{"id":$id,"type":"result","success":true,"result":{"contract":3}}""")
                "portico/doors" -> say("""{"id":$id,"type":"result","success":true,"result":{"doors":[{"door_id":"d1","door_ref":"r1"}]}}""")
                "portico/calls/subscribe" -> {
                    say("""{"id":$id,"type":"result","success":true,"result":null}""")
                    val state = if (ringOnSnapshot) """"state":"ringing","call_id":"c1"""" else """"state":"idle","call_id":null"""
                    say("""{"id":$id,"type":"event","event":{"type":"call_state_changed","door_id":"d1",$state,"snapshot":true}}""")
                }
                else -> if (id != null) say("""{"id":$id,"type":"result","success":true,"result":null}""")
            }
            return true
        }

        override fun close(code: Int, reason: String) { closedWith = reason }
        override fun promote() { promoted = true }
        fun count(type: String) = sent.count { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content == type }
    }

    private val socks = CopyOnWriteArrayList<Sock>()
    private val opens = AtomicInteger()
    private val candidateOpens = AtomicInteger()
    @Volatile private var candidateSilent = false
    @Volatile private var ringOnCandidate = false
    private val now = AtomicLong(1_000_000)
    private val calls = CopyOnWriteArrayList<CallEvent>()
    private val disconnects = AtomicInteger()
    /** The journal's text, replaced whole by each write (threads: the link's executor and the test's). */
    @Volatile private var journalText: String = ""
    private val journal = PushJournal(object : PushJournalStore {
        override fun read(): String? = journalText.takeIf { it.isNotEmpty() }
        override fun write(text: String): Boolean { journalText = text; return true }
    })

    private val mono = AtomicLong(0)
    private val refreshes = AtomicInteger()
    private val tokens = AccessTokens(
        object : LoginStore {
            override fun refreshToken() = "refresh-secret"
            override fun clientId() = "client"
            override fun signOut() {}
        },
        RefreshClient { _, _, _ -> refreshes.incrementAndGet(); HttpReply(200, """{"access_token":"fresh","token_type":"Bearer","expires_in":3600}""") },
        { "http://ha.lan:8123" },
        { mono.get() },
    )
    @Volatile private var useLogin = false
    private val config = object : HaConnectionConfig {
        override val haUrl: String get() = "http://ha.lan:8123"
        override val accessToken: String? get() = if (useLogin) null else "t"
        override val login: AccessTokens? get() = if (useLogin) tokens else null
    }
    private val raceAsked = AtomicInteger()

    private fun make(listener: WsListener, candidate: Boolean): WsTransport {
        val s = Sock(listener, candidate, ringOnSnapshot = candidate && ringOnCandidate)
        if (candidate) s.silent = candidateSilent
        socks += s
        listener.onOpen()
        s.say("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        return s
    }

    private val connector = object : Connector {
        override fun open(url: String, listener: WsListener): WsTransport {
            opens.incrementAndGet()
            return make(listener, false)
        }
        override fun openCandidate(url: String, listener: WsListener): WsTransport {
            candidateOpens.incrementAndGet()
            return make(listener, true)
        }
    }

    private val link = PorticoLink(
        RegistrationMachine(RegistrationConfig("i", null, "0"), InMemoryRegistrationStore(), { 0L }),
        DoorsCache(InMemoryDoorsCacheStore()),
        { calls += it },
        object : RegistrationSource {
            override fun requestFid() {}
            override fun requestToken() {}
        },
        listener = object : PorticoLink.Listener {
            override fun onDisconnected() { disconnects.incrementAndGet() }
        },
        journal = journal,
        clockMs = { now.get() },
    )

    private fun client(race: Boolean = true, timeoutMs: Long = 15_000) = SignalingClient(
        config, link, connector, Backoff(jitter = { 3_600_000 }, maxMs = 3_600_000), tickMs = 3_600_000,
        clockMs = { now.get() }, raceOnLink = { raceAsked.incrementAndGet(); race }, candidateTimeoutMs = timeoutMs,
    ).also { c = it }

    private var c: SignalingClient? = null

    private val portExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    @AfterEach
    fun tearDown() {
        c?.shutdown()
        portExec.shutdownNow()
    }

    /** The answer path's real port stack: PatientCommandPort over the link. */
    private fun answerPort() = PatientCommandPort(
        LinkCommandPort({ c!! }, { it() }),
        { delayMs, task ->
            val f = portExec.schedule({ try { task() } catch (_: Exception) { } }, delayMs, TimeUnit.MILLISECONDS)
            Cancellable { f.cancel(false) }
        },
        lostWaitMs = 8_000,
    )

    private fun answerFrames() = socks.sumOf { it.count("portico/answer") }

    private fun settle() {
        val l = CountDownLatch(1)
        c!!.post { l.countDown() }
        l.await(5, TimeUnit.SECONDS)
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            settle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $what; journal=\n$journalText")
    }

    private fun connected(race: Boolean = true, timeoutMs: Long = 15_000): Sock {
        client(race, timeoutMs).start()
        await("connected and handshaken") { c!!.state == SignalingClient.State.CONNECTED && link.isReady && journalText.contains("link-doc") }
        return socks[0]
    }

    /** A ring starts on a link silent for 1.5 s: the fast rule pings at once; then 2 s pass with no pong. */
    private fun ringAndLetPingGoUnanswered() {
        now.addAndGet(1_500)
        c!!.ringStarted()
        await("ping sent") { socks[0].count("ping") == 1 }
        now.addAndGet(2_100)
    }

    private fun call(type: String, onReply: (Boolean) -> Unit = {}): Boolean {
        val res = java.util.concurrent.atomic.AtomicBoolean()
        val l = CountDownLatch(1)
        c!!.post {
            res.set(it.sendCall(type, buildJsonObject { }) { r -> onReply(r != null) })
            l.countDown()
        }
        l.await(5, TimeUnit.SECONDS)
        return res.get()
    }

    private fun onLinkThread(block: (PorticoLink) -> Boolean): Boolean {
        val res = java.util.concurrent.atomic.AtomicBoolean()
        val l = CountDownLatch(1)
        c!!.post { res.set(block(it)); l.countDown() }
        l.await(5, TimeUnit.SECONDS)
        return res.get()
    }

    private val authOk = """{"type":"auth_ok","ha_version":"2026.10.0"}"""

    /** The candidate (made silent by [candidateSilent]) finally gets going. */
    private fun release(s: Sock) {
        s.silent = false
        s.say(authOk)
    }

    private fun journalHas(vararg words: String) = words.all { journalText.contains(it) }

    @Test
    fun `old link dead - the second connection takes over, the old one is closed and the link never leaves CONNECTED for good`() {
        val old = connected()
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("swapped") { old.closedWith != null && c!!.state == SignalingClient.State.CONNECTED && socks.size == 2 && link.isReady && socks[1].promoted }
        assertEquals(1, candidateOpens.get())
        assertEquals(1, opens.get(), "no plain reconnect was needed")
        assertTrue(journalHas("link-race-start", "link-race-won", "by=new"), journalText.toString())
        assertEquals(1, socks[1].count("portico/content/get"), "the profile is read again on the winner")
        assertEquals(0, socks[1].count("ping"), "nothing but the handshake went out before the swap")
    }

    @Test
    fun `old link slow but alive - its pong wins, the candidate is closed, nothing is delivered twice`() {
        val old = connected()
        candidateSilent = true // the candidate never gets going
        old.quietPings = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        old.say("""{"id":99,"type":"pong"}""") // any frame from the old link
        await("candidate closed") { socks[1].closedWith != null }
        assertTrue(link.isReady)
        assertEquals(0, old.closedWith?.length ?: 0)
        assertEquals(SignalingClient.State.CONNECTED, c!!.state)
        assertTrue(journalHas("link-race-start", "link-race-won", "by=old"), journalText.toString())
        assertEquals(0, disconnects.get())
    }

    @Test
    fun `both dead - the old one is dropped at the mobile deadline, the candidate times out, and the client connects again at once`() {
        val old = connected(timeoutMs = 600)
        old.silent = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        now.addAndGet(4_500) // 6.6 s since the ping: dead on a mobile link
        await("old dropped") { old.closedWith != null }
        await("candidate given up") { socks[1].closedWith != null }
        await("connects again at once") { opens.get() == 2 } // 0.6.91: no retry step after a dead link whose candidate also failed
        assertTrue(journalHas("link-race-start", "link-race-gaveup"), journalText.toString())
        await("the new plain socket is the link") { link.isReady && c!!.state == SignalingClient.State.CONNECTED }
    }

    @Test
    fun `old link dead for good while a SLOW candidate is on the way - the candidate is kept and becomes the link when it gets going`() {
        val old = connected()
        old.silent = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        now.addAndGet(4_500) // 6.6 s since the ping: the old link is dead
        await("old dropped") { old.closedWith != null }
        assertFalse(link.isReady)
        assertEquals(SignalingClient.State.CONNECTING, c!!.state)
        assertEquals(1, opens.get(), "no second plain connect while the candidate is still on the way")
        release(socks[1])
        await("candidate is the link") { link.isReady && c!!.state == SignalingClient.State.CONNECTED && socks[1].promoted }
        assertEquals(1, opens.get())
        assertEquals(0, socks[1].count("ping"))
    }

    @Test
    fun `old link dead and the candidate hung - after the give-up the client connects again at once, not after a retry step`() {
        val old = connected(timeoutMs = 600)
        old.silent = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        now.addAndGet(4_500)
        await("old dropped") { old.closedWith != null }
        await("candidate given up") { socks[1].closedWith != null }
        await("plain connect") { opens.get() == 2 }
        assertTrue(c!!.state != SignalingClient.State.WAITING_TO_RETRY)
    }

    @Test
    fun `reconnect and stop with a candidate on the way - the candidate is closed and journalled as cancelled`() {
        val old = connected()
        old.quietPings = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        c!!.reconnect()
        await("candidate closed") { socks[1].closedWith != null }
        await("new plain socket") { opens.get() == 2 }
        assertTrue(journalHas("link-race-cancel"), journalText.toString())
        c!!.stop()
        await("stopped") { c!!.state == SignalingClient.State.STOPPED }
    }

    @Test
    fun `a candidate closed by the peer - the old link carries on, no reconnect, journalled as gave up`() {
        val old = connected()
        old.quietPings = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        socks[1].listener.onClosed("peer closed")
        await("gave up") { journalHas("link-race-gaveup") }
        assertEquals(SignalingClient.State.CONNECTED, c!!.state)
        assertEquals(1, opens.get())
        assertTrue(link.isReady)
        assertEquals(null, old.closedWith)
        assertTrue(journalHas("why=closed"), journalText.toString())
    }

    @Test
    fun `a second race on the socket that won the first one`() {
        val old = connected()
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("first swap") { socks.size == 2 && socks[1].promoted && link.isReady }
        socks[1].silent = true // the winner goes quiet in its turn
        now.addAndGet(2_100)
        await("ping on the winner") { socks[1].count("ping") == 1 }
        now.addAndGet(2_100)
        await("second candidate") { socks.size == 3 }
        await("second swap") { socks[2].promoted && link.isReady && socks[1].closedWith != null }
        assertEquals(2, candidateOpens.get())
        assertEquals(1, opens.get())
    }

    @Test
    fun `a late frame of the closed old socket after the swap changes nothing`() {
        val old = connected()
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("swapped") { socks.size == 2 && socks[1].promoted && link.isReady }
        val calls0 = calls.size
        val disc0 = disconnects.get()
        old.say("""{"id":4,"type":"event","event":{"type":"call_state_changed","door_id":"d1","state":"ringing","call_id":"late-1"}}""")
        old.say("""{"id":99,"type":"pong"}""")
        old.listener.onClosed("late close")
        settle()
        assertEquals(calls0, calls.size)
        assertEquals(disc0, disconnects.get())
        assertEquals(SignalingClient.State.CONNECTED, c!!.state)
        assertTrue(link.isReady)
    }

    @Test
    fun `an enrolled phone whose access token has run low - no candidate is possible, the strict reconnect starts at 2 s and says why`() {
        useLogin = true
        tokens.seed("tok", 3_600)
        val old = connected()
        mono.set(3_400_000) // 200 s left: cached() is null from here
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("old dropped at once") { old.closedWith != null }
        await("refresh and reconnect") { refreshes.get() == 1 && opens.get() == 2 }
        assertEquals(0, candidateOpens.get())
        assertTrue(journalHas("link-race-skip", "why=token"), journalText.toString())
    }

    @Test
    fun `the ring window survives the swap - the fast rule still pings the new link within 2 s`() {
        val old = connected()
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("swapped") { socks.size == 2 && socks[1].promoted && link.isReady }
        assertTrue(onLinkThread { it.fastWatching() }, "the ring is still up")
        socks[1].silent = true
        now.addAndGet(2_100)
        await("ping on the new link") { socks[1].count("ping") == 1 }
    }

    @Test
    fun `a command pressed after the candidate exists is not sent on the old link and goes out once on the winner, and a hangup's late reply from the old socket reaches nobody`() {
        val old = connected()
        old.silent = true // a dead link: an answer from it would end the race
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        val answers = CopyOnWriteArrayList<Boolean>()
        assertFalse(call("portico/answer") { answers += it }, "not sent: the answer path polls and sends it on the winner")
        assertEquals(0, old.count("portico/answer"))
        val hangups = CopyOnWriteArrayList<Boolean>()
        assertTrue(call("portico/hangup") { hangups += it }, "a hangup is always sent")
        assertEquals(1, old.count("portico/hangup"))
        release(socks[1])
        await("swapped") { socks[1].promoted && link.isReady }
        assertEquals(listOf(false), hangups, "the hangup in flight on the old link was told it was lost, once")
        assertTrue(call("portico/answer") { answers += it })
        assertEquals(1, socks[1].count("portico/answer"))
        assertEquals(0, old.count("portico/answer"))
        // the old link's late answer to the hangup is ignored
        val hid = Json.parseToJsonElement(old.sent.last { it.contains("portico/hangup") }).jsonObject["id"]!!.jsonPrimitive.content
        old.say("""{"id":$hid,"type":"result","success":true,"result":null}""")
        settle()
        assertEquals(listOf(false), hangups)
        await("answer reply") { answers == listOf(true) }
    }

    @Test
    fun `end to end - an Answer pressed during the race reaches Home Assistant exactly once, on the winner`() {
        val old = connected()
        old.silent = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        val replies = CopyOnWriteArrayList<CommandReply>()
        answerPort().send("portico/answer", buildJsonObject { }) { replies += it }
        Thread.sleep(700) // the port polls "not sent" several times
        assertEquals(0, answerFrames(), "nothing goes out while the candidate is on the way")
        release(socks[1])
        await("answer replied") { replies.size == 1 }
        assertTrue(replies[0] is CommandReply.Reply)
        Thread.sleep(600)
        assertEquals(1, answerFrames())
        assertEquals(1, socks[1].count("portico/answer"))
        assertEquals(0, old.count("portico/answer"))
    }

    @Test
    fun `end to end - an Answer already written to the dying old link before the race is told lost at the swap and sent again once, on the winner`() {
        val old = connected()
        old.silent = true
        candidateSilent = true
        val replies = CopyOnWriteArrayList<CommandReply>()
        answerPort().send("portico/answer", buildJsonObject { }) { replies += it }
        await("written to the old link") { old.count("portico/answer") == 1 }
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        release(socks[1])
        await("answer replied") { replies.size == 1 }
        assertTrue(replies[0] is CommandReply.Reply)
        Thread.sleep(600)
        assertEquals(1, old.count("portico/answer"), "the dead link never answered it")
        assertEquals(1, socks[1].count("portico/answer"), "sent again once, on the winner")
    }

    @Test
    fun `a stream open on a mobile link - no race, but the mobile 6 s deadline, not the strict 2 s`() {
        val old = connected()
        assertTrue(onLinkThread { it.openStream("portico/media_open", buildJsonObject { }, { }, { }, { }) != null })
        settle() // its answer is in before the clock moves
        old.silent = true
        ringAndLetPingGoUnanswered() // 2.1 s unanswered
        settle()
        Thread.sleep(700)
        settle()
        assertEquals(null, old.closedWith, "not dropped at 2 s")
        assertEquals(0, candidateOpens.get(), "a stream blocks only the race")
        now.addAndGet(4_000) // 6.1 s
        await("dropped at 6 s") { old.closedWith != null }
        assertEquals(0, candidateOpens.get())
    }

    @Test
    fun `after the old link won a race, the next candidate waits - a slow link is not raced every few seconds`() {
        val old = connected()
        old.quietPings = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        old.say("""{"id":99,"type":"pong"}""")
        await("candidate closed") { socks[1].closedWith != null }
        now.addAndGet(2_100)
        await("second ping") { old.count("ping") == 2 }
        now.addAndGet(2_100) // strict deadline again, 4 s after the old link won
        Thread.sleep(1_200)
        settle()
        assertEquals(1, candidateOpens.get(), "no second candidate inside the gap at 2 s")
        // the gap holds back only the 2 s trigger: at 4 s a candidate is started, so the 6 s death below has one prepared
        now.addAndGet(2_000)
        await("late candidate at 4 s") { candidateOpens.get() == 2 }
        now.addAndGet(2_000) // 6 s: the old link is dead
        await("old dropped, candidate kept") { old.closedWith != null }
        assertEquals(1, opens.get(), "make before break inside the gap too: no plain drop-then-reconnect")
        release(socks[2])
        await("the late candidate is the link") { socks[2].promoted && link.isReady && c!!.state == SignalingClient.State.CONNECTED }
    }

    @Test
    fun `the network is not asked while the link is healthy`() {
        val old = connected()
        c!!.ringStarted()
        Thread.sleep(1_600) // several fast ticks, every ping answered
        settle()
        assertEquals(0, raceAsked.get())
        assertEquals(null, old.closedWith)
    }

    @Test
    fun `a ring heard by the new link only is delivered once at the swap`() {
        ringOnCandidate = true
        val old = connected()
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("swapped") { socks.size == 2 && socks[1].promoted }
        await("ring delivered") { calls.any { it is CallEvent.Ringing } }
        assertEquals(1, calls.count { it is CallEvent.Ringing })
    }

    @Test
    fun `Wi-Fi keeps the strict rule - no second socket, the dead link is dropped and reconnected`() {
        val old = connected(race = false)
        old.silent = true
        ringAndLetPingGoUnanswered()
        await("old dropped") { old.closedWith != null }
        await("plain reconnect") { opens.get() == 2 }
        assertEquals(0, candidateOpens.get())
        assertFalse(journalText.contains("link-race"))
    }

    @Test
    fun `at most one candidate at a time`() {
        val old = connected()
        old.silent = true
        candidateSilent = true
        ringAndLetPingGoUnanswered()
        await("candidate opened") { socks.size == 2 }
        now.addAndGet(500)
        Thread.sleep(700)
        settle()
        assertEquals(1, candidateOpens.get())
        assertNotNull(socks[1])
    }
}
