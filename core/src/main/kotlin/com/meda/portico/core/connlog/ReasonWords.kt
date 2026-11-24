package com.meda.portico.core.connlog

/**
 * A close reason as the connection log may carry it (DEV-705): a WHITELIST, not a truncation. The reasons the socket stack
 * produces are free text (`failure: SocketTimeoutException`, `closed by server (1001)`, `direct: ...; tailnet: <a tailnet
 * exception's message>`), and a message can hold anything. Only three kinds of token survive: words from the fixed
 * vocabulary below, Java/Kotlin exception CLASS names (`UnknownHostException`), and a 3-4 digit status in parentheses.
 * Everything else is dropped. At most 32 characters, lower-case, `_`-joined; `other` when nothing is left.
 */
object ReasonWords {
    private val VOCABULARY = setOf(
        "failure", "closed", "by", "server", "paused", "reconnect", "requested", "contract", "mismatch", "while", "connecting",
        "direct", "tailnet", "timeout", "timed", "out", "refused", "reset", "unreachable", "eof", "normal", "going", "away",
        "protocol", "error", "cancelled", "canceled", "stopped", "network", "lost", "no", "answer", "within", "ms", "link", "watchdog",
    )
    private val CLASS_NAME = Regex("[A-Z][A-Za-z]{1,40}(Exception|Error)")
    private val STATUS = Regex("\\((\\d{3,4})\\)")
    private val TOKEN = Regex("[A-Za-z]+|\\(\\d{3,4}\\)")

    fun of(reason: String?): String {
        if (reason.isNullOrBlank()) return "other"
        val out = ArrayList<String>()
        for (m in TOKEN.findAll(reason.take(300))) {
            val t = m.value
            val status = STATUS.matchEntire(t)
            when {
                status != null -> out.add(status.groupValues[1])
                CLASS_NAME.matches(t) -> out.add(t.lowercase())
                t.lowercase() in VOCABULARY -> out.add(t.lowercase())
            }
        }
        val joined = out.joinToString("_").take(32).trim('_')
        return joined.ifEmpty { "other" }
    }
}
