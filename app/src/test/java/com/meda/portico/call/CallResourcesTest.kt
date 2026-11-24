package com.meda.portico.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a host with no emulator can still check about the answer screens (ship checklist 1): every
 * string and view id the code names exists, the layouts scroll, and the control that matters
 * comes first. Working directory = app/.
 */
class CallResourcesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement

    private val strings: Map<String, String> by lazy {
        val nodes = xml("src/main/res/values/strings.xml").getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun ids(layout: String): List<String> {
        val out = ArrayList<String>()
        fun walk(e: Element) {
            e.getAttributeNS(androidNs, "id").takeIf { it.isNotEmpty() }?.let { out += it.removePrefix("@+id/") }
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(::walk)
        }
        walk(xml("src/main/res/layout/$layout.xml"))
        return out
    }

    private fun referenced(source: String, prefix: String): Set<String> =
        Regex("R\\.$prefix\\.([a-z0-9_]+)").findAll(File(source).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `every view id the in-call and ring screens look up exists in their layout`() {
        val call = ids("activity_call")
        for (id in referenced("src/main/java/com/meda/portico/call/InCallActivity.kt", "id")) assertTrue(id in call, "activity_call has no @+id/$id")
        for (id in referenced("src/main/java/com/meda/portico/call/OutgoingCallActivity.kt", "id")) assertTrue(id in call, "activity_call has no @+id/$id (OutgoingCallActivity)")
        val ring = ids("activity_ring")
        for (id in referenced("src/main/java/com/meda/portico/ring/HouseRingActivity.kt", "id")) assertTrue(id in ring, "activity_ring has no @+id/$id (HouseRingActivity)")
    }

    @Test
    fun `every string the answer code uses exists`() {
        for (f in listOf("call/InCallActivity", "call/OutgoingCallActivity", "call/CallService", "ring/HouseRingActivity")) {
            for (s in referenced("src/main/java/com/meda/portico/$f.kt", "string")) assertTrue(s in strings, "$f uses R.string.$s, which does not exist")
        }
        for (layout in listOf("activity_call", "activity_ring")) {
            val text = File("src/main/res/layout/$layout.xml").readText()
            for (m in Regex("@string/([a-z0-9_]+)").findAll(text)) assertTrue(m.groupValues[1] in strings, "$layout uses @string/${m.groupValues[1]}")
        }
    }

    @Test
    fun `string arguments match what the code passes`() {
        fun args(s: String) = Regex("%(\\d)\\$\\d*[sd]").findAll(s).map { it.groupValues[1].toInt() }.toSortedSet().toList()
        assertEquals(listOf(1), args(strings.getValue("call_title")))
        assertEquals(listOf(1), args(strings.getValue("call_notif_title")))
        assertEquals(listOf(1, 2), args(strings.getValue("call_timer")), "minutes and seconds")
        assertEquals(emptyList<Int>(), args(strings.getValue("call_notif_text")))
        assertEquals(listOf(1), args(strings.getValue("call_out_ringing")))
        assertEquals(listOf(1), args(strings.getValue("call_out_notif_title")))
    }

    @Test
    fun `both screens scroll and the control that matters is first`() {
        for (l in listOf("activity_call", "activity_ring")) {
            assertEquals("ScrollView", xml("src/main/res/layout/$l.xml").tagName, "$l must scroll (every growable screen must scroll)")
        }
        val call = ids("activity_call")
        assertTrue(call.indexOf("call_hangup") < call.indexOf("call_mute"))
        assertTrue(call.indexOf("call_hangup") < call.indexOf("call_retry"))
        assertTrue(call.indexOf("call_hangup") < call.indexOf("call_close"))
        val ring = ids("activity_ring")
        assertTrue(ring.indexOf("ring_answer") in 0 until ring.indexOf("ring_stop"), "Answer comes before Stop ringing")
    }

    @Test
    fun `the ring notification no longer says answering is elsewhere`() {
        assertTrue("intercom app" !in strings.getValue("ring_text"))
        assertTrue("panel" !in strings.getValue("ring_text"))
        assertTrue("ring_answer_note" !in strings, "the 'cannot answer yet' note is gone")
    }

    @Test
    fun `no view in the in-call layout paints a background over a surface`() {
        // Nothing here is a SurfaceView, and nothing may call setBackgroundColor on one (checklist 1).
        assertTrue("SurfaceView" !in File("src/main/res/layout/activity_call.xml").readText())
        assertTrue("setBackgroundColor" !in File("src/main/java/com/meda/portico/call/InCallActivity.kt").readText())
    }
}
