package com.meda.portico.core.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * DEV-694 P1a: the library load (up to 30 s) is waited for OUTSIDE the controller's lock, so a
 * Setup refresh, sleep() or pause() is never stuck behind it, and a second caller does not start
 * a second join.
 */
class TailnetLockTest {
    private val KEY = "${"tskey"}-auth-kTEST-SECRET99"

    /** An engine whose ready() waits until the test releases the "library load". */
    private class SlowLoad : FakeEngine() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        init {
            started = false // like the real engine: nothing loads until the first ready() / beginLoad()
        }

        override fun ready(): String? {
            readyCalls++
            started = true
            loaded = false
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            loaded = true
            return readyError
        }
    }

    private val engine = SlowLoad()
    private val keys = FakeKeys()
    private val c = TailnetController(engine, keys, hostname = { "portico-pixel" }, target = { "192.168.1.5:443" })

    private fun <T> inThread(f: () -> T): Pair<Thread, AtomicReference<Result<T>>> {
        val out = AtomicReference<Result<T>>()
        val t = Thread { out.set(runCatching(f)) }.apply { isDaemon = true; start() }
        return t to out
    }

    /** Runs [f] on a thread and fails the test if it does not finish within 3 s (it is stuck behind the lock). */
    private fun <T> mustNotBlock(what: String, f: () -> T): T {
        val (t, out) = inThread(f)
        t.join(3_000)
        assertTrue(!t.isAlive, "$what was stuck behind the library load at:\n" + Thread.getAllStackTraces().filter { it.key.name.startsWith("Thread-") }.map { (th, st) -> th.name + ":\n" + st.take(7).joinToString("\n") }.joinToString("\n--\n"))
        return out.get().getOrThrow()
    }

    @Test
    fun `refresh, sleep and pause answer while ensureUp waits for the library`() {
        engine.identity = true
        val (up, upOut) = inThread { c.ensureUp() }
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS))
        assertEquals(TailnetState.Joining(), mustNotBlock("refresh") { c.refresh() })
        mustNotBlock("sleep") { c.sleep() }
        mustNotBlock("pause") { c.pause() }
        engine.release.countDown()
        up.join(5_000)
        assertTrue(!up.isAlive)
        assertEquals(listOf<String?>(null), engine.joins) // joined once, as the stored identity
        assertTrue(upOut.get().isSuccess)
    }

    @Test
    fun `a second ensureUp during the load does not start a second join`() {
        engine.identity = true
        val (first, _) = inThread { c.ensureUp() }
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS))
        assertEquals(TailnetState.Joining(), mustNotBlock("second ensureUp") { c.ensureUp() })
        engine.release.countDown()
        first.join(5_000)
        assertEquals(1, engine.joins.size)
    }

    @Test
    fun `paused while the library loads - no join, the key and identity stay`() {
        engine.identity = true
        var paused = false
        val c2 = TailnetController(engine, keys, hostname = { "p" }, target = { "192.168.1.5:443" }, paused = { paused })
        val (t, _) = inThread { c2.ensureUp() }
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS))
        paused = true
        engine.release.countDown()
        t.join(5_000)
        assertTrue(engine.joins.isEmpty())
        assertEquals(0, engine.wipes)
    }

    @Test
    fun `a failed load is a LIBRARY failure and the join flag is released`() {
        engine.identity = true
        engine.readyError = "boom"
        engine.release.countDown() // no wait
        val s = c.ensureUp()
        assertEquals(TailnetState.Failed(FailReason.LIBRARY, "boom"), s)
        // a later ensureUp is not wedged by a stale "join pending"
        engine.readyError = null
        assertEquals(TailnetState.Joining(), c.ensureUp())
        assertEquals(1, engine.joins.size)
    }

    @Test
    fun `the enrollment save waits for the library outside the lock, then wipes and joins with the key now stored`() {
        engine.identity = true
        keys.key = PendingKey(KEY, System.currentTimeMillis())
        val (t, _) = inThread { c.onEnrollmentSaved() }
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS))
        mustNotBlock("refresh during the enrollment's library wait") { c.refresh() }
        engine.release.countDown()
        t.join(5_000)
        assertEquals(listOf<String?>(KEY), engine.joins)
        assertEquals(1, engine.wipes)
        assertEquals(1, engine.stops)
    }

    @Test
    fun `a key spent while the enrollment waited is not redeemed twice`() {
        keys.key = PendingKey(KEY, System.currentTimeMillis())
        val (t, _) = inThread { c.onEnrollmentSaved() }
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS))
        keys.key = null // the node came up some other way and the key was cleared
        engine.release.countDown()
        t.join(5_000)
        assertTrue(engine.joins.isEmpty())
        assertEquals(0, engine.wipes)
    }

    @Test
    fun `refresh while a library is still loading reports joining and never calls ready`() {
        val e = FakeEngine().apply { loaded = false; started = true }
        val c2 = TailnetController(e, keys, hostname = { "p" }, target = { "192.168.1.5:443" })
        assertEquals(TailnetState.Joining(), c2.refresh())
        assertEquals(0, e.readyCalls)
    }
}

/** DEV-694 P1c: `abandonWarm` stops a node nobody needs, never one a newer connect uses or a key is joining. */
class TailnetAbandonTest {
    private val engine = FakeEngine().apply { identity = true }
    private val keys = FakeKeys()
    private val c = TailnetController(engine, keys, hostname = { "p" }, target = { "192.168.1.5:443" })

    @Test
    fun `an Up node is stopped for the connect that started it`() {
        engine.status = "up\t100.70.97.30"
        val lease = c.newLease()
        assertEquals(TailnetState.Idle(true), c.abandonWarm(lease))
        assertEquals(1, engine.stops)
    }

    @Test
    fun `a node still joining is stopped too - sleep would have left it running`() {
        engine.status = "joining"
        val lease = c.newLease()
        c.abandonWarm(lease)
        assertEquals(1, engine.stops)
    }

    @Test
    fun `a stale lease does nothing - a newer connect may be using the node`() {
        engine.status = "up\t100.70.97.30"
        val old = c.newLease()
        c.newLease()
        c.abandonWarm(old)
        assertEquals(0, engine.stops)
    }

    @Test
    fun `never while the enrollment's single-use key is in flight`() {
        engine.status = "joining"
        keys.key = PendingKey("${"tskey"}-auth-kTEST-SECRET99", System.currentTimeMillis())
        c.abandonWarm(c.newLease())
        assertEquals(0, engine.stops)
    }

    @Test
    fun `nothing to stop when the node is idle`() {
        c.abandonWarm(c.newLease())
        assertEquals(0, engine.stops)
    }

    @Test
    fun `ensureUp does not even load the library for an attempt that is already cancelled`() {
        val e = FakeEngine().apply { identity = true; started = false }
        val c2 = TailnetController(e, keys, hostname = { "p" }, target = { "192.168.1.5:443" })
        c2.ensureUp { true }
        assertEquals(0, e.readyCalls)
        assertTrue(e.joins.isEmpty())
    }

    @Test
    fun `ensureUp does not join for an attempt that was cancelled while the library loaded`() {
        var cancelled = false
        val e = object : FakeEngine() {
            override fun ready(): String? {
                cancelled = true // the direct path won while we waited
                return super.ready()
            }
        }.apply { identity = true; started = false }
        val c2 = TailnetController(e, keys, hostname = { "p" }, target = { "192.168.1.5:443" })
        c2.ensureUp { cancelled }
        assertTrue(e.joins.isEmpty())
    }

    @Test
    fun `overlapping connects - an abandon for the older one leaves the node alone`() {
        engine.status = "up\t100.70.97.30"
        val first = c.newLease()
        c.newLease()
        c.abandonWarm(first)
        assertEquals(0, engine.stops)
    }

    @Test
    fun `an Up node with a pending key is stopped - the refresh spends the key first`() {
        engine.status = "up\t100.70.97.30"
        keys.key = PendingKey("${"tskey"}-auth-kTEST-SECRET99", System.currentTimeMillis())
        c.abandonWarm(c.newLease())
        assertEquals(1, engine.stops)
        assertEquals(null, keys.key)
    }

    @Test
    fun `nothing is stopped while the library is still loading`() {
        engine.loaded = false
        engine.started = true
        c.abandonWarm(c.newLease())
        assertEquals(0, engine.stops)
    }
}

/** DEV-694 P1c: a wait whose joiner gave up (its connect was cancelled) must not run out its 45 s. */
class TailnetCancelledJoinerTest {
    @Test
    fun `a waiter behind a cancelled joiner starts the join itself`() {
        class Slow : FakeEngine() {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            init { started = false; identity = true }
            override fun ready(): String? {
                readyCalls++
                started = true
                loaded = false
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                loaded = true
                return null
            }
        }
        val e = Slow()
        e.onStatus = { if (e.joins.isNotEmpty() && e.statusCalls >= 2) e.status = "up\t100.70.97.30" }
        val c = TailnetController(e, FakeKeys(), hostname = { "p" }, target = { "192.168.1.5:443" })
        val cancelledA = java.util.concurrent.atomic.AtomicBoolean(false)
        val a = Thread { c.ensureUp { cancelledA.get() } }.apply { isDaemon = true; start() }
        assertTrue(e.entered.await(5, TimeUnit.SECONDS))
        val result = AtomicReference<Forwarder?>()
        val b = Thread { result.set(c.awaitForwarder(10_000)) }.apply { isDaemon = true; start() }
        Thread.sleep(300) // B is now polling behind A's pending join
        cancelledA.set(true) // A's connect was abandoned: it will return without joining
        e.release.countDown()
        a.join(5_000)
        b.join(8_000)
        assertTrue(!b.isAlive, "B ran out its wait")
        assertEquals(1, e.joins.size)
        assertTrue(result.get() != null)
    }
}
