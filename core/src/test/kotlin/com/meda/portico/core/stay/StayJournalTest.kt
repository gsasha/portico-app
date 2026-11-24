package com.meda.portico.core.stay

import com.meda.portico.core.call.PathChoice
import com.meda.portico.core.call.RouteChoice
import com.meda.portico.core.connlog.JournalLines
import com.meda.portico.core.ha.NetKind
import com.meda.portico.core.netpolicy.ConnectionMode
import com.meda.portico.core.push.PushJournalFormat
import com.meda.portico.core.ring.ChannelState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

class StayJournalTest {
    private val facts = ConfigFacts(
        versionName = "0.6.92-alpha", versionCode = 6009200, mobile = ConnectionMode.PUSH, wifi = ConnectionMode.STAY, roaming = ConnectionMode.PUSH,
        linkRace = true, path = PathChoice.AUTO, compat = false, route = RouteChoice.SPEAKER, ringChannel = ChannelState.OK,
        ringBypassDnd = true, notificationsOn = true,
    )
    private val inputs = StayConnectedPolicy.Inputs(
        true, true, false, NetKind.CELLULAR, callLive = false, uiVisible = false, inGrace = true, serviceRunning = true, serviceRefused = false,
        powerSave = true, onTailnet = true, pushHere = true, ringLive = true,
    )

    private fun line(p: Pair<String, List<Pair<String, Any?>>>) = PushJournalFormat.diagLine(0L, TimeZone.getTimeZone("UTC"), "net", p.first, p.second)
    private fun body(l: String) = l.substringAfter(' ')
    private fun fields(l: String) = body(l).split(' ').drop(2).associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun `the cfg line lists every setting`() {
        val f = fields(line(StayJournalLines.cfg("start", facts)))
        assertEquals(
            mapOf(
                "why" to "start", "ver" to "0-6-92-alpha", "code" to "6009200", "mob" to "push", "wifi" to "stay", "roam" to "push",
                "race" to "mbb", "path" to "auto", "compat" to "no", "route" to "speaker", "ringch" to "ok", "bypass" to "yes", "notif" to "yes", "ansnolock" to "yes", "unlockans" to "yes",
                "panel" to "no", "enrolled" to "no", "idlemin" to "0", "barsec" to "0",
            ),
            f,
        )
    }

    @Test fun `the cfg line says whether Panel mode is on and the idle timeout and bar`() {
        val f = fields(line(StayJournalLines.cfg("start", facts.copy(panel = true, enrolled = true, idleMin = 5, barSec = 10))))
        assertEquals("yes", f["panel"]); assertEquals("yes", f["enrolled"]); assertEquals("5", f["idlemin"]); assertEquals("10", f["barsec"])
    }

    @Test fun `the inputs line lists the policy inputs and the decision`() {
        val f = fields(line(StayJournalLines.inputs("ring", inputs, "mobile", 1)))
        assertEquals(
            mapOf(
                "why" to "ring", "cls" to "mobile", "net" to "cellular", "ui" to "1", "call" to "no", "ring" to "yes", "grace" to "yes",
                "psave" to "yes", "tn" to "yes", "push" to "yes", "paused" to "no", "svc" to "yes", "dec" to "defer",
            ),
            f,
        )
    }

    @Test fun `classic reconnect, other paths and unknowns are words too`() {
        val f = fields(line(StayJournalLines.cfg("setup", facts.copy(linkRace = false, path = PathChoice.WEBRTC, compat = true, mobile = null, ringBypassDnd = null, notificationsOn = null, ringChannel = ChannelState.BLOCKED))))
        assertEquals("classic", f["race"]); assertEquals("webrtc", f["path"]); assertEquals("yes", f["compat"]); assertEquals("unknown", f["mob"])
        assertEquals("unknown", f["bypass"]); assertEquals("unknown", f["notif"]); assertEquals("blocked", f["ringch"])
    }

    @Test fun `only allowed words, small, and accepted by the Journal reporter's secret filter`() {
        val allowedKeys = setOf("why", "ver", "code", "mob", "wifi", "roam", "race", "path", "compat", "route", "ringch", "bypass", "notif",
            "ansnolock", "unlockans", "panel", "enrolled", "idlemin", "barsec", "cls", "net", "ui", "call", "ring", "grace", "psave", "tn", "push", "paused", "svc", "dec")
        val allowedValues = setOf("start", "setting", "ring", "call", "route", "setup", "other", "stay", "push", "unknown", "mbb", "classic", "auto",
            "frames", "webrtc", "yes", "no", "speaker", "earpiece", "bluetooth", "ok", "lowered", "blocked", "missing", "wifi", "mobile", "roaming",
            "ethernet", "cellular", "vpn", "none", "hold", "release", "defer", "idle", "0-6-92-alpha")
        for (why in listOf("start", "setting", "ring", "call", "route", "setup", "10.1.2.3 free text")) {
            for (net in NetKind.entries) for (path in PathChoice.entries) for (route in RouteChoice.entries) for (ch in ChannelState.entries) {
                val ls = listOf(
                    line(StayJournalLines.cfg(why, facts.copy(path = path, route = route, ringChannel = ch))),
                    line(StayJournalLines.inputs(why, inputs.copy(net = net), "mobile", 3)),
                )
                for (l in ls) {
                    val b = body(l)
                    assertTrue(b.startsWith("net cfg ") || b.startsWith("net inputs "), b)
                    assertTrue(b.length <= JournalLines.MAX_LINE, "too long: ${b.length} $b")
                    assertTrue(JournalLines.check(b) is JournalLines.Verdict.Keep, "reporter refuses: $b")
                    for ((k, v) in fields(l)) {
                        assertTrue(k in allowedKeys, "key $k")
                        assertTrue(v in allowedValues || v.matches(Regex("\\d{1,7}")), "value $v of $k in $b")
                    }
                }
            }
        }
    }

    @Test fun `size of one snapshot`() {
        val a = line(StayJournalLines.cfg("ring", facts)); val b = line(StayJournalLines.inputs("ring", inputs, "mobile", 1))
        println("snapshot size: cfg=${a.length} inputs=${b.length} total=${a.length + b.length + 2} chars")
        assertTrue(a.length + b.length < 400)
    }

    @Test fun `every why word of StayWhy is a plain journal value and unknown becomes other`() {
        for (w in StayWhy.ALL) assertTrue(w.matches(Regex("[a-z0-9-]{1,32}")), w)
        assertEquals("other", StayWhy.word("anything else"))
        assertEquals("screen-closed", StayWhy.word("a screen closed"))
    }
}
