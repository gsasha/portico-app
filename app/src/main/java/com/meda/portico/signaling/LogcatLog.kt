package com.meda.portico.signaling

import android.util.Log
import com.meda.portico.core.ha.HaLog

/**
 * [HaLog] onto logcat. The core never hands it a registration value or the HA token in full
 * (only `sha256[:8]`, see `core.ha.Mask`), so nothing here needs redacting.
 */
class LogcatLog(private val tag: String = "PorticoSignaling") : HaLog {
    override fun log(level: HaLog.Level, message: String) {
        when (level) {
            HaLog.Level.DEBUG -> Log.d(tag, message)
            HaLog.Level.INFO -> Log.i(tag, message)
            HaLog.Level.WARN -> Log.w(tag, message)
            HaLog.Level.ERROR -> Log.e(tag, message)
        }
    }
}
