package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Offline registration's user-visible text is the owner's wording (2026-10-07), lives in
 * strings.xml, and the layout block exists hidden by default. No device here, so the files are
 * checked directly (working directory = app/).
 */
class OfflineRegistrationResourcesTest {

    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'")
        }
    }

    @Test
    fun `the owner's wording`() {
        assertEquals(
            "This phone cannot reach Home Assistant, so it cannot register itself. If Home Assistant is only reachable " +
                "from your home network, connect to it, for example on your Wi-Fi, and Portico will register by itself.",
            strings["offline_reg_cannot_reach"],
        )
        assertEquals(
            "Otherwise copy the registration string below and paste it in Home Assistant: Portico > Push ring notification > " +
                "Configure > Add a phone's registration.",
            strings["offline_reg_copy_instructions"],
        )
        assertEquals("Scan the QR again from Home Assistant to get a registration string.", strings["offline_reg_scan_again"])
        assertEquals("Copy registration string", strings["offline_reg_copy"])
        assertEquals("Copied. Paste it in Home Assistant.", strings["offline_reg_copied"])
    }

    @Test
    fun `no wording mentions a prefix or format detail`() {
        for ((k, v) in strings.filterKeys { it.startsWith("offline_reg_") }) {
            assertFalse("prefix" in v.lowercase() || "PORTICO-REG" in v || "base64" in v.lowercase(), "$k: $v")
        }
    }

    @Test
    fun `the layout block is hidden by default and its button uses the string resource`() {
        val x = File("src/main/res/layout/activity_setup.xml").readText()
        val block = x.substringAfter("android:id=\"@+id/setup_offline\"").substringBefore(">")
        assertTrue("android:visibility=\"gone\"" in block, block)
        assertTrue("android:text=\"@string/offline_reg_copy\"" in x)
    }
}
