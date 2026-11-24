package com.meda.portico.core.push

import com.meda.portico.core.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.security.MessageDigest

/**
 * Runs every case of the vendored `push_v2.json` (a byte copy of portico-ha's
 * `contract/push_v2.json`, M6 E3) through [Decision.receive]. See docs/architecture/push-decision-core.md for
 * how to re-vendor the file and update [PINNED_SHA256].
 */
class ContractTest {

    companion object {
        /**
         * sha256 of `push_v2.json` as vendored from portico-ha `a16b38f` (main after 3c: push/v2 plus the v2.1 house cases, six
         * of them: `ct: house` with an opaque `from_ref` and no `door_ref`). The fixture's `expect` is the DOOR path's reaction:
         * a house message is silent there (a house ring is not a door ring), which is what this test replays; the house ring
         * itself has its own decision and its own replay of the same cases.
         */
        const val PINNED_SHA256 = "f4d5872dd3a8374ec5df2fd39ea3f7f26f802f28d35a2f3211229d3072e2f331"
        const val EXPECTED_CASES = 29

        /** The fixture's `contract` field. M7's house cases will be `push/v2.1`: cases, not a wire version. */
        const val CONTRACT_NAME = "push/v2"

        private val TOP_KEYS = setOf("contract", "about", "ring_window_s", "clock_skew_ms", "known_door_refs", "cases")
        private val CASE_KEYS = setOf("name", "messages", "now_ms", "expect")
        private val MESSAGE_KEYS = setOf("pv", "kind", "door_ref", "call_id", "seq", "t", "exp", "by", "ct", "from_ref")
        private val EXPECTS = setOf("ring", "silent", "dismiss", "missed", "quiet")

        private fun bytes(): ByteArray =
            requireNotNull(ContractTest::class.java.getResourceAsStream("/push_v2.json")) { "push_v2.json not on the test classpath" }
                .use { it.readBytes() }

        @Suppress("UNCHECKED_CAST")
        private fun root(): Map<String, Any?> = MiniJson.parse(String(bytes(), Charsets.UTF_8)) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        private fun cases(): List<Map<String, Any?>> = root()["cases"] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        private fun knownDoorRefs(): Set<String> = (root()["known_door_refs"] as List<String>).toSet()

        @Suppress("UNCHECKED_CAST")
        private fun messages(case: Map<String, Any?>): List<Map<String, String>> =
            case["messages"] as List<Map<String, String>>

        /**
         * Feed a case's messages in order to a fresh store. The file defines the phone's clock only
         * for the LAST message (`now_ms`); an earlier message is taken to arrive at its own `t`
         * ([earlierAtT]) or, as a robustness check, at `now_ms` as well.
         */
        fun run(case: Map<String, Any?>, earlierAtT: Boolean): Outcome {
            val store = InMemoryCallStore()
            val known = knownDoorRefs()
            val now = case["now_ms"] as Long
            val msgs = messages(case)
            var last: Outcome? = null
            for ((idx, data) in msgs.withIndex()) {
                val isLast = idx == msgs.lastIndex
                val clock = if (isLast || !earlierAtT) now else data["t"]?.toLongOrNull() ?: now
                last = Decision.receive(store, PushMessage.parse(data), clock, known)
            }
            return requireNotNull(last)
        }
    }

    @Test
    fun vendoredFileMatchesPinnedSha256() {
        val actual = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes()))
        assertEquals(
            PINNED_SHA256, actual,
            "push_v2.json drifted from the pinned copy: re-vendor it byte for byte from portico-ha " +
                "contract/push_v2.json and bump PINNED_SHA256 (docs/architecture/push-decision-core.md)",
        )
    }

    @Test
    fun fileShapeIsFullyMapped() {
        val root = root()
        assertEquals(TOP_KEYS, root.keys, "top-level keys")
        assertEquals(CONTRACT_NAME, root["contract"])
        val cases = cases()
        assertEquals(EXPECTED_CASES, cases.size, "case count")
        assertEquals(cases.size, cases.map { it["name"] }.toSet().size, "case names unique")
        for (case in cases) {
            assertEquals(CASE_KEYS, case.keys, "keys of case ${case["name"]}")
            assertTrue(case["expect"] in EXPECTS, "expect of ${case["name"]}")
            assertTrue(case["now_ms"] is Long, "now_ms of ${case["name"]}")
            for (m in messages(case)) {
                assertTrue(MESSAGE_KEYS.containsAll(m.keys), "unknown message key in ${case["name"]}: ${m.keys - MESSAGE_KEYS}")
                assertTrue(m.values.all { it is String }, "every value a string in ${case["name"]}")
            }
        }
    }

    @Test
    fun constantsMatchTheFile() {
        val root = root()
        assertEquals(Contract.SKEW_MS, root["clock_skew_ms"])
        assertEquals(Contract.DEFAULT_RING_WINDOW_S.toLong(), root["ring_window_s"])
        // The contract's major version is the wire `pv`; a minor (".1", M7's house cases) only adds cases.
        val name = root["contract"] as String
        assertTrue(
            name == "push/v${Contract.PV}" || name.startsWith("push/v${Contract.PV}."),
            "contract $name is not push/v${Contract.PV} or a minor of it",
        )
    }

    @TestFactory
    fun everyCase(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest(case["name"] as String) {
            assertEquals(case["expect"], run(case, earlierAtT = true).contractName)
        }
    }

    /** The verdicts must not hinge on the "earlier messages arrive at t" reading of the file. */
    @TestFactory
    fun everyCaseWithAllMessagesAtNow(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest("${case["name"]} (all at now_ms)") {
            assertEquals(case["expect"], run(case, earlierAtT = false).contractName)
        }
    }
}
