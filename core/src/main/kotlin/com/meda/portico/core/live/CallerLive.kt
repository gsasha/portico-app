package com.meda.portico.core.live

import com.meda.portico.core.content.CameraTile
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.ha.ContentCamera

/**
 * Live video of the CALLER's cameras on the ring and in-call screens (DEV-713 S2, the owner's decision Q4). The rules that are specific to those
 * screens; everything about how a tile streams (the route, the size, the notices, the guards) is [LivePlan]'s, the same as Home's.
 *
 * - The caller's FIRST AVAILABLE camera is live by default ([firstAvailableId]); the other cameras of the caller stay stills until the
 *   viewer taps their chip. The server sends no `live` key on a caller's camera, so the default is this screen's rule, not the document's.
 *   The viewer's choice is [LiveMemory]'s, by camera id, remembered only while it differs from this default.
 * - A tile is "usable" for live only once its still has been drawn ([tiles]: `available` is [PictureState.PICTURE]). Together with the
 *   screen's arming delay ([LiveArm]) and the rule that a tile must be on screen, nothing live starts before the ring screen is up and
 *   the picture the person came to see is there.
 */
object CallerLive {
    /** The id of the first camera that is available, in the caller's order (a repeated id counts once); null when there is none. */
    fun firstAvailableId(cameras: List<ContentCamera>): String? = cameras.distinctBy { it.id }.firstOrNull { it.available }?.id

    /** What the plan needs of each caller tile. [wants] is the viewer's choice over the default ([LiveMemory.effective]); [visible] the tile ids on screen. */
    fun tiles(cameras: List<CameraTile>, wants: (CameraTile) -> Boolean, visible: Set<String>, smallOnly: Boolean = false): List<LiveTileIn> = cameras.map { t ->
        LiveTileIn(
            cameraId = t.id,
            size = if (smallOnly && t.hasSmall) com.meda.portico.core.ha.CameraSize.SMALL else t.size, // 0.6.58: "Live video small" caps the stream
            available = t.picture == PictureState.PICTURE,
            wantsLive = wants(t),
            onScreen = t.id in visible,
            rtsp = t.liveRtsp,
        )
    }
}

/**
 * "Not before the screen is up": the live code of a ring or in-call screen waits [delayMs] after the screen is resumed before it may start
 * anything (DEV-713 S2 hard rule: live video never delays ringing, Answer, Decline or hang-up). [arm] when the screen is resumed,
 * [disarm] when it is paused; [ready] is true only [delayMs] after [arm]. Monotonic time only (a clock that goes backwards is "not yet").
 */
class LiveArm(private val delayMs: Long) {
    private var armedAt = -1L

    fun arm(nowMs: Long) {
        armedAt = nowMs
    }

    fun disarm() {
        armedAt = -1L
    }

    fun ready(nowMs: Long): Boolean = armedAt >= 0 && nowMs - armedAt >= delayMs
}

/**
 * ONE holder of a resource that two screens hand to each other (DEV-713 S2): the live video of the ring screen continues on the in-call
 * screen when the person answers, with no second session and no new negotiation, because both screens use the SAME resource (the app's
 * `LiveHome`, with its sessions) through this lease.
 *
 * - [claim]: the screen takes the resource (made on first use) and becomes its owner. A claim cancels a pending teardown.
 * - [release]: the owner lets go. The resource is not torn down at once: it is [graceMs] later, unless another screen has claimed it (a
 *   screen change shows the new activity's `onResume` before the old one's `onStop`; a rotation is a pause and a resume of a new
 *   instance). A release by a screen that is no longer the owner (the old activity, after the new one claimed) changes nothing.
 * - So after the last screen lets go every stream is closed within [graceMs] ([destroy] is the resource's own close), and a screen change
 *   never has two sessions of one camera and size.
 *
 * Single-threaded (the main thread); [clock]'s tasks run there too.
 */
class LiveLease<T : Any>(
    private val make: () -> T,
    private val destroy: (T) -> Unit,
    private val clock: LiveClock,
    private val graceMs: Long,
    private val onFault: (at: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private var resource: T? = null
    private var owner: Any? = null
    private var cancelTeardown: (() -> Unit)? = null

    fun claim(who: Any): T {
        cancelTeardown?.invoke()
        cancelTeardown = null
        owner = who
        return resource ?: make().also { resource = it }
    }

    fun release(who: Any) {
        if (owner !== who) return
        owner = null
        cancelTeardown?.invoke()
        cancelTeardown = clock.after(graceMs) { teardown() }
    }

    fun isOwner(who: Any): Boolean = owner === who

    /** The resource if it exists now (a screen that is not the owner may still read it; it never changes it). */
    fun current(): T? = resource

    private fun teardown() {
        cancelTeardown = null
        if (owner != null) return
        val r = resource ?: return
        resource = null
        try {
            destroy(r)
        } catch (e: Throwable) {
            onFault("teardown", e)
        }
    }
}

/**
 * Runs live code so that nothing it throws reaches the screen (DEV-713 S2). A fault is reported once per place and exception class (a
 * once-a-second tick that breaks does not fill the Journal) to [log], and [run] says whether the block completed.
 */
class LiveFaults(private val log: LiveLog) {
    private val seen = HashSet<String>()

    fun report(at: String, error: Throwable) {
        val name = error.javaClass.simpleName.ifEmpty { "Error" }
        val first = synchronized(seen) { seen.add("$at/$name") }
        if (first) {
            try {
                log.fault(at, name)
            } catch (_: Throwable) {
                // the Journal never costs the screen
            }
        }
    }

    /** [block]'s value, or [fallback] when it threw (reported). */
    inline fun <T> value(at: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        report(at, e)
        fallback
    }

    /** True when [block] completed. Catches Throwable (a libwebrtc `UnsatisfiedLinkError` is an Error), and rethrows nothing. */
    inline fun run(at: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Throwable) {
        report(at, e)
        false
    }
}
