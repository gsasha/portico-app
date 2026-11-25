package com.meda.portico.core.fcm

import com.meda.portico.core.enroll.FirebaseConfig
import com.meda.portico.core.enroll.FirebaseConfigResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FirebaseBootCoreTest {

    companion object {
        const val API_KEY = "${"AIza"}SyFAKEot-test-api-key-0123456789abc"
        val CONFIG = cfg("portico-test", "123456789012", "1:123456789012:android:0a1b2c3d4e5f", API_KEY)
        val OTHER = cfg("portico-other", "999999999999", "1:999999999999:android:ffeeddccbbaa", API_KEY + "x")

        fun cfg(p: String, s: String, a: String, k: String): FirebaseConfig =
            (FirebaseConfig.create(p, s, a, k) as FirebaseConfigResult.Ok).config
    }

    /** Plays FirebaseApp: one default app per process, initializeApp throws on a second call. */
    class FakeFirebase(var failWith: Throwable? = null) : FirebaseInitializer {
        var app: FirebaseConfig? = null
        var foreignApp = false
        val inits = AtomicInteger()

        override fun hasDefaultApp(): Boolean = app != null || foreignApp
        override fun running(): FirebaseConfig? = app
        override fun initialize(config: FirebaseConfig) {
            inits.incrementAndGet()
            failWith?.let { throw it }
            check(app == null) { "FirebaseApp name [DEFAULT] already exists!" }
            app = config
        }
    }

    @Test
    fun `no stored config - NotConfigured, nothing initialised`() {
        val f = FakeFirebase()
        assertEquals(BootResult.NotConfigured, FirebaseBootCore(f).ensure(null))
        assertEquals(0, f.inits.get())
    }

    @Test
    fun `stored config - Ready with its project, initialised once however often ensure runs`() {
        val f = FakeFirebase()
        val boot = FirebaseBootCore(f)
        repeat(5) { assertEquals(BootResult.Ready("portico-test"), boot.ensure(CONFIG)) }
        assertEquals(1, f.inits.get())
        assertEquals(CONFIG, f.app)
    }

    @Test
    fun `initializeApp throws - Failed with the exception class only, never a value`() {
        val f = FakeFirebase(failWith = IllegalArgumentException("Given String is empty or null: $API_KEY"))
        val r = FirebaseBootCore(f).ensure(CONFIG)
        assertTrue(r is BootResult.Failed, "$r")
        val reason = (r as BootResult.Failed).reason
        assertTrue("IllegalArgumentException" in reason, reason)
        assertFalse(API_KEY in reason, "the api key leaked into the reason")
        assertFalse(API_KEY in r.toString())
    }

    @Test
    fun `a Failed boot is retried on the next ensure`() {
        val f = FakeFirebase(failWith = IllegalStateException("x"))
        val boot = FirebaseBootCore(f)
        assertTrue(boot.ensure(CONFIG) is BootResult.Failed)
        f.failWith = null
        assertEquals(BootResult.Ready("portico-test"), boot.ensure(CONFIG))
    }

    @Test
    fun `options changed in a running process - keep the running app, Ready with restartToApply`() {
        val f = FakeFirebase()
        val boot = FirebaseBootCore(f)
        boot.ensure(CONFIG)
        val r = boot.ensure(OTHER)
        assertEquals(BootResult.Ready("portico-test", restartToApply = true), r)
        assertEquals(1, f.inits.get(), "a second initializeApp for [DEFAULT] would throw: never attempted")
    }

    @Test
    fun `config cleared while running (unreadable settings) - keep running, no restart flag`() {
        val f = FakeFirebase()
        val boot = FirebaseBootCore(f)
        boot.ensure(CONFIG)
        assertEquals(BootResult.Ready("portico-test"), boot.ensure(null))
    }

    @Test
    fun `a default app this code did not make - Failed, not a crash`() {
        val f = FakeFirebase().apply { foreignApp = true }
        val r = FirebaseBootCore(f).ensure(CONFIG)
        assertTrue(r is BootResult.Failed, "$r")
        assertEquals(0, f.inits.get())
    }

    @Test
    fun `an initializer that throws on hasDefaultApp still never throws out of ensure`() {
        val f = object : FirebaseInitializer {
            override fun hasDefaultApp(): Boolean = throw NoClassDefFoundError("com/google/firebase/FirebaseApp")
            override fun running(): FirebaseConfig? = null
            override fun initialize(config: FirebaseConfig) = Unit
        }
        assertTrue(FirebaseBootCore(f).ensure(CONFIG) is BootResult.Failed)
    }

    @Test
    fun `concurrent ensure from the Application, the service and Setup initialises once`() {
        val f = object : FirebaseInitializer {
            @Volatile var app: FirebaseConfig? = null
            val inits = AtomicInteger()
            override fun hasDefaultApp() = app != null
            override fun running() = app
            override fun initialize(config: FirebaseConfig) {
                inits.incrementAndGet()
                Thread.sleep(20) // widen the race window
                check(app == null) { "already exists" }
                app = config
            }
        }
        val boot = FirebaseBootCore(f)
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = (1..8).map { pool.submit<BootResult> { go.await(); boot.ensure(CONFIG) } }
        go.countDown()
        val all = results.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(all.all { it == BootResult.Ready("portico-test") }, "$all")
        assertEquals(1, f.inits.get())
    }

    @Test
    fun `status text names the project and the restart, never the api key`() {
        assertEquals("Push ring notification (Firebase): not configured", BootResult.NotConfigured.statusLine())
        assertEquals("Push ring notification (Firebase): project portico-test", BootResult.Ready("portico-test").statusLine())
        assertTrue("restart Portico" in BootResult.Ready("portico-test", true).statusLine())
        assertTrue("failed" in BootResult.Failed("initializeApp threw X").statusLine())
    }
}
