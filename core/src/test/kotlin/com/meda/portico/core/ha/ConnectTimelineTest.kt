package com.meda.portico.core.ha

import com.meda.portico.core.ha.ConnectTimeline.Stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** DEV-694 P0: the recorder of where a connect's time goes. */
class ConnectTimelineTest {
    private var now = 10_000L
    private val t = ConnectTimeline { now }

    @Test
    fun `nothing begun - nothing recorded, and a mark before begin is ignored`() {
        assertNull(t.snapshot())
        t.mark(Stage.NODE_UP)
        assertNull(t.snapshot())
        assertNull(ConnectTimeline.summary(t.snapshot()))
    }

    @Test
    fun `marks are offsets from begin, first occurrence only, sorted by time`() {
        t.begin()
        now += 120
        t.mark(Stage.DIRECT_FAILED)
        now += 1_800
        t.mark(Stage.LIBRARY_READY)
        now += 2_400
        t.mark(Stage.NODE_UP)
        now += 5
        t.mark(Stage.NODE_UP) // a repeat changes nothing
        val s = t.snapshot()!!
        assertEquals(10_000L, s.startedAtMs)
        assertEquals(
            listOf(
                ConnectTimeline.Mark(Stage.DIRECT_FAILED, 120),
                ConnectTimeline.Mark(Stage.LIBRARY_READY, 1_920),
                ConnectTimeline.Mark(Stage.NODE_UP, 4_320),
            ),
            s.marks,
        )
        assertEquals("direct failed +120 ms, library loaded +1.9 s, node up +4.3 s", ConnectTimeline.summary(s))
    }

    @Test
    fun `a new begin replaces the previous connect`() {
        t.begin()
        t.mark(Stage.DIRECT_OPENED)
        now += 50
        t.begin()
        assertEquals(emptyList<ConnectTimeline.Mark>(), t.snapshot()!!.marks)
        assertNull(ConnectTimeline.summary(t.snapshot())) // begun, but nothing happened yet
    }

    @Test
    fun `a clock that goes backwards gives offset zero, not a negative`() {
        t.begin()
        now -= 5_000
        t.mark(Stage.DIRECT_FAILED)
        assertEquals(0L, t.snapshot()!!.marks.single().offsetMs)
    }

    @Test
    fun `offsets read in plain locale-free words`() {
        assertEquals("0 ms", ConnectTimeline.offset(0))
        assertEquals("999 ms", ConnectTimeline.offset(999))
        assertEquals("1.0 s", ConnectTimeline.offset(1_000))
        assertEquals("1.9 s", ConnectTimeline.offset(1_999))
        assertEquals("12.3 s", ConnectTimeline.offset(12_345))
    }

    @Test
    fun `the summary has no address, key or token - only stage words and offsets`() {
        t.begin()
        for (s in Stage.values()) t.mark(s)
        val text = ConnectTimeline.summary(t.snapshot())!!
        assertEquals(true, Regex("""[a-z ]+ \+\d+ ms(, [a-z ]+ \+\d+ ms)*""").matches(text), text)
    }
}
