package com.meda.portico.core.connlog

import com.meda.portico.core.ha.HaError
import com.meda.portico.core.ha.HaInbound
import com.meda.portico.core.ha.NetInfo
import com.meda.portico.core.ha.NetSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnLogRepliesTest {
    private fun ok(json: String) = HaInbound.Result(1, true, Json.parseToJsonElement(json), null)

    @Test
    fun `an ok result carries the counts`() {
        assertEquals(ConnLogReply.Ok(2, 1, 3), ConnLogReplies.of(ok("""{"stored":2,"duplicates":1,"rejected":3}""")))
    }

    @Test
    fun `a missing or odd result is an ok with zeros, never a crash`() {
        assertEquals(ConnLogReply.Ok(0, 0, 0), ConnLogReplies.of(HaInbound.Result(1, true, null, null)))
        assertEquals(ConnLogReply.Ok(0, 0, 0), ConnLogReplies.of(HaInbound.Result(1, true, JsonPrimitive("x"), null)))
        assertEquals(ConnLogReply.Ok(0, 0, 0), ConnLogReplies.of(ok("""{"stored":"many"}""")))
    }

    @Test
    fun `the journal counts are read when HA sends them and are zero otherwise`() {
        assertEquals(ConnLogReply.Ok(1, 0, 0, journalLines = 3, journalDropped = 2), ConnLogReplies.of(ok("""{"stored":1,"duplicates":0,"rejected":0,"journal":{"lines":3,"dropped":2}}""")))
        assertEquals(ConnLogReply.Ok(1, 0, 0), ConnLogReplies.of(ok("""{"stored":1,"duplicates":0,"rejected":0}""")))
        assertEquals(ConnLogReply.Ok(1, 0, 0), ConnLogReplies.of(ok("""{"stored":1,"journal":"x"}""")))
        assertEquals(ConnLogReply.Ok(1, 0, 0, journalLines = 0, journalDropped = 0), ConnLogReplies.of(ok("""{"stored":1,"journal":{"lines":"many"}}""")))
    }

    @Test
    fun `an error keeps HA's code`() {
        assertEquals(ConnLogReply.Error("unknown_command"), ConnLogReplies.of(HaInbound.Result(1, false, null, HaError("unknown_command", "Unknown command."))))
        assertEquals(ConnLogReply.Error("error"), ConnLogReplies.of(HaInbound.Result(1, false, null, null)))
    }

    @Test
    fun `no frame means the socket closed first`() {
        assertEquals(ConnLogReply.Lost, ConnLogReplies.of(null))
    }

    // ---- the network mapper ----

    private val cell = NetInfo(wifiOrEthernet = false, cellular = true, vpn = false, validated = true)
    private val wifi = NetInfo(wifiOrEthernet = true, cellular = false, vpn = false, validated = true)
    private val vpnOverCell = NetInfo(wifiOrEthernet = false, cellular = true, vpn = true, validated = true)

    @Test
    fun `the default network decides the class, the other networks decide the flags`() {
        assertEquals(NetState(NetClass.WIFI, wifi = true, cell = true, vpn = false, validated = true, nid = 4), ConnNetMapper.of(NetSnapshot(listOf(wifi, cell), wifi), 4))
        assertEquals(NetState(NetClass.CELLULAR, wifi = true, cell = true, vpn = false, validated = true, nid = 5), ConnNetMapper.of(NetSnapshot(listOf(wifi.copy(validated = false), cell), cell), 5))
        assertEquals(NetClass.VPN, ConnNetMapper.of(NetSnapshot(listOf(vpnOverCell, cell), vpnOverCell), 1).def)
        assertEquals(true, ConnNetMapper.of(NetSnapshot(listOf(vpnOverCell, cell), vpnOverCell), 1).vpn)
    }

    @Test
    fun `no default network is none, and an unreadable snapshot is other - never none`() {
        assertEquals(NetClass.NONE, ConnNetMapper.of(NetSnapshot(emptyList(), null), 1).def)
        assertEquals(NetClass.OTHER, ConnNetMapper.of(null, 1).def)
        assertEquals(NetClass.OTHER, ConnNetMapper.of(NetSnapshot(listOf(NetInfo(false, false, false, true)), NetInfo(false, false, false, true)), 1).def)
    }
}
