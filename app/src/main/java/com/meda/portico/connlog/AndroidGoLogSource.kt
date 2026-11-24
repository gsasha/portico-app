package com.meda.portico.connlog

import com.meda.portico.core.connlog.GoLogSource

/**
 * DEV-762 A4: the app's own `GoLog` logcat entries (gomobile sends Go's stderr there). An app reads its own entries with no
 * permission, and logd's buffer outlives the dead process (the old app's method). UNVERIFIED on a device. Bounded in lines, bytes
 * and time; total.
 */
class AndroidGoLogSource : GoLogSource {
    override fun lines(): List<String>? {
        var p: Process? = null
        return try {
            p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-v", "epoch", "-t", "2000", "-s", "GoLog").redirectErrorStream(true).start()
            val out = ArrayList<String>()
            var chars = 0
            p.inputStream.bufferedReader(Charsets.ISO_8859_1).use { r ->
                while (out.size < 2_000 && chars < 400_000) {
                    val l = r.readLine() ?: break
                    chars += l.length
                    out.add(l)
                }
            }
            out
        } catch (_: Throwable) {
            null
        } finally {
            try { p?.destroy() } catch (_: Throwable) {}
        }
    }
}
