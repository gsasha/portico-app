package com.meda.portico.call

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.meda.portico.AppGraph
import com.meda.portico.core.push.PushJournalFormat.DiagId
import com.meda.portico.core.ring.InCallLock
import com.meda.portico.core.ring.InCallLock.Source
import com.meda.portico.core.ring.InCallLock.UnlockResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * the owner 2026-11-21: the Android half of [InCallLock]'s unlock request. One `KeyguardManager.requestDismissKeyguard` for the in-call screen,
 * from the Unlock button or (once per call) by itself right after the screen is up on a locked answer.
 *
 * The request never touches the call: no coordinator call, no service, no audio. It reports to the caller, which switches screen only on
 * [UnlockResult.OK]. Journal (`ui`, fixed words): `unlock-req` (src, locked, set) and `unlock-res` (src, r=ok|cancel|error|timeout, ms).
 * A request the system never answers is reported as `timeout` after [InCallLock.UNLOCK_TIMEOUT_MS]; the request is left alone, and an answer that
 * comes later is still honoured (and journalled `late=yes`).
 */
object InCallUnlock {
    private val main = Handler(Looper.getMainLooper())

    fun isLocked(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: true // unknown = locked

    fun request(a: Activity, graph: AppGraph, callId: String?, source: Source, onResult: (UnlockResult) -> Unit) {
        val km = a.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val setting = try { graph.callAudio.unlockWhenAnswering() } catch (_: Exception) { InCallLock.DEFAULT_UNLOCK_WHEN_ANSWERING }
        val t0 = SystemClock.elapsedRealtime()
        val timedOut = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        journal(graph, "unlock-req", "call" to callId?.let { DiagId(it) }, "src" to source.word, "locked" to (km?.isKeyguardLocked ?: true), "set" to setting)
        val finish = { r: UnlockResult ->
            if (done.compareAndSet(false, true)) {
                journal(graph, "unlock-res", "call" to callId?.let { DiagId(it) }, "src" to source.word, "r" to r.word, "ms" to (SystemClock.elapsedRealtime() - t0), "late" to (if (timedOut.get()) true else null), "set" to setting)
                onResult(r)
            }
        }
        if (km == null) return finish(UnlockResult.ERROR)
        val timeout = Runnable {
            if (!done.get() && timedOut.compareAndSet(false, true)) {
                journal(graph, "unlock-res", "call" to callId?.let { DiagId(it) }, "src" to source.word, "r" to UnlockResult.TIMEOUT.word, "ms" to (SystemClock.elapsedRealtime() - t0), "set" to setting)
                onResult(UnlockResult.TIMEOUT)
            }
        }
        main.postDelayed(timeout, InCallLock.UNLOCK_TIMEOUT_MS)
        try {
            km.requestDismissKeyguard(
                a,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { main.removeCallbacks(timeout); finish(UnlockResult.OK) }
                    override fun onDismissCancelled() { main.removeCallbacks(timeout); finish(UnlockResult.CANCEL) }
                    override fun onDismissError() { main.removeCallbacks(timeout); finish(UnlockResult.ERROR) }
                },
            )
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            finish(UnlockResult.ERROR)
        }
    }

    fun journal(graph: AppGraph, event: String, vararg fields: Pair<String, Any?>) {
        try {
            graph.pushJournal.diag("ui", event, *fields)
        } catch (_: Throwable) {
        }
    }
}
