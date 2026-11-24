package com.meda.portico.tailnet

import android.content.Context
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.tailnet.TailnetEngine

/**
 * Compiled ONLY when `app/libs/gocore.aar` is absent (app/build.gradle.kts): this APK has no
 * tailnet client, and Setup says so ("not built into this APK"). The other copy of this file,
 * under `src/gomobile/`, is compiled when the library is present. Run `gocore/bind.sh` first.
 */
object TailnetEngines {
    @Suppress("UNUSED_PARAMETER")
    fun create(app: Context, log: HaLog): TailnetEngine = TailnetEngine.NOT_BUILT
}
