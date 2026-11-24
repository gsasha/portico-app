package com.meda.portico.core.call

import kotlin.math.sqrt

/**
 * One libwebrtc stats entry, reduced to what [AudioStatsParser] reads: its `type` (`outbound-rtp`, `codec`, ...), its `id` and
 * its members. The app turns an `RTCStatsReport` into these (the only Android-only part); everything after is pure, so the JVM
 * tests drive the parser with hand-written entries.
 */
data class RawStat(val type: String, val id: String, val members: Map<String, Any?>)

/**
 * How loud the microphone was over the call, as a CLASS only (DEV-698): never samples, never a waveform. Taken from the
 * media-source's average level (`totalAudioEnergy` over `totalSamplesDuration`, root-mean-square, 0..1 of full scale).
 * libwebrtc exposes no peak, so [CLIPPED] means "so loud on average that it must be clipping", not "one sample hit the rail".
 */
enum class MicLevel(val word: String) {
    SILENT("silent"),
    LOW("low"),
    NORMAL("normal"),
    CLIPPED("clipped"),
    ;

    companion object {
        /** Below this average RMS (about -70 dBFS) the microphone delivered nothing: dead, muted at the OS, or the gain is zero. */
        const val SILENT_BELOW = 0.0003

        /** Below this (about -46 dBFS) it hears something but far too little for speech (the gate-mic-gain case). */
        const val LOW_BELOW = 0.005

        /** At or above this (about -9 dBFS on average, speech pauses included) the signal is clipping. */
        const val CLIPPED_FROM = 0.35

        fun of(rms: Double?): MicLevel? = when {
            rms == null || rms.isNaN() || rms < 0.0 -> null
            rms < SILENT_BELOW -> SILENT
            rms < LOW_BELOW -> LOW
            rms < CLIPPED_FROM -> NORMAL
            else -> CLIPPED
        }
    }
}

/** What the app knows of ONE direction of the audio. Every field is nullable: null means "could not be read" and prints as `?`, never 0. */
data class AudioDirection(
    /** Lowercase codec name from the codec stat (`opus`, `pcmu`); only a short `[a-z0-9-]` word ever gets here. */
    val codec: String? = null,
    /** The codec's RTP clock rate in Hz (Opus is always 48000 on the wire whatever it carries). */
    val clockRateHz: Int? = null,
    /** Packet size in ms: the audio duration this direction has handled divided by its packets. About 20 for Opus at the default. */
    val ptimeMs: Int? = null,
    /** Packets (frames) sent for the uplink, received for the downlink. */
    val packets: Long? = null,
    /** Uplink: lost as the far end reports it (remote-inbound). Downlink: lost as counted here. */
    val lost: Long? = null,
)

data class AudioStats(val up: AudioDirection = AudioDirection(), val down: AudioDirection = AudioDirection(), val mic: MicLevel? = null)

/** Reads the audio facts out of a stats report. Pure; tolerant of any missing entry or member (a field it cannot read stays null). */
object AudioStatsParser {
    /** Fewer packets than this say nothing reliable about the packet size. */
    const val MIN_PACKETS_FOR_PTIME = 10L

    private val CODEC_WORD = Regex("[a-z0-9-]{1,12}")

    fun parse(stats: List<RawStat>): AudioStats {
        val byId = stats.associateBy { it.id }
        fun audio(type: String) = stats.firstOrNull { it.type == type && isAudio(it) }
        val out = audio("outbound-rtp")
        val inn = audio("inbound-rtp")
        val remote = audio("remote-inbound-rtp")
        val source = audio("media-source")

        val upPackets = out?.long("packetsSent")
        val downPackets = inn?.long("packetsReceived")
        val up = AudioDirection(
            codec = codecOf(out, byId),
            clockRateHz = clockOf(out, byId),
            ptimeMs = ptimeMs(source?.double("totalSamplesDuration"), upPackets),
            packets = upPackets,
            lost = remote?.long("packetsLost")?.coerceAtLeast(0),
        )
        val down = AudioDirection(
            codec = codecOf(inn, byId),
            clockRateHz = clockOf(inn, byId),
            ptimeMs = ptimeMs(inn?.double("totalSamplesDuration"), downPackets),
            packets = downPackets,
            lost = inn?.long("packetsLost")?.coerceAtLeast(0),
        )
        return AudioStats(up, down, MicLevel.of(rms(source)))
    }

    /** Packet size, rounded to whole ms; null when the duration or the packet count is missing or too small to mean anything. */
    fun ptimeMs(durationSec: Double?, packets: Long?): Int? {
        if (durationSec == null || packets == null || durationSec.isNaN() || durationSec <= 0.0 || packets < MIN_PACKETS_FOR_PTIME) return null
        val ms = Math.round(durationSec * 1000.0 / packets).toInt()
        return ms.takeIf { it in 1..1000 }
    }

    private fun rms(source: RawStat?): Double? {
        val energy = source?.double("totalAudioEnergy") ?: return null
        val duration = source.double("totalSamplesDuration") ?: return null
        if (energy < 0.0 || duration <= 0.0) return null
        return sqrt(energy / duration)
    }

    private fun isAudio(s: RawStat): Boolean {
        val kind = (s.members["kind"] ?: s.members["mediaType"]) as? String
        return kind == null || kind == "audio" // an audio-only call: a report with no kind member is audio
    }

    private fun codecEntry(s: RawStat?, byId: Map<String, RawStat>): RawStat? = (s?.members?.get("codecId") as? String)?.let(byId::get)

    private fun codecOf(s: RawStat?, byId: Map<String, RawStat>): String? {
        val mime = codecEntry(s, byId)?.members?.get("mimeType") as? String ?: return null
        val word = mime.substringAfter('/').lowercase()
        return word.takeIf { CODEC_WORD.matches(it) }
    }

    private fun clockOf(s: RawStat?, byId: Map<String, RawStat>): Int? =
        codecEntry(s, byId)?.long("clockRate")?.takeIf { it in 1..192_000 }?.toInt()

    private fun RawStat.long(key: String): Long? = (members[key] as? Number)?.let { n ->
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite()) null else n.toLong()
    }

    private fun RawStat.double(key: String): Double? = (members[key] as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }
}
