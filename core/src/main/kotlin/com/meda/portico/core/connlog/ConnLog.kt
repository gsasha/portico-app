package com.meda.portico.core.connlog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** The default network, as a word. Pure values: nothing identifying (no SSID, address or name). */
enum class NetClass(val word: String) { WIFI("wifi"), ETHERNET("ethernet"), CELLULAR("cellular"), VPN("vpn"), NONE("none"), OTHER("other") }

/**
 * The phone's default network at one moment (DEV-705). [nid] is an app-local counter that changes when Android hands out a
 * new network object, so a Wi-Fi to Wi-Fi roam (same kind, new network) is visible without saying which network.
 */
data class NetState(
    val def: NetClass,
    val wifi: Boolean,
    val cell: Boolean,
    val vpn: Boolean,
    val validated: Boolean,
    val nid: Int = 0,
)

enum class TriggerKind(val word: String) { NET_CHANGE("net_change"), APP_START("app_start"), RECONNECT("reconnect"), WAKE("wake"), USER("user") }

enum class ConnResult(val word: String) { CONNECTED("connected"), NO_NETWORK("no_network"), GAVE_UP("gave_up"), SUPERSEDED("superseded") }

/** What the phone was doing when a sequence began: "slow" is often "just woken from Doze with a cold process", not the network. */
data class ConnEnv(
    val fg: Boolean = false,
    val doze: Boolean = false,
    val saver: Boolean = false,
    val metered: Boolean = false,
    val procMs: Long = 0,
    val cold: Boolean = false,
    val paused: Boolean = false,
)

/**
 * The connection log's assembler (DEV-705, `portico_connlog_design.md` sections 2-3). A SEQUENCE is one episode from a
 * trigger (a network change, an app start, a reconnect, the user) to its resolution (connected, no network, gave up,
 * superseded). While one is open, further network changes and connect events extend it; [resolve] turns it into one
 * compact JSON report and hands it to [sink], and nothing is open any more.
 *
 * - Offsets (`t`) are milliseconds from the sequence's start: the phone's wall clock is wrong sometimes, offsets are not.
 * - Bounded: at most [MAX_EVENTS] events and [MAX_NET] network entries; the rest are counted in `dropped`.
 * - A sequence open for more than [STALE_MS] with no activity is closed (`no_network` if the last network was none, else
 *   `superseded`) when the next trigger arrives.
 * - TOTAL: no method throws; a failure costs a report, never a connect. Thread-safe (the connect worker, the tailnet
 *   worker and the connectivity thread all call it).
 * - Only fixed words and numbers go in. [event] names and [hint] text come from the app's own vocabularies.
 */
class ConnLog(
    private val appVersion: String,
    private val sdk: Int,
    private val env: () -> ConnEnv,
    private val sink: (String) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(24) },
) {
    private class Open(
        val id: String,
        val t0: Long,
        val kind: TriggerKind,
        val from: NetState?,
        val to: NetState?,
        val env: ConnEnv,
    ) {
        val net = ArrayList<JsonObject>()
        val events = ArrayList<JsonObject>()
        var dropped = 0
        var hint: JsonObject? = null
        var tsnet: String? = null
        var path: String? = null
        var lastActivity = t0
        var lastNet: NetState? = to
    }

    private val lock = Any()
    private var open: Open? = null

    /** Finished reports wait here under the lock and go to [sink] after it is released: the sink does disk and JSON work. */
    private val outbox = ArrayList<String>()

    private fun flush() {
        val ready = synchronized(lock) {
            if (outbox.isEmpty()) return
            outbox.toList().also { outbox.clear() }
        }
        for (json in ready) {
            try {
                sink(json)
            } catch (_: Exception) {
                // a throwing sink costs the report, never the caller
            }
        }
    }

    /** The last network the log saw, so a change can be reported as from-to even when no sequence is open. */
    @Volatile
    var lastKnownNet: NetState? = null
        private set

    fun isOpen(): Boolean = synchronized(lock) { open != null }

    /** The id of the open sequence, or null: a watchdog compares it to be sure it is still talking about the same one. */
    fun openId(): String? = synchronized(lock) { open?.id }

    /** The class of the last network seen in the open sequence (or of the last known one with none open). */
    fun lastNetClass(): NetClass? = synchronized(lock) { open?.lastNet?.def ?: lastKnownNet?.def }

    /** Remember [to] as the current network without starting a sequence (nothing is going to connect). */
    fun noteNet(to: NetState) = guard { synchronized(lock) { lastKnownNet = to } }

    /**
     * The default network changed to [to]. With a sequence open it is an entry of that sequence; otherwise it starts one.
     * A call that changes nothing ([to] equal to the last state seen) is ignored.
     */
    fun netChanged(to: NetState) = guard {
        synchronized(lock) {
            val from = lastKnownNet
            if (from == to) return@synchronized
            lastKnownNet = to
            val o = open
            if (o != null && !stale(o)) {
                addNet(o, to)
                touch(o)
            } else {
                closeStale(o)
                begin(TriggerKind.NET_CHANGE, from, to)
            }
        }
    }

    /** The network the log starts from, with no sequence (the first state a process sees). */
    fun seedNet(state: NetState) = guard { synchronized(lock) { if (lastKnownNet == null) lastKnownNet = state } }

    /**
     * A non-network trigger. With a sequence already open it is an event of that sequence, except [TriggerKind.APP_START],
     * which supersedes it.
     */
    fun trigger(kind: TriggerKind) = guard {
        synchronized(lock) {
            val o = open
            if (o != null && !stale(o) && kind != TriggerKind.APP_START) {
                addEvent(o, "trigger_" + kind.word, null, null)
                touch(o)
                return@synchronized
            }
            if (o != null) finish(o, if (stale(o)) staleResult(o) else ConnResult.SUPERSEDED)
            begin(kind, lastKnownNet, lastKnownNet)
        }
    }

    /** A step of the connect (the connector's marks, the socket's open, auth, close, retry...). [cls] a fixed word; [n] a number (a wait in ms...). */
    fun event(name: String, cls: String? = null, n: Long? = null) = guard {
        synchronized(lock) {
            val o = open ?: return@synchronized
            addEvent(o, name, cls, n)
            touch(o)
        }
    }

    fun hint(mode: String, why: String, mobile: Boolean) = guard {
        synchronized(lock) {
            val o = open ?: return@synchronized
            o.hint = buildJsonObject {
                put("mode", mode.take(24))
                put("why", why.take(120))
                put("mobile", if (mobile) 1 else 0)
            }
        }
    }

    fun tsnet(text: String) = guard { synchronized(lock) { open?.tsnet = text.take(120) } }

    fun path(word: String) = guard { synchronized(lock) { open?.path = word.take(12) } }

    /** Close the open sequence with [result] and emit its report. Nothing open: nothing happens. */
    fun resolve(result: ConnResult) = guard {
        synchronized(lock) {
            val o = open ?: return@synchronized
            finish(o, result)
        }
    }

    // ---- internals (under lock) ----

    private fun begin(kind: TriggerKind, from: NetState?, to: NetState?) {
        val now = clockMs()
        val o = Open(newId(), now, kind, from, to, runCatching(env).getOrDefault(ConnEnv()))
        if (to != null) o.net.add(netEntry(0, to))
        open = o
    }

    private fun touch(o: Open) {
        o.lastActivity = clockMs()
    }

    private fun stale(o: Open): Boolean = clockMs() - o.lastActivity > STALE_MS

    private fun staleResult(o: Open): ConnResult = if (o.lastNet?.def == NetClass.NONE) ConnResult.NO_NETWORK else ConnResult.SUPERSEDED

    private fun closeStale(o: Open?) {
        if (o != null) finish(o, staleResult(o))
    }

    private fun offset(o: Open): Long = (clockMs() - o.t0).coerceAtLeast(0)

    private fun addNet(o: Open, s: NetState) {
        o.lastNet = s
        if (o.net.size >= MAX_NET) { // keep how it began and the latest, drop from between
            o.net.removeAt(HEAD_NET)
            o.dropped++
        }
        o.net.add(netEntry(offset(o), s))
    }

    private fun addEvent(o: Open, name: String, cls: String?, n: Long?) {
        if (o.events.size >= MAX_EVENTS) { // a retry loop must not lose how it ended: drop the oldest of the tail
            o.events.removeAt(HEAD_EVENTS)
            o.dropped++
        }
        o.events.add(
            buildJsonObject {
                put("t", offset(o))
                put("e", name.take(32))
                if (cls != null) put("cls", cls.take(32))
                if (n != null) put("n", n)
            },
        )
    }

    private fun netEntry(t: Long, s: NetState) = buildJsonObject {
        put("t", t)
        put("def", s.def.word)
        put("wifi", if (s.wifi) 1 else 0)
        put("cell", if (s.cell) 1 else 0)
        put("vpn", if (s.vpn) 1 else 0)
        put("val", if (s.validated) 1 else 0)
        put("nid", s.nid)
    }

    private fun finish(o: Open, result: ConnResult) {
        open = null
        fit(o)
        outbox.add(render(o, result).toString())
    }

    /**
     * The server keeps a report only up to 4 KB. A busy sequence can exceed that, so the MIDDLE of its events (then of its
     * network entries) is dropped until it fits, keeping how it began and how it ended; the drops are counted.
     */
    private fun fit(o: Open) {
        var guard = 0
        while (render(o, ConnResult.CONNECTED).toString().length > FIT_CHARS && guard++ < 200) {
            when {
                o.events.size > KEEP_MIN_EVENTS -> o.events.removeAt(o.events.size / 2)
                o.net.size > KEEP_MIN_NET -> o.net.removeAt(o.net.size / 2)
                else -> return
            }
            o.dropped++
        }
    }

    private fun render(o: Open, result: ConnResult): JsonObject {
        return buildJsonObject {
            put("v", 1)
            put("id", o.id)
            put("app", appVersion.take(32))
            put("sdk", sdk)
            put("t0", o.t0)
            put("dur", (clockMs() - o.t0).coerceAtLeast(0))
            put("result", result.word)
            put(
                "trigger",
                buildJsonObject {
                    put("kind", o.kind.word)
                    o.from?.let { put("from", it.def.word) }
                    o.to?.let {
                        put("to", it.def.word)
                        put("validated", it.validated)
                    }
                },
            )
            put(
                "env",
                buildJsonObject {
                    put("fg", o.env.fg)
                    put("doze", o.env.doze)
                    put("saver", o.env.saver)
                    put("metered", o.env.metered)
                    put("proc_ms", o.env.procMs)
                    put("cold", o.env.cold)
                    put("paused", o.env.paused)
                },
            )
            put("net", JsonArray(o.net))
            o.hint?.let { put("hint", it) }
            put("ev", JsonArray(o.events))
            o.tsnet?.let { put("tsnet", it) }
            o.path?.let { put("path", it) }
            if (o.dropped > 0) put("dropped", o.dropped)
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // a log must never disturb a connect
        }
        flush()
    }

    companion object {
        const val MAX_EVENTS = 60
        const val MAX_NET = 20
        private const val HEAD_EVENTS = 20
        private const val HEAD_NET = 4
        const val STALE_MS = 30L * 60 * 1000

        /** portico-ha rejects a report over 4096 characters; stay under it. */
        const val FIT_CHARS = 3900
        private const val KEEP_MIN_EVENTS = 8
        private const val KEEP_MIN_NET = 4
    }
}
