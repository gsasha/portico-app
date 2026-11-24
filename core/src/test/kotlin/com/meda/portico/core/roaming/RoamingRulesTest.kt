package com.meda.portico.core.roaming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A-10: the roaming decision table, row by row (the intercom's 13 rows, in Portico's words). */
class RoamingRulesTest {
    private fun mobile(
        notRoaming: Boolean = true,
        telephony: Boolean = false,
        home: String = "il",
        here: String = "il",
        lan: Boolean = false,
    ) = RoamingFacts(lan, true, false, true, notRoaming, telephony, home, here)

    private class Row(val name: String, val facts: RoamingFacts, val away: Boolean, val keep: Boolean = false)

    @Test
    fun `every row of the table`() {
        val rows = listOf(
            Row("home Wi-Fi proven, WAN down so cellular is default", mobile(notRoaming = false, here = "gr", lan = true), false),
            Row("no default network holds the last verdict", RoamingFacts(false, false, false, false, true, false, "il", ""), false, keep = true),
            Row("foreign hotel Wi-Fi is never metered", RoamingFacts(false, true, true, false, false, false, "il", "gr"), false),
            Row("wired", RoamingFacts(false, true, true, false, true, false, "il", "gr"), false),
            Row("not a mobile connection", RoamingFacts(false, true, false, false, true, false, "il", "gr"), false),
            Row("ordinary roaming: NOT_ROAMING absent", mobile(notRoaming = false), true),
            Row("telephony says roaming, capability says not", mobile(telephony = true), true),
            Row("local eSIM abroad, nothing roaming, country differs", mobile(home = "il", here = "gr"), true),
            Row("country case and spaces are ignored", mobile(home = " IL ", here = "il"), false),
            Row("mobile at home country", mobile(), false),
            Row("serving country unknown, no flag: cannot tell, picture stays", mobile(here = ""), false),
            Row("home country never learned: cannot tell, picture stays", mobile(home = ""), false),
            Row("roaming flag wins over unknown countries", mobile(notRoaming = false, home = "", here = ""), true),
        )
        for (r in rows) {
            val v = RoamingRules.judge(r.facts)
            assertEquals(r.away, v.away, r.name)
            assertEquals(r.keep, v.keepPrevious, r.name)
            assertTrue(v.why.isNotBlank(), r.name)
        }
    }

    @Test
    fun `a Wi-Fi joined but not yet the route changes the sentence and not the verdict`() {
        val plain = RoamingRules.judge(mobile(notRoaming = false))
        val joined = RoamingRules.judge(mobile(notRoaming = false).copy(wifiJoinedNotDefault = true))
        assertTrue(joined.away)
        assertTrue(joined.why.contains("Wi-Fi is joined"))
        assertFalse(plain.why.contains("Wi-Fi is joined"))
        // and it never makes a home verdict into anything else
        assertFalse(RoamingRules.judge(mobile().copy(wifiJoinedNotDefault = true)).away)
    }
}
