package com.meda.portico.core.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One ICE candidate as the live commands carry it (the keys of the browser's `RTCIceCandidateInit`). The empty [candidate] is the end-of-candidates mark. */
data class LiveCandidate(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null) {
    val isEnd: Boolean get() = candidate.isEmpty()

    /** Never prints the line: it holds an address. */
    override fun toString(): String = if (isEnd) "LiveCandidate(end)" else "LiveCandidate(${candidate.length} chars)"

    companion object {
        val END = LiveCandidate("")
    }
}

/**
 * Live video of a camera tile (DEV-713, stage S1): the three commands of the phone's WebRTC exchange with `portico-ha`'s `live.py`.
 *
 * | command                        | body                                              | answer |
 * | `portico/content/live`         | `camera_id`, `sdp`, `size?` (`big` / `small`), `endpoint?` | result `{session_id, stream}`, then the events [Event] |
 * | `portico/content/live_ice`     | `session_id`, `candidate` `{candidate, sdpMid?, sdpMLineIndex?}` | `{}` |
 * | `portico/content/live_close`   | `session_id`                                      | `{closed: true / false}`, never an error |
 *
 * The phone names a camera id from its own document (the grant is the camera), never an entity, a stream address or a token. The
 * offer is VIDEO ONLY: [live] refuses an SDP that has an audio line, so no code path can send one. The events of the stream are, in
 * order, `session` (always first), `answer`, zero or more `candidate`, or `error` (then the session is closed).
 */
object LiveCommands {
    const val LIVE = "portico/content/live"
    const val LIVE_ICE = "portico/content/live_ice"
    const val LIVE_CLOSE = "portico/content/live_close"

    /** The server refuses an offer over 16 KB (`invalid_format`). */
    const val MAX_SDP_CHARS = 16 * 1024

    private val AUDIO_LINE = Regex("(?m)^m=audio\\b")

    /** True when [sdp] has an `m=audio` line: a live offer never may. */
    fun hasAudioLine(sdp: String): Boolean = AUDIO_LINE.containsMatchIn(sdp)

    /** `portico/content/live`. [size] null leaves it out (the server's default is `big`); the app always names one. */
    fun live(cameraId: String, sdp: String, size: CameraSize? = null, endpoint: String? = null): JsonObject {
        require(cameraId.length in 1..64) { "camera_id must be 1-64 chars" }
        require(sdp.isNotEmpty() && sdp.length <= MAX_SDP_CHARS) { "sdp must be 1-$MAX_SDP_CHARS chars" }
        require(!hasAudioLine(sdp)) { "a live offer is video only: no audio line" }
        require(endpoint == null || endpoint.length in 1..64) { "endpoint must be 1-64 chars" }
        return buildJsonObject {
            put("camera_id", cameraId)
            put("sdp", sdp)
            if (size != null) put("size", size.wire)
            if (endpoint != null) put("endpoint", endpoint)
        }
    }

    /** `portico/content/live_ice` for one local candidate, or [LiveCandidate.END]. */
    fun ice(sessionId: String, candidate: LiveCandidate): JsonObject {
        require(sessionId.length in 1..128) { "session_id must be 1-128 chars" }
        return buildJsonObject {
            put("session_id", sessionId)
            put(
                "candidate",
                buildJsonObject {
                    put("candidate", candidate.candidate)
                    candidate.sdpMid?.let { put("sdpMid", it) }
                    candidate.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
                },
            )
        }
    }

    /** `portico/content/live_close`: idempotent on the server, so a repeat is harmless (the app still sends one per session). */
    fun close(sessionId: String): JsonObject {
        require(sessionId.length in 1..128) { "session_id must be 1-128 chars" }
        return buildJsonObject { put("session_id", sessionId) }
    }

    // ---- the answers ---------------------------------------------------------------------------

    sealed interface Reply {
        /** The offer was accepted: [stream] is `main` or `sub` (null when the server named none). */
        data class Opened(val sessionId: String, val stream: String?) : Reply

        /** An error answer to the command itself (before any session): [code] is the server's. */
        data class Refused(val code: String) : Reply

        /** Success without a session id: unusable (nothing could be closed by name). */
        data object Malformed : Reply

        /** The socket closed before the answer. */
        data object Lost : Reply
    }

    /** [r] null is the socket closing first. */
    fun parseReply(r: HaInbound.Result?): Reply {
        if (r == null) return Reply.Lost
        if (!r.success) return Reply.Refused(r.error?.code ?: "unknown_error")
        val o = r.result.asObject() ?: return Reply.Malformed
        val id = o.str("session_id")?.takeIf { it.isNotEmpty() } ?: return Reply.Malformed
        return Reply.Opened(id, o.str("stream"))
    }

    sealed interface Event {
        /** Always the first event: the session id again (a client that never saw the result still learns it) and the stream used. */
        data class Session(val sessionId: String, val stream: String?) : Event
        data class Answer(val sdp: String) : Event {
            /** Never prints the SDP: it holds addresses. */
            override fun toString() = "Answer(${sdp.length} chars)"
        }
        data class Candidate(val candidate: LiveCandidate) : Event

        /** The session failed after the result; the server has closed it. */
        data class Error(val code: String) : Event

        /** A type a later server adds: ignored. */
        data object Other : Event
    }

    fun parseEvent(e: JsonObject): Event = when (e.str("type")) {
        "session" -> e.str("session_id")?.takeIf { it.isNotEmpty() }?.let { Event.Session(it, e.str("stream")) } ?: Event.Other
        "answer" -> e.str("sdp")?.takeIf { it.isNotEmpty() }?.let { Event.Answer(it) } ?: Event.Other
        "candidate" -> {
            val c = e["candidate"].asObject()
            val line = c?.str("candidate")
            if (c == null || line.isNullOrEmpty()) {
                Event.Other
            } else {
                Event.Candidate(LiveCandidate(line, c.str("sdpMid"), c.int("sdpMLineIndex")))
            }
        }
        "error" -> Event.Error(e.str("code") ?: "unknown_error")
        else -> Event.Other
    }
}
