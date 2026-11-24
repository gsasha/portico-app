package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Home polish (0.5.8-alpha), checked without a device (working directory = app/): no block heading repeating the page, no
 * second line under a Home camera tile, and a long button label that neither shouts nor fills the button. The pixels are for a phone.
 */
class HomePolishResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val buttons by lazy { src("home/ActionButtons.kt") }
    private val model by lazy { File("../core/src/main/kotlin/com/meda/portico/core/content/HomeModel.kt").readText() }

    /** The body of [name] in [text], up to the next function of the same indentation. */
    private fun body(text: String, name: String): String = text.substringAfter("fun $name(").substringBefore("\n    }\n")

    private val blockButton by lazy { body(buttons, "blockButton") }

    // ---- (a) the block heading ----

    @Test
    fun `Home's blocks go through BlockHeading with the page label`() {
        assertTrue(model.contains("it.copy(title = BlockHeading.choose(pageLabel, it.title))"))
        assertTrue(model.contains("index, page?.id, pageLabel)"), "the same cleaned label is the title and the comparison")
    }

    @Test
    fun `a block with no title draws no heading widget`() {
        assertTrue(src("home/BlockViews.kt").contains("if (block.title != null) {"))
    }

    // ---- (b) camera tile: label alone on Home ----

    @Test
    fun `a Home camera tile has no detail line, the ring and in-call tiles keep theirs`() {
        val tile = body(tiles, "tile")
        assertTrue(tile.contains("if (!interactive && tile.detail != null) {"), "detail only on the plain (non-Home) tiles")
        assertFalse(tile.contains("if (tile.detail != null)"))
        assertTrue(tiles.contains("tile(tiles[i], interactive = true)"), "Home's grid is the interactive one")
        assertTrue(tiles.contains("tile(tile, interactive = false)"), "rebuild (ring, in-call) is unchanged")
    }

    @Test
    fun `the model still carries the camera detail, and a tile's own label is the caption`() {
        assertTrue(model.contains("Label.clean(c.name, Label.MAX)?.takeIf { it != label }"))
        assertTrue(src("home/HomeActivity.kt").contains("tile.detail ?: tile.label"), "place requests still use it")
    }

    @Test
    fun `an unnamed camera tile adds no name line and is described neutrally`() {
        assertTrue(body(tiles, "tile").contains("if (tile.named && !fullscreen) box.addView(TextView(context).apply {\n            text = tile.label"))
        assertTrue(body(tiles, "describe").contains("if (v.tile.named) v.tile.label else context.getString(R.string.home_tile_unnamed)"))
        assertTrue(File("src/main/res/values/strings.xml").readText().contains("<string name=\"home_tile_unnamed\">Camera</string>"))
        assertTrue(model.contains("Label.text(c.label, Label.MAX)\n"), "a camera's blank label does not fall back to its id")
    }

    // ---- (c) block buttons ----

    @Test
    fun `a block button is not forced to capitals`() {
        assertTrue(blockButton.contains("isAllCaps = false"))
    }

    @Test
    fun `a block button label is at most two lines, cut with an ellipsis at the end`() {
        assertTrue(blockButton.contains("maxLines = 2"))
        assertTrue(blockButton.contains("ellipsize = TextUtils.TruncateAt.END"))
        assertTrue(buttons.contains("import android.text.TextUtils"))
    }

    @Test
    fun `TalkBack gets the whole label whatever the visible text is cut to`() {
        assertTrue(blockButton.contains("contentDescription = if (note != null) \"\$label, \$note\" else label"))
        assertFalse(blockButton.contains("if (tile.iconOnly || note != null) contentDescription"), "set for every block button, not only icon-only ones")
    }

    @Test
    fun `under a block button only the reason it is off, nothing else`() {
        val rows = body(buttons, "blockRows")
        assertTrue(rows.contains("noteText(t)"))
        assertFalse(rows.contains("tile.detail"))
        assertFalse(rows.contains("kindText"))
        assertFalse(rows.contains("home_asks_first"))
    }
}
