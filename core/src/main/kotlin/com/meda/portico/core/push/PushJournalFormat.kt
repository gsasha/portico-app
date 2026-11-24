package com.meda.portico.core.push

import com.meda.portico.core.call.AudioDirection
import com.meda.portico.core.call.AudioProfile
import com.meda.portico.core.call.DeviceStats
import com.meda.portico.core.call.FrameReport
import com.meda.portico.core.call.FrameStats
import com.meda.portico.core.call.AudioStats
import com.meda.portico.core.call.EndReason
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.ring.NotificationPlan
import com.meda.portico.core.ring.PermissionState
import java.util.Locale
import java.util.TimeZone

/**
 * The steps of hold, switch and resume that the journal records (M7). Only these words reach a line: the step, then an enum
 * name ([PushJournalFormat.holdLine]'s `result`), small numbers and two shortened ids.
 */
enum class HoldStep(val word: String) {
    /** The live call is about to be put on hold (to answer a second ring, or to switch to the held call). */
    HOLD_REQUESTED("requested"),

    /** The live call is on hold. */
    HOLD_APPLIED("applied"),

    /** The media refused the hold (or there was no live call to hold): nothing changed. */
    HOLD_REFUSED("refused"),

    /** The held call would not come back, so the call that had been put on hold for it was put back as it was (`n`=1 if that worked). */
    HOLD_ROLLED_BACK("rolled-back"),

    /** The named call became the live one while another went on hold (second answer, or a switch). */
    SWITCHED("switched"),

    /** The named held call is live again and no other call was live. */
    RESUMED("resumed"),

    /** A resume press that changed nothing (`result` says why). */
    RESUME_REFUSED("resume-refused"),

    /** A held call was ended by the hold limit (`ms` is how long it had been held). */
    HOLD_LIMIT("limit"),

    /** A held call ended while held (`result` is the end reason: `remote_hangup` is the other side). */
    HELD_ENDED("held-ended"),

    /** Another app took the audio focus (`n` is the number of calls that have not ended). */
    FOCUS_LOST("focus-lost"),

    /** The audio focus came back. */
    FOCUS_REGAINED("focus-regained"),
}

/** The steps of a live-video session the journal records (DEV-713): [PushJournalFormat.liveLine]. */
enum class LiveStep(val word: String) {
    /** The first frame arrived. */
    START("start"),

    /** An attempt failed (the class of the reason follows). */
    FAIL("fail"),

    /** The session was closed on purpose (hidden, background, paused, switched off, link closed). */
    STOP("stop"),
}

/**
 * The steps of a house call's ring that the journal records (M7 3b-B): only these words reach a line, then an enum name (the
 * outcome or the reason the ring ended), a shortened call id and small numbers. Never a name, an endpoint id or a `from_ref`.
 */
enum class HouseStep(val word: String) {
    /** A house ring arrived (socket or push) and was decided: `result` says shown or why silent. */
    RING("ring"),

    /** The person pressed Answer on the house ring. */
    ANSWER_PRESSED("answer"),

    /** The person pressed Decline. */
    DECLINE_PRESSED("decline"),

    /** The ring is over on this phone: `result` is why. */
    DISMISSED("over"),

    /** This phone told the house its Do not disturb: `n` is 1 for on, 0 for off. */
    DND("dnd"),
}

/** Which of a call's two audio lines (DEV-698): once shortly after the audio connects, once when its media is closed. */
enum class AudioPhase(val word: String) { START("start"), END("end") }

/**
 * The text of the push and ring journal lines (0.5.12-alpha). Pure: no clock, no I/O, no Android.
 *
 * **What a line may hold.** A time of day (phone local, `HH:mm:ss.SSS`), a kind word, an outcome name from [Outcome]
 * with its reason enum, small numbers (seq, ages, counts), booleans from the permission probe, and ids shortened by
 * [Redact.prefix] (the first six characters and the length). Nothing else reaches a line: no token, URL, enrolment
 * code, name, registration or full id. The ids come in as arguments and are shortened here, never earlier, so a caller
 * cannot forget; [scrub] is the second net for anything that still looks like one.
 */
object PushJournalFormat {
    private const val DAY_MS = 86_400_000L

    /** `HH:mm:ss.SSS` in [zone] (the phone's own clock zone). */
    fun time(epochMs: Long, zone: TimeZone): String {
        val local = epochMs + zone.getOffset(epochMs)
        val day = Math.floorMod(local, DAY_MS)
        val h = day / 3_600_000
        val m = day / 60_000 % 60
        val s = day / 1000 % 60
        val ms = day % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    // ---- the lines -----------------------------------------------------------------------------------------------

    /** One line for one decided message, from either delivery path. */
    fun decisionLine(nowMs: Long, zone: TimeZone, msg: PushMessage, outcome: Outcome, knownDoors: Int?, env: PushEnv): String {
        val what = when (msg) {
            is PushMessage.CallMessage -> {
                val kind = when (msg) {
                    is PushMessage.Ring -> "ring"
                    is PushMessage.Answered -> "answered"
                    is PushMessage.Ended -> "ended"
                }
                "${via(msg.source)} $kind door=${Redact.prefix(msg.doorRef)} call=${Redact.prefix(msg.callId)} seq=${msg.seq} " +
                    "t=${time(msg.tMs, zone)} age=${nowMs - msg.tMs}ms"
            }
            is PushMessage.Test -> "push test" + (msg.tMs?.let { " t=${time(it, zone)} age=${nowMs - it}ms" } ?: "")
            is PushMessage.Ignored -> "push ignored(${msg.reason.name.lowercase()})"
        }
        val until = (outcome as? Outcome.Ring)?.let { " until=${time(it.ringUntilMs, zone)}" } ?: ""
        return "${time(nowMs, zone)} $what known=${knownDoors ?: "?"}$until ${envText(env)} -> ${outcomeName(outcome)}"
    }

    /** `Decision.receive` threw: the kind word and the exception class. */
    fun failedLine(nowMs: Long, zone: TimeZone, msg: PushMessage, e: Throwable, env: PushEnv): String =
        "${time(nowMs, zone)} ${via(sourceOf(msg))} ${kindWord(msg)} decide-failed ex=${className(e)} ${envText(env)}"

    /** A message dropped before any decision (the pause). */
    fun droppedLine(nowMs: Long, zone: TimeZone, kind: String, why: String): String =
        "${time(nowMs, zone)} push dropped kind=${word(kind)} why=${word(why)}"

    /** A `portico/calls` event as it arrived, before any decision. */
    fun socketEventLine(nowMs: Long, zone: TimeZone, event: CallEvent): String {
        val kind = when (event) {
            is CallEvent.Ringing -> "ringing"
            is CallEvent.Answered -> "answered"
            is CallEvent.Active -> "active"
            is CallEvent.Ended -> "ended"
            is CallEvent.NoLiveCall -> "idle"
        }
        val call = when (event) {
            is CallEvent.Ringing -> event.callId
            is CallEvent.Answered -> event.callId
            is CallEvent.Active -> event.callId
            is CallEvent.Ended -> event.callId
            is CallEvent.NoLiveCall -> null
        }
        val flag = when (event) {
            is CallEvent.Ringing -> if (event.snapshot) " snapshot" else ""
            is CallEvent.Answered -> if (event.snapshot) " snapshot" else ""
            is CallEvent.Active -> if (event.snapshot) " snapshot" else ""
            is CallEvent.Ended -> if (event.inferred) " inferred" else ""
            is CallEvent.NoLiveCall -> ""
        }
        val door = event.doorRef?.let { "door=${Redact.prefix(it)}" } ?: "door=unknown doorid=${Redact.prefix(event.doorId)}"
        return "${time(nowMs, zone)} ws event=$kind $door" + (call?.let { " call=${Redact.prefix(it)}" } ?: "") + flag
    }

    /** A decided outcome that never reached the handler. */
    fun gatedLine(nowMs: Long, zone: TimeZone, outcome: Outcome, why: String): String =
        "${time(nowMs, zone)} ui ${outcomeName(outcome)}${idsOf(outcome)} not-alerted why=${word(why)}"

    /** What the handler did: the plan it applied under the permission state it read. */
    fun handledLine(nowMs: Long, zone: TimeZone, outcome: Outcome, plan: NotificationPlan, perms: PermissionState): String {
        val post = plan.post?.let { s ->
            s.kind.name.lowercase() +
                (if (s.fullScreen) "+fsi" else "") + (if (s.callStyle) "+callstyle" else "") +
                (if (s.insistent) "+insistent" else "") + (if (s.onlyAlertOnce) "+repost" else "") +
                (s.backstopAtMs?.let { "+backstop" } ?: "")
        } ?: "none"
        val warn = if (plan.warnings.isEmpty()) "" else " warn=" + plan.warnings.joinToString(",") { it.name.lowercase() }
        return "${time(nowMs, zone)} ui ${outcomeName(outcome)}${idsOf(outcome)} post=$post stop=${yn(plan.cancelRing != null)}$warn " +
            "notif=${onOff(perms.notificationsEnabled)} ringchan=${onOff(perms.ringChannelEnabled)} high=${yn(perms.ringChannelHigh)} " +
            "statuschan=${onOff(perms.statusChannelEnabled)} fsi=${yn(perms.fullScreenAllowed)}"
    }

    /** A handler or dispatcher step threw. */
    fun stepFailedLine(nowMs: Long, zone: TimeZone, what: String, e: Throwable): String =
        "${time(nowMs, zone)} ui step-failed what=${word(what)} ex=${className(e)}"

    /** A ring stopped (or was not up to stop). */
    fun stopLine(nowMs: Long, zone: TimeZone, why: String, doorRef: String?, callId: String?, stopped: Boolean): String =
        "${time(nowMs, zone)} ui stop why=${word(why)}" +
            (doorRef?.let { " door=${Redact.prefix(it)}" } ?: "") + (callId?.let { " call=${Redact.prefix(it)}" } ?: "") +
            if (stopped) " stopped" else " not-ringing"

    /**
     * How a Talk to the door ended (one line per talk): `ui talk-end via=door_ringing reason=failed failure=door_ringing answered=yes
     * hangup=yes call=out-4…(4) press=640ms answer=120ms`. [via] is the session's own word for the code path, never free text.
     */
    fun talkEndLine(nowMs: Long, zone: TimeZone, e: com.meda.portico.core.call.TalkEnd): String =
        "${time(nowMs, zone)} ui talk-end via=${if (VIA.matches(e.via)) e.via else "?"} reason=${word(e.reason.name.lowercase())}" +
            (e.failure?.let { " failure=${word(it.name.lowercase())}" } ?: "") +
            " answered=${yn(e.answered)} hangup=${yn(e.hangup)}" +
            (e.callId?.let { " call=${Redact.prefix(it)}" } ?: "") +
            (e.sincePressMs?.let { " press=${it}ms" } ?: "") + (e.sinceAnswerMs?.let { " answer=${it}ms" } ?: "")

    /** Every ring stopped at once (the pause). */
    fun stopAllLine(nowMs: Long, zone: TimeZone, why: String, n: Int): String =
        "${time(nowMs, zone)} ui stop-all why=${word(why)} n=$n"

    /** One step of a house ring (M7 3b-B). [callId] is shortened here, never earlier; [result] is an enum and only its lowercase name is written. */
    fun houseLine(nowMs: Long, zone: TimeZone, step: HouseStep, callId: String?, result: Enum<*>?, n: Int?): String =
        "${time(nowMs, zone)} house ${step.word}" + (callId?.let { " call=${Redact.prefix(it)}" } ?: "") +
            (result?.let { " result=${word(it.name.lowercase())}" } ?: "") + (n?.let { " n=$it" } ?: "")

    /**
     * The FLOW line of one call's audio over the socket (design 15.17, DEV-811 P1), written when the audio closes: `frames v=2 call=hso-17...(10)
     * codec=l16 rate=16000 secs=42 sent=1050 dropped=0 recv=1049 underruns=1 maxgap=120ms p95gap=60ms qmin=1 qmax=5`. The first word is in the
     * server's fixed list and `v=2` keeps `call=` where the reporter expects it. Numbers, words and a shortened call id only: no colon, no
     * dot, no run of 24 hex digits, so the line passes the character and secret-shape filter (a unit test runs the real line through it).
     */
    fun framesLine(nowMs: Long, zone: TimeZone, callId: String?, profile: AudioProfile, f: FrameStats.Summary): String =
        "${time(nowMs, zone)} frames v=2" + (callId?.let { " call=${Redact.prefix(it)}" } ?: "") +
            " codec=${profile.codec} rate=${profile.rate} secs=${f.secs} sent=${f.sent} dropped=${f.dropped} recv=${f.received}" +
            " underruns=${f.underruns} maxgap=${f.maxGapMs}ms p95gap=${f.p95GapMs}ms qmin=${f.qMin} qmax=${f.qMax}"

    /**
     * The DEVICE line (DEV-811 P1): `audio dev call=hso-17...(10) first=40ms uppeak=-12 dnpeak=-9 upclip=0 dnclip=0 readmax=45ms aec=on ns=on
     * route=speaker why=none mark=none fail=none`. Peaks are dBFS; the clip counts are `n/a` at `pcmu` (a mu-law decode cannot reach 32767);
     * `why` says why 8 kHz was used (`none`, `compat`, `prep16`, `mark`); `mark` is the device's "wideband failed" state when the call began
     * (`none`, `temp`, `perm`). The fixed second word `dev` keeps the reporter from taking the line for a session end.
     */
    fun deviceLine(nowMs: Long, zone: TimeZone, r: FrameReport, route: String): String {
        val wide = r.profile.wideband
        return "${time(nowMs, zone)} audio dev" + (r.callId?.let { " call=${Redact.prefix(it)}" } ?: "") +
            " first=${if (r.firstMs >= 0) "${r.firstMs}ms" else "?"} uppeak=${DeviceStats.dbfs(r.upPeak)} dnpeak=${DeviceStats.dbfs(r.dnPeak)}" +
            " upclip=${if (wide) r.upClip.toString() else "n/a"} dnclip=${if (wide) r.dnClip.toString() else "n/a"}" +
            " readmax=${r.device.readMaxMs}ms aec=${word(r.device.aec)} ns=${word(r.device.ns)} route=${word(route)}" +
            " why=${tok(r.why)} mark=${tok(r.mark)} fail=${tok(r.fail)}"
    }

    /**
     * One call's audio facts (DEV-698), at [AudioPhase.START] (about 2 s after the audio connects) or [AudioPhase.END] (when its
     * media is closed, with the session's [why]). `up` and `down` read `codec/clockrate/packetsize` as the app's media reports them;
     * `sent`/`recv` count packets, `updrop`/`downdrop` the ones lost each way; `mic` is a level CLASS (silent, low, normal, clipped),
     * never samples. Anything the media could not tell is `?`, never 0. [muted] adds `muted=yes` (a silent mic may be the person's
     * doing). Only sanitised words, numbers and a shortened call id reach the line: no SDP, no candidate, no fingerprint, no token.
     */
    fun audioLine(
        nowMs: Long, zone: TimeZone, phase: AudioPhase, path: String, callId: String?,
        stats: AudioStats?, muted: Boolean, why: EndReason?,
    ): String =
        "${time(nowMs, zone)} audio ${phase.word} ${word(path)} call=${Redact.prefix(callId)} " +
            "up=${direction(stats?.up)} down=${direction(stats?.down)} " +
            "sent=${count(stats?.up?.packets)} updrop=${count(stats?.up?.lost)} " +
            "recv=${count(stats?.down?.packets)} downdrop=${count(stats?.down?.lost)} " +
            "mic=${stats?.mic?.word ?: "?"}" + (if (muted) " muted=yes" else "") +
            (why?.let { " why=${word(it.name.lowercase())}" } ?: "")

    /**
     * DEV-694: one line per successful connect, with where its time went ([ConnectTimeline.summary]: stage words and offsets
     * only), so a posted Journal carries the connect numbers without anyone reading Settings. [via] is `direct` or `tailnet`.
     */
    fun connectLine(nowMs: Long, zone: TimeZone, via: String, summary: String?): String =
        "${time(nowMs, zone)} net connect via=${word(via)}" + (summary?.let { " $it" } ?: "")

    /**
     * DEV-713: one live-video line. `live start cam=gate…(4) size=big stream=main ttff=840ms` (the first frame), `live fail cam=… size=big
     * why=connect retry=1 next=yes` (an attempt failed; [why] is a class word, [retry] the failures before it, [willRetry] whether another
     * follows) and `live stop cam=… size=big why=hidden secs=12` (closed on purpose). The camera id is shortened here; no SDP, no
     * candidate, no URL and no token ever reaches a line (only words from the app's own enums and small numbers). The away route
     * adds `route=rtsp stream=<go2rtc stream name>` (S1b; [rtspStream]), the name only: never the host, port or any credential.
     */
    fun liveLine(
        nowMs: Long, zone: TimeZone, step: LiveStep, cameraId: String, size: String,
        stream: String? = null, ttffMs: Long? = null, why: String? = null, retry: Int? = null, willRetry: Boolean? = null, playedSecs: Long? = null,
        rtspStream: String? = null,
    ): String =
        "${time(nowMs, zone)} live ${step.word} cam=${Redact.prefix(cameraId)} size=${word(size)}" +
            (rtspStream?.let { " route=rtsp stream=${streamName(it)}" } ?: "") + (stream?.let { " stream=${word(it)}" } ?: "") + (ttffMs?.let { " ttff=${it}ms" } ?: "") + (why?.let { " why=${word(it)}" } ?: "") +
            (retry?.let { " retry=$it" } ?: "") + (willRetry?.let { " next=${if (it) "yes" else "no"}" } ?: "") + (playedSecs?.let { " secs=$it" } ?: "")

    /** DEV-713 S2: `live thermal paused status=severe` / `live thermal resumed status=light`, one per transition. [status] is Android's `THERMAL_STATUS_*` number. */
    fun liveThermalLine(nowMs: Long, zone: TimeZone, hot: Boolean, statusWord: String): String =
        "${time(nowMs, zone)} live thermal ${if (hot) "paused" else "resumed"} status=${word(statusWord)}"

    /** DEV-713 S2: `live fault at=sync err=IllegalStateException`: live code threw where it is fenced off. Names only, never a message. */
    fun liveFaultLine(nowMs: Long, zone: TimeZone, at: String, error: String): String =
        "${time(nowMs, zone)} live fault at=${word(at)} err=${if (CLASS.matches(error)) error else "?"}"

    /** DEV-713: `connlog journal fault ex=IllegalStateException`: the Journal reporter threw (reported once per process). Not a live or audio line. */
    fun journalFaultLine(nowMs: Long, zone: TimeZone, e: Throwable): String =
        "${time(nowMs, zone)} connlog journal fault ex=${className(e)}"

    private val CODEC = Regex("[a-z0-9-]{1,12}")

    /** DEV-713 S1b: go2rtc's stream NAME (`gate_intercom_sub`), the only part of an RTSP address that reaches a line; anything but a plain name is `?`. */
    private val STREAM = Regex("[A-Za-z0-9_-]{1,64}")

    private fun streamName(s: String): String = if (STREAM.matches(s)) s else "?"

    private fun direction(d: AudioDirection?): String =
        if (d == null) "?" else "${d.codec?.takeIf { CODEC.matches(it) } ?: "?"}/${d.clockRateHz?.toString() ?: "?"}/${d.ptimeMs?.let { "${it}ms" } ?: "?"}"

    private fun count(n: Long?): String = n?.toString() ?: "?"

    /**
     * One hold, switch or resume step (M7). [doorRef] and [callId] are shortened here, never earlier; [result] is an enum
     * (a SwitchResult, an EndReason) and only its lowercase name is written; [ms] and [n] are plain numbers.
     */
    fun holdLine(nowMs: Long, zone: TimeZone, step: HoldStep, doorRef: String?, callId: String?, result: Enum<*>?, ms: Long?, n: Int?): String =
        "${time(nowMs, zone)} hold ${step.word}" +
            (doorRef?.let { " door=${Redact.prefix(it)}" } ?: "") + (callId?.let { " call=${Redact.prefix(it)}" } ?: "") +
            (result?.let { " result=${word(it.name.lowercase())}" } ?: "") + (ms?.let { " ms=$it" } ?: "") + (n?.let { " n=$it" } ?: "")

    // ---- pieces ----------------------------------------------------------------------------------------------------

    /** `Ring`, `Dismiss`, `Missed(reason)`, `Silent(reason)`, `Quiet`. */
    fun outcomeName(o: Outcome): String = when (o) {
        is Outcome.Ring -> "Ring"
        is Outcome.Dismiss -> "Dismiss"
        is Outcome.Missed -> "Missed(${o.reason.name.lowercase()})"
        is Outcome.Silent -> "Silent(${o.reason.name.lowercase()})"
        is Outcome.Quiet -> "Quiet"
    }

    private fun idsOf(o: Outcome): String {
        val door = when (o) {
            is Outcome.Ring -> o.doorRef
            is Outcome.Dismiss -> o.doorRef
            is Outcome.Missed -> o.doorRef
            is Outcome.Silent -> o.update?.doorRef
            is Outcome.Quiet -> null
        }
        val call = when (o) {
            is Outcome.Ring -> o.callId
            is Outcome.Dismiss -> o.callId
            is Outcome.Missed -> o.callId
            is Outcome.Silent -> o.update?.callId
            is Outcome.Quiet -> null
        }
        return (door?.let { " door=${Redact.prefix(it)}" } ?: "") + (call?.let { " call=${Redact.prefix(it)}" } ?: "")
    }

    private fun envText(e: PushEnv): String =
        "fg=${when (e.foreground) { true -> "fg"; false -> "bg"; null -> "?" }} fsi=${tri(e.fullScreen)} " +
            "chan=${word(e.ringChannel)}/${e.ringImportance ?: "?"} notif=${tri(e.notifications, "on", "off")} dnd=${word(e.dnd)}"

    private fun via(s: Source) = if (s == Source.WS) "ws" else "push"

    private fun sourceOf(m: PushMessage): Source = (m as? PushMessage.CallMessage)?.source ?: Source.FCM

    private fun kindWord(m: PushMessage) = when (m) {
        is PushMessage.Ring -> "ring"
        is PushMessage.Answered -> "answered"
        is PushMessage.Ended -> "ended"
        is PushMessage.Test -> "test"
        is PushMessage.Ignored -> "ignored"
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"
    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun tri(b: Boolean?, t: String = "yes", f: String = "no") = when (b) { true -> t; false -> f; null -> "?" }

    // ---- DEV-764: diagnostic events ------------------------------------------------------------------------------

    /** An id for a diag line: shortened by [Redact.prefix] and cut down to characters the server's journal rules accept. */
    class DiagId(val raw: String?)

    private val DIAG_WORDS = setOf("ui", "net", "ws", "wall")
    private val DIAG_EVENT = Regex("[a-z][a-z0-9-]{0,23}")
    private val DIAG_KEY = Regex("[a-z][a-z0-9]{0,11}")
    private val DIAG_VALUE = Regex("[a-z0-9_-]{1,32}")

    /**
     * DEV-764: one compact diagnostic event, `HH:mm:ss.SSS <word> <event> key=value ...`. [word] must be one of the journal words the server
     * reports (`ui`, `net`, `ws`; anything else becomes `ui`). Values: numbers, booleans (`yes`/`no`), a [DiagId] (shortened), or a lowercase word
     * of `a-z 0-9 _ -` up to 32 characters (anything else is `?`); a null value drops its key. Never free text, so no token, URL, address or SDP can get in.
     */
    fun diagLine(nowMs: Long, zone: TimeZone, word: String, event: String, fields: List<Pair<String, Any?>>): String {
        val sb = StringBuilder(time(nowMs, zone)).append(' ').append(if (word in DIAG_WORDS) word else "ui").append(' ')
        sb.append(if (DIAG_EVENT.matches(event)) event else "?")
        for ((k, v) in fields) {
            if (v == null || !DIAG_KEY.matches(k)) continue
            sb.append(' ').append(k).append('=')
            when (v) {
                is Boolean -> sb.append(yn(v))
                is Int, is Long -> sb.append(v.toString())
                is DiagId -> sb.append(Redact.prefix(v.raw).map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == '\u2026' || it == '(' || it == ')') it else '_' }.joinToString(""))
                is Enum<*> -> sb.append(diagValue(v.name.lowercase()))
                else -> sb.append(diagValue(v.toString()))
            }
        }
        return sb.toString()
    }

    private fun diagValue(s: String): String = if (DIAG_VALUE.matches(s)) s else "?"

    /** The talk-end path word: the session's own, with the leg's reason after a colon. */
    private val VIA = Regex("[a-z0-9_]{1,40}(:[a-z0-9_]{1,40})?")

    private val WORD = Regex("[a-z][a-z_ -]{0,23}")

    /** A producer's own short word (a reason, a channel state); anything else (it could hold anything) is `?`. */
    /** A short lowercase token that may hold digits (`prep16`); anything else is `?`. */
    private fun tok(s: String?): String = if (s != null && TOKEN.matches(s)) s else "?"

    private val TOKEN = Regex("[a-z][a-z0-9]{0,11}")

    private fun word(s: String?): String = if (s != null && WORD.matches(s)) s.replace(' ', '_') else "?"

    private val CLASS = Regex("[A-Za-z][A-Za-z0-9_$]{0,59}")

    /** An exception's class name only, never its message. */
    private fun className(e: Throwable): String = e.javaClass.simpleName.takeIf { CLASS.matches(it) } ?: "?"

    // ---- the second net --------------------------------------------------------------------------------------------

    private val URL = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S*")
    private val LONG_RUN = Regex("[A-Za-z0-9_-]{16,}")

    /**
     * One clean line from [raw]: control characters become spaces, anything URL-shaped becomes `<url>`, a run of 16 or more
     * id characters holding a digit (an id, a token, a code) or of 48 or more (anything) becomes `<id>`, and the line is
     * cut at [PushJournal.MAX_LINE]. The formatter never produces any of these; this is for a stored line from another
     * build and for a caller that bypasses the formatter.
     */
    fun scrub(raw: String): String {
        val noCtl = buildString(raw.length) { for (c in raw) append(if (c < ' ' || c == '\u007f') ' ' else c) }.trim()
        val noUrl = URL.replace(noCtl, "<url>")
        val noId = LONG_RUN.replace(noUrl) { m -> if (m.value.length >= 48 || m.value.any(Char::isDigit)) "<id>" else m.value }
        return noId.take(PushJournal.MAX_LINE)
    }
}
