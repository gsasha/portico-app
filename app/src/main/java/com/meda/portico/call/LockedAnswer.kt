package com.meda.portico.call

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.meda.portico.AppGraph
import com.meda.portico.core.ring.AnswerLockPolicy
import com.meda.portico.core.ring.AnswerLockPolicy.Route

/**
 * the owner 2026-11-21 (D-20261121-2918): Answer from the lock screen. The Android half of [AnswerLockPolicy]: reads the keyguard and the
 * Setup control, journals one `ui ans-lock` line (locked, setting, route, what it waited for), and hands the route to the caller.
 *
 * - HOME: the caller does what it always did (Home presses Answer).
 * - IN_CALL_OVER_LOCK: [openInCall]: the answer is pressed by this visible window and [InCallActivity] (show-when-locked in the manifest) is opened.
 * - UNLOCK_FIRST: the system's unlock prompt, then HOME; a cancelled prompt calls [onRefused] and the ring stays.
 *
 * Observation only beyond the routing: the journal never throws.
 */
object LockedAnswer {
    fun decide(a: Activity, graph: AppGraph, callId: String, onRoute: (Route) -> Unit, onRefused: () -> Unit) {
        val km = a.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = km?.isKeyguardLocked ?: true // unknown = locked, like every other keyguard read here
        val setting = try { graph.callAudio.answerWithoutUnlock() } catch (_: Exception) { AnswerLockPolicy.DEFAULT_ANSWER_WITHOUT_UNLOCK }
        val route = AnswerLockPolicy.route(locked, setting)
        journal(graph, "ans-lock", "call" to com.meda.portico.core.push.PushJournalFormat.DiagId(callId), "locked" to locked, "set" to setting, "route" to route.word, "waited" to AnswerLockPolicy.waitedWord(route))
        if (route != Route.UNLOCK_FIRST || km == null) return onRoute(if (route == Route.UNLOCK_FIRST) Route.HOME else route)
        val t0 = SystemClock.elapsedRealtime()
        km.requestDismissKeyguard(
            a,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    journal(graph, "ans-unlock", "r" to "ok", "ms" to (SystemClock.elapsedRealtime() - t0))
                    onRoute(Route.HOME)
                }

                override fun onDismissCancelled() {
                    journal(graph, "ans-unlock", "r" to "cancelled", "ms" to (SystemClock.elapsedRealtime() - t0))
                    onRefused()
                }

                override fun onDismissError() {
                    journal(graph, "ans-unlock", "r" to "error", "ms" to (SystemClock.elapsedRealtime() - t0))
                    onRefused()
                }
            },
        )
    }

    /** IN_CALL_OVER_LOCK: press Answer (the coordinator's one path; a second press for the same call is ignored) and open the in-call screen. */
    fun openInCall(a: Activity, graph: AppGraph, doorRef: String, callId: String) {
        graph.answer.press(doorRef, callId)
        try {
            a.startActivity(
                Intent(a, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            journal(graph, "incall-start", "call" to com.meda.portico.core.push.PushJournalFormat.DiagId(callId), "r" to "ok", "from" to a.javaClass.simpleName.lowercase())
        } catch (e: Exception) {
            journal(graph, "incall-start", "call" to com.meda.portico.core.push.PushJournalFormat.DiagId(callId), "r" to "err", "err" to e.javaClass.simpleName.lowercase())
            throw e // the caller keeps its window and journals the hand-off failure
        }
    }

    private fun journal(graph: AppGraph, event: String, vararg fields: Pair<String, Any?>) {
        try {
            graph.pushJournal.diag("ui", event, *fields)
        } catch (_: Throwable) {
        }
    }
}
