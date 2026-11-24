package com.meda.portico.core.call

import com.meda.portico.core.ha.CallEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The answer path against fakes: the card's message order, the error mapping, the Phase 2 rule, and every way out. */
class AnswerSessionTest {

    private val cmds = FakeCommands()
    private val media = FakeMedia()
    private val audio = FakeAudio()
    private val sched = FakeScheduler()
    private val store = InMemoryActiveCallStore()
    private var micGranted = true
    private var wall = 1_000_000L
    private val snaps = mutableListOf<AnswerSnapshot>()
    private var micReady = 0
    private var claiming = 0
    private val ended = mutableListOf<AnswerSnapshot>()
    private val config = AnswerConfig()

    private val hooks = object : AnswerHooks {
        override fun onMicReady() { micReady++ }
        override fun onClaiming() { claiming++ }
        override fun onEnded(snapshot: AnswerSnapshot) { ended += snapshot }
    }

    private fun session(doorId: String? = DEVICE) = AnswerSession(
        CALL, doorId, cmds, media, audio, { micGranted }, sched, store, { wall }, hooks, config, onChange = { snaps += it },
    )

    private val s by lazy { session() }

    /** begin -> offer ready -> claim granted (relay) -> offer answered -> media connected. */
    private fun fullCall(): FakeMediaCall {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(true)
        m.listener.onConnected()
        return m
    }

    private fun assertCleanedUp(m: FakeMediaCall?) {
        assertEquals(Phase.ENDED, s.phase)
        if (m != null) assertEquals(1, m.closes, "media closed exactly once")
        assertEquals(audio.enters, audio.leaves, "audio route left as often as entered")
        assertEquals(0, sched.pending, "no timer left armed")
    }

    // ---- the card's sequence -------------------------------------------------------------------

    @Test
    fun `the message sequence is the card's - offer first, then answer, then webrtc_offer`() {
        s.begin()
        assertEquals(Phase.PREPARING, s.phase)
        assertTrue(cmds.sent.isEmpty(), "nothing is sent before the offer exists")
        val m = media.calls.single()
        assertEquals(config.gatherTimeoutMs, m.gatherTimeout)
        m.offerCb!!(OFFER)

        assertEquals(Phase.CLAIMING, s.phase)
        val claim = cmds.sent.single()
        assertEquals("portico/answer", claim.type)
        assertEquals(obj("door_id" to DEVICE, "call_id" to CALL, "client_kind" to "browser"), claim.body)
        assertEquals(1, claiming)

        claim.ok(obj("mode" to "relay"))
        assertEquals(Phase.NEGOTIATING, s.phase)
        assertTrue(claiming == 1)
        val offer = cmds.last("portico/webrtc_offer")
        assertEquals(obj("call_id" to CALL, "door_id" to DEVICE, "sdp" to OFFER), offer.body)

        offer.ok(obj("sdp" to ANSWER))
        assertEquals(ANSWER, m.answerSdp)
        assertEquals(Phase.CONNECTING, s.phase)
        assertEquals(1, audio.enters)
        m.answerCb!!(true)
        m.listener.onConnected()
        assertEquals(Phase.IN_CALL, s.phase)
        assertEquals(wall, s.snapshot.connectedAtMs)
        assertEquals(listOf("portico/answer", "portico/webrtc_offer"), cmds.types())
        assertFalse(cmds.types().any { it == "portico/webrtc_ice" }, "the card never trickles; neither do we")
        assertEquals(DEVICE, store.value!!.doorId, "the claim is recorded for a restart to release")
    }

    // ---- the Phase 2 rule: nothing without a press -----------------------------------------------

    @Test
    fun `nothing is opened or sent until begin - every other input leaves a fresh session silent`() {
        s.onMicResult(true)
        s.onCallEvent(CallEvent.Ringing(DEVICE, REF, CALL))
        s.onCallEvent(CallEvent.Answered(DEVICE, REF, CALL))
        s.onCallEvent(CallEvent.Ended(DEVICE, REF, CALL, false))
        s.onLinkLost()
        s.onLinkRestored()
        s.setMuted(true)
        s.hangUp()
        sched.advance(10 * 60_000)
        assertEquals(Phase.IDLE, s.phase)
        assertTrue(cmds.sent.isEmpty())
        assertTrue(media.calls.isEmpty())
        assertEquals(0, audio.enters)
        assertEquals(0, micReady)
        assertEquals(0, claiming)
        assertNull(store.value)
    }

    @Test
    fun `a mic grant that nobody asked for does not start an answer`() {
        micGranted = false
        s.begin()
        assertEquals(Phase.AWAITING_MIC, s.phase)
        s.hangUp() // the user backs out
        s.onMicResult(true) // a late dialog result
        assertEquals(Phase.ENDED, s.phase)
        assertTrue(media.calls.isEmpty())
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `begin twice does not answer twice`() {
        s.begin()
        s.begin()
        assertEquals(1, media.calls.size)
        media.calls.single().offerCb!!(OFFER)
        s.begin()
        assertEquals(1, cmds.count("portico/answer"))
    }

    // ---- microphone -------------------------------------------------------------------------------

    @Test
    fun `without the microphone the session waits and opens nothing`() {
        micGranted = false
        s.begin()
        assertEquals(Phase.AWAITING_MIC, s.phase)
        assertTrue(media.calls.isEmpty())
        assertEquals(0, micReady)
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `mic granted from the dialog continues into the normal path`() {
        micGranted = false
        s.begin()
        s.onMicResult(true)
        assertEquals(Phase.PREPARING, s.phase)
        assertEquals(1, micReady)
        media.calls.single().offerCb!!(OFFER)
        assertEquals("portico/answer", cmds.sent.single().type)
    }

    @Test
    fun `mic refused ends with MIC_DENIED, sends nothing, claims nothing, and may be tried again`() {
        micGranted = false
        s.begin()
        s.onMicResult(false)
        assertCleanedUp(null)
        assertEquals(AnswerFailure.Kind.MIC_DENIED, s.snapshot.failure!!.kind)
        assertTrue(s.snapshot.failure!!.retryable)
        assertFalse(s.snapshot.failure!!.dismissRing)
        assertTrue(cmds.sent.isEmpty())
        assertTrue(media.calls.isEmpty())
        assertNull(store.value)
    }

    // ---- claim errors ------------------------------------------------------------------------------

    @Test
    fun `already_answered - the clear message, the ring dismissed, no retry, no hangup`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").error("already_answered", "this call was already answered or has ended")

        val f = s.snapshot.failure!!
        assertEquals("Someone else already answered this call", f.message)
        assertEquals(AnswerFailure.Kind.ALREADY_ANSWERED, f.kind)
        assertTrue(f.dismissRing)
        assertFalse(f.retryable)
        assertCleanedUp(m)
        assertEquals(EndReason.FAILED, s.snapshot.endReason)
        sched.advance(10 * 60_000)
        assertEquals(1, cmds.sent.size, "one portico/answer, no retry, no webrtc_offer, no hangup (nothing was claimed)")
        assertNull(store.value)
        assertEquals(1, ended.size)
    }

    @Test
    fun `device_busy has its own message and is not a dismissed ring`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").error("device_busy", "x")
        val f = s.snapshot.failure!!
        assertEquals(AnswerFailure.Kind.DEVICE_BUSY, f.kind)
        assertEquals(AnswerMessages.DEVICE_BUSY, f.message)
        assertTrue(f.message != AnswerMessages.ALREADY_ANSWERED)
        assertFalse(f.dismissRing)
        assertTrue(f.retryable)
        assertEquals(1, cmds.sent.size)
    }

    @Test
    fun `refused is not shown as someone else answered, and nothing is dismissed or retried by itself`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").error("refused", "x")
        val f = s.snapshot.failure!!
        assertEquals(AnswerMessages.REFUSED, f.message)
        assertTrue(f.message != AnswerMessages.ALREADY_ANSWERED)
        assertFalse(f.dismissRing)
        assertTrue(f.retryable)
        sched.advance(10 * 60_000)
        assertEquals(1, cmds.sent.size, "one portico/answer, no retry by itself")
    }

    @Test
    fun `not_supported shows the device's own reason, made safe for a TextView`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").error("not_supported", "  The observer\u0000 cannot \n answer\t\t this.  ")
        assertEquals("The observer cannot answer this.", s.snapshot.failure!!.message)
        assertFalse(s.snapshot.failure!!.retryable)
    }

    @Test
    fun `an unknown code is reported with the code, never silently`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").error("something_new", "do not show me")
        assertEquals(AnswerFailure.Kind.OTHER, s.snapshot.failure!!.kind)
        assertTrue("something_new" in s.snapshot.failure!!.message)
        assertFalse("do not show me" in s.snapshot.failure!!.message)
    }

    @Test
    fun `unknown_command and unknown_door have their own messages`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").error("unknown_command")
        assertEquals(AnswerFailure.Kind.SERVER_TOO_OLD, s.snapshot.failure!!.kind)
        assertEquals(AnswerFailure.Kind.UNKNOWN_DOOR, AnswerErrors.fromCode("unknown_door").kind)
    }

    @Test
    fun `a door the cache cannot translate fails at once without touching the microphone`() {
        val noDevice = session(doorId = null)
        noDevice.begin()
        assertEquals(AnswerFailure.Kind.UNKNOWN_DOOR, noDevice.snapshot.failure!!.kind)
        assertTrue(media.calls.isEmpty())
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `no socket - not connected, nothing claimed`() {
        cmds.connected = false
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        assertEquals(AnswerFailure.Kind.NOT_CONNECTED, s.snapshot.failure!!.kind)
        assertEquals(1, media.calls.single().closes)
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `a claim that lost the socket ends as a network failure`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").lost()
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertEquals(AnswerFailure.Kind.NETWORK_LOST, s.snapshot.failure!!.kind)
        // The claim may have been granted before the socket dropped: the release is owed and the record kept.
        assertTrue(s.hangupOwed)
        assertNotNull(store.value, "the record stays so a restart can release the claim")
    }

    @Test
    fun `a mode other than relay releases the claim it was just given`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "direct"))
        assertEquals(AnswerFailure.Kind.UNEXPECTED_MODE, s.snapshot.failure!!.kind)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertEquals("error", (cmds.sent.last().body["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse("portico/webrtc_offer" in cmds.types())
    }

    // ---- offer / negotiation errors -----------------------------------------------------------------

    @Test
    fun `an offer that cannot be made ends before anything is claimed`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(null)
        assertEquals(AnswerFailure.Kind.MEDIA, s.snapshot.failure!!.kind)
        assertTrue(cmds.sent.isEmpty())
        assertCleanedUp(m)
    }

    @Test
    fun `audio that cannot open ends before anything is claimed`() {
        media.failOpen = true
        s.begin()
        assertEquals(AnswerFailure.Kind.MEDIA, s.snapshot.failure!!.kind)
        assertTrue(cmds.sent.isEmpty())
    }

    @Test
    fun `webrtc_offer refused after the claim releases the claim`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").error("no_transport", "no configured Portico device")
        assertEquals(Phase.ENDED, s.phase)
        val hangup = cmds.last("portico/hangup")
        assertEquals(CALL, (hangup.body["call_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        hangup.ok()
        assertNull(store.value, "released: the record is gone")
        assertFalse(s.hangupOwed)
        assertCleanedUp(m)
    }

    @Test
    fun `a remote answer the media layer rejects ends and hangs up`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(false)
        assertEquals(AnswerFailure.Kind.MEDIA, s.snapshot.failure!!.kind)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertCleanedUp(m)
    }

    @Test
    fun `audio that never connects times out after the connect window and hangs up`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(true)
        sched.advance(config.connectTimeoutMs - 1)
        assertEquals(Phase.CONNECTING, s.phase)
        sched.advance(1)
        assertEquals(AnswerFailure.Kind.MEDIA, s.snapshot.failure!!.kind)
        assertEquals(AnswerMessages.AUDIO_NOT_CONNECTED, s.snapshot.failure!!.message)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertCleanedUp(m)
    }

    @Test
    fun `media that fails before it ever connected says the audio did not connect`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(true)
        m.listener.onFailed()
        assertEquals(AnswerMessages.AUDIO_NOT_CONNECTED, s.snapshot.failure!!.message)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertCleanedUp(m)
    }

    @Test
    fun `a reply that never comes ends the attempt, and a claim that may have been granted is released`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        sched.advance(config.stepTimeoutMs)
        assertEquals(AnswerFailure.Kind.TIMEOUT, s.snapshot.failure!!.kind)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertNotNull(store.value, "kept until the hang-up is settled")
        // The late reply of the abandoned claim does nothing.
        cmds.sent.first().ok(obj("mode" to "relay"))
        assertEquals(1, cmds.count("portico/answer"))
        assertEquals(0, cmds.count("portico/webrtc_offer"))
        cmds.last("portico/hangup").ok()
        assertNull(store.value)
        assertCleanedUp(m)
    }

    // ---- every end path ---------------------------------------------------------------------------------

    @Test
    fun `user hang-up releases the claim and cleans up`() {
        val m = fullCall()
        s.hangUp()
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        val h = cmds.last("portico/hangup")
        assertEquals("user_hangup", (h.body["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
        h.ok()
        assertCleanedUp(m)
        assertNull(store.value)
        assertEquals(1, ended.size)
    }

    @Test
    fun `remote hang-up ends without a hangup of ours and cleans up`() {
        val m = fullCall()
        s.onCallEvent(CallEvent.Ended(DEVICE, REF, CALL, inferred = false))
        assertEquals(EndReason.REMOTE_HANGUP, s.snapshot.endReason)
        assertFalse("portico/hangup" in cmds.types(), "the call is already over on the server")
        assertCleanedUp(m)
        assertNull(store.value)
    }

    @Test
    fun `an ended event for another call is ignored`() {
        fullCall()
        s.onCallEvent(CallEvent.Ended(DEVICE, REF, "gso-other", inferred = false))
        assertEquals(Phase.IN_CALL, s.phase)
    }

    @Test
    fun `a snapshot saying the door has no live call ends a claimed call`() {
        val m = fullCall()
        s.onCallEvent(CallEvent.NoLiveCall(DEVICE, REF))
        assertEquals(EndReason.REMOTE_HANGUP, s.snapshot.endReason)
        assertCleanedUp(m)
    }

    @Test
    fun `our own answered event during the claim does not end the call`() {
        s.begin()
        media.calls.single().offerCb!!(OFFER)
        s.onCallEvent(CallEvent.Answered(DEVICE, REF, CALL))
        assertEquals(Phase.CLAIMING, s.phase)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        s.onCallEvent(CallEvent.Active(DEVICE, REF, CALL))
        assertEquals(Phase.NEGOTIATING, s.phase)
    }

    @Test
    fun `somebody else answering before our claim ends the attempt at once with the same message`() {
        s.begin()
        val m = media.calls.single()
        s.onCallEvent(CallEvent.Answered("gate", REF, CALL))
        assertEquals(Phase.ENDED, s.phase)
        assertEquals("Someone else already answered this call", s.snapshot.failure!!.message)
        assertTrue(s.snapshot.failure!!.dismissRing)
        m.offerCb!!(OFFER) // the offer finishing late changes nothing
        assertTrue(cmds.sent.isEmpty())
        assertCleanedUp(m)
    }

    @Test
    fun `the call ending before the claim is a dismissed ring, not a failure to retry`() {
        micGranted = false
        s.begin()
        s.onCallEvent(CallEvent.Ended(DEVICE, REF, CALL, inferred = true))
        assertEquals(AnswerFailure.Kind.CALL_ENDED, s.snapshot.failure!!.kind)
        assertTrue(s.snapshot.failure!!.dismissRing)
        s.onMicResult(true)
        assertTrue(media.calls.isEmpty())
    }

    @Test
    fun `the 300 s cap ends a connected call and hangs up`() {
        val m = fullCall()
        sched.advance(299_999)
        assertEquals(Phase.IN_CALL, s.phase)
        sched.advance(1)
        assertEquals(EndReason.TIME_CAP, s.snapshot.endReason)
        val h = cmds.last("portico/hangup")
        assertEquals("timeout", (h.body["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
        h.ok()
        assertCleanedUp(m)
        assertNull(store.value)
    }

    @Test
    fun `the cap starts at the claim, not at the press`() {
        s.begin()
        val m = media.calls.single()
        sched.advance(3_000)
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(true)
        m.listener.onConnected()
        sched.advance(299_999)
        assertNotEquals(Phase.ENDED, s.phase)
        sched.advance(1)
        assertEquals(EndReason.TIME_CAP, s.snapshot.endReason)
    }

    private fun assertNotEquals(a: Any, b: Any) = assertTrue(a != b)

    @Test
    fun `media failure ends the call as a network loss and hangs up`() {
        val m = fullCall()
        m.listener.onFailed()
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertCleanedUp(m)
    }

    @Test
    fun `a media drop that recovers inside the grace period keeps the call`() {
        val m = fullCall()
        m.listener.onDisconnected()
        sched.advance(config.graceMs - 1)
        m.listener.onConnected()
        sched.advance(config.graceMs * 2)
        assertEquals(Phase.IN_CALL, s.phase)
    }

    @Test
    fun `a media drop that does not recover ends the call after the grace period`() {
        val m = fullCall()
        m.listener.onDisconnected()
        sched.advance(config.graceMs)
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertCleanedUp(m)
    }

    @Test
    fun `losing the socket mid-call ends it after the grace period, and the hang-up is owed until a socket returns`() {
        val m = fullCall()
        cmds.connected = false
        s.onLinkLost()
        sched.advance(config.graceMs)
        assertEquals(EndReason.NETWORK_LOST, s.snapshot.endReason)
        assertTrue(s.hangupOwed)
        assertNotNull(store.value, "the record stays so a restart can release the call")
        assertCleanedUp(m)

        cmds.connected = true
        s.onLinkRestored()
        val h = cmds.last("portico/hangup")
        h.error("unknown_call", "gone") // already over on the server: still settles it
        assertFalse(s.hangupOwed)
        assertNull(store.value)
    }

    @Test
    fun `a late hang-up reply does not clear the record of a newer call`() {
        fullCall()
        cmds.connected = false
        s.onLinkLost()
        sched.advance(config.graceMs)
        cmds.connected = true
        s.onLinkRestored()
        val h = cmds.last("portico/hangup")
        store.save(ActiveCall(DEVICE, "gso-newer", wall)) // the next call was claimed before this reply arrived
        h.ok()
        assertFalse(s.hangupOwed)
        assertEquals("gso-newer", store.value?.callId, "only this call's own record is cleared")
    }

    @Test
    fun `a socket that comes back inside the grace period keeps the call`() {
        fullCall()
        s.onLinkLost()
        sched.advance(config.graceMs - 1)
        s.onLinkRestored()
        sched.advance(config.graceMs * 2)
        assertEquals(Phase.IN_CALL, s.phase)
    }

    @Test
    fun `a hang-up whose socket closes first stays owed`() {
        fullCall()
        s.hangUp()
        cmds.last("portico/hangup").lost()
        assertTrue(s.hangupOwed)
        assertNotNull(store.value)
    }

    @Test
    fun `the app being stopped under a live call releases it`() {
        val m = fullCall()
        s.onAppStopping()
        assertEquals(EndReason.APP_STOPPED, s.snapshot.endReason)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertCleanedUp(m)
    }

    @Test
    fun `the app being stopped before any claim sends nothing`() {
        s.begin()
        val m = media.calls.single()
        s.onAppStopping()
        assertTrue(cmds.sent.isEmpty())
        assertCleanedUp(m)
    }

    @Test
    fun `hang-up while the claim is in flight waits for the reply and releases a granted claim`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        s.hangUp()
        assertEquals(Phase.CLAIMING, s.phase, "the reply decides")
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        assertEquals(Phase.ENDED, s.phase)
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertEquals("portico/hangup", cmds.sent.last().type)
        assertFalse("portico/webrtc_offer" in cmds.types())
        assertCleanedUp(m)
    }

    @Test
    fun `hang-up while the claim is in flight and refused sends no hangup`() {
        s.begin()
        val m = media.calls.single()
        m.offerCb!!(OFFER)
        s.hangUp()
        cmds.last("portico/answer").error("already_answered")
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertFalse("portico/hangup" in cmds.types())
        assertCleanedUp(m)
    }

    @Test
    fun `hang-up before the claim cancels without sending anything`() {
        s.begin()
        val m = media.calls.single()
        s.hangUp()
        m.offerCb!!(OFFER)
        assertTrue(cmds.sent.isEmpty())
        assertEquals(EndReason.USER_HANGUP, s.snapshot.endReason)
        assertCleanedUp(m)
    }

    @Test
    fun `finish is once - a late event or timer after the end changes nothing`() {
        val m = fullCall()
        s.hangUp()
        cmds.last("portico/hangup").ok()
        val n = cmds.sent.size
        s.onCallEvent(CallEvent.Ended(DEVICE, REF, CALL, false))
        m.listener.onFailed()
        m.listener.onDisconnected()
        s.onAppStopping()
        s.hangUp()
        sched.advance(10 * 60_000)
        assertEquals(n, cmds.sent.size)
        assertEquals(1, ended.size)
        assertEquals(1, m.closes)
    }

    @Test
    fun `mute reaches the media only while a call is up`() {
        s.begin()
        val m = media.calls.single()
        s.setMuted(true)
        assertFalse(m.micOff, "not before the call is negotiated")
        m.offerCb!!(OFFER)
        cmds.last("portico/answer").ok(obj("mode" to "relay"))
        cmds.last("portico/webrtc_offer").ok(obj("sdp" to ANSWER))
        m.answerCb!!(true)
        m.listener.onConnected()
        s.setMuted(true)
        assertTrue(m.micOff)
        assertTrue(s.snapshot.muted)
    }

    // ---- restart: the sweep ------------------------------------------------------------------------------

    @Test
    fun `sweep releases a call a previous run left, and clears it on any reply`() {
        store.save(ActiveCall(DEVICE, "gso-old", 5))
        AnswerSweep(cmds, store).run(liveCallId = null)
        val h = cmds.sent.single()
        assertEquals("portico/hangup", h.type)
        assertEquals(obj("call_id" to "gso-old", "reason" to "error"), h.body)
        h.error("unknown_call")
        assertNull(store.value)
    }

    @Test
    fun `sweep keeps the record when the socket closed first`() {
        store.save(ActiveCall(DEVICE, "gso-old", 5))
        AnswerSweep(cmds, store).run(null)
        cmds.sent.single().lost()
        assertNotNull(store.value)
    }

    @Test
    fun `sweep leaves a live session's call alone, and sends nothing when there is nothing`() {
        store.save(ActiveCall(DEVICE, CALL, 5))
        AnswerSweep(cmds, store).run(liveCallId = CALL)
        assertTrue(cmds.sent.isEmpty())
        store.clear()
        AnswerSweep(cmds, store).run(null)
        assertTrue(cmds.sent.isEmpty())
    }

    companion object {
        const val DEVICE = "gate"
        const val REF = "entry-gate"
        const val CALL = "gso-0123456789"
        const val OFFER = "v=0 offer"
        const val ANSWER = "v=0 answer"
    }
}
