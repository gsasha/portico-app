package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The layout journal (0.5.7-alpha): what one line says, that it is bounded, and that it never carries a name. */
class LayoutJournalTest {
    @BeforeEach
    @AfterEach
    fun clean() = LayoutJournal.clear()

    private fun tile(id: String, size: CameraSize) = CameraTile(id, "Secret label $id", "detail", PictureState.PICTURE, size)

    @Test
    fun `a rebuild line says the block count and per block the sizes, buttons, grid rows and child counts`() {
        val sizes = LayoutJournal.sizes(listOf(tile("g", CameraSize.SMALL), tile("e", CameraSize.BIG)))
        assertEquals("SB", sizes)
        val line = LayoutJournal.rebuildLine(listOf(LayoutJournal.BlockStat(sizes, 1, 2, 2, 1)))
        assertEquals("layout: rebuilt blocks=1 | b0 cams=2[SB] btns=1 gridRows=2 gridKids=2 btnRows=1", line)
    }

    @Test
    fun `a failed block is named in the line with its exception, and the others still have theirs`() {
        val line = LayoutJournal.rebuildLine(
            listOf(
                LayoutJournal.BlockStat("B", 0, 1, 0, 0, LayoutJournal.describe(IllegalStateException("boom"))),
                LayoutJournal.BlockStat("S", 2, 1, 2, 1),
            ),
        )
        assertTrue(line.contains("b0 cams=1[B] btns=0 gridRows=1 gridKids=0 btnRows=0 FAILED IllegalStateException: boom"), line)
        assertTrue(line.contains("b1 cams=1[S] btns=2 gridRows=1 gridKids=2 btnRows=1"), line)
        assertFalse(line.substringAfter("b1").contains("FAILED"))
    }

    @Test
    fun `an exception message is cut short, one line, and may be absent`() {
        assertEquals("NullPointerException", LayoutJournal.describe(NullPointerException()))
        val d = LayoutJournal.describe(RuntimeException("x".repeat(500) + "\nsecond line"))
        assertEquals("RuntimeException: " + "x".repeat(80), d)
        assertFalse(d.contains("\n"))
    }

    @Test
    fun `no label, id or detail reaches a line`() {
        val sizes = LayoutJournal.sizes(listOf(tile("gate_id", CameraSize.BIG)))
        val line = LayoutJournal.rebuildLine(listOf(LayoutJournal.BlockStat(sizes, 0, 1, 1, 0)))
        assertFalse(line.contains("Secret") || line.contains("gate_id") || line.contains("detail"), line)
    }

    @Test
    fun `the measured line lists the heights of the screen, the body and each block`() {
        assertEquals("layout: measured scroll=2100 body=900 blocks=500,380", LayoutJournal.measuredLine(2100, 900, listOf(500, 380)))
    }

    @Test
    fun `the journal keeps only the newest lines`() {
        assertTrue(LayoutJournal.isEmpty())
        for (i in 1..LayoutJournal.MAX_LINES + 5) LayoutJournal.add("line $i")
        val kept = LayoutJournal.text().lines()
        assertEquals(LayoutJournal.MAX_LINES, kept.size)
        assertEquals("line 6", kept.first())
        assertEquals("line ${LayoutJournal.MAX_LINES + 5}", kept.last())
    }

    private val failing = listOf(
        LayoutJournal.BlockStat("SB", 1, 2, 2, 1),
        LayoutJournal.BlockStat("B", 0, 1, 0, 0, LayoutJournal.describe(IllegalStateException("secret label at 10.0.0.5 token=abc"))),
    )

    private fun reporter(on: () -> Boolean, sent: MutableList<String>) =
        com.meda.portico.core.connlog.JournalReporter("0.6.25-alpha", on, { sent.add(it) }, { _, t -> t() })

    @Test
    fun `A5 no failure no line, and the line carries class names and counts only`() {
        assertEquals(null, LayoutJournal.failureLine(listOf(LayoutJournal.BlockStat("S", 1, 1, 1, 1))))
        val line = LayoutJournal.failureLine(failing)!!
        assertEquals("layout fail blocks=2 failed=1 b1 cams=1[B] btns=0 gridRows=1 exc=IllegalStateException", line)
        assertTrue(line.length <= com.meda.portico.core.connlog.JournalLines.MAX_LINE)
        assertTrue(com.meda.portico.core.connlog.JournalLines.check(line) is com.meda.portico.core.connlog.JournalLines.Verdict.Keep, line)
    }

    @Test
    fun `A5 the line reaches HA through the Journal reporter, and nothing when the diagnostics switch is off`() {
        LayoutJournal.resetReporting()
        val sent = mutableListOf<String>()
        var on = true
        val r = reporter({ on }, sent)
        LayoutJournal.observer = r::offer
        try {
            LayoutJournal.reportFailure(failing, 1_000)
            r.flushNow()
            assertEquals(1, sent.size)
            assertTrue(sent[0].contains("layout fail blocks=2"), sent[0])
            assertFalse(sent[0].contains("secret") || sent[0].contains("10.0.0.5"), sent[0])
            LayoutJournal.resetReporting()
            on = false
            LayoutJournal.reportFailure(failing, 500_000)
            r.flushNow()
            assertEquals(1, sent.size)
            assertEquals(0, r.waiting())
        } finally {
            LayoutJournal.observer = null
            LayoutJournal.resetReporting()
        }
    }

    @Test
    fun `A5 reporting is rate limited by gap and by count`() {
        LayoutJournal.resetReporting()
        val got = mutableListOf<String>()
        LayoutJournal.observer = { got.add(it) }
        try {
            LayoutJournal.reportFailure(failing, 1_000)
            LayoutJournal.reportFailure(failing, 1_000 + LayoutJournal.REPORT_GAP_MS - 1)
            assertEquals(1, got.size)
            LayoutJournal.reportFailure(failing, 1_000 + LayoutJournal.REPORT_GAP_MS)
            assertEquals(2, got.size)
            for (k in 3..30) LayoutJournal.reportFailure(failing, 1_000 + k * LayoutJournal.REPORT_GAP_MS)
            assertEquals(LayoutJournal.REPORT_MAX, got.size)
        } finally {
            LayoutJournal.observer = null
            LayoutJournal.resetReporting()
        }
    }
}
