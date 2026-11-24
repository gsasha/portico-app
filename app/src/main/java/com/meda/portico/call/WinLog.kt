package com.meda.portico.call

import com.meda.portico.AppGraph
import com.meda.portico.core.push.PushJournalFormat.DiagId

/**
 * D-20261121-ee09: which window is on screen, as Journal lines the server can read (`ui win-*`). The window classes' older "ring screen: ..."
 * lines stay on the phone only; these reach the connection log. Observation only; never throws.
 *
 * `win` is `ring` (RingCallActivity), `incall` (InCallActivity) or `outgoing`. Events: `win-open` (created), `win-front` (resumed),
 * `win-close` (asked to finish, with why), `win-gone` (destroyed, with whether it was finishing).
 */
object WinLog {
    fun open(graph: AppGraph, win: String, callId: String?, vararg more: Pair<String, Any?>) = line(graph, "win-open", win, callId, *more)
    fun front(graph: AppGraph, win: String, callId: String?, vararg more: Pair<String, Any?>) = line(graph, "win-front", win, callId, *more)
    fun close(graph: AppGraph, win: String, callId: String?, why: String, vararg more: Pair<String, Any?>) = line(graph, "win-close", win, callId, "why" to why, *more)
    fun gone(graph: AppGraph, win: String, callId: String?, finishing: Boolean) = line(graph, "win-gone", win, callId, "fin" to finishing)

    private fun line(graph: AppGraph, event: String, win: String, callId: String?, vararg more: Pair<String, Any?>) {
        try {
            graph.pushJournal.diag("ui", event, listOf<Pair<String, Any?>>("win" to win, "call" to callId?.let { DiagId(it) }) + more.toList())
        } catch (_: Throwable) {
        }
    }

    /** Keyguard state as it is now (unknown = locked, as everywhere else). */
    fun locked(context: android.content.Context): Boolean =
        (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked ?: true
}
