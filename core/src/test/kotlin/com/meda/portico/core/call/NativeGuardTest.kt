package com.meda.portico.core.call

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-764 (app fixes): nothing touches the native peer connection after the call has closed. */
class NativeGuardTest {
    @Test
    fun `a delivery after close never reads the native object`() {
        val guard = NativeGuard()
        var reads = 0
        val out = mutableListOf<String?>()
        val d = OfferDelivery(guard, { reads++; "v=0" }, { out += it })
        guard.close() // the call closed inside the gather window
        d.deliver("timeout")
        assertEquals(0, reads)
        assertEquals(listOf<String?>(null), out)
    }

    @Test
    fun `a delivery before close reads once, and a second trigger does nothing`() {
        val guard = NativeGuard()
        var reads = 0
        val out = mutableListOf<String?>()
        val d = OfferDelivery(guard, { reads++; "v=0" }, { out += it })
        d.deliver("gathered")
        d.deliver("timeout")
        assertEquals(1, reads)
        assertEquals(listOf<String?>("v=0"), out)
        assertFalse(d.fail())
    }

    @Test
    fun `a throwing read gives null and the breadcrumb was written first`() {
        val guard = NativeGuard()
        val order = mutableListOf<String>()
        val out = mutableListOf<String?>()
        val d = OfferDelivery(guard, { order += "read"; throw IllegalStateException() }, { out += it }, { via, _ -> order += "before:$via" })
        d.deliver("timeout")
        assertEquals(listOf("before:timeout", "read"), order)
        assertNull(out.single())
    }

    @Test
    fun `awaitIdle waits for a touch already running, and run refuses once closed`() {
        val guard = NativeGuard()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val t = Thread { guard.run { inside.countDown(); release.await(5, TimeUnit.SECONDS) } }
        t.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        guard.close()
        assertFalse(guard.run { error("must not run") })
        assertFalse(guard.awaitIdle(50), "a touch is still in flight: the dispose must wait")
        release.countDown()
        assertTrue(guard.awaitIdle(5_000))
        t.join()
    }

    @Test
    fun `fail ends the offer once`() {
        val out = mutableListOf<String?>()
        val d = OfferDelivery(NativeGuard(), { "x" }, { out += it })
        assertTrue(d.fail())
        d.deliver("timeout")
        assertEquals(listOf<String?>(null), out)
    }
}
