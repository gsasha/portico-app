package com.meda.portico.core.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** The stats reader: which numbers it takes from a libwebrtc report, and that anything it cannot read stays null (printed `?`), never 0. */
class AudioFactsTest {
    private fun stat(type: String, id: String, vararg m: Pair<String, Any?>) = RawStat(type, id, mapOf(*m))

    private val codecOpus = stat("codec", "C1", "mimeType" to "audio/opus", "clockRate" to 48000L, "channels" to 2)
    private val codecIn = stat("codec", "C2", "mimeType" to "audio/opus", "clockRate" to BigInteger.valueOf(48000))

    /** A healthy 10 s call: 20 ms packets both ways, normal speech level, 2 lost downlink, none uplink. */
    private fun healthy() = listOf(
        codecOpus,
        codecIn,
        stat("outbound-rtp", "O1", "kind" to "audio", "codecId" to "C1", "packetsSent" to BigInteger.valueOf(500)),
        stat("remote-inbound-rtp", "R1", "kind" to "audio", "packetsLost" to 0),
        stat("inbound-rtp", "I1", "kind" to "audio", "codecId" to "C2", "packetsReceived" to 490L, "packetsLost" to 2, "totalSamplesDuration" to 9.8),
        stat("media-source", "S1", "kind" to "audio", "totalSamplesDuration" to 10.0, "totalAudioEnergy" to 0.04, "audioLevel" to 0.1),
    )

    @Test
    fun `a healthy report gives codec, rate, packet size, counts and the mic class per direction`() {
        val s = AudioStatsParser.parse(healthy())
        assertEquals(AudioDirection("opus", 48000, 20, 500, 0), s.up)
        assertEquals(AudioDirection("opus", 48000, 20, 490, 2), s.down)
        assertEquals(MicLevel.NORMAL, s.mic) // sqrt(0.04 / 10) = 0.063
    }

    @Test
    fun `a 40 ms uplink is visible as 40 ms (the bug the journal exists to show)`() {
        val s = AudioStatsParser.parse(
            healthy().map { if (it.id == "S1") stat("media-source", "S1", "kind" to "audio", "totalSamplesDuration" to 20.0, "totalAudioEnergy" to 0.1) else it },
        )
        assertEquals(40, s.up.ptimeMs) // 20 s of audio in 500 packets
    }

    @Test
    fun `an empty report is all unknown, not zero`() {
        val s = AudioStatsParser.parse(emptyList())
        assertEquals(AudioStats(), s)
        assertNull(s.up.packets)
        assertNull(s.down.lost)
        assertNull(s.mic)
    }

    @Test
    fun `a missing codec entry or member leaves just that field unknown`() {
        val s = AudioStatsParser.parse(listOf(stat("inbound-rtp", "I1", "kind" to "audio", "codecId" to "nope", "packetsReceived" to 5L)))
        assertEquals(AudioDirection(null, null, null, 5, null), s.down)
    }

    @Test
    fun `a video entry is ignored and a kind-less entry counts as audio`() {
        val s = AudioStatsParser.parse(
            listOf(
                stat("outbound-rtp", "V", "kind" to "video", "packetsSent" to 999L),
                stat("outbound-rtp", "A", "packetsSent" to 7L),
            ),
        )
        assertEquals(7L, s.up.packets)
    }

    @Test
    fun `a codec word that is not a short lowercase token never gets in`() {
        for (bad in listOf("audio/opus; sdp=v=0", "audio/", "audio/A=B", "audio/averyveryverylongcodecname", "")) {
            val s = AudioStatsParser.parse(listOf(stat("codec", "C", "mimeType" to bad), stat("inbound-rtp", "I", "codecId" to "C")))
            assertNull(s.down.codec, bad)
        }
        val upper = AudioStatsParser.parse(listOf(stat("codec", "C", "mimeType" to "audio/PCMU"), stat("inbound-rtp", "I", "codecId" to "C")))
        assertEquals("pcmu", upper.down.codec)
    }

    @Test
    fun `NaN, infinity and negative numbers are unknown`() {
        val s = AudioStatsParser.parse(
            listOf(
                stat("outbound-rtp", "O", "packetsSent" to Double.NaN),
                stat("inbound-rtp", "I", "packetsLost" to -3, "packetsReceived" to Double.POSITIVE_INFINITY),
                stat("media-source", "S", "totalSamplesDuration" to Double.NaN, "totalAudioEnergy" to 1.0),
            ),
        )
        assertNull(s.up.packets)
        assertNull(s.down.packets)
        assertEquals(0L, s.down.lost, "a negative lost (RFC 3550 duplicates) is shown as 0, it is a real count")
        assertNull(s.mic)
    }

    @Test
    fun `packet size needs enough packets to mean something`() {
        assertNull(AudioStatsParser.ptimeMs(0.18, 9))
        assertEquals(20, AudioStatsParser.ptimeMs(0.2, 10))
        assertNull(AudioStatsParser.ptimeMs(null, 100))
        assertNull(AudioStatsParser.ptimeMs(2.0, null))
        assertNull(AudioStatsParser.ptimeMs(0.0, 100))
    }

    @Test
    fun `mic classes at their boundaries`() {
        assertNull(MicLevel.of(null))
        assertNull(MicLevel.of(Double.NaN))
        assertEquals(MicLevel.SILENT, MicLevel.of(0.0))
        assertEquals(MicLevel.SILENT, MicLevel.of(MicLevel.SILENT_BELOW - 1e-9))
        assertEquals(MicLevel.LOW, MicLevel.of(MicLevel.SILENT_BELOW))
        assertEquals(MicLevel.LOW, MicLevel.of(MicLevel.LOW_BELOW - 1e-9))
        assertEquals(MicLevel.NORMAL, MicLevel.of(MicLevel.LOW_BELOW))
        assertEquals(MicLevel.NORMAL, MicLevel.of(MicLevel.CLIPPED_FROM - 1e-9))
        assertEquals(MicLevel.CLIPPED, MicLevel.of(MicLevel.CLIPPED_FROM))
        assertEquals(MicLevel.CLIPPED, MicLevel.of(1.0))
    }

    @Test
    fun `the mic class is read from energy over duration, not from the instantaneous level`() {
        fun cls(energy: Double, secs: Double) = AudioStatsParser.parse(
            listOf(stat("media-source", "S", "kind" to "audio", "totalAudioEnergy" to energy, "totalSamplesDuration" to secs, "audioLevel" to 1.0)),
        ).mic
        assertEquals(MicLevel.SILENT, cls(0.0, 2.0))
        assertEquals(MicLevel.LOW, cls(0.00002 * 2.0, 2.0)) // rms 0.0045
        assertEquals(MicLevel.CLIPPED, cls(0.5 * 2.0, 2.0)) // rms 0.707
    }
}
