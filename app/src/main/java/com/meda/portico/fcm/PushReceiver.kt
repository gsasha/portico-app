package com.meda.portico.fcm

import com.meda.portico.core.call.HousePush
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.push.CallStore
import com.meda.portico.core.push.Contract
import com.meda.portico.core.push.Decision
import com.meda.portico.core.push.Outcome
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.PushLog
import com.meda.portico.core.push.PushMessage
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegValues

/**
 * The push receive path (P2 plan §3.3), without Android: an FCM data map (data-only message,
 * every value a string) -> [PushMessage.parse] -> [Decision.receive] on the shared store with
 * the PERSISTED doors cache's door refs (a cold process must not silence a real ring) -> the
 * journal line -> [OutcomeDispatcher]. The record is durable before the dispatcher runs.
 */
class PushReceiver(
    private val store: CallStore,
    private val knownDoorRefs: () -> Set<String>,
    private val dispatcher: OutcomeDispatcher,
    private val log: HaLog,
    /** Pause: when the last pause ended (0 if never). A ring whose call began before it is not rung. */
    private val resumedAt: () -> Long = { 0L },
    /** The push and ring journal (0.5.12-alpha): write-only observation, told what was decided; never read here. */
    private val journal: PushJournal? = null,
    /**
     * M7 3b-B: a house ring's push (`ct: house`). True only while house calls are on for this phone AND it is set to receive them;
     * otherwise a house message takes the door path and is ignored exactly as before (`UNKNOWN_KIND`, silent).
     */
    private val houseOn: () -> Boolean = { false },
    private val onHouse: (HousePush, Long) -> Unit = { _, _ -> },
    /** DEV-807 (Panel mode): false for a door this panel's screen does not ring for. Always true off Panel mode. */
    private val doorTaken: (String) -> Boolean = { true },
) {
    /** Returns the outcome, or null if deciding failed or the message was a house one (it never reaches the door decision). */
    fun onMessage(
        data: Map<String, String?>,
        nowMs: Long,
        priority: Int = PRIORITY_UNKNOWN,
        originalPriority: Int = PRIORITY_UNKNOWN,
    ): Outcome? {
        if (houseOn()) {
            val house = HousePush.parse(data)
            if (house != null) {
                try {
                    onHouse(house, nowMs)
                } catch (e: Exception) {
                    log.log(HaLog.Level.ERROR, "portico push: a house message failed: ${e.javaClass.simpleName}")
                }
                return null
            }
        }
        val msg = PushMessage.parse(data)
        val outcome = try {
            val resumed = try {
                resumedAt()
            } catch (e: Exception) {
                0L // fail open: an unreadable resume time withholds nothing
            }
            Decision.receive(store, msg, nowMs, knownDoorRefs(), resumed, doorTaken)
        } catch (e: Exception) {
            log.log(HaLog.Level.ERROR, "portico push: deciding failed: ${e.javaClass.simpleName}")
            observe { it.decideFailed(msg, nowMs, e) }
            return null
        }
        log.log(HaLog.Level.INFO, "${PushLog.decision(msg, outcome)} ${priorityNote(priority, originalPriority)}")
        observe { it.decided(msg, outcome, nowMs, try { knownDoorRefs().size } catch (e: Exception) { null }) }
        dispatcher.dispatch(outcome)
        return outcome
    }

    /** Journal calls never throw into the receive path (the journal catches too; this is the second belt). */
    private inline fun observe(block: (PushJournal) -> Unit) {
        val j = journal ?: return
        try {
            block(j)
        } catch (e: Throwable) {
            // write-only observation
        }
    }

    companion object {
        /** `RemoteMessage.PRIORITY_*` values, mirrored so this class has no Firebase import. */
        const val PRIORITY_UNKNOWN = 0
        const val PRIORITY_HIGH = 1
        const val PRIORITY_NORMAL = 2

        /**
         * Spec §4.4 / plan §3.3: journal the delivered and the original priority; a HIGH message
         * delivered as NORMAL was demoted by FCM (too few visible notifications), a P4 diagnosis.
         */
        fun priorityNote(priority: Int, original: Int): String {
            fun name(p: Int) = when (p) {
                PRIORITY_HIGH -> "high"
                PRIORITY_NORMAL -> "normal"
                else -> "unknown"
            }
            return if (original == PRIORITY_HIGH && priority == PRIORITY_NORMAL) {
                "priority DEMOTED high->normal"
            } else {
                "priority=${name(priority)}"
            }
        }
    }
}

/**
 * Everything `PorticoMessagingService` does, minus Android, so the JVM tests drive the real
 * service path: the registration callbacks become [RegValue]s of the right kind (journalled as
 * `sha256[:8]` only) and go to [deliver] (the app posts them onto the link's thread); a message
 * goes to [PushReceiver]. Never throws.
 *
 * Pause: while [paused], EVERY message (any kind, garbage included) is dropped here, the first
 * thing the service does with it: no decision, no call-store write, no dispatcher (so no socket
 * wake and no backstop alarm), no notification; one log line naming only the kind. FAIL OPEN: a
 * flag that cannot be read is "not paused". A registration rotation still goes to [deliver]: the
 * machine stores it, and with the socket closed it reaches HA on the resume's first connect.
 */
class MessagingServiceLogic(
    private val receiver: PushReceiver,
    private val deliver: (RegValue) -> Unit,
    private val installationId: () -> String?,
    private val log: HaLog,
    private val paused: () -> Boolean = { false },
    private val journal: PushJournal? = null,
) {
    fun onRegistered(value: String) = take("onRegistered") { RegValues.fromOnRegistered(value) }

    fun onNewToken(value: String) = take("onNewToken") { RegValues.fromOnNewToken(value) }

    fun onMessage(data: Map<String, String?>, nowMs: Long, priority: Int = 0, originalPriority: Int = 0) {
        val drop = try {
            paused()
        } catch (e: Exception) {
            false
        }
        if (drop) {
            log.log(HaLog.Level.INFO, "portico push: dropped (paused) kind=${kindForLog(data)}")
            try {
                journal?.dropped(kindForLog(data), "paused")
            } catch (e: Throwable) {
                // write-only observation
            }
            return
        }
        try {
            receiver.onMessage(data, nowMs, priority, originalPriority)
        } catch (e: Exception) {
            log.log(HaLog.Level.ERROR, "portico push: onMessageReceived failed: ${e.javaClass.simpleName}")
        }
    }

    /** The contract's own kind words only; anything else (it could hold anything) is "other". */
    private fun kindForLog(data: Map<String, String?>): String = try {
        data["kind"]?.takeIf { it in LOGGABLE_KINDS } ?: "other"
    } catch (e: Exception) {
        "other"
    }

    private inline fun take(callback: String, make: () -> RegValue) {
        try {
            val v = make()
            val id = try { installationId() } catch (e: Exception) { null }
            log.log(HaLog.Level.INFO, "$callback: ${PushLog.registration(v.kind.wire, v.value, id)}")
            deliver(v)
        } catch (e: Exception) {
            log.log(HaLog.Level.ERROR, "portico push: $callback failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        val LOGGABLE_KINDS = setOf(Contract.KIND_RING, Contract.KIND_ANSWERED, Contract.KIND_ENDED, Contract.KIND_TEST)
    }
}
