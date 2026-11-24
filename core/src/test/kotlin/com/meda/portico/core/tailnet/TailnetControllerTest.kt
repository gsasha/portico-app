package com.meda.portico.core.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A scriptable stand-in for gocore/tailnet: [status] is whatever the test sets. */
open class FakeEngine(override val built: Boolean = true) : TailnetEngine {
    override var started = built
    var readyCalls = 0
    var statusCalls = 0
    var readyError: String? = null
    var status = "idle"
    var identity = false
    val joins = mutableListOf<String?>() // the key of each join ("" never; null = rejoin)
    var stops = 0
    var wipes = 0
    var forwarderTargets = mutableListOf<String>()
    var forwarderReply = "40123:" + "ab".repeat(32)
    var refuseJoin = false

    /** DEV-694: a scripted blocking wait. When [handlesWait], [awaitStatusChange] counts as the caller's sleep. */
    var handlesWait = false
    var waits = 0
    var phases = ""
    override fun awaitStatusChange(timeoutMs: Long): Boolean {
        waits++
        return handlesWait
    }
    override fun joinPhases() = phases

    /** False while a (scripted) library load is still running: [TailnetController] must not wait for it under its lock. */
    override var loaded = true

    /** Runs on each status() call, to script a join's progress. */
    var onStatus: (FakeEngine) -> Unit = {}

    override fun ready(): String? {
        readyCalls++
        started = true
        return readyError
    }
    override fun join(authKey: String?, hostname: String) {
        if (refuseJoin) throw IllegalStateException("tailnet: a join is already running")
        joins.add(authKey)
        status = "joining"
    }
    override fun status(): String {
        statusCalls++
        onStatus(this)
        return status
    }
    override fun startForwarder(target: String): String {
        if (!status.startsWith("up")) throw IllegalStateException("not up")
        forwarderTargets.add(target)
        return forwarderReply
    }
    override fun stop() {
        stops++
        status = "idle"
    }
    override fun hasIdentity() = identity
    override fun wipeIdentity() {
        wipes++
        identity = false
    }
}

class FakeKeys(var key: PendingKey? = null) : PendingKeyStore {
    var clears = 0
    override fun load() = key
    override fun clear() {
        clears++
        key = null
    }
}

class TailnetControllerTest {

    private val KEY = "tskey-auth-kTEST-SECRET99"
    private var now = 1_000_000_000L
    private val engine = FakeEngine()
    private val keys = FakeKeys()
    private var target: String? = "192.168.1.5:443"
    private val sleeps = mutableListOf<Long>()

    private fun controller(e: TailnetEngine = engine) = TailnetController(
        e, keys, hostname = { "portico-pixel" }, target = { target }, wallClockMs = { now },
        sleeper = { sleeps.add(it); now += it },
    )

    @Test
    fun `not built is its own state and nothing is attempted`() {
        val c = controller(TailnetEngine.NOT_BUILT)
        assertEquals(TailnetState.NotBuilt, c.state)
        keys.key = PendingKey(KEY, now)
        assertEquals(TailnetState.NotBuilt, c.onEnrollmentSaved())
        assertEquals(TailnetState.NotBuilt, c.ensureUp())
        assertNull(c.awaitForwarder(1_000))
        assertEquals(0, keys.clears) // left alone: an APK built with the library may still use it
        assertNotNull(keys.key)
    }

    @Test
    fun `a saved key wipes the old identity and joins with it at once`() {
        engine.identity = true
        keys.key = PendingKey(KEY, now)
        val s = controller().onEnrollmentSaved()
        assertEquals(TailnetState.Joining(), s)
        assertEquals(listOf<String?>(KEY), engine.joins)
        assertEquals(1, engine.wipes)
        assertEquals(1, engine.stops)
    }

    @Test
    fun `an enrollment without a key changes nothing`() {
        engine.identity = true
        val s = controller().onEnrollmentSaved()
        assertEquals(TailnetState.Idle(hasIdentity = true), s)
        assertTrue(engine.joins.isEmpty())
        assertEquals(0, engine.wipes)
    }

    @Test
    fun `the key is deleted the moment the node is up`() {
        keys.key = PendingKey(KEY, now)
        val c = controller()
        c.onEnrollmentSaved()
        assertNotNull(keys.key, "kept while joining: a failed attempt may be retried")
        engine.status = "up\t100.101.102.103"
        assertEquals(TailnetState.Up("100.101.102.103"), c.refresh())
        assertNull(keys.key)
    }

    @Test
    fun `a refused key is deleted and the failure shown`() {
        keys.key = PendingKey(KEY, now)
        val c = controller()
        c.onEnrollmentSaved()
        engine.status = "failed\tkey_rejected\tthe tailnet refused this key: invalid key"
        val s = c.refresh()
        assertEquals(TailnetState.Failed(FailReason.KEY_REJECTED, "the tailnet refused this key: invalid key"), s)
        assertNull(keys.key)
        // nothing left to try
        assertEquals(s, c.ensureUp())
        assertEquals(1, engine.joins.size)
    }

    @Test
    fun `needs_login after a join WITH a key also deletes the key`() {
        keys.key = PendingKey(KEY, now)
        val c = controller()
        c.onEnrollmentSaved()
        engine.status = "failed\tneeds_login\tx"
        c.refresh()
        assertNull(keys.key)
    }

    @Test
    fun `a timeout keeps the key and the next connect retries it`() {
        keys.key = PendingKey(KEY, now)
        val c = controller()
        c.onEnrollmentSaved()
        engine.status = "failed\ttimeout\tthe tailnet did not answer in time"
        assertEquals(TailnetState.Failed(FailReason.TIMEOUT, "the tailnet did not answer in time"), c.refresh())
        assertNotNull(keys.key)
        now += 10 * 60_000
        assertEquals(TailnetState.Joining(), c.ensureUp())
        assertEquals(listOf<String?>(KEY, KEY), engine.joins)
    }

    @Test
    fun `an unredeemed key expires after 55 minutes`() {
        keys.key = PendingKey(KEY, now)
        val c = controller()
        c.onEnrollmentSaved()
        engine.status = "failed\ttimeout\tx"
        now += TailnetController.MAX_KEY_AGE_MS - 1
        c.refresh()
        assertNotNull(keys.key)
        now += 2
        assertEquals(TailnetState.KeyExpired, c.refresh())
        assertNull(keys.key)
        assertEquals(TailnetState.KeyExpired, c.ensureUp())
        assertEquals(1, engine.joins.size)
    }

    @Test
    fun `a key saved long ago (process was dead) expires on the first look`() {
        keys.key = PendingKey(KEY, now - TailnetController.MAX_KEY_AGE_MS - 1)
        val c = controller()
        assertEquals(TailnetState.KeyExpired, c.onEnrollmentSaved())
        assertTrue(engine.joins.isEmpty())
        assertNull(keys.key)
    }

    @Test
    fun `a clock that jumped far back cannot keep a key alive`() {
        keys.key = PendingKey(KEY, now + TailnetController.CLOCK_SKEW_MS + 1)
        assertEquals(TailnetState.KeyExpired, controller().refresh())
        assertNull(keys.key)
    }

    @Test
    fun `a new enrollment after expiry clears KeyExpired`() {
        keys.key = PendingKey(KEY, 0)
        val c = controller()
        assertEquals(TailnetState.KeyExpired, c.refresh())
        keys.key = PendingKey(KEY, now)
        assertEquals(TailnetState.Joining(), c.onEnrollmentSaved())
    }

    @Test
    fun `with a stored identity and no key, ensureUp rejoins without a key`() {
        engine.identity = true
        val c = controller()
        assertEquals(TailnetState.Joining(), c.ensureUp())
        assertEquals(listOf<String?>(null), engine.joins)
    }

    @Test
    fun `no identity and no key - nothing to do`() {
        assertEquals(TailnetState.Idle(hasIdentity = false), controller().ensureUp())
        assertTrue(engine.joins.isEmpty())
    }

    @Test
    fun `a dead identity is not retried on every connect`() {
        engine.identity = true
        val c = controller()
        c.ensureUp()
        engine.status = "failed\tneeds_login\tthe tailnet refused this key or no longer knows this phone"
        assertEquals(FailReason.NEEDS_LOGIN, (c.refresh() as TailnetState.Failed).reason)
        c.ensureUp()
        c.ensureUp()
        assertEquals(1, engine.joins.size)
    }

    @Test
    fun `waiting for approval is shown while joining`() {
        keys.key = PendingKey(KEY, now)
        val c = controller()
        c.onEnrollmentSaved()
        engine.status = "joining\tneeds_approval"
        assertEquals(TailnetState.Joining(needsApproval = true), c.refresh())
    }

    @Test
    fun `a library that did not load is a failure, and no join is tried`() {
        engine.readyError = "UnsatisfiedLinkError"
        keys.key = PendingKey(KEY, now)
        assertEquals(TailnetState.Failed(FailReason.LIBRARY, "UnsatisfiedLinkError"), controller().onEnrollmentSaved())
        assertTrue(engine.joins.isEmpty())
        assertNotNull(keys.key)
    }

    @Test
    fun `awaitForwarder waits for the join and opens the forwarder to the target`() {
        engine.identity = true
        var polls = 0
        engine.onStatus = { if (it.status == "joining" && ++polls >= 3) it.status = "up\t100.64.0.7" }
        val f = controller().awaitForwarder(30_000)
        assertNotNull(f)
        assertEquals(40123, f!!.port)
        assertEquals(listOf("192.168.1.5:443"), engine.forwarderTargets)
        assertTrue(sleeps.isNotEmpty() && sleeps.all { it == TailnetController.POLL_MS })
    }

    @Test
    fun `awaitForwarder stops polling once cancelled and leaves the join running`() {
        engine.identity = true
        var polls = 0
        val c = controller()
        // the probe is asked twice by ensureUp (before and after the library wait) and then once per poll
        assertNull(c.awaitForwarder(45_000) { ++polls > 5 })
        assertEquals(3, sleeps.size)
        assertEquals(TailnetState.Joining(), c.state)
    }

    @Test
    fun `awaitForwarder gives up at its deadline`() {
        engine.identity = true
        val c = controller()
        assertNull(c.awaitForwarder(2_000))
        assertTrue(sleeps.sum() in 2_000..2_000 + TailnetController.POLL_MS)
        assertEquals(TailnetState.Joining(), c.state)
    }

    @Test
    fun `awaitForwarder returns null at once when there is nothing to join with`() {
        val c = controller()
        assertNull(c.awaitForwarder(30_000))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `awaitForwarder without a target is NO_TARGET`() {
        target = null
        engine.identity = true
        val c = controller()
        assertNull(c.awaitForwarder(30_000))
        assertEquals(TailnetState.Failed(FailReason.NO_TARGET), c.state)
        assertTrue(engine.joins.isEmpty())
    }

    @Test
    fun `an unparseable forwarder reply is null, not a crash`() {
        engine.status = "up\t100.64.0.7"
        engine.forwarderReply = "nonsense"
        assertNull(controller().awaitForwarder(1_000))
    }

    @Test
    fun `sleep stops a node that is up, and leaves a join alone`() {
        val c = controller()
        engine.status = "joining"
        c.sleep()
        assertEquals(0, engine.stops)
        engine.status = "up\t100.64.0.7"
        engine.identity = true
        assertEquals(TailnetState.Idle(hasIdentity = true), c.sleep())
        assertEquals(1, engine.stops)
    }

    @Test
    fun `a join refused by the library (already joining) is harmless`() {
        engine.identity = true
        engine.refuseJoin = true
        assertEquals(TailnetState.Idle(hasIdentity = true), controller().ensureUp())
    }

    @Test
    fun `refresh never wakes a library that is not loaded yet`() {
        engine.started = false
        engine.identity = true
        keys.key = PendingKey(KEY, now - TailnetController.MAX_KEY_AGE_MS - 1)
        val c = controller()
        assertEquals(TailnetState.KeyExpired, c.refresh()) // the expiry rule still runs, from files alone
        assertEquals(0, engine.readyCalls)
        assertEquals(0, engine.statusCalls)
        assertEquals(TailnetState.Joining(), c.ensureUp()) // a real need loads it
        assertTrue(engine.readyCalls >= 1)
    }

    @Test
    fun `a pending key shows as pending while idle`() {
        keys.key = PendingKey(KEY, now)
        assertEquals(TailnetState.Idle(hasIdentity = false, keyPending = true), controller().refresh())
    }

    @Test
    fun `an unreadable status is an internal failure`() {
        engine.status = "zzz"
        assertEquals(FailReason.INTERNAL, (controller().refresh() as TailnetState.Failed).reason)
    }

    @Test
    fun `no state, log line or toString carries the key`() {
        val lines = mutableListOf<String>()
        keys.key = PendingKey(KEY, now)
        val c = TailnetController(engine, keys, { "h" }, { target }, { now }, {}, { _, m -> lines.add(m) })
        c.onEnrollmentSaved()
        engine.status = "failed\tkey_rejected\tx"
        c.refresh()
        val all = lines + listOf(c.state.toString(), PendingKey(KEY, 1).toString(), Forwarder.parse("1:" + "cd".repeat(32)).toString())
        assertFalse(all.any { "kTEST" in it || "cdcd" in it }, all.toString())
    }
}
