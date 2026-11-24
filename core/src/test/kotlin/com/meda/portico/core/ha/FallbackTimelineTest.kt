package com.meda.portico.core.ha

import com.meda.portico.core.ha.ConnectTimeline.Stage
import com.meda.portico.core.ha.FallbackConnectorTest.FakeConnector
import com.meda.portico.core.ha.FallbackConnectorTest.ManualTimer
import com.meda.portico.core.ha.FallbackConnectorTest.QueueExecutor
import com.meda.portico.core.ha.FallbackConnectorTest.Recorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** DEV-694 P0: the connector marks its own links of the chain; it never changes what it does. */
class FallbackTimelineTest {
    private var now = 50_000L
    private val timeline = ConnectTimeline { now }
    private val direct = FakeConnector()
    private val tailnet = FakeConnector()
    private val timer = ManualTimer()
    private val worker = QueueExecutor()
    private val rec = Recorder()

    private fun fc(paused: Boolean = false) = FallbackConnector(
        direct, tailnet, { true }, timer, worker, directTimeoutMs = 5_000,
        wallClockMs = { now }, timeline = timeline, paused = { paused },
    )

    private val URL = "wss://haos.example.net/api/websocket"
    private fun stages() = timeline.snapshot()!!.marks.map { it.stage to it.offsetMs }

    @Test
    fun `direct opens - one mark, and the tailnet is never mentioned`() {
        fc().open(URL, rec)
        now += 80
        direct.last.listener.onOpen()
        assertEquals(listOf(Stage.DIRECT_OPENED to 80L), stages())
    }

    @Test
    fun `away - direct fails at once, the tailnet starts, then opens`() {
        fc().open(URL, rec)
        now += 30
        direct.last.listener.onClosed("failure: UnknownHostException")
        now += 10
        worker.runAll()
        now += 4_000
        tailnet.last.listener.onOpen()
        assertEquals(
            listOf(Stage.DIRECT_FAILED to 30L, Stage.TAILNET_STARTED to 40L, Stage.TAILNET_OPENED to 4_040L),
            stages(),
        )
    }

    @Test
    fun `the tailnet failing is marked too`() {
        fc().open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        worker.runAll()
        now += 700
        tailnet.last.listener.onClosed("failure: IOException")
        assertEquals(Stage.TAILNET_FAILED, stages().last().first)
    }

    @Test
    fun `each connect starts a fresh timeline`() {
        val c = fc()
        c.open(URL, rec)
        now += 10
        direct.last.listener.onOpen()
        now += 1_000
        c.open(URL, Recorder())
        assertEquals(emptyList<Pair<Stage, Long>>(), stages())
        assertEquals(51_010L, timeline.snapshot()!!.startedAtMs)
    }

    @Test
    fun `a paused phone connects nothing and starts no timeline`() {
        runCatching { fc(paused = true).open(URL, rec) }
        assertNull(timeline.snapshot())
    }
}
