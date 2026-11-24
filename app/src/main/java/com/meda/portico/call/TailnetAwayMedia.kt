package com.meda.portico.call

import com.meda.portico.core.call.AwayMedia
import com.meda.portico.core.call.AwayPathPolicy
import com.meda.portico.core.call.FrameChannel
import com.meda.portico.core.call.FrameMedia
import com.meda.portico.core.call.AudioChoice
import com.meda.portico.core.call.CallAudioConfig
import com.meda.portico.core.call.FramePath
import com.meda.portico.core.call.FrameReport
import com.meda.portico.core.call.PathChoice
import com.meda.portico.core.call.Scheduler
import com.meda.portico.core.call.MediaListener
import com.meda.portico.core.call.PcmDevice
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.LinkPath

/**
 * The app's [AwayMedia]: frames are preferred from the start when the open socket came through the
 * tailnet ([linkPath] == TAILNET), the one case where UDP media is known not to reach HA. Always built (0.6.64:
 * the Setup switch is gone).
 *
 * DEV-811: every frame path counts its audio and reports it at its close ([onReport]: the journal's `frames v=2` and `audio dev`
 * lines, answered door calls and Away Talk alike); the profile (16 kHz `l16` or 8 kHz `pcmu`) comes from [config]; Setup's "Call audio
 * path" applies on the home network only ([AwayPathPolicy.decide] with the choice).
 */
class TailnetAwayMedia(
    private val linkPath: () -> LinkPath?,
    /** The last path the app knew, for the moment [linkPath] is null (socket not open). */
    private val lastPath: () -> LinkPath? = { null },
    private val channel: FrameChannel,
    private val pcm: () -> PcmDevice,
    private val post: (() -> Unit) -> Unit,
    private val log: HaLog,
    private val config: CallAudioConfig? = null,
    private val scheduler: Scheduler? = null,
    /** The call's audio at its close, for the journal. */
    private val onReport: ((FrameReport) -> Unit)? = null,
    /** 0.6.95: the frame path's diagnostic lines and its audio-environment request. */
    private val onDiag: ((String, List<Pair<String, Any?>>) -> Unit)? = null,
    private val onEnv: ((String) -> Unit)? = null,
) : AwayMedia {
    private fun decision() = AwayPathPolicy.decide(linkPath(), lastPath(), config?.let { try { it.pathChoice() } catch (_: Exception) { null } } ?: PathChoice.AUTO)

    override fun preferFrames(): Boolean = decision().frames

    override fun pathWhy(): String = decision().why

    override fun open(listener: MediaListener): FramePath {
        val choice = try { config?.choose() } catch (_: Exception) { null } ?: AudioChoice.WIDEBAND
        return FrameMedia(
            channel, pcm(), listener, post, log,
            choice = choice, scheduler = scheduler, onReport = onReport, onDiag = onDiag, onEnv = onEnv,
            onWidebandFailed = { config?.recordWidebandFailure() },
            markWord = { config?.mark()?.word ?: "none" },
        )
    }
}
