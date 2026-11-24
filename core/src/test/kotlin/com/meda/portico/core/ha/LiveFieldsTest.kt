package com.meda.portico.core.ha

import com.meda.portico.core.content.ContentCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DEV-713 stage S0 (dark): the additive live-video fields are parsed and cached, and nothing reads them yet. A camera's `live` and
 * the document's `show_fps` are true only for a JSON boolean true; absent, false and every other value read as false (the
 * "a later build's value never drops the page" rule), and an older cached document without them keeps working.
 */
class LiveFieldsTest {
    /** A `content/get` result: one block with [camera] as its only camera (extra keys in [camera] after `label`), [top] at document level. */
    private fun frame(camera: String = "", top: String = "", flatCamera: String? = null, callerCamera: String? = null): String {
        val cams = """{"id":"gate","label":"Gate","available":true$camera}"""
        val page = if (flatCamera != null) {
            """{"id":"p","label":"P","cameras":[{"id":"gate","label":"Gate","available":true$flatCamera}],"actions":[]}"""
        } else {
            """{"id":"p","label":"P","blocks":[{"id":"b","title":"B","cameras":[$cams],"actions":[]}]}"""
        }
        val callers = if (callerCamera != null) {
            ""","callers":{"ep-x":{"name":"X","kind":"phone","cameras":[{"id":"c-1","label":"Door","available":true$callerCamera}],"ring_buttons":[],"call_buttons":[]}}"""
        } else {
            ""
        }
        return """{"id":1,"type":"result","success":true,"result":{"rev":"r1"$top,"pages":[$page]$callers}}"""
    }

    private fun profile(text: String) =
        (ContentCommands.parseGet(HaProtocol.parse(text).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    private fun live(camera: String) = profile(frame(camera = camera)).pageList.single().cameras.single().live
    private fun fps(top: String) = profile(frame(top = top)).showFps

    @Test
    fun `the defaults are off`() {
        assertFalse(ContentCamera("c", "C", null, true).live)
        assertFalse(ContentProfile.EMPTY.showFps)
        assertFalse(live(""))
        assertFalse(fps(""))
    }

    @Test
    fun `live true on a camera is read, and only a JSON boolean true counts`() {
        assertTrue(live(""","live":true"""))
        assertFalse(live(""","live":false"""))
        for (hostile in listOf("0", "1", "\"true\"", "\"yes\"", "null", "{}", "[]", "[true]", "\"\"")) {
            assertFalse(live(""","live":$hostile"""), "live: $hostile")
        }
    }

    @Test
    fun `show_fps true on the document is read, and only a JSON boolean true counts`() {
        assertTrue(fps(""","show_fps":true"""))
        assertFalse(fps(""","show_fps":false"""))
        for (hostile in listOf("0", "1", "\"true\"", "\"yes\"", "null", "{}", "[]", "[true]", "\"\"")) {
            assertFalse(fps(""","show_fps":$hostile"""), "show_fps: $hostile")
        }
    }

    @Test
    fun `a hostile live or show_fps never drops the page or its other fields`() {
        val p = profile(frame(camera = ""","live":"true","size":"big"""", top = ""","show_fps":0"""))
        val cam = p.pageList.single().cameras.single()
        assertEquals("gate", cam.id)
        assertEquals(CameraSize.BIG, cam.size)
        assertTrue(cam.available)
        assertFalse(cam.live)
        assertFalse(p.showFps)
    }

    @Test
    fun `live is read on flat page cameras and a caller's cameras too`() {
        val p = profile(frame(flatCamera = ""","live":true""", callerCamera = ""","live":true"""))
        assertTrue(p.pageList.single().cameras.single().live)
        assertTrue(p.callers.getValue("ep-x").cameras.single().live)
        val q = profile(frame(flatCamera = "", callerCamera = ""))
        assertFalse(q.pageList.single().cameras.single().live)
        assertFalse(q.callers.getValue("ep-x").cameras.single().live)
    }

    @Test
    fun `the cache keeps both fields through encode and decode`() {
        val on = profile(frame(camera = ""","live":true""", top = ""","show_fps":true""", callerCamera = ""","live":true"""))
        val back = ContentCodec.decode(ContentCodec.encode(on))!!
        assertEquals(on, back.copy(phone = on.phone), "the whole document survives the cache")
        assertTrue(back.showFps)
        assertTrue(back.pageList.single().cameras.single().live)
        assertTrue(back.callers.getValue("ep-x").cameras.single().live)
        val flat = profile(frame(flatCamera = ""","live":true"""))
        assertTrue(ContentCodec.decode(ContentCodec.encode(flat))!!.pageList.single().cameras.single().live)
    }

    @Test
    fun `the cache writes no key when the fields are off, so an older app reads the same document`() {
        val text = ContentCodec.encode(profile(frame(callerCamera = "")))
        assertFalse(text.contains("live"), text)
        assertFalse(text.contains("show_fps"), text)
        val back = ContentCodec.decode(text)!!
        assertFalse(back.showFps)
        assertFalse(back.pageList.single().cameras.single().live)
        val on = ContentCodec.encode(profile(frame(camera = ""","live":true""", top = ""","show_fps":true""")))
        assertTrue(on.contains("\"live\":true"), on)
        assertTrue(on.contains("\"show_fps\":true"), on)
    }

    @Test
    fun `a document cached by the build before this one still decodes, with both off`() {
        // exactly what the previous encoder wrote: version 3, no live and no show_fps
        val old = """{"v":3,"rev":"r9","home_name":"Home","pages":[{"id":"p","label":"P","blocks":[{"id":"b","cameras":[{"id":"gate","label":"Gate","available":true,"size":"big"}],"actions":[]}]}],"callers":{},"roster":[]}"""
        val back = ContentCodec.decode(old)!!
        assertEquals("r9", back.rev)
        assertEquals("Home", back.homeName)
        assertFalse(back.showFps)
        val cam = back.pageList.single().cameras.single()
        assertEquals("gate", cam.id)
        assertEquals(CameraSize.BIG, cam.size)
        assertFalse(cam.live)
    }

    @Test
    fun `a damaged cache value reads as off and never throws`() {
        for (hostile in listOf("0", "1", "\"true\"", "null", "{}", "[]")) {
            val text = """{"v":3,"rev":"r9","show_fps":$hostile,"pages":[{"id":"p","label":"P","cameras":[{"id":"gate","label":"Gate","available":true,"live":$hostile}],"actions":[]}],"callers":{},"roster":[]}"""
            val back = ContentCodec.decode(text)!!
            assertFalse(back.showFps, "show_fps: $hostile")
            assertFalse(back.pageList.single().cameras.single().live, "live: $hostile")
        }
    }
}
