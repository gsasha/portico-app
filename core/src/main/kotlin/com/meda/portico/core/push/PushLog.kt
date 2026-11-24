package com.meda.portico.core.push

/**
 * Journal lines for the receive and registration paths (P2 plan §3.7). The Android layer passes
 * these strings to its logger unchanged, so what reaches logcat is what the tests check.
 *
 * Call ids and door refs are logged in full: they are opaque ids, and the server logs call ids in
 * full (`notifier.py` summary line), which is what lets the two journals be matched. A
 * registration value is only ever rendered through [Redact.reg].
 */
object PushLog {

    fun decision(msg: PushMessage, outcome: Outcome): String {
        val what = when (msg) {
            is PushMessage.Ring -> "ring door=${msg.doorRef} call=${msg.callId} seq=${msg.seq} via=${msg.source.wire}"
            is PushMessage.Answered -> "answered door=${msg.doorRef} call=${msg.callId} seq=${msg.seq} via=${msg.source.wire}"
            is PushMessage.Ended -> "ended door=${msg.doorRef} call=${msg.callId} seq=${msg.seq} via=${msg.source.wire}"
            is PushMessage.Test -> "test"
            is PushMessage.Ignored -> "ignored ${msg.reason.name.lowercase()} (${msg.detail})"
        }
        val why = when (outcome) {
            is Outcome.Silent -> " reason=${outcome.reason.name.lowercase()}"
            is Outcome.Missed -> " reason=${outcome.reason.name.lowercase()}"
            else -> ""
        }
        return "portico push: $what -> ${outcome.contractName}$why"
    }

    /** `kind` is `fid` or `token`; [value] is the registration itself and is never rendered. */
    fun registration(kind: String, value: String?, installationId: String?): String =
        "portico push: registration kind=$kind reg=${Redact.reg(value)} installation=${Redact.prefix(installationId)}"

    private val Source.wire: String get() = name.lowercase()
}
