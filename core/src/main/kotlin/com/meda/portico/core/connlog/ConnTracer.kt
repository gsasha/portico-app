package com.meda.portico.core.connlog

import com.meda.portico.core.push.PushJournal

/**
 * The connection log's glue (DEV-705), pure so every scenario is a JVM test: it turns the socket client's steps and the
 * network watcher's changes into [ConnLog] triggers, events and resolutions, and decides when the sender should run.
 *
 * The rules, each a defect an independent review found:
 * - a CONNECTED socket kicks the sender (about [KICK_AFTER_CONNECT_MS] later, not in the middle of the connect it describes);
 * - a network change that opens a sequence while the socket is already connected is closed by a watchdog if the socket
 *   survives it ([WATCHDOG_MS]); one that opens while NOTHING is trying to connect (stopped, a terminal state) is closed too;
 * - a network change while the link is in a terminal state (paused, auth rejected, signed out, not configured, URL refused)
 *   starts nothing: no connect will follow;
 * - the network going to NONE for [NO_NETWORK_AFTER_MS] resolves the open sequence as `no_network`, so an outage is its own
 *   report and the return of signal starts a fresh one ("no signal to signal");
 * - reasons are whitelisted by [ReasonWords].
 *
 * [schedule] runs a task later (the app: an executor); [kick] asks the sender to run after a delay. Total: nothing here throws.
 */
class ConnTracer(
    private val log: ConnLog,
    private val path: () -> String?,
    private val kick: (delayMs: Long) -> Unit,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    /** DEV-764: the journal the link's steps go to (`net link-*`): the socket client's events, wake triggers and network changes. Observation only. */
    private val journal: PushJournal? = null,
) {
    private fun diag(event: String, vararg fields: Pair<String, Any?>) {
        try {
            journal?.diag("net", event, fields.toList())
        } catch (_: Exception) {
        }
    }

    @Volatile
    private var state = ""

    /** The default network changed. */
    fun netChanged(to: NetState) {
        try {
            diag("link-net", "def" to to.def.word, "wifi" to to.wifi, "cell" to to.cell, "vpn" to to.vpn, "val" to to.validated, "nid" to to.nid, "state" to state.ifEmpty { null })
            if (state in TERMINAL) {
                log.noteNet(to) // no connect will follow: remember the network, open nothing
                return
            }
            val wasOpen = log.isOpen()
            log.netChanged(to)
            val id = log.openId() ?: return
            if (!wasOpen) {
                schedule(WATCHDOG_MS) { watchdog(id) }
            }
            if (to.def == NetClass.NONE) {
                schedule(NO_NETWORK_AFTER_MS) {
                    if (log.openId() == id && log.lastNetClass() == NetClass.NONE) log.resolve(ConnResult.NO_NETWORK)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** A step of the socket client ([event], an optional class word [cls], an optional number [n]). */
    fun trace(event: String, cls: String?, n: Long?) {
        try {
            // DEV-764: the socket client's own steps, as the journal's words (a close reason through the same whitelist the report uses)
            diag("sock-" + event.replace('_', '-'), "cls" to (if (event == "closed") ReasonWords.of(cls) else cls), "n" to n)
            when (event) {
                "state" -> onState(cls ?: "")
                "closed" -> {
                    if (state == "connected") log.trigger(TriggerKind.RECONNECT) // an established socket was lost
                    log.event("closed", ReasonWords.of(cls))
                }
                "reconnect_requested" -> log.trigger(TriggerKind.USER)
                "watchdog" -> { // DEV-764 aftermath: the link went silent while "connected"; the socket is dropped and rebuilt at once
                    log.trigger(TriggerKind.RECONNECT)
                    log.event(event, cls, n)
                }
                else -> log.event(event, cls, n)
            }
        } catch (_: Exception) {
        }
    }

    private fun onState(now: String) {
        state = now
        if (now == "connecting" && !log.isOpen()) log.trigger(TriggerKind.WAKE) // a start nothing else explained
        log.event("state", now)
        when (now) {
            "connected" -> {
                log.path(path() ?: "unknown")
                log.event("connected")
                log.resolve(ConnResult.CONNECTED)
                kick(KICK_AFTER_CONNECT_MS)
            }
            in TERMINAL -> log.resolve(ConnResult.GAVE_UP)
            "stopped" -> log.resolve(ConnResult.SUPERSEDED)
        }
    }

    /** [WATCHDOG_MS] after a network change opened sequence [id]: is anything still going to close it? */
    private fun watchdog(id: String) {
        try {
            if (log.openId() != id) return
            when {
                state == "connected" -> log.resolve(ConnResult.CONNECTED) // the socket survived the change
                state == "connecting" || state == "waiting_to_retry" -> schedule(WATCHDOG_MS) { watchdog(id) } // still trying
                else -> log.resolve(ConnResult.SUPERSEDED) // nothing is trying to connect (stopped, paused...)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val KICK_AFTER_CONNECT_MS = 3_000L
        const val WATCHDOG_MS = 10_000L
        const val NO_NETWORK_AFTER_MS = 120_000L
        val TERMINAL = setOf("auth_rejected", "url_refused", "signed_out", "not_configured", "paused")
    }
}
