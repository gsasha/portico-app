package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallRecordCodecTest {
    private val a = CallRecord("door-1", "gso-1-2-abc", CallState.RINGING, 1, 1_700_000_000_000, Source.FCM, 1_700_000_000_500)
    private val b = CallRecord("door-2", "c:2", CallState.MISSED, 1, 5, Source.WS, 6)

    @Test
    fun `round trip keeps order and every field`() {
        val all = CallState.entries.mapIndexed { i, s -> a.copy(callId = "c$i", state = s, seq = s.seq) } + b
        assertEquals(all, CallRecordCodec.decode(CallRecordCodec.encode(all)))
    }

    @Test
    fun `garbage decodes to empty, bad entries are skipped, never a throw`() {
        assertEquals(emptyList<CallRecord>(), CallRecordCodec.decode(null))
        assertEquals(emptyList<CallRecord>(), CallRecordCodec.decode(""))
        assertEquals(emptyList<CallRecord>(), CallRecordCodec.decode("{not json"))
        assertEquals(emptyList<CallRecord>(), CallRecordCodec.decode("{}"))
        val good = CallRecordCodec.encode(listOf(b)).removePrefix("[").removeSuffix("]")
        val mixed = """[{"door_ref":"x"},{"door_ref":"x","call_id":"y","state":"BOGUS","seq":1,"t":1,"source":"fcm","at":1},$good,7]"""
        assertEquals(listOf(b), CallRecordCodec.decode(mixed))
    }
}
