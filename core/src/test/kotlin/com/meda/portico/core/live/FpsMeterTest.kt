package com.meda.portico.core.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FpsMeterTest {
    @Test
    fun `the formatter is -- or N fps`() {
        assertEquals("--", FpsMeter.format(null))
        assertEquals("24 fps", FpsMeter.format(24))
        assertEquals("0 fps", FpsMeter.format(0))
        assertEquals("--", FpsMeter().label)
    }

    @Test
    fun `-- while warming up, then the rate over the window`() {
        val m = FpsMeter()
        m.sample(0, 0)
        m.sample(1_000, 25)
        assertEquals("--", m.label, "one second watched: not enough")
        m.sample(2_000, 50)
        assertEquals("25 fps", m.label)
    }

    @Test
    fun `a bursty second does not swing the number`() {
        val m = FpsMeter()
        var total = 0L
        m.sample(0, 0)
        // 25 fps source, but the decoder hands over 10, 40, 20, 30 ... per reading
        val per = listOf(10, 40, 20, 30, 35, 15, 25, 25, 38, 12)
        val shown = ArrayList<String>()
        per.forEachIndexed { i, n ->
            total += n
            m.sample((i + 1) * 1_000L, total)
            if (i >= 1) shown.add(m.label)
        }
        // the old one-second meter swung 10..40 (30 fps wide); the window keeps within a few fps of 25
        val rates = shown.map { it.removeSuffix(" fps").toInt() }
        assertTrue(rates.max() - rates.min() <= 8, "got $rates")
    }

    @Test
    fun `hysteresis keeps the number until it has moved a full frame`() {
        val m = FpsMeter()
        m.sample(0, 0)
        m.sample(2_000, 50) // 25
        assertEquals("25 fps", m.label)
        m.sample(4_000, 50 + 51) // window 2s..4s earlier: 101-50=51 over 2s? window holds t=0.. so raw ~25.25
        assertEquals("25 fps", m.label)
        // a long steady 30 fps pulls it over
        var t = 4_000L
        var n = 101L
        repeat(6) { t += 1_000; n += 30; m.sample(t, n) }
        assertEquals("30 fps", m.label)
    }

    @Test
    fun `0 fps only after 2 seconds without a frame, then measured afresh`() {
        val m = FpsMeter()
        m.sample(0, 0)
        m.sample(1_000, 30)
        m.sample(2_000, 60)
        assertEquals("30 fps", m.label)
        m.sample(3_000, 60)
        assertEquals("20 fps", m.label, "one silent second: 60 frames over 3 s")
        m.sample(4_000, 60)
        assertEquals("0 fps", m.label)
        m.sample(5_000, 80)
        assertEquals("0 fps", m.label, "one second of the new run: still warming up")
        m.sample(6_000, 100)
        assertEquals("20 fps", m.label)
    }

    @Test
    fun `a clock that goes back or a counter that restarts begins a new window`() {
        val m = FpsMeter()
        m.sample(10_000, 0)
        m.sample(12_000, 60)
        assertEquals("30 fps", m.label)
        m.sample(5_000, 90) // the clock went back
        assertEquals("--", m.label)
        m.sample(7_000, 150)
        assertEquals("30 fps", m.label)
        m.sample(8_000, 3) // a new peer connection's counter
        assertEquals("--", m.label)
        m.sample(10_000, 63)
        assertEquals("30 fps", m.label)
        m.sample(10_000, 63) // the same instant twice: no division by zero
        assertEquals("30 fps", m.label)
    }

    @Test
    fun `the window forgets old readings`() {
        val m = FpsMeter()
        var t = 0L
        var n = 0L
        m.sample(t, n)
        repeat(4) { t += 1_000; n += 10; m.sample(t, n) }
        assertEquals("10 fps", m.label)
        repeat(8) { t += 1_000; n += 30; m.sample(t, n) }
        assertEquals("30 fps", m.label)
    }

    @Test
    fun `reset forgets everything`() {
        val m = FpsMeter()
        m.sample(0, 0)
        m.sample(2_000, 60)
        m.reset()
        assertEquals("--", m.label)
    }
}
