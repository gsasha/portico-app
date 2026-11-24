package com.meda.portico.core.call

/**
 * DEV-811 P2: what one call's frame audio looks like on the wire and on the device. `pcmu` is 8 kHz mu-law (telephone quality,
 * the fallback and the Compatibility setting); `l16` is 16 kHz little-endian PCM16 (the rate the gate station, the server's
 * internal link and WebRTC use). A frame is always 40 ms. [backlogBytes] is how much the socket may hold unsent before an uplink
 * frame is dropped: about five frames as they travel (base64 plus the JSON envelope).
 */
class AudioProfile private constructor(
    val codec: String,
    val rate: Int,
    val frameSamples: Int,
    val wireBytes: Int,
    val backlogBytes: Long,
) {
    /** One frame of PCM16, as bytes, for the device buffers. */
    val frameBytes: Int get() = frameSamples * 2

    val wideband: Boolean get() = rate > 8000

    /** The wire bytes of one frame of [pcm] (which must be [frameSamples] long). */
    fun encode(pcm: ShortArray): ByteArray = if (this === L16) L16Codec.encode(pcm) else G711.encode(pcm)

    fun decode(bytes: ByteArray): ShortArray = if (this === L16) L16Codec.decode(bytes) else G711.decode(bytes)

    override fun toString(): String = "AudioProfile($codec)"

    companion object {
        val PCMU = AudioProfile("pcmu", 8000, 320, 320, 2_500)
        val L16 = AudioProfile("l16", 16000, 640, 1280, 8_990)

        fun forCodec(codec: String?): AudioProfile? = when (codec) {
            PCMU.codec -> PCMU
            L16.codec -> L16
            else -> null
        }
    }
}

/** `l16` on the wire: signed 16-bit, little-endian (portico-ha `wsmedia.rs`). */
object L16Codec {
    fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2) { i ->
        ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }
}
