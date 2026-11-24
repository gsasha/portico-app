package com.meda.portico.tailnet

import android.content.Context
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.tailnet.TailnetEngine

/**
 * Compiled ONLY when `app/libs/gocore.aar` is present (app/build.gradle.kts, `gocore/bind.sh`).
 * The other copy, under `src/nogomobile/`, returns [TailnetEngine.NOT_BUILT].
 */
object TailnetEngines {
    fun create(app: Context, log: HaLog): TailnetEngine = GoTailnetEngine(app.applicationContext, log)
}
