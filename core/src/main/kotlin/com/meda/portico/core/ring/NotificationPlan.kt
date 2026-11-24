package com.meda.portico.core.ring

import com.meda.portico.core.push.CallKey
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.RingGate
import com.meda.portico.core.push.SilentReason

/**
 * The two notification channels (P2 plan §3.4). Importance is fixed when a channel is created and
 * can only ever be lowered afterwards, so a change of importance or sound policy is a NEW id
 * (`_v2`) plus deleting the old one, never an edit of these values.
 */
enum class Channel(val id: String, val importance: Importance, val bypassDnd: Boolean = false) {
    /**
     * Door rings: IMPORTANCE_HIGH, ringtone sound on the alarm stream, vibration, and a request to
     * bypass Do Not Disturb (A-03, as the old app's channel did; FR-9 "no DND"); the ring itself sets
     * FLAG_INSISTENT. `_v2` because `_v1` was created without the bypass and without the alarm audio
     * attributes, and neither can be changed on an existing channel; `_v1` is in [RETIRED_IDS].
     */
    RING("portico_ring_v2", Importance.HIGH, bypassDnd = true),

    /**
     * "Answered elsewhere", "Missed call", the `kind: test` check. DEFAULT, not LOW: LOW files it
     * under the shade's collapsed "Silent" group where nobody looks. Silence comes from the channel
     * (no sound, no vibration).
     */
    STATUS("portico_status_v1", Importance.DEFAULT),

    /**
     * Pause: the one ongoing "Portico is paused. Resume" notification. LOW and silent by request
     * (2026-10-09): it must never sound. Unlike STATUS it is allowed to sit in the shade's
     * "Silent" group: Setup's own top line says the phone is paused as well.
     */
    PAUSED("portico_paused_v1", Importance.LOW),

    /**
     * "Stay connected": the one ongoing, silent notification of the connection service ("Connected
     * to <home>" / "Waiting for Wi-Fi"). LOW and silent by decision (2026-10-15): it is there
     * because Android requires it, not to be looked at. Never a ring, never a CallStyle.
     */
    CONNECTION("portico_connection_v1", Importance.LOW),

    /**
     * 0.6.76: the door ring's notification while the CALL WINDOW is (about to be) on screen on an unlocked phone: LOW, no sound, no vibration,
     * so Android shows no heads-up banner on top of the window; the window plays the ring channel's own sound and vibration itself. It is
     * NOT in the ring family ([RingChannelIds]), so the start-up cleanup never deletes it. Locked or screen off keeps [RING].
     */
    RING_WINDOW("portico_ring_window", Importance.LOW),
    ;

    companion object {
        /** Ids this app created in earlier versions; deleted at start-up. */
        val RETIRED_IDS: List<String> = listOf("portico_ring_v1")
    }
}

enum class Importance { HIGH, DEFAULT, LOW }

/** What a notification says; the app maps each key to a string resource. */
enum class TextKey {
    RING_TITLE, RING_TEXT,

    /** A ring that comes while both door sessions are in use (M7 H2): "Both of your calls are in use." */
    RING_TEXT_BOTH_BUSY,
    ANSWERED_TITLE, ANSWERED_TEXT,
    MISSED_TITLE, MISSED_TEXT,
    TEST_TITLE, TEST_TEXT,
}

/** The kinds of notification Portico posts. */
enum class NoteKind { RING, ANSWERED_ELSEWHERE, MISSED, TEST }

/**
 * A plain description of one notification to post. The Android layer builds exactly this and
 * decides nothing itself.
 *
 * @param timeoutMs `setTimeoutAfter`, null for none.
 * @param fullScreen attach the full-screen intent (`RingActivity`). Only a ring.
 * @param callStyle `Notification.CallStyle.forIncomingCall`; if the system refuses it, a plain
 *   notification with the same actions is posted instead (the ring must not be lost).
 * @param insistent `FLAG_INSISTENT`: the ring's sound repeats until the notification goes.
 * @param onlyAlertOnce `setOnlyAlertOnce(true)`: a re-post that must not alert again.
 * @param backstopAtMs an AlarmManager cancel at this wall-clock time (a cap that needs no later
 *   message); null for none.
 */
data class NotificationSpec(
    val kind: NoteKind,
    val channel: Channel,
    val doorRef: String?,
    val callId: String?,
    val title: TextKey,
    val text: TextKey,
    val timeoutMs: Long?,
    val fullScreen: Boolean,
    val callStyle: Boolean,
    val insistent: Boolean,
    val ongoing: Boolean,
    val autoCancel: Boolean,
    val onlyAlertOnce: Boolean,
    val backstopAtMs: Long?,
)

/** Why a posted notification may not reach the user as intended. Logged, and shown in Setup. */
enum class PlanWarning {
    /** App notifications are off (or POST_NOTIFICATIONS is not granted): the system drops it. */
    NOTIFICATIONS_BLOCKED,

    /** The user switched the ring channel off. */
    RING_CHANNEL_BLOCKED,

    /** The ring channel's importance was lowered below HIGH: no heads-up, no full-screen intent. */
    RING_CHANNEL_LOWERED,

    /** The status channel is off: "Answered elsewhere" / "Missed call" are not shown. */
    STATUS_CHANNEL_BLOCKED,

    /** Android 14+: full-screen intents are not allowed for this app; a heads-up only. */
    FULL_SCREEN_DENIED,
}

/** What the phone allows right now, read by the app just before it posts. */
data class PermissionState(
    val notificationsEnabled: Boolean = true,
    val ringChannelEnabled: Boolean = true,
    val ringChannelHigh: Boolean = true,
    val statusChannelEnabled: Boolean = true,
    val fullScreenAllowed: Boolean = true,
)

/** The status notification last posted for a door, so a later silent message can re-post it. */
data class LastStatus(val key: CallKey, val kind: NoteKind)

/**
 * The result of [NotificationPlanner.plan].
 *
 * @param cancelRing stop the ring for this call, if the ring up for its door is this call.
 * @param post the notification to post, or null.
 * @param recordStatus the status this plan posts, to remember for a later silent re-post.
 */
data class NotificationPlan(
    val cancelRing: CallKey?,
    val post: NotificationSpec?,
    val warnings: List<PlanWarning>,
    val recordStatus: LastStatus?,
) {
    val postsNothing: Boolean get() = cancelRing == null && post == null

    companion object {
        val NOTHING = NotificationPlan(null, null, emptyList(), null)
    }
}

/**
 * Maps a decided [Outcome] to what to post (P2 plan §3.3-§3.4, spec §4.2-§4.3). Pure: the clock
 * and the phone's state are parameters.
 */
object NotificationPlanner {
    /** "Answered elsewhere" stays this long (spec §4.2: ~10 s, keeps the HIGH message visible). */
    const val DISMISS_TIMEOUT_MS = 10_000L

    /** A ring is never posted with a timeout shorter than this (a clock that moved under us). */
    const val MIN_RING_TIMEOUT_MS = 1_000L

    /**
     * @param ringOnThisPhone the local switch (Q2, default ON). [com.meda.portico.core.push.RingGate]
     *   is applied again here, so a door-call alert is never posted with the switch off whoever calls.
     * @param lastStatus the status notification last posted for the outcome's door, if any.
     * @param quietRing M7 H2 (15.13 failure f): both door sessions are in use, so this ring is posted on the silent status channel,
     *   without the full-screen takeover or the CallStyle, and says so. Default false: the ring is exactly what it was.
     */
    fun plan(
        outcome: Outcome,
        nowMs: Long,
        ringOnThisPhone: Boolean,
        perms: PermissionState,
        lastStatus: LastStatus?,
        quietRing: Boolean = false,
    ): NotificationPlan {
        if (!RingGate.shouldHandle(outcome, ringOnThisPhone)) return NotificationPlan.NOTHING
        return when (outcome) {
            is Outcome.Ring -> if (quietRing) {
                val spec = NotificationSpec(
                    kind = NoteKind.RING,
                    channel = Channel.STATUS,
                    doorRef = outcome.doorRef,
                    callId = outcome.callId,
                    title = TextKey.RING_TITLE,
                    text = TextKey.RING_TEXT_BOTH_BUSY,
                    timeoutMs = maxOf(outcome.ringUntilMs - nowMs, MIN_RING_TIMEOUT_MS),
                    fullScreen = false,
                    callStyle = false,
                    insistent = false,
                    ongoing = true,
                    autoCancel = false,
                    onlyAlertOnce = true,
                    backstopAtMs = outcome.ringUntilMs,
                )
                NotificationPlan(null, spec, warnings(Channel.STATUS, perms), null)
            } else {
                val spec = NotificationSpec(
                    kind = NoteKind.RING,
                    channel = Channel.RING,
                    doorRef = outcome.doorRef,
                    callId = outcome.callId,
                    title = TextKey.RING_TITLE,
                    text = TextKey.RING_TEXT,
                    timeoutMs = maxOf(outcome.ringUntilMs - nowMs, MIN_RING_TIMEOUT_MS),
                    fullScreen = true,
                    callStyle = true,
                    insistent = true,
                    ongoing = true,
                    autoCancel = false,
                    onlyAlertOnce = false,
                    backstopAtMs = outcome.ringUntilMs,
                )
                NotificationPlan(null, spec, warnings(Channel.RING, perms), null)
            }
            is Outcome.Dismiss -> statusPlan(CallKey(outcome.doorRef, outcome.callId), NoteKind.ANSWERED_ELSEWHERE, perms, repost = false)
            is Outcome.Missed -> statusPlan(CallKey(outcome.doorRef, outcome.callId), NoteKind.MISSED, perms, repost = false)
            is Outcome.Silent -> {
                val key = outcome.update?.key
                if (outcome.reason == SilentReason.AFTER_TERMINAL && key != null && lastStatus?.key == key) {
                    // P2 plan §3.4: "ended after answered" is silent, but the "Answered elsewhere"
                    // already shown is re-posted with onlyAlertOnce, so the message stays visible.
                    statusPlan(key, lastStatus.kind, perms, repost = true)
                } else {
                    NotificationPlan.NOTHING
                }
            }
            is Outcome.Quiet -> NotificationPlan(null, statusSpec(null, NoteKind.TEST, repost = false), warnings(Channel.STATUS, perms), null)
        }
    }

    private fun statusPlan(key: CallKey, kind: NoteKind, perms: PermissionState, repost: Boolean): NotificationPlan =
        NotificationPlan(
            cancelRing = if (repost) null else key,
            post = statusSpec(key, kind, repost),
            warnings = warnings(Channel.STATUS, perms),
            recordStatus = LastStatus(key, kind),
        )

    private fun statusSpec(key: CallKey?, kind: NoteKind, repost: Boolean): NotificationSpec {
        val (title, text, timeout) = when (kind) {
            NoteKind.ANSWERED_ELSEWHERE -> Triple(TextKey.ANSWERED_TITLE, TextKey.ANSWERED_TEXT, DISMISS_TIMEOUT_MS)
            // Kept until the user clears it: a missed door call is something to see later.
            NoteKind.MISSED -> Triple(TextKey.MISSED_TITLE, TextKey.MISSED_TEXT, null)
            NoteKind.TEST -> Triple(TextKey.TEST_TITLE, TextKey.TEST_TEXT, null)
            NoteKind.RING -> error("a ring is not a status notification")
        }
        return NotificationSpec(
            kind = kind,
            channel = Channel.STATUS,
            doorRef = key?.doorRef,
            callId = key?.callId,
            title = title,
            text = text,
            timeoutMs = timeout,
            fullScreen = false,
            callStyle = false,
            insistent = false,
            ongoing = false,
            autoCancel = true,
            onlyAlertOnce = repost,
            backstopAtMs = null,
        )
    }

    private fun warnings(channel: Channel, perms: PermissionState): List<PlanWarning> = buildList {
        if (!perms.notificationsEnabled) add(PlanWarning.NOTIFICATIONS_BLOCKED)
        when (channel) {
            Channel.RING -> {
                if (!perms.ringChannelEnabled) add(PlanWarning.RING_CHANNEL_BLOCKED)
                if (!perms.ringChannelHigh) add(PlanWarning.RING_CHANNEL_LOWERED)
                if (!perms.fullScreenAllowed) add(PlanWarning.FULL_SCREEN_DENIED)
            }
            Channel.STATUS -> if (!perms.statusChannelEnabled) add(PlanWarning.STATUS_CHANNEL_BLOCKED)
            Channel.PAUSED -> Unit // never planned here: the pause posts its own notification
            Channel.CONNECTION -> Unit // never planned here: the connection service posts its own
            Channel.RING_WINDOW -> Unit // never planned here: the notifier swaps a ring onto it at post time
        }
    }
}
