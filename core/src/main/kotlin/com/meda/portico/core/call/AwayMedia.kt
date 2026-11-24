package com.meda.portico.core.call

import com.meda.portico.core.ha.HaInbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Call audio as frames on the HA WebSocket, for a phone that cannot reach the SFU's UDP port
 * (away from home, through the embedded tailnet client, which forwards only the signaling
 * socket). Design: the estate repo automation/portico_away_media_design.md; wire contract:
 * portico-ha contract/ws_media_v2.json (contract 3: `door_id`).
 *
 * OFF by default. Only AnswerSession's `away` hook reaches any of this, and the app passes null
 * unless Setup's "Away call audio" switch is on, so with it off none of this code runs.
 */

/** Where the session is told about away media. `null` in the session means the feature is off. */
interface AwayMedia {
    /** True when this call's audio should skip WebRTC and use frames from the start (signaling runs through the tailnet). */
    fun preferFrames(): Boolean

    /** Why [preferFrames] answered as it did, for the journal (`ans-path`). */
    fun pathWhy(): String = ""

    /** A frame path for one call. [listener] gets onConnected / onFailed on the answer thread. Throws if audio cannot start. */
    fun open(listener: MediaListener): FramePath
}

/** The audio of one call carried as frames. Every method is called on the answer thread. */
interface FramePath {
    /** Asks HA to open the channel (after the claim), then starts the microphone and speaker. [onResult] null = running. */
    fun start(callId: String, doorId: String, onResult: (AnswerFailure?) -> Unit)
    fun setMuted(muted: Boolean)

    /**
     * HOLD: stop the recorder and the player but KEEP SENDING SILENT UPLINK FRAMES (the hub closes a leg after 5 s without
     * an uplink frame, 15.13), and keep the channel open. Resume restarts both. The default throws, as [MediaCall.setHeld].
     */
    fun setHeld(held: Boolean) {
        throw UnsupportedOperationException("this frame path cannot be put on hold")
    }

    /** Stops audio and tells HA. Idempotent, returns at once, never throws. */
    fun close()
}

/** What the channel delivers; may be called on any thread. */
interface FrameListener {
    fun onFrame(payloadBase64: String)

    /** HA closed the channel (`portico/media_open`'s event `{"t":"end","reason"}`). */
    fun onEnd(reason: String)

    /** The WebSocket closed under the channel. */
    fun onLost()
}

/** The phone's one HA socket, as the frame path uses it. Implemented over `PorticoLink` in `:app`. */
interface FrameChannel {
    /** Sends `portico/media_open`; [onOpened] gets the command's reply once, then events go to [listener]. */
    fun open(doorId: String, callId: String, codec: String, listener: FrameListener, onOpened: (CommandReply) -> Unit)

    /** One uplink frame. False when it was not sent (socket gone, or the socket's backlog is too long). */
    fun send(callId: String, payloadBase64: String): Boolean

    /** Best-effort `portico/media_close`. */
    fun close(callId: String)
}

/** How the platform's effects and reads went on a device (DEV-811 P1): `aec`/`ns` are `on`, `unavailable` or `failed`. */
data class DeviceReport(val aec: String, val ns: String, val readMaxMs: Long) {
    companion object {
        val NONE = DeviceReport("unavailable", "unavailable", 0)
    }
}

/** The microphone and speaker, mono, 16-bit, in 40 ms frames at the [AudioProfile]'s rate (8 or 16 kHz). */
interface PcmDevice {
    /**
     * Creates the recorder and the player at [profile]'s rate, checks they initialised and attaches the effects, WITHOUT starting
     * (DEV-811 2.1: on a door the open IS the answer, so a device that cannot work must be known before it). Throws if it cannot.
     * [stop] releases a prepared device that was never started.
     */
    fun prepare(profile: AudioProfile)

    /**
     * Starts capture ([onCapture], on the device's thread) and playout ([nextPlayout]: a frame, or null for silence) on the prepared
     * device. Throws if audio cannot start (a recorder not RECORDING, a player not PLAYING). [onError] is told, from the device's thread,
     * of a read or write error after the start.
     */
    fun start(onCapture: (ShortArray) -> Unit, nextPlayout: () -> ShortArray?, onError: (String) -> Unit)

    fun setMuted(muted: Boolean)

    /** Stops both and releases everything, prepared or running. Idempotent. */
    fun stop()

    /** Effects and the longest blocking read, for the journal. */
    fun report(): DeviceReport = DeviceReport.NONE

    /** 0.6.95: the live output/input facts (sample rate, buffer, underruns, stream type) as Journal fields, `emptyList()` where the device has none. Never throws. */
    fun facts(): List<Pair<String, Any?>> = emptyList()
}

object MediaCommands {
    const val OPEN = "portico/media_open"
    const val FRAME = "portico/media_frame"
    const val CLOSE = "portico/media_close"

    fun open(doorId: String, callId: String, codec: String = AudioProfile.PCMU.codec): JsonObject {
        require(doorId.isNotEmpty()) { "door_id" }
        require(callId.isNotEmpty()) { "call_id" }
        return buildJsonObject {
            put("door_id", doorId)
            put("call_id", callId)
            put("codec", codec)
        }
    }

    fun frame(callId: String, payloadBase64: String): JsonObject = buildJsonObject {
        put("call_id", callId)
        put("d", payloadBase64)
    }

    fun close(callId: String): JsonObject = buildJsonObject { put("call_id", callId) }

    sealed interface Opened {
        data class Ok(val codec: String) : Opened
        data class Refused(val failure: AnswerFailure) : Opened
    }

    /**
     * The reply to `media_open`: its codec must equal the one REQUESTED ([expected]) and, when the reply says a `frame_bytes`, it must be
     * that profile's wire size; anything else is refused, because the phone would play a frame at the wrong rate.
     */
    fun parseOpen(r: HaInbound.Result, expected: AudioProfile): Opened {
        if (!r.success) return Opened.Refused(AnswerErrors.fromCode(r.error?.code ?: "unknown_error", r.error?.message))
        val obj = r.result as? JsonObject
        val codec = (obj?.get("codec") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (codec != expected.codec) return Opened.Refused(AnswerErrors.fromCode("malformed_result"))
        val fb = obj.get("frame_bytes")
        if (fb != null) {
            val n = (fb as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            if (n != expected.wireBytes) return Opened.Refused(AnswerErrors.fromCode("malformed_result"))
        }
        return Opened.Ok(codec)
    }

    /** One event on the open command: a downlink frame `{"t":"f","d"}` or the end `{"t":"end","reason"}`. */
    fun dispatch(event: JsonObject, listener: FrameListener) {
        val t = (event["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (t) {
            "f" -> (event["d"] as? JsonPrimitive)?.takeIf { it.isString }?.let { listener.onFrame(it.content) }
            "end" -> listener.onEnd((event["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "closed")
            else -> Unit
        }
    }
}

/** G.711 mu-law, the same algorithm as portico-ha `internal/wsmedia` (copied from `internal/sfu`). */
object G711 {
    private const val BIAS = 0x84
    private const val CLIP = 32635
    private val SEGMENT_END = intArrayOf(0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF)

    fun encode(pcm: Short): Byte {
        var v = pcm.toInt()
        val mask: Int
        if (v < 0) {
            v = BIAS - v
            mask = 0x7F
        } else {
            v += BIAS
            mask = 0xFF
        }
        if (v > CLIP) v = CLIP
        var seg = 8
        for (i in SEGMENT_END.indices) {
            if (v <= SEGMENT_END[i]) {
                seg = i
                break
            }
        }
        if (seg >= 8) return (0x7F xor mask).toByte()
        val u = (seg shl 4) or ((v shr (seg + 3)) and 0x0F)
        return (u xor mask).toByte()
    }

    fun decode(u: Byte): Short {
        val uv = u.toInt().inv() and 0xFF
        var t = ((uv and 0x0F) shl 3) + BIAS
        t = t shl ((uv and 0x70) shr 4)
        return (if (uv and 0x80 != 0) BIAS - t else t - BIAS).toShort()
    }

    fun encode(frame: ShortArray): ByteArray = ByteArray(frame.size) { encode(frame[it]) }
    fun decode(bytes: ByteArray): ShortArray = ShortArray(bytes.size) { decode(bytes[it]) }
}

/**
 * Downlink playout for frames that arrive over TCP: a stall releases a burst, and a queue that
 * only ever drained at the playback rate would keep that burst as permanent delay. So: nothing
 * plays until [PREBUFFER] frames are queued; when more than [HIGH_WATER] are queued the oldest
 * are dropped down to [TARGET] (a skipped syllable beats a growing delay). Thread-safe.
 */
class PlayoutQueue {
    private val frames = java.util.ArrayDeque<ShortArray>()
    private var primed = false

    @Synchronized
    fun offer(frame: ShortArray) {
        frames.addLast(frame)
        if (frames.size > HIGH_WATER) while (frames.size > TARGET) frames.removeFirst()
    }

    /** The next frame to play, or null for silence (not primed yet, or an underrun). */
    @Synchronized
    fun next(): ShortArray? {
        if (!primed) {
            if (frames.size < PREBUFFER) return null
            primed = true
        }
        val f = frames.pollFirst()
        if (f == null) primed = false // an underrun: prime again before resuming
        return f
    }

    @Synchronized
    fun size(): Int = frames.size

    /** Throws away every queued frame and primes again (a hold or a resume: stale audio must not play). */
    @Synchronized
    fun clear() {
        frames.clear()
        primed = false
    }

    companion object {
        const val PREBUFFER = 2
        const val HIGH_WATER = 6
        const val TARGET = 3
    }
}
