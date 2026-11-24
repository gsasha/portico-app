package com.meda.portico.setup

import com.meda.portico.core.ring.Severity
import com.meda.portico.core.ring.StatusLine

/**
 * APP-04: the old settings screen is two screens of one activity. The screen is chosen by an
 * intent extra (no manifest change, no new activity); [badgeCount] is the number on the Status
 * button: the checks that are failing right now.
 */
object SettingsStatusSplit {
    const val EXTRA_SCREEN = "portico.setup.screen"

    enum class Screen(val value: String) {
        SETTINGS("settings"),
        STATUS("status");

        companion object {
            /** Anything but "status" (including no extra, as every existing caller sends) is Settings. */
            fun of(value: String?): Screen = if (value == STATUS.value) STATUS else SETTINGS
        }
    }

    fun badgeCount(lines: List<StatusLine>): Int = lines.count { it.severity == Severity.PROBLEM }

    /** Where the Home screen enters: the status line opens Status; the menu has an item for each screen. */
    val HOME_STATUS_LINE_TARGET: Screen = Screen.STATUS
    val HOME_MENU_TARGETS: List<Screen> = listOf(Screen.SETTINGS, Screen.STATUS)
}
