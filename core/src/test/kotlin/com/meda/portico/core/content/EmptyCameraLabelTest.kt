package com.meda.portico.core.content

import com.meda.portico.core.ha.ContentCamera
import com.meda.portico.core.ha.ContentPage
import com.meda.portico.core.ha.ContentProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A camera whose label is empty shows no name at all: the picture identifies itself. */
class EmptyCameraLabelTest {

    private fun tile(label: String, name: String? = "Gate camera") =
        HomeModel.cameraTile(ContentCamera("camera.gate", label, name, available = true), usable = true, pictures = emptySet())

    @Test
    fun `an empty label is no name - not the entity id, and not HA's own name either`() {
        val t = tile("")
        assertEquals("", t.label)
        assertFalse(t.named)
        assertNull(t.detail)
    }

    @Test
    fun `a blank or invisible-only label is no name either`() {
        for (label in listOf("   ", "\t\n", "​‎")) {
            val t = tile(label)
            assertFalse(t.named, label)
            assertEquals("", t.label, label)
            assertNull(t.detail, label)
        }
    }

    @Test
    fun `a normal label is unchanged - shown, with HA's name under it when it differs`() {
        val t = tile("Gate")
        assertTrue(t.named)
        assertEquals("Gate", t.label)
        assertEquals("Gate camera", t.detail)
        assertNull(tile("Gate", name = "Gate").detail)
        assertNull(tile("Gate", name = null).detail)
    }

    @Test
    fun `the state words still work on an unnamed tile`() {
        val c = ContentCamera("camera.gate", "", "Gate camera", available = false)
        assertEquals(PictureState.CAMERA_UNAVAILABLE, HomeModel.cameraTile(c, usable = true, pictures = emptySet()).picture)
        assertEquals(PictureState.OFFLINE, HomeModel.cameraTile(c, usable = false, pictures = emptySet()).picture)
    }

    @Test
    fun `the cache round trips an empty camera label`() {
        val cam = ContentCamera("camera.gate", "", "Gate camera", available = true)
        val profile = ContentProfile("", "r1", listOf(cam), emptyList(), pages = listOf(ContentPage("main", "Main", listOf(cam), emptyList())))
        val back = ContentCodec.decode(ContentCodec.encode(profile))
        assertEquals(profile, back)
        assertEquals("", back!!.cameras.single().label)
    }
}
