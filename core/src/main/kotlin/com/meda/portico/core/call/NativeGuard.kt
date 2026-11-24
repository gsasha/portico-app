package com.meda.portico.core.call

import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEV-764 (app fixes): the rule that keeps a callback from touching a native object (libwebrtc's `PeerConnection`) that is being or has
 * been disposed. Touching a disposed native object is not an exception, it is a SIGSEGV that kills the app (seen 2026-11-17 23:09:
 * `PeerConnection.getLocalDescription` from the offer's gather timer after the call had closed).
 *
 * Every native touch goes through [run]: it does nothing and returns false once [close] has been called, otherwise it counts itself as in
 * flight while it runs. The disposer calls [close] (no new touch can start from then on) and [awaitIdle] (the touches already running
 * finish), and only then disposes. The lock is NOT held while a touch runs or while disposing: a libwebrtc callback runs on its signaling
 * thread and a dispose waits for that thread, so holding a lock across either would deadlock. [awaitIdle] is bounded for the same reason.
 */
class NativeGuard {
    private val lock = Object()
    private var closed = false
    private var active = 0

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Runs [block] unless closed. True if it ran (even if it threw: the exception goes to the caller). */
    fun run(block: () -> Unit): Boolean {
        synchronized(lock) {
            if (closed) return false
            active++
        }
        try {
            block()
        } finally {
            synchronized(lock) {
                active--
                lock.notifyAll()
            }
        }
        return true
    }

    /** From now on [run] refuses. Idempotent. */
    fun close() {
        synchronized(lock) { closed = true }
    }

    /** Waits until no [run] is in flight, at most [timeoutMs]. True if idle. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (active > 0) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                lock.wait(left)
            }
            return true
        }
    }
}

/**
 * One offer's delivery: the SDP is read from the native object ONCE, by whichever comes first (gathering complete, the gather timer, a
 * failure), and never after the call has closed. [before] is written to the journal BEFORE the native read (a crash leaves it as the last
 * word); [read] is the native read (`p.localDescription?.description`). When the guard is closed the read is skipped and [onSdp] gets null.
 */
class OfferDelivery(
    private val guard: NativeGuard,
    private val read: () -> String?,
    private val onSdp: (String?) -> Unit,
    private val before: (via: String, closed: Boolean) -> Unit = { _, _ -> },
) {
    private val once = AtomicBoolean(false)

    val delivered: Boolean get() = once.get()

    fun deliver(via: String) {
        if (!once.compareAndSet(false, true)) return
        before(via, guard.isClosed)
        var sdp: String? = null
        try {
            guard.run { sdp = read() }
        } catch (_: Exception) {
            sdp = null
        }
        onSdp(sdp)
    }

    /** Ends the offer with no SDP (create or set failed). */
    fun fail(): Boolean {
        if (!once.compareAndSet(false, true)) return false
        onSdp(null)
        return true
    }
}
