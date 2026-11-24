package com.meda.portico.core.ring

/**
 * What a PendingIntent does. `Intent.filterEquals` ignores extras, so two PendingIntents to the
 * same component are the SAME object unless their request code (or action) differs, and
 * `FLAG_UPDATE_CURRENT` then rewrites one with the other's extras. Every PendingIntent Portico
 * makes takes its request code from [RequestCodes]; nothing else may invent one.
 */
enum class PiAction(val offset: Int) {
    /** The ring's full-screen intent, also its content intent (opens `RingActivity`). */
    FULL_SCREEN(0),

    /** CallStyle's answer button: opens `RingActivity`, whose Answer button starts the answer. */
    ANSWER(1),

    /** CallStyle's decline button: stop ringing on this phone (`RingReceiver`). */
    DECLINE(2),

    /** The AlarmManager backstop that cancels the ring at `ringUntilMs` (`RingReceiver`). */
    EXPIRE(3),
}

/**
 * The one registry of PendingIntent request codes and notification ids (P2 plan §3.4; memory
 * `pendingintent-identity-ignores-extras`).
 *
 * Codes are per DOOR slot and action, not per call: a door has at most one live call, and a new
 * call on a door replaces that door's ring notification, so its PendingIntents are meant to be
 * replaced too (the call id rides in the extras and the receiver checks it). Two doors never share
 * a slot ([DoorSlots]), so no action of one door can be rewritten by another door's.
 */
object RequestCodes {
    /** Doors that can hold a slot at once. More than any one Home Assistant has door devices. */
    const val SLOTS = 32

    /** Room for every [PiAction] in a slot, with spare. */
    const val STRIDE = 8

    const val PI_BASE = 10_000
    const val NOTIFICATION_BASE = 20_000

    /** The `kind: test` notification: not a door call, one at a time. */
    const val TEST_NOTIFICATION_ID = 19_999

    /** Pause: the auto-resume alarm, the paused notification's Resume action, and its content intent (Setup). */
    const val PAUSE_ALARM = 9_000
    const val PAUSE_RESUME = 9_001
    const val PAUSE_OPEN = 9_002

    /** Pause: the one silent, ongoing "Portico is paused" notification. */
    const val PAUSE_NOTIFICATION_ID = 19_998

    /** Answering: the in-call notification's content intent (opens `InCallActivity`) and its Hang up action. */
    const val CALL_OPEN = 9_010
    const val CALL_HANGUP = 9_011

    /** Answering: the one ongoing "On a call" notification of `CallService`. */
    const val CALL_NOTIFICATION_ID = 19_997

    /** Stay connected: the ongoing notification's content intent (opens Home), and its one notification id. */
    const val CONNECTION_OPEN = 9_020
    const val CONNECTION_NOTIFICATION_ID = 19_996

    /** A HOUSE ring (M7 3b-B): the one notification, its full-screen/content intent, Answer and Decline. A house call has no door slot: it is one at a time. */
    const val HOUSE_RING_FULL = 9_030
    const val HOUSE_RING_ANSWER = 9_031
    const val HOUSE_RING_DECLINE = 9_032
    const val HOUSE_RING_NOTIFICATION_ID = 19_995

    fun requestCode(slot: Int, action: PiAction): Int {
        require(slot in 0 until SLOTS) { "slot $slot out of range" }
        return PI_BASE + slot * STRIDE + action.offset
    }

    fun ringNotificationId(slot: Int): Int {
        require(slot in 0 until SLOTS) { "slot $slot out of range" }
        return NOTIFICATION_BASE + slot * 2
    }

    fun statusNotificationId(slot: Int): Int {
        require(slot in 0 until SLOTS) { "slot $slot out of range" }
        return NOTIFICATION_BASE + slot * 2 + 1
    }
}

/** Where [DoorSlots] keeps its table across process deaths (a cold FCM process must agree). */
interface DoorSlotStore {
    fun load(): String?
    fun save(encoded: String)
}

class InMemoryDoorSlotStore(var value: String? = null) : DoorSlotStore {
    override fun load(): String? = value
    override fun save(encoded: String) {
        value = encoded
    }
}

/**
 * Assigns each `door_ref` a stable slot in `0 until RequestCodes.SLOTS`: the first free slot on
 * first sight, the same slot ever after, persisted. When every slot is taken, the least recently
 * used door gives up its slot. Deterministic for a given history; thread-safe.
 */
class DoorSlots(private val store: DoorSlotStore) {
    private val lock = Any()

    // slot -> door, and a use counter per slot for LRU.
    private val doors = arrayOfNulls<String>(RequestCodes.SLOTS)
    private val used = LongArray(RequestCodes.SLOTS)
    private var counter = 0L

    init {
        // Encoded as lines "slot\tuse\tdoorRef" (door refs match the contract's ID_RE: no tab, no newline).
        val text = try {
            store.load()
        } catch (e: Exception) {
            null
        }
        text?.lineSequence()?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@forEach
            val slot = parts[0].toIntOrNull() ?: return@forEach
            val use = parts[1].toLongOrNull() ?: return@forEach
            val door = parts[2]
            if (slot !in 0 until RequestCodes.SLOTS || door.isEmpty() || doors.contains(door) || doors[slot] != null) return@forEach
            doors[slot] = door
            used[slot] = use
            counter = maxOf(counter, use)
        }
    }

    fun slotFor(doorRef: String): Int = synchronized(lock) {
        var slot = doors.indexOf(doorRef)
        if (slot < 0) {
            slot = doors.indexOf(null)
            if (slot < 0) slot = used.indices.minByOrNull { used[it] }!!
            doors[slot] = doorRef
        }
        used[slot] = ++counter
        try {
            store.save(encode())
        } catch (e: Exception) {
            // Kept in memory; the next save retries. A slot must never cost a ring.
        }
        slot
    }

    private fun encode(): String = doors.indices
        .filter { doors[it] != null }
        .joinToString("\n") { "$it\t${used[it]}\t${doors[it]}" }

    /** The slot [doorRef] holds, without assigning one. */
    fun peek(doorRef: String): Int? = synchronized(lock) { doors.indexOf(doorRef).takeIf { it >= 0 } }
}
