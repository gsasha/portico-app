package com.meda.portico.core.traffic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrafficMeterTest {
    private class Mem(var text: String = "", var fail: Boolean = false) : TrafficStore {
        override fun read() = if (fail) throw IllegalStateException() else text
        override fun write(text: String) {
            if (fail) throw IllegalStateException()
            this.text = text
        }
    }

    // one fake day per 1000 ms: "2026-11-01" + n, month changes after day 30
    private fun dayOf(ms: Long): String {
        val d = (ms / 1000).toInt()
        val month = 11 + d / 30
        val day = 1 + d % 30
        return "2026-%02d-%02d".format(month, day)
    }

    private fun meter(m: Mem = Mem(), max: Int = 62) = TrafficMeter(m, ::dayOf, max)

    @Test
    fun `accumulates into today, month and since reset, with other as the remainder`() {
        val t = meter()
        t.add(0, MobileUse(os = 1000, video = 600, audio = 100))
        t.add(500, MobileUse(os = 500, video = 100, audio = 0))
        assertEquals(MobileUse(1500, 700, 100), t.today(900))
        assertEquals(700, t.today(900).other)
        assertEquals(1500, t.month(900).os)
        assertEquals(1500, t.sinceReset(900).os)
    }

    @Test
    fun `day and month rollover`() {
        val t = meter()
        t.add(0, MobileUse(os = 100)) // Nov 1
        t.add(1_000, MobileUse(os = 200)) // Nov 2
        t.add(30_000, MobileUse(os = 400)) // Dec 1
        assertEquals(200, t.today(1_500).os)
        assertEquals(300, t.month(1_500).os)
        assertEquals(400, t.today(30_500).os)
        assertEquals(400, t.month(30_500).os)
        assertEquals(700, t.sinceReset(30_500).os)
        assertEquals(0, t.today(2_000).os)
    }

    @Test
    fun `storage is bounded and the since reset total survives the cut`() {
        val m = Mem()
        val t = meter(m, max = 5)
        for (i in 0 until 40) t.add(i * 1000L, MobileUse(os = 10))
        assertEquals(5, t.rows())
        assertEquals(400, t.sinceReset(40_000).os)
        assertTrue(m.text.lines().size <= 5 + 3)
    }

    @Test
    fun `survives a restart and a damaged line`() {
        val m = Mem()
        meter(m).apply { add(0, MobileUse(100, 50, 10)); add(1000, MobileUse(200, 0, 0)) }
        m.text += "garbage\n2026-11-xx,a,b,c\n"
        val again = meter(m)
        assertEquals(300, again.sinceReset(1500).os)
        assertEquals(200, again.today(1500).os)
    }

    @Test
    fun `reset clears everything and restarts the clock`() {
        val m = Mem()
        val t = meter(m)
        t.add(0, MobileUse(100, 50, 10))
        t.reset(5000)
        assertEquals(MobileUse(), t.sinceReset(5000))
        assertEquals(MobileUse(), t.month(5000))
        assertEquals(5000, t.sinceMs(5000))
        t.add(5100, MobileUse(os = 7))
        assertEquals(7, meter(m).sinceReset(5200).os) // and the reset is what a restart reads back
    }

    @Test
    fun `a failing store never throws and the meter still counts in memory`() {
        val t = meter(Mem(fail = true))
        t.add(0, MobileUse(os = 5))
        assertEquals(5, t.sinceReset(0).os)
    }

    @Test
    fun `total never shows less than the classified parts`() {
        assertEquals(150, MobileUse(os = 100, video = 100, audio = 50).total)
        assertEquals(0, MobileUse(os = 100, video = 100, audio = 50).other)
    }

    // ---- the sampler ---------------------------------------------------------------------

    @Test
    fun `sampler charges only metered intervals, baselines first, and reads a falling counter as a reboot`() {
        val t = meter()
        val s = MobileSampler(t)
        s.sample(0, Medium.MOBILE, os = 1000, video = 0, audio = 0) // baseline: nothing
        assertEquals(0, t.sinceReset(0).os)
        s.sample(100, Medium.MOBILE, os = 1500, video = 200, audio = 50)
        assertEquals(MobileUse(500, 200, 50), t.sinceReset(100))
        s.sample(200, null, os = 9000, video = 5000, audio = 0) // no medium: not charged
        assertEquals(500, t.sinceReset(200).os)
        s.sample(300, Medium.MOBILE, os = 9100, video = 5000, audio = 0) // the uncharged bytes were not carried over
        assertEquals(600, t.sinceReset(300).os)
        s.sample(400, Medium.MOBILE, os = 40, video = 5000, audio = 0) // reboot: counter fell
        assertEquals(600, t.sinceReset(400).os)
        s.sample(500, Medium.MOBILE, os = 140, video = 5000, audio = 0)
        assertEquals(700, t.sinceReset(500).os)
    }

    @Test
    fun `sampler with no OS counter charges only the classified bytes`() {
        val t = meter()
        val s = MobileSampler(t)
        s.sample(0, Medium.MOBILE, null, 0, 0)
        s.sample(10, Medium.MOBILE, null, 300, 0)
        assertEquals(MobileUse(0, 300, 0), t.sinceReset(10))
        assertEquals(300, t.sinceReset(10).total)
    }

    @Test
    fun `byte formatting`() {
        assertEquals("999 B", TrafficFormat.bytes(999))
        assertEquals("1.5 KB", TrafficFormat.bytes(1_500))
        assertEquals("12.3 MB", TrafficFormat.bytes(12_345_678))
        assertEquals("1.4 GB", TrafficFormat.bytes(1_400_000_000))
        assertEquals("0 B", TrafficFormat.bytes(-5))
    }

    // ---- 0.6.70: Wi-Fi beside mobile ---------------------------------------------------------

    @Test
    fun `sampler charges Wi-Fi intervals to the Wi-Fi column and mobile ones to mobile`() {
        val t = meter()
        val s = MobileSampler(t)
        s.sample(0, Medium.WIFI, os = 1000, video = 0, audio = 0)
        s.sample(100, Medium.WIFI, os = 5000, video = 3000, audio = 0)
        s.sample(200, Medium.MOBILE, os = 5200, video = 3000, audio = 100)
        assertEquals(MobileUse(4000, 3000, 0), t.sinceReset(200, Medium.WIFI))
        assertEquals(MobileUse(200, 0, 100), t.sinceReset(200, Medium.MOBILE))
        assertEquals(MobileUse(200, 0, 100), t.sinceReset(200)) // no medium means mobile
    }

    @Test
    fun `an old ledger reads back as mobile and Wi-Fi starts empty`() {
        val old = Mem("since,1000\ntotal,300,50,10\n2026-11-01,100,20,0\n2026-11-02,200,30,10\n")
        val t = meter(old)
        assertEquals(MobileUse(300, 50, 10), t.sinceReset(1500))
        assertEquals(MobileUse(100, 20, 0), t.today(0))
        assertEquals(MobileUse(), t.sinceReset(1500, Medium.WIFI))
        assertEquals(0, t.rows(Medium.WIFI))
    }

    @Test
    fun `Wi-Fi survives a restart, resets with mobile and keeps the mobile lines in the old format`() {
        val m = Mem()
        val t = meter(m)
        t.add(0, MobileUse(100, 10, 0))
        t.add(0, MobileUse(900, 800, 50), Medium.WIFI)
        t.add(1000, MobileUse(70, 0, 0), Medium.WIFI)
        val again = meter(m)
        assertEquals(MobileUse(970, 800, 50), again.sinceReset(1500, Medium.WIFI))
        assertEquals(MobileUse(900, 800, 50), again.today(0, Medium.WIFI))
        assertEquals(MobileUse(100, 10, 0), again.sinceReset(1500))
        // an older build parses every line it knows and skips the Wi-Fi ones
        val knownToOld = m.text.lines().filter { it.isNotBlank() && !it.startsWith("w") }
        assertTrue(knownToOld.any { it.startsWith("total,") } && knownToOld.any { it.startsWith("2026-11-01,") })
        again.reset(2000)
        assertEquals(MobileUse(), again.sinceReset(2000, Medium.WIFI))
        assertEquals(MobileUse(), again.sinceReset(2000))
        assertEquals(0, meter(m).rows(Medium.WIFI))
    }

    @Test
    fun `the day bound applies to each medium on its own`() {
        val t = meter(max = 3)
        for (i in 0 until 10) {
            t.add(i * 1000L, MobileUse(os = 1))
            t.add(i * 1000L, MobileUse(os = 2), Medium.WIFI)
        }
        assertEquals(3, t.rows())
        assertEquals(3, t.rows(Medium.WIFI))
        assertEquals(20, t.sinceReset(0, Medium.WIFI).os)
    }
}
