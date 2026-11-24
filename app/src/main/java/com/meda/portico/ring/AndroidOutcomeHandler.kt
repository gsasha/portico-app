package com.meda.portico.ring

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.Redact
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.core.ring.NoteKind
import com.meda.portico.core.ring.NotificationPlan
import com.meda.portico.core.ring.NotificationPlanner
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PermissionState
import com.meda.portico.fcm.OutcomeHandler

/**
 * The Android side of what to post, minus Android: [RingNotifier] implements it. Every call
 * returns quickly and may throw (the handler catches and journals).
 */
interface RingPoster {
    /** Post [spec]. [slot] is the door's slot, null only for the `kind: test` notification. */
    fun post(spec: NotificationSpec, slot: Int?, doorName: String?)

    fun cancelRing(slot: Int)

    /** AlarmManager backstop: at [atMs], cancel this door's ring if it is still [callId]. */
    fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long)

    fun cancelBackstop(slot: Int)
}

/**
 * PR-F's [OutcomeHandler] (replaces PR-E's logging one in `AppGraph`). It asks
 * [NotificationPlanner] what to post, then applies the plan through [poster]:
 *
 * 1. stop a ring the plan names, only if that door's active ring is THAT call;
 * 2. for a ring: record the active ring, arm the AlarmManager backstop, then post (the backstop is
 *    armed before the post can fail, so no exit leaves an unbounded ongoing notification);
 * 3. remember the status posted, for a later silent re-post.
 *
 * The same object serves [stopRing] for `RingReceiver` (decline, backstop) and `RingActivity`
 * (the stop button). Thread-safe (both delivery paths call in); never throws; journals a
 * masked call id only (`Redact.prefix`), never a registration or token (it never sees one).
 */
class AndroidOutcomeHandler(
    private val poster: RingPoster,
    private val state: RingState,
    private val slots: DoorSlots,
    private val perms: () -> PermissionState,
    private val ringOnThisPhone: () -> Boolean,
    private val doorName: (String) -> String?,
    private val clock: () -> Long,
    private val log: HaLog,
    /** The push and ring journal (0.5.12-alpha): write-only observation of what was applied; never read here. */
    private val journal: PushJournal? = null,
    /** M7 H2: both door sessions are in use, so a new ring is posted quietly. Always false while hold is off. */
    private val quietRing: () -> Boolean = { false },
    /** DEV-807 (Panel mode): a ring was filtered out by the screen's `wake` list (door ref, call id). Never throws into the ring path. */
    private val onFiltered: (String, String) -> Unit = { _, _ -> },
) : OutcomeHandler {

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** APP-03: told (on whichever thread changed it) every time a ring starts or stops, so the main screen draws it at once. Returns the remover. */
    fun addChangeListener(l: () -> Unit): () -> Unit {
        changeListeners += l
        return { changeListeners.remove(l) }
    }

    private fun changed() {
        for (l in changeListeners) {
            try {
                l()
            } catch (_: Exception) {
                // a screen that cannot redraw must never break the ring path
            }
        }
    }

    /** APP-03: the rings that are up on this phone now, door ref -> call id (never throws). */
    fun activeRings(): Map<String, String> = try {
        state.activeRings()
    } catch (e: Exception) {
        emptyMap()
    }

    override fun onRing(outcome: Outcome.Ring) = handle(outcome)
    override fun onDismiss(outcome: Outcome.Dismiss) = handle(outcome)
    override fun onMissed(outcome: Outcome.Missed) = handle(outcome)
    override fun onSilent(outcome: Outcome.Silent) {
        val door = outcome.doorRef
        val call = outcome.callId
        if (outcome.reason == com.meda.portico.core.push.SilentReason.FILTERED && door != null && call != null) {
            try { onFiltered(door, call) } catch (_: Throwable) { }
        }
        handle(outcome)
    }
    override fun onQuiet(outcome: Outcome.Quiet) = handle(outcome)

    /** Stops [callId]'s ring on this phone (decline, backstop, the ring screen's button). HA is not told. */
    @Synchronized
    fun stopRing(doorRef: String, callId: String, why: String) {
        guarded("stop") {
            val stopped = stopIfActive(doorRef, callId)
            log.log(HaLog.Level.INFO, "portico ring: $why door=${Redact.prefix(doorRef)} call=${Redact.prefix(callId)} ${if (stopped) "stopped" else "not ringing"}")
            observe { it.stopped(why, doorRef, callId, stopped) }
        }
        changed()
    }

    /**
     * Pause: stop every ring that is up on this phone (its notification and its backstop alarm).
     * HA is not told. Returns how many were stopped.
     */
    @Synchronized
    fun stopAllRings(why: String): Int {
        var n = 0
        guarded("stop all") {
            for ((door, call) in state.activeRings()) {
                if (stopIfActive(door, call)) n++
            }
            log.log(HaLog.Level.INFO, "portico ring: $why: stopped $n ring(s)")
            observe { it.stoppedAll(why, n) }
        }
        changed()
        return n
    }

    /** True while [callId] is the ring up for [doorRef] on this phone. */
    fun isRinging(doorRef: String, callId: String): Boolean = try {
        state.activeRing(doorRef) == callId
    } catch (e: Exception) {
        false
    }

    @Synchronized
    private fun handle(outcome: Outcome) {
        guarded(outcome.contractName) {
            val doorRef = doorOf(outcome)
            val p = try {
                perms()
            } catch (e: Exception) {
                PermissionState() // unknown: post anyway; the system has the last word
            }
            val ringOn = try {
                ringOnThisPhone()
            } catch (e: Exception) {
                true
            }
            val last = doorRef?.let { state.lastStatus(it) }
            val quiet = try {
                quietRing()
            } catch (e: Exception) {
                false
            }
            val plan = NotificationPlanner.plan(outcome, clock(), ringOn, p, last, quiet)
            apply(plan)
            log.log(HaLog.Level.INFO, describe(outcome, plan))
            observe { it.handled(outcome, plan, p) }
        }
        changed()
    }

    private fun apply(plan: NotificationPlan) {
        plan.cancelRing?.let { stopIfActive(it.doorRef, it.callId) }
        plan.post?.let { spec ->
            val door = spec.doorRef
            val slot = door?.let(slots::slotFor)
            if (spec.kind == NoteKind.RING && door != null && slot != null) {
                val call = spec.callId!!
                state.setActiveRing(door, call)
                spec.backstopAtMs?.let { poster.armBackstop(slot, door, call, it) }
            }
            poster.post(spec, slot, door?.let(::nameOrNull))
        }
        plan.recordStatus?.let(state::setLastStatus)
    }

    private fun stopIfActive(doorRef: String, callId: String): Boolean {
        if (!state.clearActiveRing(doorRef, callId)) return false
        val slot = slots.slotFor(doorRef)
        poster.cancelRing(slot)
        poster.cancelBackstop(slot)
        return true
    }

    private fun nameOrNull(doorRef: String): String? = try {
        doorName(doorRef)
    } catch (e: Exception) {
        null
    }

    private fun doorOf(o: Outcome): String? = when (o) {
        is Outcome.Ring -> o.doorRef
        is Outcome.Dismiss -> o.doorRef
        is Outcome.Missed -> o.doorRef
        is Outcome.Silent -> o.update?.doorRef
        is Outcome.Quiet -> null
    }

    private fun describe(o: Outcome, plan: NotificationPlan): String {
        val call = when (o) {
            is Outcome.Ring -> o.callId
            is Outcome.Dismiss -> o.callId
            is Outcome.Missed -> o.callId
            is Outcome.Silent -> o.update?.callId
            is Outcome.Quiet -> null
        }
        val what = plan.post?.let { s -> s.kind.name.lowercase() + if (s.onlyAlertOnce) " (re-post, no alert)" else "" } ?: "nothing"
        val warn = if (plan.warnings.isEmpty()) "" else " warnings=${plan.warnings.joinToString(",") { it.name.lowercase() }}"
        return "portico ring: ${o.contractName} call=${Redact.prefix(call)} -> $what${if (plan.cancelRing != null) " +stop" else ""}$warn"
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(HaLog.Level.ERROR, "portico ring: $what failed: ${e.javaClass.simpleName}")
            observe { it.failed(what, e) }
        }
    }

    /** Journal calls never throw into the ring path (the journal catches too; this is the second belt). */
    private inline fun observe(block: (PushJournal) -> Unit) {
        val j = journal ?: return
        try {
            block(j)
        } catch (e: Throwable) {
            // write-only observation
        }
    }
}
