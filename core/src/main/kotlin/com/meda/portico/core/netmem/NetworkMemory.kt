package com.meda.portico.core.netmem

/** What is remembered about one network for the path hint. */
enum class Memory {
    /** Never seen, expired, or forgotten. */
    UNSEEN,

    /** Home Assistant answered over a verified, authenticated, private-peer direct session here. */
    HOME,

    /** Reachability failed on the direct path here, repeatedly, and the tailnet had to carry it. */
    AWAY,

    /** Flipped between HOME and AWAY too often (a key collision such as 192.168.0.0/24): no learning, direct first. */
    AMBIGUOUS,
}

/** Where [NetworkMemory] keeps its text across process death (the app: an encrypted store; tests: memory). */
interface NetMemoryStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * Which networks are home and which are not (DEV-694 Stage 2, design section 10; the owner's D5).
 * Keys come from [NetworkIdentity.key]. Pure and deterministic given the clock.
 *
 * Learning, and why it is shaped like this:
 * - HOME is proven only by [recordHomeProof] (the caller requires a verified `wss://` session to
 *   a private peer that ACCEPTED the token). One proof makes a network HOME at once.
 * - AWAY needs [recordStrike]s: a reachability failure on the direct path while the tailnet
 *   carried the connect. Strikes at least [MIN_STRIKE_GAP_MS] apart count (one router reboot or AP
 *   roam is ONE event). A network never seen needs 2; a HOME one needs 3, or 2 on different
 *   calendar days, and never within [HOME_PROOF_GRACE_DAYS] of its last proof (a flaky home
 *   router that is bad for an hour must not teach that the house is away).
 * - A network that flips between HOME and AWAY [AMBIGUOUS_FLIPS] times within
 *   [AMBIGUOUS_WINDOW_DAYS] becomes AMBIGUOUS: a hotel and a home sharing one address range. It
 *   then teaches and learns nothing.
 * - Bounded: [MAX_ENTRIES] (least recently seen go first), entries expire after [EXPIRE_DAYS].
 * - Stored times are minute-precision for strikes, a day for everything else: a movement log it is not.
 *
 * Thread-safe. A store that throws or holds garbage costs the memory, never the connect.
 */
class NetworkMemory(
    private val store: NetMemoryStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry(
        var current: Memory, // HOME or AWAY (the established verdict); UNSEEN while only strikes exist
        var lastSeenDay: Long,
        var lastHomeProofDay: Long, // 0 = never
        val strikes: MutableList<Long>, // epoch minutes
        val flipDays: MutableList<Long>,
        var ambiguous: Boolean,
    )

    private val lock = Any()

    fun lookup(key: String?): Memory = synchronized(lock) {
        if (key == null) return@synchronized Memory.UNSEEN
        val e = load().entries[key] ?: return@synchronized Memory.UNSEEN
        verdict(e)
    }

    /** A verified, authenticated, private-peer direct session on [key]: it is home. */
    fun recordHomeProof(key: String?) = synchronized(lock) {
        if (key == null) return@synchronized
        val m = load()
        val e = m.entries.getOrPut(key) { fresh() }
        val day = today()
        e.lastSeenDay = day
        if (e.ambiguous) return@synchronized save(m)
        e.lastHomeProofDay = day
        e.strikes.clear()
        flip(e, Memory.HOME, day)
        save(m)
    }

    /** The direct path failed for a reachability reason on [key] and the tailnet carried the connect. */
    fun recordStrike(key: String?) = synchronized(lock) {
        if (key == null) return@synchronized
        val m = load()
        val e = m.entries.getOrPut(key) { fresh() }
        val now = clockMs()
        val day = today()
        e.lastSeenDay = day
        if (e.ambiguous || verdict(e) == Memory.AWAY) return@synchronized save(m)
        val minute = now / 60_000
        if (e.strikes.isNotEmpty() && minute - e.strikes.last() < MIN_STRIKE_GAP_MS / 60_000) return@synchronized save(m) // the same event
        e.strikes.add(minute)
        while (e.strikes.size > MAX_STRIKES) e.strikes.removeAt(0)
        if (becomesAway(e, day)) {
            e.strikes.clear()
            flip(e, Memory.AWAY, day)
        }
        save(m)
    }

    /** "Forget learned networks" in Settings, and a new enrollment (a new home). */
    fun forget() = synchronized(lock) {
        try { store.write("") } catch (_: Exception) { }
    }

    // ---- rules ----

    private fun becomesAway(e: Entry, day: Long): Boolean {
        val gap = MIN_STRIKE_GAP_MS / 60_000
        val s = e.strikes.filterIndexed { i, v -> i == 0 || v - e.strikes[i - 1] >= gap }
        if (e.current != Memory.HOME) return s.size >= 2 // never seen (or only strikes): two events
        if (e.lastHomeProofDay != 0L && day - e.lastHomeProofDay < HOME_PROOF_GRACE_DAYS) return false
        val days = s.map { it / 1440 }.toSet()
        return s.size >= 3 || (s.size >= 2 && days.size >= 2)
    }

    private fun flip(e: Entry, to: Memory, day: Long) {
        if (e.current == to) return
        val was = e.current
        e.current = to
        if (was == Memory.UNSEEN) return // establishing a first verdict is not a flip
        e.flipDays.add(day)
        e.flipDays.removeAll { day - it > AMBIGUOUS_WINDOW_DAYS }
        if (e.flipDays.size >= AMBIGUOUS_FLIPS) e.ambiguous = true
    }

    private fun verdict(e: Entry): Memory = when {
        e.ambiguous -> Memory.AMBIGUOUS
        e.current == Memory.HOME || e.current == Memory.AWAY -> e.current
        else -> Memory.UNSEEN
    }

    private fun fresh() = Entry(Memory.UNSEEN, today(), 0L, mutableListOf(), mutableListOf(), false)
    private fun today() = clockMs() / DAY_MS

    // ---- persistence: one line per network, `key|cur|seen|proof|strikes,|flips,|amb` ----

    private class Book(val entries: LinkedHashMap<String, Entry>)

    private fun load(): Book {
        val raw = try { store.read() } catch (_: Exception) { null }
        val out = LinkedHashMap<String, Entry>()
        val day = today()
        for (line in (raw ?: "").lineSequence()) {
            val p = line.split('|')
            if (p.size != 7 || !KEY.matches(p[0])) continue
            val cur = when (p[1]) { "H" -> Memory.HOME; "A" -> Memory.AWAY; else -> Memory.UNSEEN }
            val seen = p[2].toLongOrNull() ?: continue
            if (day - seen > EXPIRE_DAYS) continue
            out[p[0]] = Entry(cur, seen, p[3].toLongOrNull() ?: 0L, nums(p[4]), nums(p[5]), p[6] == "1")
        }
        return Book(out)
    }

    private fun save(b: Book) {
        val keep = b.entries.entries.sortedByDescending { it.value.lastSeenDay }.take(MAX_ENTRIES)
        val text = keep.joinToString("\n") { (k, e) ->
            val c = when (e.current) { Memory.HOME -> "H"; Memory.AWAY -> "A"; else -> "U" }
            "$k|$c|${e.lastSeenDay}|${e.lastHomeProofDay}|${e.strikes.joinToString(",")}|${e.flipDays.joinToString(",")}|${if (e.ambiguous) 1 else 0}"
        }
        try { store.write(text) } catch (_: Exception) { }
    }

    private fun nums(s: String): MutableList<Long> = s.split(',').mapNotNull { it.toLongOrNull() }.toMutableList()

    companion object {
        const val MIN_STRIKE_GAP_MS = 10L * 60 * 1000
        const val HOME_PROOF_GRACE_DAYS = 7L
        const val AMBIGUOUS_FLIPS = 2
        const val AMBIGUOUS_WINDOW_DAYS = 30L
        const val EXPIRE_DAYS = 60L
        const val MAX_ENTRIES = 64
        const val MAX_STRIKES = 8
        private const val DAY_MS = 86_400_000L
        private val KEY = Regex("[0-9a-f]{16}")
    }
}
