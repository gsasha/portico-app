package com.meda.portico.live

import com.meda.portico.core.live.RtspSourceFactory

/**
 * Compiled ONLY when `app/libs/gocore.aar` is absent (app/build.gradle.kts): this APK has no tailnet client, so it has no RTSP client
 * either, and away from home a live tile says live video is not available. The other copy, under `src/gomobile/`, wraps the Go client.
 */
object RtspBackends {
    @Suppress("UNUSED_PARAMETER")
    fun create(post: (() -> Unit) -> Unit): RtspSourceFactory? = null
}
