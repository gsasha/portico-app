package com.meda.portico.core.live

/**
 * What [LiveManager] and the tile need from one running live stream, whatever its route: the WebRTC [LiveSession] at home or the
 * RTSP [RtspLiveSession] away (DEV-713 S1b). Single-threaded: the owner calls it, and its ports' callbacks arrive, on ONE thread.
 */
interface LiveRun {
    val state: LiveSession.State

    /** The class of the last failure, for the notice. Cleared by [start] and by the first frame. */
    val why: LiveWhy?

    /** `--` or `N fps`, measured while PLAYING. */
    val fpsLabel: String

    /** Begins (or, from [LiveSession.State.FAILED], begins again, as a tap on the chip does). A running or retrying run is left alone. */
    fun start()

    /** Closes the run for good. Idempotent. */
    fun stop(reason: StopWhy)
}
