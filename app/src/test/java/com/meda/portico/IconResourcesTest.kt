package com.meda.portico

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The icon set (design/icon/) is plain resource files. AAPT2 fails the build if the adaptive
 * icon references a missing drawable; this pins the files and the references without a build,
 * and checks the notification glyph is the one RingNotifier uses. Gradle runs unit tests with
 * the module directory (app/) as the working directory.
 */
class IconResourcesTest {

    private val res = File("src/main/res")

    private val drawables = listOf("ic_launcher_foreground", "ic_launcher_background", "ic_launcher_monochrome", "ic_stat_portico")

    @Test
    fun `the four icon drawables exist`() {
        for (d in drawables) assertTrue(File(res, "drawable/$d.xml").isFile, "missing drawable/$d.xml")
    }

    @Test
    fun `both adaptive icon files exist and are identical`() {
        val plain = File(res, "mipmap-anydpi-v26/ic_launcher.xml")
        val round = File(res, "mipmap-anydpi-v26/ic_launcher_round.xml")
        assertTrue(plain.isFile, "missing ic_launcher.xml")
        assertTrue(round.isFile, "missing ic_launcher_round.xml")
        assertEquals(plain.readText(), round.readText())
    }

    @Test
    fun `the adaptive icon references foreground, background and monochrome`() {
        val x = File(res, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue("<adaptive-icon" in x)
        assertTrue("<foreground android:drawable=\"@drawable/ic_launcher_foreground\"" in x, x)
        assertTrue("<background android:drawable=\"@drawable/ic_launcher_background\"" in x, x)
        assertTrue("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\"" in x, x)
    }

    private fun pathDatas(name: String): List<String> =
        Regex("android:pathData=\"([^\"]*)\"").findAll(File(res, "drawable/$name.xml").readText()).map { it.groupValues[1] }.toList()

    /** Every coordinate pair of an absolute M/L/C/A path (curve control points included, so conservative). */
    private fun points(d: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for (m in Regex("([MLCAZ])([^MLCAZ]*)").findAll(d)) {
            val n = Regex("-?\\d*\\.?\\d+").findAll(m.groupValues[2]).map { it.value.toDouble() }.toList()
            when (m.groupValues[1]) {
                "M", "L", "C" -> n.chunked(2).forEach { out += it[0] to it[1] }
                "A" -> n.chunked(7).forEach { out += it[5] to it[6] } // rx ry rot large sweep x y
            }
        }
        return out
    }

    @Test
    fun `launcher foreground and monochrome are vectors on a 108 viewport with every point inside the 66dp safe zone`() {
        for (name in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val text = File(res, "drawable/$name.xml").readText()
            assertTrue("<vector" in text && "viewportWidth=\"108\"" in text && "viewportHeight=\"108\"" in text, "$name must be a 108 vector")
            assertTrue("<group" !in text, "$name: transforms hide the real coordinates; bake them in")
            val all = pathDatas(name).flatMap { points(it) }
            assertTrue(all.size > 20, "$name: no path points found")
            for ((x, y) in all) {
                assertTrue(x in 21.0..87.0 && y in 21.0..87.0, "$name: point ($x,$y) leaves the safe zone 21..87")
                // inside the central 66dp circle too, allowing the 1.75dp half stroke
                assertTrue(Math.hypot(x - 54, y - 54) <= 33.5, "$name: point ($x,$y) is outside the 66dp circle")
            }
        }
    }

    @Test
    fun `the monochrome glyph is the foreground glyph in one colour`() {
        assertEquals(pathDatas("ic_launcher_foreground"), pathDatas("ic_launcher_monochrome"))
        val mono = File(res, "drawable/ic_launcher_monochrome.xml").readText()
        val colours = Regex("(?:fill|stroke)Color=\"(#[0-9A-Fa-f]+)\"").findAll(mono).map { it.groupValues[1].uppercase() }.toSet()
        assertEquals(setOf("#FFFFFF"), colours)
    }

    @Test
    fun `the foreground draws a stroked house and a phone, and the background is a solid colour`() {
        val fg = File(res, "drawable/ic_launcher_foreground.xml").readText()
        assertTrue("android:strokeLineCap=\"round\"" in fg && "android:strokeLineJoin=\"round\"" in fg)
        assertTrue(pathDatas("ic_launcher_foreground").size >= 3, "house, handset, call arcs")
        val bg = File(res, "drawable/ic_launcher_background.xml").readText()
        assertTrue("fillColor=\"#1F4E8C\"" in bg, "brand blue background")
    }

    @Test
    fun `the notification small icon is a flat single-colour 24 vector with every point inside 2 to 22`() {
        val text = File(res, "drawable/ic_stat_portico.xml").readText()
        assertTrue("<vector" in text && "viewportWidth=\"24\"" in text && "viewportHeight=\"24\"" in text, "must be a 24 vector")
        assertTrue("android:width=\"24dp\"" in text && "android:height=\"24dp\"" in text)
        assertTrue("<group" !in text && "<gradient" !in text && "aapt:attr" !in text, "no group transforms or gradients")
        val colours = Regex("(?:fill|stroke)Color=\"(#[0-9A-Fa-f]+)\"").findAll(text).map { it.groupValues[1].uppercase() }.toList()
        assertTrue(colours.isNotEmpty() && colours.all { it == "#FFFFFF" }, "only white allowed: $colours")
        assertTrue("android:fillAlpha" !in text && "android:strokeAlpha" !in text, "no alpha tricks")
        for (w in Regex("strokeWidth=\"([\\d.]+)\"").findAll(text)) assertTrue(w.groupValues[1].toDouble() >= 1.5, "stroke too thin for the status bar")
        val all = pathDatas("ic_stat_portico").flatMap { points(it) }
        assertTrue(all.size > 10, "no path points found")
        for ((x, y) in all) assertTrue(x in 2.0..22.0 && y in 2.0..22.0, "point ($x,$y) leaves 2..22")
    }

    @Test
    fun `no bitmap launcher images exist`() {
        val bitmaps = res.walkTopDown().filter { it.isFile && it.name.startsWith("ic_launcher") && it.extension in setOf("png", "webp", "jpg") }.toList()
        assertTrue(bitmaps.isEmpty(), "bitmap launcher images: $bitmaps")
    }

    @Test
    fun `the notification glyph is used by RingNotifier for both kinds and the stock icons are gone`() {
        val src = File("src/main/java/com/meda/portico/ring/RingNotifier.kt").readText()
        assertTrue("R.drawable.ic_stat_portico" in src)
        assertTrue("sym_call_incoming" !in src && "ic_dialog_info" !in src && "sym_call_missed" !in src, "a stock small icon remains")
    }
}
