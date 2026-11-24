package com.meda.portico.core.panel

/** The Settings entries Panel mode (DEV-807, design sections 3 and 7) treats differently. [OTHER] is everything not named here. */
enum class SettingsEntry { STAY_CONNECTED, ENROLMENT, IDLE_TIMEOUT, PAUSE, RING_SWITCH, OTHER }

/** How a Settings entry is offered. */
enum class EntryAccess {
    /** Used as any entry is. */
    OPEN,

    /** Shown, but changed only after a long press and a confirmation. */
    LONG_PRESS_CONFIRM,

    /** Not offered at all. */
    HIDDEN,
}

/**
 * Panel mode's Settings rules (design section 3, default 5, "Agreed 2026-11-19"): the entries that could silence the panel (Stay
 * connected, enrolment, the idle timeout) sit behind a long press and a confirmation; Pause and the ring switch are not offered.
 * There is no Panel mode entry any more (D-20261121-73ff: the server's flag decides); outside Panel mode nothing is protected.
 */
object PanelSettingsGuard {
    fun access(panelMode: Boolean, entry: SettingsEntry): EntryAccess = when (entry) {
        SettingsEntry.STAY_CONNECTED, SettingsEntry.ENROLMENT, SettingsEntry.IDLE_TIMEOUT ->
            if (panelMode) EntryAccess.LONG_PRESS_CONFIRM else EntryAccess.OPEN
        SettingsEntry.PAUSE, SettingsEntry.RING_SWITCH -> if (panelMode) EntryAccess.HIDDEN else EntryAccess.OPEN
        SettingsEntry.OTHER -> EntryAccess.OPEN
    }

    /** Every entry that is not [EntryAccess.OPEN] for this mode, so a screen can lock and hide them as a group. */
    fun restricted(panelMode: Boolean): Map<SettingsEntry, EntryAccess> =
        SettingsEntry.entries.associateWith { access(panelMode, it) }.filterValues { it != EntryAccess.OPEN }
}

/** Why the app is in front (design section 7). Slice 1 derives it from what the app can see; the idle-return rules come with slice 2. */
enum class FrontReason(val wire: String) { RING("ring"), CALL("call"), HAND("hand"), NONE("none") }

object FrontReasons {
    /** A call (placed, Talk, answered) outranks a ring, a ring outranks "opened by hand"; not in front is [FrontReason.NONE]. */
    fun of(inFront: Boolean, ringUp: Boolean, callLive: Boolean): FrontReason = when {
        !inFront -> FrontReason.NONE
        callLive -> FrontReason.CALL
        ringUp -> FrontReason.RING
        else -> FrontReason.HAND
    }
}

/** The "Microphone is open" banner (design section 10): shown for the whole call in Panel mode, with Hang up; never outside it. */
object MicBanner {
    fun visible(panelMode: Boolean, callLive: Boolean): Boolean = panelMode && callLive
}

/** Whether the app holds the screen on (design section 7): while it is in front in Panel mode, never otherwise. */
object KeepScreenOn {
    fun wanted(panelMode: Boolean, inFront: Boolean): Boolean = panelMode && inFront
}
