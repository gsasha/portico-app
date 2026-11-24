package com.meda.portico.core.ha

import com.meda.portico.core.ha.FallbackConnectorTest.FakeConnector
import com.meda.portico.core.ha.FallbackConnectorTest.ManualTimer
import com.meda.portico.core.ha.FallbackConnectorTest.QueueExecutor
import com.meda.portico.core.ha.FallbackConnectorTest.Recorder
import com.meda.portico.core.tailnet.ForwarderDial
import com.meda.portico.core.tailnet.TailnetUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What FallbackConnector reports about each path's attempt, for Setup. */
class FallbackAttemptsTest {
    private val direct = FakeConnector()
    private val timer = ManualTimer()
    private val worker = QueueExecutor()
    private val rec = Recorder()
    private val attempts = mutableListOf<PathAttempt>()
    private var now = 1_000L
    private var forwarder: ForwarderDial? = null
    private val URL = "wss://haos.example.net/api/websocket"

    private fun fc(tailnet: Connector) = FallbackConnector(
        direct, tailnet, { true }, timer, worker, directTimeoutMs = 5_000,
        onAttempt = { attempts.add(it) },
        refineTailnet = { f, start -> LinkFailures.tailnet(f, forwarder, start) },
        wallClockMs = { now },
    )

    @Test
    fun `direct opens - one OK attempt`() {
        fc(FakeConnector()).open(URL, rec)
        direct.last.listener.onOpen()
        assertEquals(listOf(PathAttempt(LinkPath.DIRECT, 1_000, null)), attempts)
    }

    @Test
    fun `direct fails with a reason, the tailnet then opens`() {
        val tailnet = FakeConnector()
        fc(tailnet).open(URL, rec)
        direct.last.listener.onFailure(LinkFailure(FailureClass.NAME_NOT_RESOLVED))
        direct.last.listener.onClosed("failure: UnknownHostException")
        now = 2_000
        worker.runAll()
        now = 3_000
        tailnet.last.listener.onOpen()
        assertEquals(
            listOf(
                PathAttempt(LinkPath.DIRECT, 1_000, LinkFailure(FailureClass.NAME_NOT_RESOLVED)),
                PathAttempt(LinkPath.TAILNET, 3_000, null),
            ),
            attempts,
        )
    }

    @Test
    fun `direct closes without a reason - closed early`() {
        fc(FakeConnector()).open(URL, rec)
        direct.last.listener.onClosed("closed (1000)")
        assertEquals(FailureClass.CLOSED_EARLY, attempts.single().failure!!.cls)
    }

    @Test
    fun `direct deadline - timed out, and the late failure of the abandoned socket is ignored`() {
        fc(FakeConnector()).open(URL, rec)
        timer.fire()
        direct.last.listener.onFailure(LinkFailure(FailureClass.CLOSED_EARLY))
        direct.last.listener.onClosed("failure: EOFException")
        assertEquals(listOf(PathAttempt(LinkPath.DIRECT, 1_000, LinkFailures.DIRECT_DEADLINE)), attempts)
    }

    @Test
    fun `tailnet fails after the forwarder's dial timed out - the forwarder's reason wins`() {
        val tailnet = FakeConnector()
        fc(tailnet).open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        now = 2_000
        worker.runAll()
        forwarder = ForwarderDial("timeout", 21_000)
        now = 22_000
        tailnet.last.listener.onFailure(LinkFailure(FailureClass.TLS))
        tailnet.last.listener.onClosed("failure: SSLHandshakeException")
        assertEquals(PathAttempt(LinkPath.TAILNET, 22_000, LinkFailure(FailureClass.DIAL_TIMEOUT)), attempts.last())
        assertEquals(2, attempts.size)
    }

    @Test
    fun `tailnet connector throws because the node is not up`() {
        fc(FakeConnector(throwOnOpen = TailnetUnavailableException("Joining"))).open(URL, rec)
        direct.last.listener.onClosed("failure: UnknownHostException")
        forwarder = ForwarderDial("timeout", 5_000) // irrelevant: the node never came up
        worker.runAll()
        assertEquals(PathAttempt(LinkPath.TAILNET, 1_000, LinkFailure(FailureClass.TAILNET_NOT_UP)), attempts.last())
    }

    @Test
    fun `a throwing refine or listener never breaks the connection`() {
        val tailnet = FakeConnector()
        FallbackConnector(
            direct, tailnet, { true }, timer, worker, directTimeoutMs = 5_000,
            onAttempt = { throw IllegalStateException("ui gone") },
            refineTailnet = { _, _ -> throw IllegalStateException("go gone") },
        ).open(URL, rec)
        direct.last.listener.onClosed("x")
        worker.runAll()
        tailnet.last.listener.onClosed("y")
        assertEquals(listOf("closed:direct: x; tailnet: y"), rec.events)
    }
}
