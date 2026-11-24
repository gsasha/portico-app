package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Stay connected's strings take the arguments the code passes, and the switch is wired in the layout. */
class StayResourcesTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun args(s: String) = Regex("%(\\d)\\\$s").findAll(s).map { it.groupValues[1].toInt() }.toSortedSet().toList()

    @Test
    fun `the notification lines take the home name, the waiting line takes none`() {
        assertEquals(listOf(1), args(strings.getValue("stay_note_connected")))
        assertEquals(listOf(1), args(strings.getValue("stay_note_connecting")))
        for (k in listOf("stay_note_waiting_wifi", "stay_note_push_only", "stay_note_title", "stay_note_home_fallback", "conn_stay", "conn_push", "channel_connection_name", "channel_connection_description")) {
            assertEquals(emptyList<Int>(), args(strings.getValue(k)), k)
        }
        assertEquals("Waiting for Wi-Fi", strings["stay_note_waiting_wifi"])
    }

    @Test
    fun `the choices are short words, no explanation`() {
        assertEquals("Stay connected", strings["conn_stay"])
        assertEquals("Push notification", strings["conn_push"])
    }

    @Test
    fun `a connection row per network class is in the Settings layout`() {
        val layout = File("src/main/res/layout/activity_setup.xml").readText()
        for (c in listOf("wifi", "mobile", "roaming")) assertTrue("setup_conn_$c" in layout, c)
        assertTrue("setup_stay_switch" !in layout)
    }

    @Test
    fun `Settings counts itself visible on start and stop, like Home, not on resume and pause`() {
        val src = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
        fun body(name: String) = src.substringAfter("override fun $name()").substringBefore("override fun ")
        assertTrue("graph(this).uiStarted()" in body("onStart") && "graph(this).uiStopped()" in body("onStop"))
        assertTrue("uiStarted" !in body("onResume") && "uiStopped" !in body("onPause"))
    }
}
