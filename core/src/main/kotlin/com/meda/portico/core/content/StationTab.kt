package com.meda.portico.core.content

import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.callbar.StationCalls
import com.meda.portico.core.callbar.StationPhase
import com.meda.portico.core.callbar.StationSurface
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ring.LinkState

/**
 * D-20261110-78f9 (app-05): taking the call goes into the app's corresponding page itself. Pure rules for the two things Home does with a
 * station call besides the call bar: which tab it shows, and which Ring page content it draws as a panel.
 */
object StationTab {
    /**
     * The id of the first page whose blocks hold the Talk button (or the gate call button) of the calling door, else null (no page does,
     * or the doors cache does not know the door: the screen stays and shows the call bar only, [StationSurface.BAR_ONLY]). Same test as
     * [StationCalls.surface], so the tab chosen is exactly the one on which the button turns green. The roster page is never chosen.
     */
    fun pageFor(profile: ContentProfile, doors: List<Door>, call: StationCall?): String? {
        if (call?.doorId == null) return null
        return profile.pageList.firstOrNull { page ->
            val buttons = page.blockList.mapNotNull { HomeModel.blockView(it, true, emptySet(), emptySet(), null, page.id, roster = profile.roster, doors = doors) }.flatMap { it.buttons }
            StationCalls.surface(buttons, call) == StationSurface.TALK_BUTTON_AND_BAR
        }?.id
    }
}

/**
 * What the Ring page content on Home is (D-20261110, app-06): the Ring page exists only while the call RINGS; answering it closes the
 * view, and the call goes on as one started from the phone. A Ring page's buttons are simply "the buttons": the server's `ring_buttons`
 * and `call_buttons` lists, merged.
 */
object StationPanel {
    /**
     * The panel under the call bar, only while the call is RINGING: the calling door's cameras and its buttons (`ring_buttons` then
     * `call_buttons`, a repeated id drawn once, in order). Null when there is no call, the call is answered or connected (the panel closes),
     * the document has no caller entry for the door, or the entry draws nothing (no cameras, no buttons).
     */
    fun build(
        profile: ContentProfile,
        endpointId: String?,
        call: StationCall?,
        link: LinkState,
        support: ContentSupport,
        running: Set<String>,
        pictures: Set<String>,
    ): CallerView? {
        if (call == null || call.phase != StationPhase.RINGING) return null
        return RingPage.content(profile, endpointId, link, support, running, pictures)?.takeIf { it.cameras.isNotEmpty() || it.buttons.isNotEmpty() }
    }
}

/**
 * 0.6.75: THE resolver of "the Ring page's content for a door", shared by Home's ring panel ([StationPanel]) and the call window
 * (`:core`'s CallWindowModel): the document's `callers` entry for the door's endpoint id (else `*`), its cameras and its buttons
 * (`ring_buttons` then `call_buttons`, a repeated id drawn once, in order). Null when the document has no entry for the door and no `*`.
 * The editor's Ring page is what the server resolved into that entry (`content_resolve.resolve_caller`), so this is the single source.
 */
object RingPage {
    fun content(
        profile: ContentProfile,
        endpointId: String?,
        link: LinkState,
        support: ContentSupport,
        running: Set<String>,
        pictures: Set<String>,
    ): CallerView? {
        val ring = CallerModel.build(profile, endpointId, CallScreen.RING, link, support, running, pictures) ?: return null
        val more = CallerModel.build(profile, endpointId, CallScreen.IN_CALL, link, support, running, pictures)
        return ring.copy(buttons = (ring.buttons + (more?.buttons ?: emptyList())).distinctBy { it.id })
    }
}

/**
 * The tab rule of a station call. [update] is called at every redraw with the call, the tab now shown and (lazily) the tab that holds the
 * call's Talk button; it returns the page id to select, or null to leave the tab alone.
 * - A NEW call first seen RINGING selects the target tab (if any, and not the one shown), remembering the user's tab.
 * - A call first seen already answered (a recreated screen, a late open) is tracked but never moves the tab.
 * - If the shown tab is not the one this memory selected, the user moved: nothing is restored at the end.
 * - When the call is gone (ended, declined, finished) the remembered tab comes back, only if the tab shown is still the one selected here.
 */
class StationTabMemory(state: State = State()) {
    /** What survives a rotation: the call tracked, the tab to give back and the tab this memory selected. */
    data class State(val callId: String? = null, val restoreTo: String? = null, val selected: String? = null)

    var state: State = state
        private set

    fun update(call: StationCall?, shown: String?, target: () -> String?): String? {
        val s = state
        if (call == null) {
            state = State()
            return if (s.callId != null && s.restoreTo != null && s.selected != null && shown == s.selected && shown != s.restoreTo) s.restoreTo else null
        }
        if (call.callId != s.callId) {
            // A call already tracked that is now answered under another id is the same call going on: keep what we remember.
            if (s.callId != null && call.phase != StationPhase.RINGING) {
                state = s.copy(callId = call.callId)
                return null
            }
            val to = if (call.phase == StationPhase.RINGING) target()?.takeIf { it != shown } else null
            state = State(call.callId, if (to != null) shown else null, to)
            return to
        }
        // Same call: the user changing tab by hand ends our claim on the restore.
        if (s.selected != null && shown != s.selected) state = State(s.callId)
        return null
    }
}
