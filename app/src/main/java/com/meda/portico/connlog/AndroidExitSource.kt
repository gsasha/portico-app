package com.meda.portico.connlog

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.meda.portico.core.connlog.ExitRecord
import com.meda.portico.core.connlog.ExitSource
import com.meda.portico.core.connlog.PlatformExit

/** DEV-762 A3: the platform's exit records for our package (API 30+; older phones have none and rely on the start-up marker). Total. */
class AndroidExitSource(private val context: Context) : ExitSource {
    override fun recent(max: Int): List<PlatformExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, max).map { info ->
                PlatformExit(info.reason, info.importance, info.timestamp) {
                    try {
                        // bounded read: a trace can be megabytes; only the first 64 KB is looked at
                        info.traceInputStream?.use { s ->
                            val buf = ByteArray(65_536)
                            var n = 0
                            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                            ExitRecord.linesOf(String(buf, 0, n, Charsets.ISO_8859_1))
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
