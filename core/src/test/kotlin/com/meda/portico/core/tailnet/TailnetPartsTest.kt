package com.meda.portico.core.tailnet

import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.ring.Severity
import com.meda.portico.core.ring.SetupStatus
import com.meda.portico.core.ring.StatusKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TailnetPartsTest {

    @Test
    fun `GoStatus parses every line gocore prints`() {
        assertEquals(GoStatus.Idle, GoStatus.parse("idle"))
        assertEquals(GoStatus.Joining(false), GoStatus.parse("joining"))
        assertEquals(GoStatus.Joining(true), GoStatus.parse("joining\tneeds_approval"))
        assertEquals(GoStatus.Up("100.64.0.1"), GoStatus.parse("up\t100.64.0.1"))
        assertEquals(GoStatus.Failed(FailReason.KEY_REJECTED, "the tailnet refused this key: invalid authkey"), GoStatus.parse("failed\tkey_rejected\tthe tailnet refused this key: invalid authkey"))
        assertEquals(FailReason.NEEDS_LOGIN, (GoStatus.parse("failed\tneeds_login\tx") as GoStatus.Failed).reason)
        assertEquals(FailReason.NEEDS_APPROVAL, (GoStatus.parse("failed\tneeds_approval\tx") as GoStatus.Failed).reason)
        assertEquals(FailReason.TIMEOUT, (GoStatus.parse("failed\ttimeout\tx") as GoStatus.Failed).reason)
        assertEquals(FailReason.LIBRARY, (GoStatus.parse("failed\tnot_prepared\tx") as GoStatus.Failed).reason)
        assertEquals(FailReason.INTERNAL, (GoStatus.parse("failed\tpanic\tgo panic: x") as GoStatus.Failed).reason)
        assertEquals(FailReason.INTERNAL, (GoStatus.parse("failed\terror\tx") as GoStatus.Failed).reason)
        assertEquals(FailReason.INTERNAL, (GoStatus.parse("failed\tsomething_new\tx") as GoStatus.Failed).reason)
    }

    @Test
    fun `GoStatus never throws on junk`() {
        for (junk in listOf(null, "", "\t", "up", "failed", "UP\t1", "\u0000")) {
            GoStatus.parse(junk) // must not throw
        }
        assertEquals(FailReason.INTERNAL, (GoStatus.parse("") as GoStatus.Failed).reason)
    }

    @Test
    fun `Forwarder parses port and token`() {
        val f = Forwarder.parse("40123:" + "0f".repeat(32))!!
        assertEquals(40123, f.port)
        assertArrayEquals(ByteArray(32) { 0x0f }, f.token())
        f.token()[0] = 1
        assertEquals(0x0f, f.token()[0].toInt(), "token() hands out a copy")
        assertEquals("127.0.0.1:40123", f.endpoint(), "IPv4 loopback, as Go binds it: never ::1")
        assertEquals("127.0.0.1", Forwarder.HOST)
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), Forwarder.hostBytes())
        for (bad in listOf(null, "", "40123", "0:" + "00".repeat(32), "70000:" + "00".repeat(32), "1:" + "00".repeat(31), "1:" + "zz".repeat(32), "a:" + "00".repeat(32))) {
            assertNull(Forwarder.parse(bad), bad)
        }
    }

    @Test
    fun `hostname is a DNS label from the phone name`() {
        assertEquals("portico-bob-s-pixel-8", TailnetHostname.of("Bob's Pixel 8"))
        assertEquals("portico-phone", TailnetHostname.of(null))
        assertEquals("portico-phone", TailnetHostname.of("  ***  "))
        assertEquals("portico-a", TailnetHostname.of("--A--"))
        val long = TailnetHostname.of("x".repeat(200))
        assertEquals(63, long.length)
        assertEquals("portico-", long.take(8))
    }

    private fun keysOf(state: TailnetState, path: LinkPath? = null) = SetupStatus.tailnetLines(state, path).map { it.key to it.severity }

    @Test
    fun `Setup lines for each tailnet state`() {
        assertEquals(listOf(StatusKey.TAILNET_NOT_BUILT to Severity.INFO), keysOf(TailnetState.NotBuilt))
        assertEquals(listOf(StatusKey.TAILNET_NOT_SET_UP to Severity.INFO), keysOf(TailnetState.Idle(false)))
        assertEquals(listOf(StatusKey.TAILNET_READY to Severity.OK), keysOf(TailnetState.Idle(true)))
        assertEquals(listOf(StatusKey.TAILNET_KEY_PENDING to Severity.INFO), keysOf(TailnetState.Idle(false, keyPending = true)))
        assertEquals(listOf(StatusKey.TAILNET_JOINING to Severity.INFO), keysOf(TailnetState.Joining()))
        assertEquals(listOf(StatusKey.TAILNET_NEEDS_APPROVAL to Severity.PROBLEM), keysOf(TailnetState.Joining(true)))
        assertEquals(listOf(StatusKey.TAILNET_UP to Severity.OK), keysOf(TailnetState.Up("100.64.0.1")))
        assertEquals("100.64.0.1", SetupStatus.tailnetLines(TailnetState.Up("100.64.0.1"), null).single().arg)
        assertEquals(listOf(StatusKey.TAILNET_KEY_EXPIRED to Severity.PROBLEM), keysOf(TailnetState.KeyExpired))
        val failures = mapOf(
            FailReason.KEY_REJECTED to (StatusKey.TAILNET_KEY_REJECTED to Severity.PROBLEM),
            FailReason.NEEDS_LOGIN to (StatusKey.TAILNET_NEEDS_LOGIN to Severity.PROBLEM),
            FailReason.NEEDS_APPROVAL to (StatusKey.TAILNET_NEEDS_APPROVAL to Severity.PROBLEM),
            FailReason.TIMEOUT to (StatusKey.TAILNET_TIMEOUT to Severity.INFO),
            FailReason.LIBRARY to (StatusKey.TAILNET_LIBRARY_FAILED to Severity.PROBLEM),
            FailReason.INTERNAL to (StatusKey.TAILNET_ERROR to Severity.PROBLEM),
            FailReason.NO_TARGET to (StatusKey.TAILNET_NO_TARGET to Severity.PROBLEM),
        )
        assertEquals(FailReason.entries.toSet(), failures.keys)
        for ((r, want) in failures) assertEquals(listOf(want), keysOf(TailnetState.Failed(r, "d")), r.name)
    }

    @Test
    fun `the path line comes first`() {
        assertEquals(
            listOf(StatusKey.LINK_PATH_TAILNET to Severity.OK, StatusKey.TAILNET_UP to Severity.OK),
            keysOf(TailnetState.Up("100.64.0.1"), LinkPath.TAILNET),
        )
        assertEquals(StatusKey.LINK_PATH_DIRECT, SetupStatus.tailnetLines(TailnetState.Idle(true), LinkPath.DIRECT).first().key)
    }
}
