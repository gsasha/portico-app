package com.meda.portico.core.panel

import com.meda.portico.core.ha.ContentProfile

/**
 * DEV-807 (D-20261121-73ff): whether this device is a panel is the SERVER's word and nothing else. The server's "panel screen" flag
 * (`panel: true` on the phone entry) makes it add `screens` and `phone_endpoint` to the content result of that login (its phone's
 * and its screen's alike) and to no other login's. So a result that carries either one is a panel's. A result that carries neither
 * (an old server, or a login that is no panel) is a phone's.
 */
object PanelFlag {
    /** The flag a content result states. */
    fun of(profile: ContentProfile): Boolean = profile.phoneEndpoint != null || profile.screens.isNotEmpty()

    /** The refusal code of `portico/wall/heartbeat` that says the server's flag is off for this login. */
    const val NOT_A_PANEL = "not_a_panel"

    /** Whether a heartbeat refusal code is the server saying "not a panel" (the code arrives as the app lowercases it for the journal, so compare folded). */
    fun isNotAPanel(code: String): Boolean = code.equals(NOT_A_PANEL, ignoreCase = true)
}
