package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Pause's strings (read from strings.xml; working directory = app/) take exactly the arguments the code passes. */
class PauseResourcesTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'")
        }
    }

    private fun args(s: String) = Regex("%(\\d)\\\$s").findAll(s).map { it.groupValues[1].toInt() }.toSortedSet().toList()

    @Test
    fun `the HH_MM strings take one argument, the rest none`() {
        assertEquals(listOf(1), args(strings.getValue("st_paused_until")))
        assertEquals(listOf(1), args(strings.getValue("pause_notification_text_until")))
        for (k in listOf(
            "st_paused_until_resumed", "st_link_paused", "st_push_paused", "st_tailnet_paused", "pause_heading", "pause_hint",
            "pause_1h", "pause_4h", "pause_until_resumed", "pause_resume", "pause_failed", "pause_notification_title",
            "pause_notification_text_resumed", "channel_paused_name", "channel_paused_description",
        )) {
            assertEquals(emptyList<Int>(), args(strings.getValue(k)), k)
        }
    }

    @Test
    fun `the notification ends with the word on its button, as the brief says`() {
        assertEquals("It will not ring until you resume. Resume", strings["pause_notification_text_resumed"])
        assertEquals("Resume", strings["pause_resume"])
    }
}
