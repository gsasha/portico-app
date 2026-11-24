package com.meda.portico

import com.meda.portico.core.stay.StayWhy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 0.6.92: every reason the app gives `stay.evaluate` has a Journal word (else the line would say `why=other`), and the ring and snapshot hooks exist. */
class StayJournalWiringTest {
    private val graph by lazy { File("src/main/java/com/meda/portico/AppGraph.kt").readText() }
    private val all by lazy { File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() } }

    @Test fun `every literal evaluate reason has a journal word`() {
        val reasons = Regex("""stay\.evaluate\((?:true|false), "([^"]+)"""").findAll(all).map { it.groupValues[1] }.toSet()
        assertTrue(reasons.size >= 5, "found ${reasons.size}")
        for (r in reasons - "battery saver ") assertTrue(StayWhy.word(r) != "other", "no journal word for stay.evaluate reason \"$r\"")
        // the battery-saver reason is built from "on"/"off"
        assertTrue(StayWhy.word("battery saver on") != "other" && StayWhy.word("battery saver off") != "other")
    }

    @Test fun `a ring edge reaches the stay policy and each snapshot trigger is wired`() {
        assertTrue(graph.contains("stay.onRingChanged(up)"))
        for (why in listOf("start", "ring", "call", "setting")) assertTrue(graph.contains("journalConfig(\"$why\")"), why)
    }
}
