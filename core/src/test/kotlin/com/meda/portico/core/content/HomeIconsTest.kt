package com.meda.portico.core.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HomeIconsTest {
    private val contract: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/icons_v1.json")
        assertNotNull(stream, "core/src/test/resources/icons_v1.json is missing")
        Json.parseToJsonElement(stream!!.bufferedReader().readText()).jsonObject
    }

    @Test
    fun `the curated list is exactly the shared contract list, in order`() {
        val fromContract = (contract["icons"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(fromContract, HomeIcons.CURATED)
    }

    @Test
    fun `the contract copy has no duplicates and only valid names`() {
        val names = contract["icons"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.size in 60..120, "${names.size} icons")
        for (n in names) assertTrue(n.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")), n)
        assertTrue("none" !in names, "'none' is the picker's word for no icon, never an icon")
    }

    @Test
    fun `every curated key has a drawable file and no drawable is unlisted`() {
        // Gradle runs :core tests with core/ as the working directory.
        val dir = File("../app/src/main/res/drawable")
        for (k in HomeIcons.CURATED) assertTrue(File(dir, HomeIcons.drawableName(k) + ".xml").isFile, "no drawable for $k")
        val onDisk = dir.listFiles { f -> f.name.startsWith("ic_mdi_") }!!.map { it.name.removeSuffix(".xml") }.toSet()
        assertEquals(HomeIcons.CURATED.map { HomeIcons.drawableName(it) }.toSet(), onDisk)
    }

    @Test
    fun `every fallback is a curated icon and every kind the plugin has is covered`() {
        for (v in HomeIcons.KIND_FALLBACK.values) assertTrue(v in HomeIcons.CURATED, v)
        assertEquals(
            setOf("portico_open", "lock_open", "button_press", "script_run", "scene_activate", "call", "lock_lock", "lock_unlock", "cover_open", "cover_close", "talk"),
            HomeIcons.KIND_FALLBACK.keys,
        )
    }

    @Test
    fun `names are found with or without the prefix, in any case and padding`() {
        assertEquals("door-open", HomeIcons.keyOf("mdi:door-open"))
        assertEquals("door-open", HomeIcons.keyOf("door-open"))
        assertEquals("door-open", HomeIcons.keyOf("  MDI:Door-Open \n"))
        assertEquals("door-open", HomeIcons.keyOf("door_open"))
        assertEquals("gate", HomeIcons.keyOf("mdi: gate"))
    }

    @Test
    fun `unknown names give no icon`() {
        for (s in listOf(null, "", "  ", "none", "mdi:", "mdi:not-an-icon", "hass:door", "phu:door", "door-open.svg", "../x", "mdi:mdi:gate")) {
            assertNull(HomeIcons.keyOf(s), "'$s'")
        }
    }

    @Test
    fun `a button's own icon wins, else its kind's, else none`() {
        assertEquals("gate", HomeIcons.resolve("mdi:gate", "lock_open"))
        assertEquals("door-open", HomeIcons.resolve(null, "portico_open"))
        assertEquals("lock-open-variant", HomeIcons.resolve("mdi:unknown", "lock_open"))
        assertEquals("gesture-tap-button", HomeIcons.resolve("", "button_press"))
        assertEquals("script-text", HomeIcons.resolve(null, "script_run"))
        assertEquals("palette", HomeIcons.resolve(null, "scene_activate"))
        assertNull(HomeIcons.resolve(null, "future_kind"))
        assertNull(HomeIcons.resolve("mdi:bogus", "future_kind"))
    }

    @Test
    fun `drawable names follow the generator rule`() {
        assertEquals("ic_mdi_door_open", HomeIcons.drawableName("door-open"))
        assertEquals("ic_mdi_lock_open_variant_outline", HomeIcons.drawableName("lock-open-variant-outline"))
    }
}
