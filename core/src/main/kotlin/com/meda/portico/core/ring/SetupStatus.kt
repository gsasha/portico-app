package com.meda.portico.core.ring

import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.ha.PushCommands
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.pause.PauseRules
import com.meda.portico.core.pause.PauseState
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.ha.Ago
import com.meda.portico.core.ha.ConnectTimeline
import com.meda.portico.core.ha.FailureClass
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ha.PathAttempt
import com.meda.portico.core.tailnet.FailReason
import com.meda.portico.core.tailnet.RouteCheck
import com.meda.portico.core.tailnet.TailnetState

/**
 * One line of Setup's status (P2 plan §3.4, §3.6); the app maps [key] to a string resource.
 * [arg2] is the resource's second argument. [failure] (the per-path lines) is the reason, which
 * the app turns into its own plain-words string and passes as the second argument.
 */
data class StatusLine(
    val key: StatusKey,
    val severity: Severity,
    val fix: Fix? = null,
    val arg: String? = null,
    val arg2: String? = null,
    val failure: FailureClass? = null,
)

enum class Severity { OK, INFO, PROBLEM }

/**
 * A button Status offers next to a line: an in-app action only. 0.6.69: none opens an Android system page (those
 * buttons live in Settings > Android settings and the line points there); a runtime permission request is in-app.
 */
enum class Fix {
    REQUEST_NOTIFICATIONS,
    RESTART_APP,
}

enum class StatusKey {
    // permissions and channels
    NOTIFICATIONS_OK, NOTIFICATIONS_NOT_ASKED, NOTIFICATIONS_DENIED, NOTIFICATIONS_DENIED_PERMANENT, NOTIFICATIONS_OFF,
    RING_CHANNEL_LOWERED, RING_CHANNEL_BLOCKED, STATUS_CHANNEL_BLOCKED,
    FULL_SCREEN_OK, FULL_SCREEN_DENIED, FULL_SCREEN_NOT_A_QUESTION,
    DND_ON, RING_BYPASS_DND, RING_NO_BYPASS_DND, BACKGROUND_RESTRICTED, BATTERY_OPTIMISED,

    // PR-G: the last ring could not use CallStyle; a plain notification was posted
    CALL_STYLE_REFUSED,

    // connection and push
    LINK_CONNECTED, LINK_CONNECTING, LINK_RETRYING, LINK_AUTH_REJECTED, LINK_SIGNED_OUT, LINK_NOT_ENROLLED, LINK_STOPPED,

    // PR-J: the network URL policy (UrlPolicy)
    LINK_URL_REFUSED, URL_PLAIN_HTTP, URL_REFUSED,
    PUSH_NO_FIREBASE, PUSH_FIREBASE_FAILED, PUSH_RESTART_TO_APPLY,
    PUSH_WAITING_FOR_FIREBASE, PUSH_REGISTERING, PUSH_OFF_IN_HA, PUSH_ON, PUSH_ON_LAST_OK,
    PUSH_REFUSED, PUSH_SERVER_WITHOUT_PUSH, PUSH_RETRY, PUSH_OFF_LOCAL, PUSH_SIGNED_OUT, PUSH_SERVER_STATE,

    // the local switch
    RING_SWITCH_OFF,

    // DEV-557: the tailnet client and the path the socket took
    LINK_PATH_DIRECT, LINK_PATH_TAILNET,
    TAILNET_NOT_BUILT, TAILNET_NOT_SET_UP, TAILNET_READY, TAILNET_KEY_PENDING, TAILNET_JOINING,
    TAILNET_NEEDS_APPROVAL, TAILNET_UP, TAILNET_KEY_REJECTED, TAILNET_NEEDS_LOGIN, TAILNET_TIMEOUT,
    TAILNET_LIBRARY_FAILED, TAILNET_ERROR, TAILNET_NO_TARGET, TAILNET_KEY_EXPIRED,

    // the last attempt on each path, why it failed, and the tailnet route to the dial IP
    PATH_DIRECT_OPENED, PATH_DIRECT_FAILED, PATH_TAILNET_OPENED, PATH_TAILNET_FAILED, PATH_TAILNET_NOT_BUILT,
    ROUTE_VIA, ROUTE_NONE, ROUTE_UNKNOWN,

    // DEV-694 P0: where the time of the last connect went (offsets only)
    CONNECT_TIMELINE,

    // Pause ("Pause this phone"): the top line, and what replaces the connection lines while paused
    PAUSED_UNTIL, PAUSED_UNTIL_RESUMED, LINK_PAUSED, PUSH_PAUSED, TAILNET_PAUSED,
}

/** POST_NOTIFICATIONS as Setup sees it. "Not asked" is not "refused" (memory `first-install-state-is-never-retested`). */
enum class NotifPermission {
    /** Below Android 13 there is no runtime permission (the user can still switch notifications off). */
    NOT_NEEDED, GRANTED, NOT_ASKED, DENIED_ASK_AGAIN, DENIED_PERMANENTLY;

    /**
     * 0.6.70: Settings > Android settings > Notifications shows the system permission prompt first when it can still be shown
     * (never asked, or refused once with the prompt still allowed); otherwise it opens Android's notifications page.
     */
    val promptsFirst: Boolean get() = this == NOT_ASKED || this == DENIED_ASK_AGAIN

    companion object {
        fun of(sdk: Int, granted: Boolean, askedBefore: Boolean, showRationale: Boolean): NotifPermission = when {
            sdk < 33 -> NOT_NEEDED
            granted -> GRANTED
            !askedBefore -> NOT_ASKED
            showRationale -> DENIED_ASK_AGAIN
            else -> DENIED_PERMANENTLY
        }
    }
}

enum class ChannelState {
    OK,

    /** The user lowered its importance (for the ring channel: below HIGH, which vetoes the full-screen intent). */
    LOWERED,
    BLOCKED,

    /** Not created yet (the app creates it at start-up; shown as OK). */
    MISSING,
}

/** What the phone reports, read by the app each time Setup is shown. */
data class PhoneState(
    val notifPermission: NotifPermission,
    val notificationsEnabled: Boolean,
    val ringChannel: ChannelState,
    val statusChannel: ChannelState,
    /**
     * The ring channel's `canBypassDnd()` as the phone reports it now (A-03): the app asks for the
     * bypass at creation, but the user can switch it off in the channel's settings, and some builds
     * ignore the request. Read fresh; never assumed.
     */
    val ringBypassesDnd: Boolean,
    /** `canUseFullScreenIntent()` on Android 14+; null below 14, where the manifest grant is enough. */
    val fullScreenAllowed: Boolean?,
    val dndActive: Boolean,
    val backgroundRestricted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
)

/**
 * The socket's state, mirrored from the app's `SignalingClient.State`. [PAUSED]: the phone is
 * paused, so the socket is closed and not retrying on purpose (not a failure).
 */
enum class LinkState { STOPPED, NOT_CONFIGURED, CONNECTING, CONNECTED, WAITING_TO_RETRY, AUTH_REJECTED, URL_REFUSED, PAUSED, SIGNED_OUT }

/** Pure: the phone's and the server's state -> Setup's lines, worst problems first within each group. */
object SetupStatus {

    fun permissionLines(p: PhoneState): List<StatusLine> = buildList {
        when (p.notifPermission) {
            NotifPermission.NOT_ASKED -> add(StatusLine(StatusKey.NOTIFICATIONS_NOT_ASKED, Severity.PROBLEM, Fix.REQUEST_NOTIFICATIONS))
            NotifPermission.DENIED_ASK_AGAIN -> add(StatusLine(StatusKey.NOTIFICATIONS_DENIED, Severity.PROBLEM, Fix.REQUEST_NOTIFICATIONS))
            NotifPermission.DENIED_PERMANENTLY -> add(StatusLine(StatusKey.NOTIFICATIONS_DENIED_PERMANENT, Severity.PROBLEM))
            NotifPermission.GRANTED, NotifPermission.NOT_NEEDED ->
                if (p.notificationsEnabled) {
                    add(StatusLine(StatusKey.NOTIFICATIONS_OK, Severity.OK))
                } else {
                    add(StatusLine(StatusKey.NOTIFICATIONS_OFF, Severity.PROBLEM))
                }
        }
        when (p.ringChannel) {
            ChannelState.BLOCKED -> add(StatusLine(StatusKey.RING_CHANNEL_BLOCKED, Severity.PROBLEM))
            ChannelState.LOWERED -> add(StatusLine(StatusKey.RING_CHANNEL_LOWERED, Severity.PROBLEM))
            ChannelState.OK, ChannelState.MISSING -> Unit
        }
        if (p.statusChannel == ChannelState.BLOCKED) {
            add(StatusLine(StatusKey.STATUS_CHANNEL_BLOCKED, Severity.PROBLEM))
        }
        // A-03: the truth about Do Not Disturb for a door call. Not asked of a blocked channel (that line says it all).
        if (p.ringChannel != ChannelState.BLOCKED) {
            if (p.ringBypassesDnd) {
                add(StatusLine(StatusKey.RING_BYPASS_DND, Severity.OK))
            } else {
                add(StatusLine(StatusKey.RING_NO_BYPASS_DND, Severity.INFO))
            }
        }
        when (p.fullScreenAllowed) {
            true -> add(StatusLine(StatusKey.FULL_SCREEN_OK, Severity.OK))
            false -> add(StatusLine(StatusKey.FULL_SCREEN_DENIED, Severity.PROBLEM))
            null -> add(StatusLine(StatusKey.FULL_SCREEN_NOT_A_QUESTION, Severity.OK))
        }
        if (p.dndActive) add(StatusLine(StatusKey.DND_ON, Severity.INFO))
        if (p.backgroundRestricted) add(StatusLine(StatusKey.BACKGROUND_RESTRICTED, Severity.PROBLEM))
        if (!p.ignoringBatteryOptimizations) add(StatusLine(StatusKey.BATTERY_OPTIMISED, Severity.INFO))
    }

    /**
     * @param ownRow this phone's row from `portico/push/status` (matched by installation id), if
     *   fetched; its `push_enabled` is fresher than the register result's.
     */
    fun pushLines(boot: BootResult, link: LinkState, reg: RegStatus?, ownRow: PushCommands.StatusRow?): List<StatusLine> = buildList {
        add(
            when (link) {
                LinkState.CONNECTED -> StatusLine(StatusKey.LINK_CONNECTED, Severity.OK)
                LinkState.CONNECTING -> StatusLine(StatusKey.LINK_CONNECTING, Severity.INFO)
                LinkState.WAITING_TO_RETRY -> StatusLine(StatusKey.LINK_RETRYING, Severity.INFO)
                LinkState.AUTH_REJECTED -> StatusLine(StatusKey.LINK_AUTH_REJECTED, Severity.PROBLEM)
                LinkState.NOT_CONFIGURED -> StatusLine(StatusKey.LINK_NOT_ENROLLED, Severity.PROBLEM)
                LinkState.STOPPED -> StatusLine(StatusKey.LINK_STOPPED, Severity.INFO)
                LinkState.URL_REFUSED -> StatusLine(StatusKey.LINK_URL_REFUSED, Severity.PROBLEM)
                LinkState.PAUSED -> StatusLine(StatusKey.LINK_PAUSED, Severity.INFO)
                LinkState.SIGNED_OUT -> StatusLine(StatusKey.LINK_SIGNED_OUT, Severity.PROBLEM)
            },
        )
        when (boot) {
            BootResult.NotConfigured -> {
                add(StatusLine(StatusKey.PUSH_NO_FIREBASE, Severity.INFO))
                return@buildList
            }
            is BootResult.Failed -> {
                add(StatusLine(StatusKey.PUSH_FIREBASE_FAILED, Severity.PROBLEM, arg = boot.reason))
                return@buildList
            }
            is BootResult.Ready -> if (boot.restartToApply) add(StatusLine(StatusKey.PUSH_RESTART_TO_APPLY, Severity.PROBLEM, Fix.RESTART_APP))
        }
        // Paused: the machine's last word and HA's last row are history. HA keeps this phone's
        // registration as it is (pushes still arrive, and are dropped here); nothing else to say.
        if (link == LinkState.PAUSED) {
            add(StatusLine(StatusKey.PUSH_PAUSED, Severity.INFO))
            return@buildList
        }
        when (reg) {
            null, RegStatus.NoFirebaseConfig, RegStatus.WaitingForRegistration ->
                add(StatusLine(StatusKey.PUSH_WAITING_FOR_FIREBASE, Severity.INFO))
            RegStatus.Ready, RegStatus.Sent -> add(StatusLine(StatusKey.PUSH_REGISTERING, Severity.INFO))
            is RegStatus.Registered -> {
                val enabled = ownRow?.pushEnabled ?: reg.pushEnabled
                when {
                    // Decision 4: HA's per-phone switch is off for a new phone; this is the normal first state.
                    !enabled -> add(StatusLine(StatusKey.PUSH_OFF_IN_HA, Severity.INFO))
                    ownRow?.lastOkAt != null -> add(StatusLine(StatusKey.PUSH_ON_LAST_OK, Severity.OK, arg = ownRow.lastOkAt))
                    else -> add(StatusLine(StatusKey.PUSH_ON, Severity.OK))
                }
            }
            is RegStatus.Refused -> add(StatusLine(StatusKey.PUSH_REFUSED, Severity.PROBLEM, arg = reg.userText))
            RegStatus.ServerWithoutPush -> add(StatusLine(StatusKey.PUSH_SERVER_WITHOUT_PUSH, Severity.INFO))
            is RegStatus.RetryNextConnect -> add(StatusLine(StatusKey.PUSH_RETRY, Severity.INFO, arg = reg.reason))
            RegStatus.PushOff -> add(StatusLine(StatusKey.PUSH_OFF_LOCAL, Severity.INFO))
            RegStatus.SignedOut -> add(StatusLine(StatusKey.PUSH_SIGNED_OUT, Severity.INFO))
        }
        val state = ownRow?.state
        if (state == "unregistered" || state == "project_mismatch") {
            add(StatusLine(StatusKey.PUSH_SERVER_STATE, Severity.PROBLEM, arg = state))
        }
    }

    /**
     * PR-G (ship checklist): the last ring's CallStyle was refused by this phone and the plain
     * fallback was used ([CallStyleFallback]). [refusedBy] is the persisted refusal (the
     * exception's class name), null when the last CallStyle was accepted or none was tried yet.
     * INFO, not PROBLEM: the plain ring still rings, with the same full-screen intent.
     */
    fun ringStyleLines(refusedBy: String?): List<StatusLine> = buildList {
        if (refusedBy == null) return@buildList
        val name = refusedBy.trim().ifEmpty { "unknown" }.take(MAX_REFUSAL_CHARS)
        add(StatusLine(StatusKey.CALL_STYLE_REFUSED, Severity.INFO, arg = name))
    }

    const val MAX_REFUSAL_CHARS = 64

    /**
     * PR-J: the stored `ha_url` under the network URL policy. Plain http on the LAN is allowed and
     * gets a persistent INFO line (the same text Setup shows under the configured host); a stored
     * URL the policy refuses (saved by an older version) is a PROBLEM with the policy's reason,
     * which never quotes the URL. https, or nothing stored, adds no line.
     */
    fun urlLines(check: UrlCheck?): List<StatusLine> = when (check) {
        is UrlCheck.HttpPrivate -> listOf(StatusLine(StatusKey.URL_PLAIN_HTTP, Severity.INFO))
        is UrlCheck.Refused -> listOf(StatusLine(StatusKey.URL_REFUSED, Severity.PROBLEM, arg = check.reason.message))
        is UrlCheck.Https, null -> emptyList()
    }

    /**
     * DEV-557: the path the open socket took, then the tailnet client's state. A failure is a
     * PROBLEM only when it leaves this phone unable to use the tailnet until somebody acts (a new
     * QR, an admin's approval); a timeout is retried by itself and is INFO. Never shows the key.
     */
    fun tailnetLines(state: TailnetState, path: LinkPath?, paused: Boolean = false): List<StatusLine> = buildList {
        // Paused: the node is asleep on purpose; its last state (a timeout, a join) is history.
        if (paused && state !is TailnetState.NotBuilt) {
            add(StatusLine(StatusKey.TAILNET_PAUSED, Severity.INFO))
            return@buildList
        }
        when (path) {
            LinkPath.DIRECT -> add(StatusLine(StatusKey.LINK_PATH_DIRECT, Severity.OK))
            LinkPath.TAILNET -> add(StatusLine(StatusKey.LINK_PATH_TAILNET, Severity.OK))
            null -> Unit
        }
        add(
            when (state) {
                TailnetState.NotBuilt -> StatusLine(StatusKey.TAILNET_NOT_BUILT, Severity.INFO)
                is TailnetState.Idle -> when {
                    state.keyPending -> StatusLine(StatusKey.TAILNET_KEY_PENDING, Severity.INFO)
                    state.hasIdentity -> StatusLine(StatusKey.TAILNET_READY, Severity.OK)
                    else -> StatusLine(StatusKey.TAILNET_NOT_SET_UP, Severity.INFO)
                }
                is TailnetState.Joining ->
                    if (state.needsApproval) StatusLine(StatusKey.TAILNET_NEEDS_APPROVAL, Severity.PROBLEM)
                    else StatusLine(StatusKey.TAILNET_JOINING, Severity.INFO)
                is TailnetState.Up -> StatusLine(StatusKey.TAILNET_UP, Severity.OK, arg = state.address)
                TailnetState.KeyExpired -> StatusLine(StatusKey.TAILNET_KEY_EXPIRED, Severity.PROBLEM)
                is TailnetState.Failed -> when (state.reason) {
                    FailReason.KEY_REJECTED -> StatusLine(StatusKey.TAILNET_KEY_REJECTED, Severity.PROBLEM)
                    FailReason.NEEDS_LOGIN -> StatusLine(StatusKey.TAILNET_NEEDS_LOGIN, Severity.PROBLEM)
                    FailReason.NEEDS_APPROVAL -> StatusLine(StatusKey.TAILNET_NEEDS_APPROVAL, Severity.PROBLEM)
                    FailReason.TIMEOUT -> StatusLine(StatusKey.TAILNET_TIMEOUT, Severity.INFO)
                    FailReason.LIBRARY -> StatusLine(StatusKey.TAILNET_LIBRARY_FAILED, Severity.PROBLEM, arg = state.detail.ifBlank { "unknown" })
                    FailReason.INTERNAL -> StatusLine(StatusKey.TAILNET_ERROR, Severity.PROBLEM, arg = state.detail.ifBlank { "unknown" })
                    FailReason.NO_TARGET -> StatusLine(StatusKey.TAILNET_NO_TARGET, Severity.PROBLEM)
                }
            },
        )
    }

    /**
     * The last attempt on each path ([PathAttempt], from FallbackConnector) with its time and,
     * when it failed, the reason ([StatusLine.failure], the app's plain words; [StatusLine.arg2]
     * is the HTTP status for [FailureClass.HTTP_STATUS], the server's contract number for [FailureClass.CONTRACT_MISMATCH]). Then, when the tailnet dial target is an
     * IP ([dialIp]), whether a peer routes to it. Never shows a URL, token or key: the arguments
     * are a time, an HTTP status, an IP, a peer name and a prefix.
     *
     * Severity: a failed tailnet attempt is a PROBLEM (it was the last way in); a failed direct
     * attempt is INFO while the tailnet is there to fall back on, else a PROBLEM.
     */
    fun pathLines(
        direct: PathAttempt?,
        tailnet: PathAttempt?,
        state: TailnetState,
        route: RouteCheck,
        dialIp: String?,
        nowMs: Long,
        paused: Boolean = false,
        timeline: ConnectTimeline.Snapshot? = null,
    ): List<StatusLine> = pathLinesOf(direct, tailnet, state, route, dialIp, nowMs, paused) + timelineLines(timeline, paused)

    /**
     * DEV-694 P0: one line with the last connect's timings ("direct failed +120 ms, library loaded
     * +1.9 s, node up +4.3 s"), or nothing when none was recorded or the phone is paused (the
     * numbers are history then, like the attempts). Offsets only: no address, key or token.
     */
    fun timelineLines(timeline: ConnectTimeline.Snapshot?, paused: Boolean = false): List<StatusLine> {
        if (paused) return emptyList()
        val text = ConnectTimeline.summary(timeline) ?: return emptyList()
        return listOf(StatusLine(StatusKey.CONNECT_TIMELINE, Severity.INFO, arg = text))
    }

    private fun pathLinesOf(
        direct: PathAttempt?,
        tailnet: PathAttempt?,
        state: TailnetState,
        route: RouteCheck,
        dialIp: String?,
        nowMs: Long,
        paused: Boolean,
    ): List<StatusLine> = buildList {
        if (paused) return@buildList // the last attempts are history: no stale failure while paused
        val noFallback = state is TailnetState.NotBuilt || tailnet == null
        direct?.let { a ->
            val ago = Ago.text(nowMs, a.atMs)
            val f = a.failure
            add(
                if (f == null) {
                    StatusLine(StatusKey.PATH_DIRECT_OPENED, Severity.OK, arg = ago)
                } else {
                    StatusLine(
                        StatusKey.PATH_DIRECT_FAILED, if (noFallback) Severity.PROBLEM else Severity.INFO,
                        arg = ago, arg2 = f.httpStatus?.toString() ?: f.endpoint ?: f.serverContract?.toString(), failure = f.cls,
                    )
                },
            )
        }
        if (state is TailnetState.NotBuilt) {
            if (direct?.failure != null) add(StatusLine(StatusKey.PATH_TAILNET_NOT_BUILT, Severity.INFO))
            return@buildList
        }
        tailnet?.let { a ->
            val ago = Ago.text(nowMs, a.atMs)
            val f = a.failure
            add(
                if (f == null) {
                    StatusLine(StatusKey.PATH_TAILNET_OPENED, Severity.OK, arg = ago)
                } else {
                    StatusLine(StatusKey.PATH_TAILNET_FAILED, Severity.PROBLEM, arg = ago, arg2 = f.httpStatus?.toString() ?: f.endpoint ?: f.serverContract?.toString(), failure = f.cls)
                },
            )
        }
        // The route matters once the tailnet is (or was just) in use, and only for an IP target.
        if (dialIp == null || (state !is TailnetState.Up && tailnet == null)) return@buildList
        add(
            when (route) {
                is RouteCheck.Via -> StatusLine(StatusKey.ROUTE_VIA, Severity.OK, arg = dialIp, arg2 = "${route.peer} (${route.prefix})")
                is RouteCheck.None -> StatusLine(StatusKey.ROUTE_NONE, Severity.PROBLEM, arg = route.ip, arg2 = route.peersSeen.toString())
                RouteCheck.Unknown, RouteCheck.NotIp -> StatusLine(StatusKey.ROUTE_UNKNOWN, Severity.INFO, arg = dialIp)
            },
        )
    }

    /**
     * The local switch (P2 plan §3.6). Portico does not look for other apps on the phone: that
     * would be one installer's glue in a public app (plan §3.6). Setup's text next to the switch
     * explains it in generic terms instead.
     */
    fun coexistenceLines(ringOnThisPhone: Boolean): List<StatusLine> = buildList {
        if (!ringOnThisPhone) add(StatusLine(StatusKey.RING_SWITCH_OFF, Severity.INFO))
    }

    /**
     * Setup's first line while paused: "Paused until HH:MM" ([hhmm] formats the deadline in the
     * phone's time zone) or "Paused until you resume". Nothing when not paused (or already expired).
     */
    fun pauseLines(state: PauseState, nowMs: Long, hhmm: (Long) -> String): List<StatusLine> = buildList {
        if (!PauseRules.active(state, nowMs)) return@buildList
        add(
            if (state.untilMs == 0L) StatusLine(StatusKey.PAUSED_UNTIL_RESUMED, Severity.INFO)
            else StatusLine(StatusKey.PAUSED_UNTIL, Severity.INFO, arg = hhmm(state.untilMs)),
        )
    }

    /** This phone's row in a `portico/push/status` result, by installation id. */
    fun ownRow(status: PushCommands.Outcome?, installationId: String?): PushCommands.StatusRow? {
        val rows = (status as? PushCommands.Outcome.Status)?.registrations ?: return null
        if (installationId.isNullOrBlank()) return null
        return rows.firstOrNull { it.installationId == installationId }
    }
}
