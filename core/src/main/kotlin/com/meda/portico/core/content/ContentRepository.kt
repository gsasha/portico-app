package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentAction
import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentCommands.ErrorKind
import com.meda.portico.core.ha.ContentCommands.Outcome
import com.meda.portico.core.ha.ContentProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Where the last content profile survives a process death (the Android layer implements it with prefs). */
interface ContentStore {
    fun load(): ContentProfile?
    fun save(profile: ContentProfile)
    fun clear()
}

class InMemoryContentStore(initial: ContentProfile? = null) : ContentStore {
    var saved: ContentProfile? = initial
        private set
    var saves = 0
        private set

    override fun load(): ContentProfile? = saved
    override fun save(profile: ContentProfile) {
        saved = profile
        saves++
    }
    override fun clear() {
        saved = null
    }
}

/** What the last `portico/content/get` told us about the server's content support. */
enum class ContentSupport {
    /** No answer yet on this run (the cached profile, if any, is from an earlier one). */
    UNKNOWN,
    YES,

    /** An older portico-ha without the content commands. */
    NO,

    /** HA answered `unauthorized`: this phone's HA user is not an enrolled Portico phone. */
    NOT_ENROLLED,
}

/** How the last button press ended, for the one line under the buttons. */
data class RunReport(val actionId: String, val result: RunResult, val atMs: Long)

enum class RunResult { OK, NOT_CONNECTED, RATE_LIMITED, UNAVAILABLE, NOT_ALLOWED, FAILED }

/**
 * The phone's content, as the Home screen needs it: the last profile HA sent (kept across restarts so
 * the screen has something to show before the socket is up), the newest camera picture per camera
 * (memory only: a picture is never kept beyond the process, and never shown once the link is down),
 * the buttons being run, and the last press's result. Single writer (the link's thread), any reader.
 * [listen]ers are called on the writer's thread; the UI marshals to its own.
 *
 * Nothing here is secret: names, ids and availability. A press goes to HA by action id only.
 */
class ContentRepository(
    private val store: ContentStore,
    /** DEV-764: write-only observation: `ui talk-doc` when a Talk (or gate call) element's `available` / `reason` changes. */
    private val journal: com.meda.portico.core.push.PushJournal? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    constructor(store: ContentStore, clockMs: () -> Long) : this(store, null, clockMs)

    /** The last `available/reason` seen per Talk or gate-call element (this process). */
    private val talkSeen = HashMap<String, String>()

    private fun noteTalkChanges(fresh: ContentProfile) {
        val j = journal ?: return
        try {
            val now = LinkedHashMap<String, Pair<Boolean, String?>>()
            for (a in fresh.pageList.flatMap { it.actions } + fresh.callers.values.flatMap { it.ringButtons + it.callButtons }) if (a.isTalk || a.isCall) now[a.id] = a.available to a.reason
            for ((id, st) in now) {
                val word = (if (st.first) "yes" else "no") + "/" + (st.second ?: "none")
                val was = talkSeen.put(id, word)
                if (was != word) {
                    j.diag("ui", "talk-doc", "act" to com.meda.portico.core.push.PushJournalFormat.DiagId(id), "avail" to st.first, "reason" to (st.second ?: "none"), "was" to (was?.replace('/', '_') ?: "first"))
                }
            }
            val gone = talkSeen.keys.filter { it !in now }
            for (id in gone) {
                talkSeen.remove(id)
                j.diag("ui", "talk-doc", "act" to com.meda.portico.core.push.PushJournalFormat.DiagId(id), "gone" to true)
            }
        } catch (_: Throwable) {
        }
    }


    @Volatile
    var profile: ContentProfile = try {
        store.load() ?: ContentProfile.EMPTY
    } catch (_: Exception) {
        ContentProfile.EMPTY
    }
        private set

    @Volatile
    var support: ContentSupport = ContentSupport.UNKNOWN
        private set

    /** True once HA has answered `portico/content/get` on this run (so "no content yet" is a fact, not a blank). */
    @Volatile
    var answered: Boolean = false
        private set

    @Volatile
    var lastRun: RunReport? = null
        private set

    private val pictures = ConcurrentHashMap<String, ByteArray>()
    private val stills = StillGate(clockMs)
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unlisten(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun changed() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
                // a screen's refresh must not break the link
            }
        }
    }

    // ---- reads ---------------------------------------------------------------------------

    fun picture(cameraId: String): ByteArray? = pictures[cameraId]
    fun pictureIds(): Set<String> = pictures.keys.toSet()
    fun runningIds(): Set<String> = running.toSet()

    /** A camera or button of any page or any caller (the call screens' too), by id. */
    fun camera(id: String): ContentCamera? = profile.findCamera(id)
    fun action(id: String): ContentAction? = profile.findAction(id)

    /**
     * The UI is about to ask for a still of [cameraId]: false if one is already on its way (the guard is shared by every
     * screen), so there is never more than one request in flight per camera. The answer, whatever it is, ends it.
     */
    fun beginSnapshot(cameraId: String): Boolean = stills.tryBegin(cameraId)

    /** The request was never sent (the socket is down): free the camera for the next try. */
    fun endSnapshot(cameraId: String) = stills.end(cameraId)

    // ---- writes (the link's thread, and the UI's markRunning) ----------------------------

    fun onContent(outcome: Outcome) {
        when (outcome) {
            is Outcome.Profile -> {
                answered = true
                support = ContentSupport.YES
                val fresh = outcome.profile
                noteTalkChanges(fresh)
                if (fresh != profile) {
                    profile = fresh
                    try {
                        store.save(fresh)
                    } catch (_: Exception) {
                        // kept in memory; the next answer saves again
                    }
                    // A camera that left the document (any page, any caller) leaves with its picture.
                    pictures.keys.retainAll(fresh.cameraIds())
                }
            }
            is Outcome.Failed -> {
                answered = true
                when (outcome.kind) {
                    ErrorKind.UNKNOWN_COMMAND -> support = ContentSupport.NO
                    ErrorKind.UNAUTHORIZED -> support = ContentSupport.NOT_ENROLLED
                    else -> Unit // a transient failure keeps what we knew
                }
            }
            else -> return
        }
        changed()
    }

    fun onSnapshot(cameraId: String, outcome: Outcome) {
        stills.end(cameraId)
        when (outcome) {
            is Outcome.Snapshot -> {
                pictures[cameraId] = outcome.bytes
                com.meda.portico.core.traffic.TrafficTally.addVideo(outcome.bytes.size.toLong()) // A-10: a still is video traffic
            }
            is Outcome.Failed -> {
                // rate_limited says "I just sent you one": the picture we hold is still the newest.
                if (outcome.kind != ErrorKind.RATE_LIMITED) pictures.remove(cameraId)
            }
            else -> pictures.remove(cameraId) // lost: never leave an old picture to look live
        }
        changed()
    }

    /** The UI is about to send [actionId]: the button shows "running" until [onRun]. False if it is already running. */
    fun markRunning(actionId: String): Boolean {
        val added = running.add(actionId)
        if (added) changed()
        return added
    }

    fun onRun(actionId: String, outcome: Outcome) {
        running.remove(actionId)
        val result = when (outcome) {
            Outcome.Ran -> RunResult.OK
            Outcome.Lost -> RunResult.NOT_CONNECTED
            is Outcome.Failed -> when (outcome.kind) {
                ErrorKind.RATE_LIMITED -> RunResult.RATE_LIMITED
                ErrorKind.UNAVAILABLE, ErrorKind.NOT_AVAILABLE, ErrorKind.NO_DOOR, ErrorKind.NOT_CONNECTED, ErrorKind.BUSY -> RunResult.UNAVAILABLE
                ErrorKind.UNAUTHORIZED, ErrorKind.UNKNOWN_ACTION, ErrorKind.NOT_ALLOWED -> RunResult.NOT_ALLOWED
                else -> RunResult.FAILED
            }
            else -> RunResult.FAILED
        }
        lastRun = RunReport(actionId, result, clockMs())
        changed()
    }

    /** The send itself did not happen (not connected): same as a lost answer, without ever having asked. */
    fun onRunNotSent(actionId: String) = onRun(actionId, Outcome.Lost)

    /** A new enrollment (another HA or phone): nothing from the old one may stay on screen. */
    fun reset() {
        profile = ContentProfile.EMPTY
        support = ContentSupport.UNKNOWN
        answered = false
        lastRun = null
        pictures.clear()
        stills.clear()
        running.clear()
        try {
            store.clear()
        } catch (_: Exception) {
        }
        changed()
    }

    /** The socket closed: no answer is coming for anything in flight, and no picture should look live. */
    fun onDisconnected() {
        answered = false
        support = if (support == ContentSupport.YES) ContentSupport.UNKNOWN else support
        pictures.clear()
        stills.clear()
        // A press in flight is reported by the link as Lost; clearing here is only a backstop.
        running.clear()
        changed()
    }
}
