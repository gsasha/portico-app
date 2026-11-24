package com.meda.portico.core.panel

import com.meda.portico.core.ha.ContentCommands.ErrorKind
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.ha.Door
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DEV-807 slice 2: the panel's door filter and the screen slug (design section 6). */
class PanelWakeTest {
    private val gate = Door("d-gate", "http://gate.example:8091", "fake:http://gate.example:8091", "fake", "Gate")
    private val front = Door("d-front", "vto-front.example", "dahua:front", "fake", "Front")

    @Test
    fun `an absent wake rings for every door`() {
        assertTrue(PanelWake.accepts(null, gate.doorRef, gate))
        assertTrue(PanelWake.accepts(null, "unknown-ref", null))
    }

    @Test
    fun `an empty wake rings for none`() {
        assertFalse(PanelWake.accepts(emptyList(), gate.doorRef, gate))
    }

    @Test
    fun `a door matches by its id, its door ref or its endpoint id`() {
        assertTrue(PanelWake.accepts(listOf("d-gate"), gate.doorRef, gate), "the door's own id (a new or changed Talk element)")
        assertTrue(PanelWake.accepts(listOf("dahua:front"), front.doorRef, front), "the legacy provider:id form")
        assertTrue(PanelWake.accepts(listOf(gate.doorRef), gate.doorRef, gate), "the door ref")
        assertTrue(PanelWake.accepts(listOf("d-front", "d-gate"), gate.doorRef, gate), "one of several")
    }

    @Test
    fun `a door outside the list is filtered, and an unknown door matches only by its ref`() {
        assertFalse(PanelWake.accepts(listOf("d-front"), gate.doorRef, gate))
        assertFalse(PanelWake.accepts(listOf("d-gate"), "mystery", null))
        assertTrue(PanelWake.accepts(listOf("mystery"), "mystery", null))
    }

    @Test
    fun `the journal words`() {
        assertEquals("wake-missing", PanelWake.lineFor(null))
        assertEquals("wake-empty", PanelWake.lineFor(emptyList()))
        assertNull(PanelWake.lineFor(listOf("d-gate")))
    }

    // ---- the slug ----------------------------------------------------------------------

    private fun profile(screens: List<String> = emptyList(), endpoint: String? = null, phoneEndpoint: String? = "ep-phone") =
        ContentProfile(phone = "p", rev = "r", cameras = emptyList(), actions = emptyList(), endpoint = endpoint, phoneEndpoint = phoneEndpoint, screens = screens)

    @Test
    fun `exactly one screen is adopted, none or several are not`() {
        assertEquals(SlugStep.Adopt("kitchen-panel"), PanelSlug.afterProfile(null, profile(listOf("kitchen-panel"))))
        assertEquals(SlugStep.Keep, PanelSlug.afterProfile(null, profile(emptyList())))
        assertEquals(SlugStep.Keep, PanelSlug.afterProfile(null, profile(listOf("a", "b"))))
    }

    @Test
    fun `a slug that screens no longer lists is dropped, one that it lists is kept`() {
        assertEquals(SlugStep.Drop, PanelSlug.afterProfile("old", profile(listOf("new"))))
        assertEquals(SlugStep.Keep, PanelSlug.afterProfile("kitchen-panel", profile(listOf("kitchen-panel"))))
        assertEquals(SlugStep.Keep, PanelSlug.afterProfile("kitchen-panel", profile(emptyList())), "a document without `screens` says nothing")
    }

    @Test
    fun `only unauthorized and unknown_endpoint on a NAMED call make a slug stale`() {
        assertEquals(SlugStep.Drop, PanelSlug.afterRefusal("kitchen-panel", ErrorKind.UNAUTHORIZED))
        assertEquals(SlugStep.Drop, PanelSlug.afterRefusal("kitchen-panel", ErrorKind.UNKNOWN_ENDPOINT))
        assertEquals(SlugStep.Keep, PanelSlug.afterRefusal("kitchen-panel", ErrorKind.RATE_LIMITED))
        assertEquals(SlugStep.Keep, PanelSlug.afterRefusal(null, ErrorKind.UNAUTHORIZED), "no slug named: nothing to drop")
    }

    @Test
    fun `endpoint_ok needs the slug, a profile that came back for it, and the phone endpoint`() {
        assertTrue(PanelSlug.endpointOk("k", profile(endpoint = "k")))
        assertFalse(PanelSlug.endpointOk(null, profile(endpoint = "k")), "no slug named")
        assertFalse(PanelSlug.endpointOk("k", profile(endpoint = "ep-phone")), "the profile is the phone's, not the screen's")
        assertFalse(PanelSlug.endpointOk("k", profile(endpoint = "k", phoneEndpoint = null)), "an unsupported server")
    }
}
