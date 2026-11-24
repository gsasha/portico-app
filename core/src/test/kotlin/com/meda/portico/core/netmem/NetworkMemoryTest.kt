package com.meda.portico.core.netmem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class NetworkMemoryTest {
    private class MemStore : NetMemoryStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private val min = 60_000L
    private val day = 86_400_000L
    private var now = 1_800_000_000_000L // a Monday-ish instant; only differences matter
    private val store = MemStore()
    private val m = NetworkMemory(store) { now }
    private val k = "0123456789abcdef"
    private val k2 = "fedcba9876543210"

    @Test
    fun `an unknown network and a null key are UNSEEN`() {
        assertEquals(Memory.UNSEEN, m.lookup(k))
        assertEquals(Memory.UNSEEN, m.lookup(null))
        m.recordHomeProof(null); m.recordStrike(null) // no key, nothing learned, no crash
        assertEquals(Memory.UNSEEN, m.lookup(null))
    }

    @Test
    fun `one home proof makes it HOME at once`() {
        m.recordHomeProof(k)
        assertEquals(Memory.HOME, m.lookup(k))
        assertEquals(Memory.UNSEEN, m.lookup(k2))
    }

    @Test
    fun `an unseen network needs two strikes at least ten minutes apart`() {
        m.recordStrike(k)
        assertEquals(Memory.UNSEEN, m.lookup(k))
        now += 9 * min
        m.recordStrike(k) // the same event seen again
        assertEquals(Memory.UNSEEN, m.lookup(k))
        now += 2 * min
        m.recordStrike(k)
        assertEquals(Memory.AWAY, m.lookup(k))
    }

    @Test
    fun `strikes at the same instant are one event`() {
        repeat(5) { m.recordStrike(k) }
        assertEquals(Memory.UNSEEN, m.lookup(k))
    }

    @Test
    fun `a HOME network is not made AWAY by two strikes in one bad hour`() {
        m.recordHomeProof(k)
        now += 8 * day // past the grace
        m.recordStrike(k); now += 11 * min; m.recordStrike(k)
        assertEquals(Memory.HOME, m.lookup(k))
    }

    @Test
    fun `a HOME network goes AWAY on three strikes, or two on different calendar days, once the proof is a week old`() {
        m.recordHomeProof(k)
        now += 8 * day
        m.recordStrike(k); now += 11 * min; m.recordStrike(k); now += 11 * min; m.recordStrike(k)
        assertEquals(Memory.AWAY, m.lookup(k))

        m.recordHomeProof(k2)
        now += 8 * day
        m.recordStrike(k2)
        now += day
        m.recordStrike(k2)
        assertEquals(Memory.AWAY, m.lookup(k2))
    }

    @Test
    fun `any home proof in the last week blocks the change to AWAY`() {
        m.recordHomeProof(k)
        now += 3 * day
        m.recordStrike(k); now += 11 * min; m.recordStrike(k); now += 11 * min; m.recordStrike(k)
        assertEquals(Memory.HOME, m.lookup(k))
    }

    @Test
    fun `a home proof forgives the strikes so far`() {
        m.recordStrike(k); now += 11 * min
        m.recordHomeProof(k)
        now += 11 * min
        m.recordStrike(k)
        assertEquals(Memory.HOME, m.lookup(k))
    }

    @Test
    fun `AWAY back to HOME is a flip, and the second flip makes the key AMBIGUOUS`() {
        m.recordStrike(k); now += 11 * min; m.recordStrike(k) // AWAY (first verdict, not a flip)
        assertEquals(Memory.AWAY, m.lookup(k))
        m.recordHomeProof(k) // flip 1
        assertEquals(Memory.HOME, m.lookup(k))
        now += 8 * day
        m.recordStrike(k); now += 11 * min; m.recordStrike(k); now += 11 * min; m.recordStrike(k) // flip 2
        assertEquals(Memory.AMBIGUOUS, m.lookup(k))
    }

    @Test
    fun `an AMBIGUOUS network learns and teaches nothing`() {
        m.recordStrike(k); now += 11 * min; m.recordStrike(k); m.recordHomeProof(k)
        now += 8 * day
        repeat(3) { m.recordStrike(k); now += 11 * min }
        assertEquals(Memory.AMBIGUOUS, m.lookup(k))
        m.recordHomeProof(k); repeat(5) { m.recordStrike(k); now += 11 * min }
        assertEquals(Memory.AMBIGUOUS, m.lookup(k))
    }

    @Test
    fun `flips more than thirty days apart do not make it ambiguous`() {
        m.recordStrike(k); now += 11 * min; m.recordStrike(k)
        m.recordHomeProof(k) // flip 1
        now += 40 * day
        m.recordStrike(k); now += 11 * min; m.recordStrike(k); now += 11 * min; m.recordStrike(k) // flip 2, outside the window
        assertEquals(Memory.AWAY, m.lookup(k))
    }

    @Test
    fun `entries expire after sixty days`() {
        m.recordHomeProof(k)
        now += 61 * day
        assertEquals(Memory.UNSEEN, m.lookup(k))
    }

    @Test
    fun `at most sixty-four networks, the least recently seen go first`() {
        val keys = (0 until 70).map { "%016x".format(it + 1L) }
        for (key in keys) { m.recordHomeProof(key); now += day / 4 }
        assertEquals(Memory.UNSEEN, m.lookup(keys.first()))
        assertEquals(Memory.HOME, m.lookup(keys.last()))
        assertEquals(64, store.text!!.lines().size)
    }

    @Test
    fun `forget empties it`() {
        m.recordHomeProof(k)
        m.forget()
        assertEquals(Memory.UNSEEN, m.lookup(k))
    }

    @Test
    fun `it survives a restart - a new instance on the same store`() {
        m.recordHomeProof(k)
        m.recordStrike(k2)
        val again = NetworkMemory(store) { now }
        assertEquals(Memory.HOME, again.lookup(k))
        now += 11 * min
        again.recordStrike(k2)
        assertEquals(Memory.AWAY, again.lookup(k2))
    }

    @Test
    fun `garbage in the store is ignored, a throwing store costs the memory and never the caller`() {
        store.text = "garbage\n||||||\nzzzzzzzzzzzzzzzz|H|1|0|||0\n$k|H|notanumber|0|||0"
        assertEquals(Memory.UNSEEN, m.lookup(k))
        val throwing = object : NetMemoryStore {
            override fun read(): String? = throw IllegalStateException("keystore")
            override fun write(text: String) = throw IllegalStateException("keystore")
        }
        val t = NetworkMemory(throwing) { now }
        t.recordHomeProof(k); t.recordStrike(k); t.forget()
        assertEquals(Memory.UNSEEN, t.lookup(k))
    }

    @Test
    fun `the stored text has keys and numbers only`() {
        m.recordHomeProof(k)
        m.recordStrike(k2)
        assertTrue(store.text!!.lines().all { Regex("[0-9a-f]{16}\\|[HAU]\\|\\d+\\|\\d+\\|[0-9,]*\\|[0-9,]*\\|[01]").matches(it) }, store.text)
    }

    /** Random sequences of events over a few networks and times: invariants that must hold whatever happens. */
    @Test
    fun `property - random event sequences keep the invariants`() {
        val rnd = Random(42)
        val keys = listOf(k, k2, "aaaaaaaaaaaaaaaa")
        repeat(300) { run ->
            val s = MemStore()
            var t = 1_800_000_000_000L
            val mem = NetworkMemory(s) { t }
            val everAmbiguous = HashSet<String>()
            val lastRecord = HashMap<String, Long>()
            fun touched(key: String) {
                // an entry unseen for more than the expiry is forgotten, and so is its ambiguity
                if (lastRecord[key]?.let { t / 86_400_000L - it / 86_400_000L > NetworkMemory.EXPIRE_DAYS } == true) everAmbiguous.remove(key)
                lastRecord[key] = t
            }
            repeat(120) {
                val key = keys[rnd.nextInt(keys.size)]
                when (rnd.nextInt(6)) {
                    0 -> { touched(key); mem.recordHomeProof(key) }
                    1, 2 -> { touched(key); mem.recordStrike(key) }
                    3 -> t += rnd.nextInt(30) * 60_000L
                    4 -> t += rnd.nextInt(12) * 86_400_000L
                    else -> mem.lookup(key)
                }
                for (kk in keys) {
                    val v = mem.lookup(kk)
                    val expired = lastRecord[kk]?.let { t / 86_400_000L - it / 86_400_000L > NetworkMemory.EXPIRE_DAYS } == true
                    if (v == Memory.AMBIGUOUS) everAmbiguous.add(kk)
                    else assertFalse(kk in everAmbiguous && !expired, "run $run: $kk left AMBIGUOUS for $v without expiring")
                }
                assertTrue((s.text ?: "").lines().filter { l -> l.isNotEmpty() }.size <= NetworkMemory.MAX_ENTRIES)
            }
        }
    }
}
