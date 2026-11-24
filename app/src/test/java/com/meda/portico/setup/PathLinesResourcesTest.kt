package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The per-path and route lines' strings (no device here, so strings.xml is read directly;
 * working directory = app/): each template takes exactly the arguments SetupChecks passes, and
 * every reason reads as the end of "Direct path, 2 s ago: ...".
 */
class PathLinesResourcesTest {

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
    fun `templates take the arguments SetupChecks passes`() {
        assertEquals(listOf(1), args(strings.getValue("st_path_direct_opened")))
        assertEquals(listOf(1, 2), args(strings.getValue("st_path_direct_failed")))
        assertEquals(listOf(1), args(strings.getValue("st_path_tailnet_opened")))
        assertEquals(listOf(1, 2), args(strings.getValue("st_path_tailnet_failed")))
        assertEquals(emptyList<Int>(), args(strings.getValue("st_path_tailnet_not_built")))
        assertEquals(listOf(1, 2), args(strings.getValue("st_route_via")))
        assertEquals(listOf(1, 2), args(strings.getValue("st_route_none")))
        assertEquals(listOf(1), args(strings.getValue("st_route_unknown")))
        assertEquals(listOf(1), args(strings.getValue("why_http_status")))
        assertEquals(listOf(1), args(strings.getValue("why_forwarder_down_at")))
        assertEquals(listOf(1), args(strings.getValue("why_contract_mismatch_at")))
    }

    @Test
    fun `the forwarder's refusal names the address and port tried`() {
        assertEquals(
            "the local forwarder was not listening at 127.0.0.1:41234.",
            strings.getValue("why_forwarder_down_at").replace("%1\$s", "127.0.0.1:41234"),
        )
    }

    @Test
    fun `every reason is a lower-case clause ending in a full stop, without arguments but the HTTP, forwarder and contract ones`() {
        val why = strings.filterKeys { it.startsWith("why_") }
        assertEquals(22, why.size, why.keys.toString())
        for ((k, v) in why) {
            assertTrue(v.first().isLowerCase() && v.endsWith("."), "$k: $v")
            if (k != "why_http_status" && k != "why_forwarder_down_at" && k != "why_contract_mismatch_at") assertFalse("%" in v, "$k: $v")
        }
    }

    @Test
    fun `the not-built path says so plainly`() {
        assertEquals("Tailnet path: not tried, because this APK has no tailnet client.", strings["st_path_tailnet_not_built"])
    }
}
