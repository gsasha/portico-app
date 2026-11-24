package com.meda.portico.core.ha

import com.meda.portico.core.ha.FallbackConnectorTest.FakeConnector
import com.meda.portico.core.ha.FallbackConnectorTest.ManualTimer
import com.meda.portico.core.ha.FallbackConnectorTest.QueueExecutor
import com.meda.portico.core.ha.FallbackConnectorTest.Recorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** While the phone is paused the socket's path choice opens nothing, on either path. */
class FallbackPauseTest {
    private val direct = FakeConnector()
    private val tailnet = FakeConnector()
    private val timer = ManualTimer()
    private val worker = QueueExecutor()
    private val rec = Recorder()
    private var paused = false

    private fun fc(pausedFlag: () -> Boolean = { paused }) =
        FallbackConnector(direct, tailnet, { true }, timer, worker, directTimeoutMs = 5_000, paused = pausedFlag)

    private val url = "wss://haos.example.net/api/websocket"

    @Test
    fun `open refuses while paused - no direct socket, no tailnet`() {
        paused = true
        assertThrows(IllegalStateException::class.java) { fc().open(url, rec) }
        assertTrue(direct.socks.isEmpty())
        assertTrue(tailnet.socks.isEmpty())
        assertTrue(worker.queue.isEmpty())
    }

    @Test
    fun `paused between the direct attempt and its failure - the tailnet is not woken`() {
        fc().open(url, rec)
        paused = true
        direct.last.listener.onClosed("failure: UnknownHostException")
        worker.runAll()
        assertTrue(tailnet.socks.isEmpty(), "the tailnet was opened while paused")
        assertEquals(listOf("closed:direct: failure: UnknownHostException"), rec.events)
    }

    @Test
    fun `FAIL OPEN - an unreadable pause flag lets the socket open`() {
        fc { error("unreadable") }.open(url, rec)
        assertEquals(1, direct.socks.size)
    }
}
