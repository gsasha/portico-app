package com.meda.portico.core.live

/**
 * One resource shared by SEVERAL holders at once (the Home screen and the ring pre-warm hold the same `LiveHome`, so the sessions the
 * pre-warm started are the ones the screen then shows). Unlike [LiveLease] (one owner, a hand-over between two screens) every holder
 * is counted: the resource is made on the first [acquire], and torn down [graceMs] after the LAST holder let go, unless someone
 * acquired in between. Releasing a holder that is not holding changes nothing. Single-threaded (the main thread).
 */
class RefHolder<T : Any>(
    private val make: () -> T,
    private val destroy: (T) -> Unit,
    private val clock: LiveClock,
    private val graceMs: Long,
    private val onFault: (at: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private var resource: T? = null
    private val holders = LinkedHashSet<Any>()
    private var cancelTeardown: (() -> Unit)? = null

    fun acquire(who: Any): T {
        cancelTeardown?.invoke()
        cancelTeardown = null
        holders.add(who)
        return resource ?: make().also { resource = it }
    }

    fun release(who: Any) {
        if (!holders.remove(who) || holders.isNotEmpty()) return
        cancelTeardown?.invoke()
        cancelTeardown = clock.after(graceMs) { teardown() }
    }

    fun holds(who: Any): Boolean = who in holders

    fun holderCount(): Int = holders.size

    /** The resource if it exists now; never makes one. */
    fun current(): T? = resource

    private fun teardown() {
        cancelTeardown = null
        if (holders.isNotEmpty()) return
        val r = resource ?: return
        resource = null
        try {
            destroy(r)
        } catch (e: Throwable) {
            onFault("teardown", e)
        }
    }
}
