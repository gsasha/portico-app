package com.meda.portico.core.content

import com.meda.portico.core.ha.CameraSize

/**
 * 0.6.53: the video-size menu names a stream the way YouTube's quality picker does, by its vertical resolution ("1440p", "720p", "360p").
 * Pure. A camera's real height is rarely a round number (2688x1520, 1920x1072, 640x360), so [quality] snaps to the nearest standard rung.
 */
object StreamLabels {
    /** The standard vertical resolutions, as in YouTube's picker. */
    val LADDER = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

    /** 0.6.58: the EXACT height plus "p" (352x288 is "288p", 1520 stays "1520p"; no snapping to [LADDER]); null for no height. */
    fun quality(height: Int): String? = if (height <= 0) null else "${height}p"
}

/**
 * The picture heights the app has actually SEEN, per camera and stream variant (from the live decoder / renderer: the picture size of the
 * playing stream). A variant that has not played since the app started has no entry; since 0.6.54 the server's measured `streams` heights
 * ([CameraTile.bigHeight]) stand in for it ([height] with a `server` value), and the menu falls back to its generic words only when neither is known.
 * What has really played always wins. In memory, per run.
 */
class StreamHeights {
    private val seen = HashMap<Pair<String, CameraSize>, Int>()

    /** True if the stored height changed. A height of 0 or less is ignored. */
    @Synchronized
    fun record(cameraId: String, size: CameraSize, height: Int): Boolean {
        if (height <= 0) return false
        return seen.put(cameraId to size, height) != height
    }

    @Synchronized
    fun height(cameraId: String, size: CameraSize): Int? = seen[cameraId to size]

    /** The height to name a stream by: the one that has played, else the [server]'s, else null. */
    @Synchronized
    fun height(cameraId: String, size: CameraSize, server: Int?): Int? = seen[cameraId to size] ?: server?.takeIf { it > 0 }
}
