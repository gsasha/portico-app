package com.meda.portico.core.call

import com.meda.portico.core.connlog.JournalLines
import com.meda.portico.core.ha.TalkStateEvent
import com.meda.portico.core.ha.TalkWireState
import com.meda.portico.core.push.PushJournalFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** Every talk tells how it ended, once, with the code path's own word (the uploaded Journal's `ui talk-end` line). */
class TalkEndPathTest {
    private val outId = "out-41e45910ff2b1068"

    @Test
    fun `the user's stop names user_hangup and says how soon after the press and the answer`() {
        val r = TalkRig()
        r.s.begin()
        r.wall += 100
        r.grant(outId)
        r.s.onTalkState(TalkStateEvent(TALK_DOOR, outId, TalkWireState.IN_CALL, null))
        r.wall += 700
        r.s.hangUp()
        val e = r.ends.single()
        assertEquals("user_hangup", e.via)
        assertEquals(EndReason.USER_HANGUP, e.reason)
        assertTrue(e.answered && e.hangup)
        assertEquals(800L, e.sincePressMs)
        assertEquals(700L, e.sinceAnswerMs)
        assertEquals(outId, e.callId)
    }

    @Test
    fun `a door that answers before the run reply (events kept as early) ends by media failure with the leg's own reason`() {
        val r = TalkRig()
        r.s.begin()
        // the server's order for an instant door: dialing, in_call, then the run reply
        r.s.onTalkState(TalkStateEvent(TALK_DOOR, outId, TalkWireState.IN_CALL, null))
        r.grant(outId)
        val leg = r.media.legs.single()
        assertEquals(TalkPhase.CONNECTING, r.phase)
        leg.failure = "offer_refused_unknown_call"
        leg.listener.onFailed()
        val e = r.ends.single()
        assertEquals("media_failed_connecting:offer_refused_unknown_call", e.via)
        assertEquals(EndReason.FAILED, e.reason)
        assertEquals(TalkFailure.Kind.entries.first { it == e.failure }, e.failure)
        assertTrue(e.answered && e.hangup)
    }

    @Test
    fun `a ring of another call, the door ringing and the app stopping each name themselves`() {
        val a = TalkRig()
        a.fullTalk()
        a.s.onDoorRinging()
        assertEquals("door_ringing", a.ends.single().via)

        val b = TalkRig()
        b.fullTalk()
        b.s.onAppStopping()
        assertEquals("app_stopping", b.ends.single().via)

        val c = TalkRig()
        c.fullTalk()
        c.s.onCallEvent(ringing(call = "gso-other"))
        assertEquals("ring_event_other_call", c.ends.single().via)
    }

    @Test
    fun `a refused press that never reached the server and the audio timeout name themselves`() {
        val r = TalkRig(gateOpen = false)
        r.s.begin()
        assertTrue(r.ends.isEmpty(), "an ignored press is not an end")

        val t = TalkRig()
        t.s.begin()
        t.grant()
        t.s.onCallEvent(answeredEv())
        t.sched.advance(t.config.audioTimeoutMs + 1)
        assertEquals("audio_timeout:?", t.ends.single().via, "a leg that does not say how far it got")
    }

    @Test
    fun `the audio timeout names how far the leg got`() {
        val t = TalkRig()
        t.s.begin()
        t.grant()
        t.s.onCallEvent(answeredEv())
        t.media.legs.single().stage = "answer_set"
        t.sched.advance(t.config.audioTimeoutMs + 1)
        assertEquals("audio_timeout:answer_set", t.ends.single().via)
    }

    @Test
    fun `the end is told once even if finish is reached twice`() {
        val r = TalkRig()
        r.fullTalk()
        r.s.hangUp()
        r.s.onAppStopping()
        assertEquals(1, r.ends.size)
    }

    @Test
    fun `the Journal line is a ui line the reporter keeps, flushes at once, and carries no secret`() {
        val e = TalkEnd("media_failed_connecting:offer_refused_unknown_call", EndReason.FAILED, TalkFailure.Kind.entries.first(), true, true, outId, 640, 120)
        val line = PushJournalFormat.talkEndLine(1_000_000L, TimeZone.getTimeZone("UTC"), e)
        assertTrue(" ui talk-end via=media_failed_connecting:offer_refused_unknown_call reason=failed" in line, line)
        assertTrue("answered=yes hangup=yes" in line && "press=640ms answer=120ms" in line, line)
        assertFalse("41e45910ff2b1068" in line, "the call id is shortened: $line")
        val body = line.substringAfter(' ')
        assertNotNull((JournalLines.check(body) as? JournalLines.Verdict.Keep), "kept by the reporter: $body")
        assertTrue(JournalLines.endsSession(body))
    }
}
