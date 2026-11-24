package com.meda.portico.core.traffic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrafficTableTest {
    @Test
    fun `one row per category in order, two value columns`() {
        val rows = TrafficTable.rows(
            mobile = MobileUse(os = 12_345_678, video = 10_000_000, audio = 1_000_000),
            wifi = MobileUse(os = 1_500, video = 0, audio = 0),
        )
        assertEquals(listOf(TrafficCategory.VIDEO, TrafficCategory.CALLS, TrafficCategory.OTHER, TrafficCategory.TOTAL), rows.map { it.category })
        assertEquals(TrafficTableRow(TrafficCategory.VIDEO, "10.0 MB", "-"), rows[0])
        assertEquals(TrafficTableRow(TrafficCategory.CALLS, "1.0 MB", "-"), rows[1])
        assertEquals(TrafficTableRow(TrafficCategory.OTHER, "1.3 MB", "1.5 KB"), rows[2])
        assertEquals(TrafficTableRow(TrafficCategory.TOTAL, "12.3 MB", "1.5 KB"), rows[3])
    }

    @Test
    fun `a column with no data shows a dash in every cell`() {
        val rows = TrafficTable.rows(mobile = MobileUse(), wifi = MobileUse(os = 2_000, video = 500))
        assertEquals(listOf("-", "-", "-", "-"), rows.map { it.mobile })
        assertEquals(listOf("500 B", "-", "1.5 KB", "2.0 KB"), rows.map { it.wifi })
        assertEquals(listOf("-", "-", "-", "-"), TrafficTable.rows(MobileUse(), MobileUse()).map { it.wifi })
    }

    @Test
    fun `total never shows less than the classified parts`() {
        val rows = TrafficTable.rows(MobileUse(os = 100, video = 100, audio = 50), MobileUse())
        assertEquals("150 B", rows.single { it.category == TrafficCategory.TOTAL }.mobile)
        assertEquals("-", rows.single { it.category == TrafficCategory.OTHER }.mobile)
    }

    @Test
    fun `blocks read both media for today, the month and since the reset`() {
        val m = TrafficMeter(object : TrafficStore {
            var t = ""
            override fun read() = t
            override fun write(text: String) { t = text }
        }, { "2026-11-05" })
        m.add(10, MobileUse(os = 4_000, video = 1_000), Medium.MOBILE)
        m.add(10, MobileUse(os = 9_000), Medium.WIFI)
        val b = TrafficTable.blocks(m, 20)
        assertEquals(listOf(TrafficPeriod.TODAY, TrafficPeriod.MONTH, TrafficPeriod.SINCE_RESET), b.map { it.period })
        for (block in b) {
            assertEquals("4.0 KB", block.rows.last().mobile)
            assertEquals("9.0 KB", block.rows.last().wifi)
        }
        m.reset(30)
        assertEquals("-", TrafficTable.blocks(m, 40).first().rows.last().wifi)
    }

    @Test
    fun `no decimals from 100 up, one below, one unit per cell`() {
        assertEquals("99.9 KB", TrafficFormat.bytes(99_900))
        assertEquals("100 KB", TrafficFormat.bytes(99_960))
        assertEquals("219 MB", TrafficFormat.bytes(219_040_000))
        assertEquals("74.4 MB", TrafficFormat.bytes(74_400_000))
        assertEquals("1.2 GB", TrafficFormat.bytes(1_234_500_000)) // a value never reads "1234.5 MB": it moves to GB
        assertEquals("999 KB", TrafficFormat.bytes(999_400))
        assertEquals("1.0 MB", TrafficFormat.bytes(999_960))
        assertEquals("630 MB", TrafficFormat.bytes(630_000_000))
        assertEquals("12.3 GB", TrafficFormat.bytes(12_345_678_901))
        assertEquals("123 GB", TrafficFormat.bytes(123_456_789_012))
    }
}
