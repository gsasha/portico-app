package com.meda.portico.core.ring

/** The colour of a status row's dot. GREEN ok, AMBER needs attention or in progress, RED broken or blocked, GREY off or unknown. */
enum class Dot { GREEN, AMBER, RED, GREY }

/** The two titled groups of the Status screen, in display order. 0.6.70: "This phone" is merged into Home Assistant; "Ringing on this phone" is gone. */
enum class StatusGroup { HOME_ASSISTANT, PUSH }

data class StatusRow(val group: StatusGroup, val line: StatusLine, val dot: Dot)

/** What a group shows, top to bottom: a check (dot + text) or a fact (label: value, two aligned columns). */
sealed interface StatusItem {
    data class Check(val row: StatusRow) : StatusItem
    data class Fact(val group: StatusGroup, val fact: StatusFact) : StatusItem
}

/**
 * 0.6.69: the Status screen's board. Pure: the lines the producers in [SetupStatus] emit -> [dedupe] (one row per
 * fact) -> grouped rows, each with its [Dot]. The producers are unchanged; every check still exists.
 */
object StatusBoard {

    private val AMBER_PROBLEMS = setOf(StatusKey.PUSH_RESTART_TO_APPLY)

    /** INFO lines that mean "off / not set up / paused / unknown", not "something is in progress or worth a look". */
    private val GREY_INFOS = setOf(
        StatusKey.LINK_STOPPED, StatusKey.LINK_PAUSED, StatusKey.PUSH_PAUSED, StatusKey.TAILNET_PAUSED,
        StatusKey.PUSH_NO_FIREBASE, StatusKey.PUSH_OFF_LOCAL, StatusKey.PUSH_SIGNED_OUT, StatusKey.PUSH_SERVER_WITHOUT_PUSH,
        StatusKey.PUSH_OFF_IN_HA, StatusKey.TAILNET_NOT_BUILT, StatusKey.TAILNET_NOT_SET_UP, StatusKey.PATH_TAILNET_NOT_BUILT,
        StatusKey.ROUTE_UNKNOWN, StatusKey.CONNECT_TIMELINE,
        StatusKey.PAUSED_UNTIL, StatusKey.PAUSED_UNTIL_RESUMED,
    )

    /**
     * 0.6.70: phone and ring configuration, not status. The producers still exist (the permission logic is reused), but a line
     * with one of these keys is never shown on the Status screen.
     */
    val RETIRED_KEYS = setOf(
        StatusKey.NOTIFICATIONS_OK, StatusKey.NOTIFICATIONS_NOT_ASKED, StatusKey.NOTIFICATIONS_DENIED,
        StatusKey.NOTIFICATIONS_DENIED_PERMANENT, StatusKey.NOTIFICATIONS_OFF,
        StatusKey.RING_CHANNEL_LOWERED, StatusKey.RING_CHANNEL_BLOCKED, StatusKey.STATUS_CHANNEL_BLOCKED,
        StatusKey.FULL_SCREEN_OK, StatusKey.FULL_SCREEN_DENIED, StatusKey.FULL_SCREEN_NOT_A_QUESTION,
        StatusKey.DND_ON, StatusKey.RING_BYPASS_DND, StatusKey.RING_NO_BYPASS_DND,
        StatusKey.BACKGROUND_RESTRICTED, StatusKey.BATTERY_OPTIMISED, StatusKey.CALL_STYLE_REFUSED, StatusKey.RING_SWITCH_OFF,
    )

    fun groupOf(key: StatusKey): StatusGroup = when {
        key.name.startsWith("PUSH_") -> StatusGroup.PUSH
        else -> StatusGroup.HOME_ASSISTANT // LINK_*, URL_*, TAILNET_*, PATH_*, ROUTE_*, PAUSED_*, CONNECT_TIMELINE
    }

    fun dotOf(line: StatusLine): Dot = when (line.severity) {
        Severity.OK -> Dot.GREEN
        Severity.PROBLEM -> if (line.key in AMBER_PROBLEMS) Dot.AMBER else Dot.RED
        Severity.INFO -> if (line.key in GREY_INFOS) Dot.GREY else Dot.AMBER
    }

    /**
     * One row per fact. Each rule drops the line that only repeats another that is present:
     * - connected: the "connected" line and the "direct path opened / tailnet path opened" lines say what the path line says;
     * - paused: the connection line says it, the push and tailnet "paused" lines repeat it;
     * - signed out: the push "signed out" line repeats the connection line;
     * - refused address: the connection line repeats the address line (which carries the reason);
     * - tailnet not built: the "path not tried" line repeats it;
     * - not enrolled: no address, sign-in or phone-name fact (there is none) and no Firebase fact;
     * - signed out, or the address refused: the sign-in / address fact repeats what the line says;
     * - retired (phone and ring configuration) keys are never shown.
     */
    fun dedupe(lines: List<StatusLine>): List<StatusLine> {
        val keys = lines.mapTo(HashSet()) { it.key }
        val drop = HashSet<StatusKey>()
        val pathRow = StatusKey.LINK_PATH_DIRECT in keys || StatusKey.LINK_PATH_TAILNET in keys
        if (StatusKey.LINK_CONNECTED in keys) {
            drop += StatusKey.PATH_DIRECT_OPENED
            drop += StatusKey.PATH_TAILNET_OPENED
            if (pathRow) drop += StatusKey.LINK_CONNECTED
        }
        if (StatusKey.LINK_PAUSED in keys) {
            drop += StatusKey.PUSH_PAUSED
            drop += StatusKey.TAILNET_PAUSED
        }
        if (StatusKey.LINK_SIGNED_OUT in keys) drop += StatusKey.PUSH_SIGNED_OUT
        if (StatusKey.URL_REFUSED in keys) drop += StatusKey.LINK_URL_REFUSED
        if (StatusKey.TAILNET_NOT_BUILT in keys) drop += StatusKey.PATH_TAILNET_NOT_BUILT
        drop += RETIRED_KEYS
        return lines.filter { it.key !in drop }
    }

    /** Facts that only repeat a check that is present (the merged "This phone" facts, 0.6.70). */
    fun dedupeFacts(facts: List<StatusFact>, lines: List<StatusLine>): List<StatusFact> {
        val keys = lines.mapTo(HashSet()) { it.key }
        val drop = HashSet<FactKey>()
        if (StatusKey.LINK_NOT_ENROLLED in keys) {
            drop += FactKey.ADDRESS; drop += FactKey.TAILNET_DIAL; drop += FactKey.SIGN_IN; drop += FactKey.PHONE_NAME; drop += FactKey.FIREBASE
        }
        if (StatusKey.LINK_SIGNED_OUT in keys) drop += FactKey.SIGN_IN
        if (StatusKey.URL_REFUSED in keys) drop += FactKey.ADDRESS // that line carries the address and the reason
        if (StatusKey.PUSH_NO_FIREBASE in keys) drop += FactKey.FIREBASE
        return facts.filter { it.key !in drop }
    }

    private fun rank(d: Dot) = when (d) { Dot.RED -> 0; Dot.AMBER -> 1; Dot.GREY -> 2; Dot.GREEN -> 3 }

    /** The deduped lines grouped in display order. Home Assistant keeps its logical order (see [homeOrder]); push puts the worst first (stable). Every group is present, possibly empty. */
    fun build(lines: List<StatusLine>): Map<StatusGroup, List<StatusRow>> {
        val rows = dedupe(lines).map { StatusRow(groupOf(it.key), it, dotOf(it)) }
        return StatusGroup.entries.associateWith { g ->
            val mine = rows.filter { it.group == g }
            if (g == StatusGroup.PUSH) mine.sortedBy { rank(it.dot) } else mine
        }
    }

    /**
     * 0.6.70: the whole Status screen. Home Assistant is ONE section, top to bottom: the connection state (and how: directly or
     * through the tailnet, with the tailnet's details), the address, the address's security, the tailnet dial target, who is
     * signed in, the phone's name, the app version. The push group is Firebase set-up, registration, last push.
     * Checks keep the producers' order within a rank; facts are placed by [homeOrder].
     */
    fun items(lines: List<StatusLine>, facts: List<StatusFact>): Map<StatusGroup, List<StatusItem>> {
        val rows = build(lines)
        val kept = dedupeFacts(facts, lines)
        return StatusGroup.entries.associateWith { g ->
            val all = rows.getValue(g).map<StatusRow, StatusItem> { StatusItem.Check(it) } +
                kept.filter { it.key.group == g }.map { StatusItem.Fact(g, it) }
            if (g == StatusGroup.HOME_ASSISTANT) all.sortedBy { homeOrder(it) } else if (g == StatusGroup.PUSH) all.sortedBy { if (it is StatusItem.Fact) 1 else 0 } else all
        }
    }

    /** The place of an item in the Home Assistant section (a stable sort keeps the producers' order inside one rank). */
    fun homeOrder(item: StatusItem): Int = when (item) {
        is StatusItem.Fact -> when (item.fact.key) {
            FactKey.ADDRESS -> 20
            FactKey.TAILNET_DIAL -> 40
            FactKey.SIGN_IN -> 50
            FactKey.PHONE_NAME -> 60
            FactKey.ENROLMENT -> 70
            FactKey.VERSION -> 80
            FactKey.FIREBASE -> 90
        }
        is StatusItem.Check -> when {
            item.row.line.key.name.startsWith("LINK_") && item.row.line.key != StatusKey.LINK_URL_REFUSED -> if (item.row.line.key in PATH_LINKS) 5 else 0
            item.row.line.key.name.startsWith("PAUSED_") -> 0
            item.row.line.key.name.startsWith("URL_") || item.row.line.key == StatusKey.LINK_URL_REFUSED -> 30
            else -> 10 // TAILNET_*, PATH_*, ROUTE_*, CONNECT_TIMELINE: how it connects, after the connection state
        }
    }

    private val PATH_LINKS = setOf(StatusKey.LINK_PATH_DIRECT, StatusKey.LINK_PATH_TAILNET)
}
