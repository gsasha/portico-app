package com.meda.portico.core.push

import com.meda.portico.core.ha.OutboundCallIds
import com.meda.portico.core.pause.PauseRules

/** Why an [Outcome.Silent] said nothing. For the journal only. */
enum class SilentReason {
    /** The message was [PushMessage.Ignored] (unknown `pv`, unknown kind, malformed). */
    IGNORED,

    /** `door_ref` is not in the cached `portico/doors` door refs (P2 plan §7 Q5: stay silent). */
    UNKNOWN_DOOR,

    /** This call already has a record with the same or a higher `seq` (duplicate, or out of order). */
    STALE,

    /** `answered` for a call this phone never rang: recorded, so a late ring stays silent. */
    ANSWERED_NO_RING,

    /**
     * A later kind for a call already finished on this phone: `ended` after `answered`, or
     * `answered` / `ended` after a late ring already posted "Missed call". Recorded, no new alert.
     */
    AFTER_TERMINAL,

    /** v1.1 (Q6): `ended` for a call this phone never rang. Recorded; no "Missed call". */
    ENDED_NO_RING,

    /**
     * Pause: a ring whose call began before this phone's last resume (a Doze-delayed push, or the
     * socket's snapshot on the first connect after a resume). Recorded as MISSED, so nothing later
     * for the call alerts either; never rung ([com.meda.portico.core.pause.PauseRules.replayable]).
     */
    BEFORE_RESUME,

    /**
     * P3-C: a message about a call this house DIALLED (`out-` id). Never a ring, never a notification, nothing recorded.
     * The server keeps these off every phone; this is the app's second layer, for the push path and the socket alike.
     */
    OUTBOUND_CALL,

    /**
     * DEV-807 (Panel mode): the door is not in the panel screen's `wake` list, so this panel does not take its ring. Nothing is recorded.
     * [Outcome.Silent.doorRef] and [Outcome.Silent.callId] say which call, for the `wall ring-front ... path=filtered` line and the heartbeat.
     */
    FILTERED,
}

/** Why an [Outcome.Missed] was posted. */
enum class MissedReason {
    /** A ring arrived after `exp + SKEW_MS`: nothing rings, a quiet "Missed call" is posted. */
    LATE_RING,

    /** `ended` while this phone was ringing and nobody answered. */
    ENDED_UNANSWERED,
}

/**
 * What the app does with one message. [update] is the store write [Decision.receive] has already
 * applied (null: nothing to record). The Android layer (PR-E receives, PR-F rings) acts only on the
 * outcome; it never re-derives it.
 *
 * The contract file's `expect` values are [contractName]: `ring`, `silent`, `dismiss`, `missed`,
 * `quiet` ([Quiet], v1.1).
 */
sealed interface Outcome {
    val update: CallRecord?
    val contractName: String

    /**
     * **Android:** post the CallStyle ring with a full-screen intent on `portico_ring_v2`,
     * `setTimeoutAfter(ringUntilMs - now)` plus an AlarmManager cancel at [ringUntilMs] (a backstop
     * that needs no later message), and start the socket connect so HA can confirm the call
     * (spec §4.3 "the push is a wake hint, HA is authoritative"). No foreground service.
     * [ringUntilMs] is `exp + SKEW_MS`, or for a socket ring `now + DEFAULT_RING_WINDOW + SKEW_MS`.
     */
    data class Ring(
        val doorRef: String,
        val callId: String,
        val ringUntilMs: Long,
        override val update: CallRecord,
    ) : Outcome {
        override val contractName get() = "ring"
    }

    /**
     * **Android:** post nothing new and do not stop anything; journal [reason]. (For
     * [SilentReason.AFTER_TERMINAL] the app may re-post the existing status notification with
     * `setOnlyAlertOnce(true)`, P2 plan §3.4; that is still no new alert.)
     */
    data class Silent(
        val reason: SilentReason,
        override val update: CallRecord? = null,
        /** Set for [SilentReason.FILTERED] only. */
        val doorRef: String? = null,
        val callId: String? = null,
    ) : Outcome {
        override val contractName get() = "silent"
    }

    /**
     * **Android:** stop the ring for [callId] and replace it with an "Answered elsewhere"
     * notification on `portico_status_v1`, `setTimeoutAfter(~10 s)` (spec §4.2: keeps the HIGH
     * message user-visible).
     */
    data class Dismiss(
        val doorRef: String,
        val callId: String,
        override val update: CallRecord,
    ) : Outcome {
        override val contractName get() = "dismiss"
    }

    /**
     * **Android:** stop the ring for [callId] if one is up, and post a quiet "Missed call" on
     * `portico_status_v1`. Never rings.
     */
    data class Missed(
        val doorRef: String,
        val callId: String,
        val reason: MissedReason,
        override val update: CallRecord,
    ) : Outcome {
        override val contractName get() = "missed"
    }

    /**
     * v1.1 `kind: "test"` (Q6). **Android:** post one quiet notification
     * ("Portico push ring notification works") on `portico_status_v1`; never a ring, never the full-screen intent.
     * Touches no call state.
     */
    data class Quiet(val tMs: Long?) : Outcome {
        override val update: CallRecord? get() = null
        override val contractName get() = "quiet"
    }
}

/**
 * The receive-path decision (P2 plan §3.3, spec §4.3). Pure: no clock, no I/O, no Android.
 */
object Decision {

    /**
     * Decide what [msg] means given what [store] already holds. Reads the store, never writes it:
     * the write is [Outcome.update]. Use [receive] from a delivery path.
     *
     * @param nowMs the phone's wall clock when the message arrived, epoch ms.
     * @param knownDoorRefs the cached `portico/doors` door refs (persisted; P2 plan §7 Q5).
     * @param resumedAtMs when this phone's last pause ended (epoch ms), 0 if never: a ring whose
     *   call time is not newer is history ([SilentReason.BEFORE_RESUME]).
     */
    fun decide(
        store: CallStore,
        msg: PushMessage,
        nowMs: Long,
        knownDoorRefs: Set<String>,
        resumedAtMs: Long = 0L,
        doorTaken: (String) -> Boolean = { true },
    ): Outcome {
        val call = when (msg) {
            is PushMessage.Ignored -> return Outcome.Silent(SilentReason.IGNORED)
            is PushMessage.Test -> return Outcome.Quiet(msg.tMs)
            is PushMessage.CallMessage -> msg
        }
        // 0. P3-C: a dialled call is not a visitor. Before the door check, and nothing is recorded.
        if (OutboundCallIds.isOutbound(call.callId)) return Outcome.Silent(SilentReason.OUTBOUND_CALL)
        // 1. Q5: a door this phone does not know stays silent, and nothing is recorded.
        if (call.doorRef !in knownDoorRefs) return Outcome.Silent(SilentReason.UNKNOWN_DOOR)

        val existing = store.get(CallKey(call.doorRef, call.callId))
        // 1b. DEV-807 (Panel mode): a door outside the screen's `wake` is not this panel's. Only a call nothing is recorded for: a ring this panel
        //     already took is followed to its end whatever `wake` says now. Nothing is recorded, so a later message for it is filtered too.
        if (existing == null && !doorTaken(call.doorRef)) return Outcome.Silent(SilentReason.FILTERED, null, call.doorRef, call.callId)
        // 2. Highest seq wins. `seq` is fixed per kind (ring 1, answered 2, ended 3), so this is
        //    "terminal kinds win": a duplicate, or anything older than what is held, says nothing.
        //    Checked before expiry, so a duplicate late ring does not post a second "Missed call".
        if (existing != null && call.seq <= existing.seq) return Outcome.Silent(SilentReason.STALE)

        fun record(state: CallState) =
            CallRecord(call.doorRef, call.callId, state, call.seq, call.tMs, call.source, nowMs)

        return when (call) {
            is PushMessage.Ring -> {
                // existing is null here: no ring kind has a seq below SEQ_RING.
                val exp = call.expMs
                if (PauseRules.withheld(call, resumedAtMs)) {
                    Outcome.Silent(SilentReason.BEFORE_RESUME, record(CallState.MISSED))
                } else if (exp != null && nowMs > exp + Contract.SKEW_MS) {
                    Outcome.Missed(call.doorRef, call.callId, MissedReason.LATE_RING, record(CallState.MISSED))
                } else {
                    val until = (exp ?: (nowMs + Contract.DEFAULT_RING_WINDOW_S * 1000L)) + Contract.SKEW_MS
                    Outcome.Ring(call.doorRef, call.callId, until, record(CallState.RINGING))
                }
            }
            is PushMessage.Answered -> when (existing?.state) {
                CallState.RINGING -> Outcome.Dismiss(call.doorRef, call.callId, record(CallState.ANSWERED))
                null -> Outcome.Silent(SilentReason.ANSWERED_NO_RING, record(CallState.ANSWERED))
                // MISSED (a late ring already posted "Missed call"): nothing is ringing to stop.
                else -> Outcome.Silent(SilentReason.AFTER_TERMINAL, record(CallState.ANSWERED))
            }
            is PushMessage.Ended -> when (existing?.state) {
                CallState.RINGING ->
                    Outcome.Missed(call.doorRef, call.callId, MissedReason.ENDED_UNANSWERED, record(CallState.ENDED))
                // v1.1 (Q6, contract case "ended with no ring seen"): no ring seen -> silent, recorded.
                null -> Outcome.Silent(SilentReason.ENDED_NO_RING, record(CallState.ENDED))
                else -> Outcome.Silent(SilentReason.AFTER_TERMINAL, record(CallState.ENDED))
            }
        }
    }

    /**
     * Decide and record in one [CallStore.atomically] step. The record is durable when this returns,
     * before the caller posts anything.
     */
    fun receive(
        store: CallStore,
        msg: PushMessage,
        nowMs: Long,
        knownDoorRefs: Set<String>,
        resumedAtMs: Long = 0L,
        doorTaken: (String) -> Boolean = { true },
    ): Outcome =
        store.atomically { s ->
            val outcome = decide(s, msg, nowMs, knownDoorRefs, resumedAtMs, doorTaken)
            outcome.update?.let(s::put)
            outcome
        }
}
