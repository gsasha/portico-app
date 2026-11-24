package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.HaProtocol
import com.meda.portico.core.ha.WsContract
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The Talk buttons of the server's recorded documents (`ws_v3.json`, portico-ha B7a, #131) through the real parser and
 * `HomeModel`: every Talk button the server can record today arrives `available: false`, so Home draws it disabled with the
 * recorded reason and nothing can start (`TalkGate`).
 */
class TalkContractTest {

    private fun profile(name: String): ContentProfile {
        val r = HaProtocol.parse(WsContract.reply(name, 1)).single() as HaInbound.Result
        return (ContentCommands.parseGet(r) as ContentCommands.Outcome.Profile).profile
    }

    private fun talkTile(p: ContentProfile): ButtonTile =
        HomeModel.build(p, LinkState.CONNECTED, ContentSupport.YES, true, emptySet(), emptySet()).buttons.single { it.kind == "talk" }

    @Test
    fun `the recorded phone document has its Talk button off, switched off, and the gate says not ready`() {
        val p = profile("blocks, content get: a phone with two blocks (door, sizes, colour, Talk off, flat form beside them)")
        val t = talkTile(p)
        assertEquals(ButtonState.UNAVAILABLE, t.state)
        assertEquals(ButtonReason.TALK_OFF, t.reason)
        assertFalse(TalkGate.ready(p.findAction("talk"), true))
    }

    @Test
    fun `a draft whose door does not exist shows no_door, as the fixture changed it in B7a`() {
        val p = profile("blocks, content preview: a draft whose door does not exist (door_available false)")
        val t = talkTile(p)
        assertEquals(ButtonState.UNAVAILABLE, t.state)
        assertEquals(ButtonReason.NO_DOOR, t.reason)
        assertFalse(TalkGate.ready(p.findAction("talk"), true))
    }

    @Test
    fun `a Talk button on a screen's page shows phone_only`() {
        val p = profile("blocks, content preview: a Talk button on a screen's page is off, phone_only")
        val t = talkTile(p)
        assertEquals(ButtonState.UNAVAILABLE, t.state)
        assertEquals(ButtonReason.PHONE_ONLY, t.reason)
        assertFalse(TalkGate.ready(p.findAction("talk"), true))
    }

    @Test
    fun `no Talk button in any recorded document is available - the app can start nothing from the fixture`() {
        val names = WsContract.cases.filter { it.receive.firstOrNull()?.get("success") == true && it.type in setOf(ContentCommands.GET, "portico/content/preview", "portico/content/document") }.map { it.name }
        var seen = 0
        for (n in names) {
            val r = HaProtocol.parse(WsContract.reply(n, 1)).single() as HaInbound.Result
            val p = (ContentCommands.parseGet(r) as? ContentCommands.Outcome.Profile)?.profile ?: continue
            for (a in p.pageList.flatMap { it.actions } + p.callers.values.flatMap { it.ringButtons + it.callButtons }) {
                if (a.isTalk) {
                    seen++
                    if ("(gate as a call target)" in n) {
                        // M7 3f's recorded documents: the gate's dial is switched ON there (CanDial), so the server calls Talk
                        // ready; the app's gate still follows the server's word exactly (`available`), nothing more.
                        assertEquals(a.available, TalkGate.ready(a, true), "$n: ${a.id}")
                        continue
                    }
                    assertFalse(a.available, "$n: ${a.id}")
                    assertFalse(TalkGate.ready(a, true), "$n: ${a.id}")
                }
            }
        }
        assertEquals(true, seen >= 3, "the fixture has Talk buttons ($seen)")
    }
}
