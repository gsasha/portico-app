package com.meda.portico.home

import com.meda.portico.core.live.ChipLook
import com.meda.portico.core.live.LiveWhy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 0.6.50-alpha: the Live chip. One word "LIVE" in every state; before it the play symbol (live off) or the same red dot (live wanted:
 * connecting or playing), in one fixed slot; after it a fixed-width slot with the loading spinner (connecting) or the fps text (playing).
 * The picture itself is for a phone; these pin the decision and the wiring (working directory = app/).
 */
class LiveChipGlyphTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val glyph by lazy { src("home/LiveChipGlyph.kt") }
    private val tiles by lazy { src("home/StillTiles.kt") }
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }

    @Test
    fun `the icon before the word follows the live state, the dot is the same while connecting and playing`() {
        assertEquals(ChipLead.PLAY, LiveChipGlyphs.lead(ChipLook.OFFER), "live off: play")
        assertEquals(ChipLead.DOT, LiveChipGlyphs.lead(ChipLook.ON))
        assertEquals(ChipLead.DOT, LiveChipGlyphs.lead(ChipLook.PENDING), "connecting keeps the very same dot")
        assertNull(LiveChipGlyphs.lead(ChipLook.NONE))
        assertNull(LiveChipGlyphs.lead(ChipLook.ROAMING))
    }

    @Test
    fun `the trailing slot is the spinner while connecting and the fps once live`() {
        assertEquals(ChipTrail.SPINNER, LiveChipGlyphs.trail(ChipLook.PENDING, false, null, null))
        assertEquals(ChipTrail.SPINNER, LiveChipGlyphs.trail(ChipLook.PENDING, false, LiveWhy.CONNECT, null), "waiting to retry still tries")
        assertEquals(ChipTrail.FPS, LiveChipGlyphs.trail(ChipLook.ON, false, null, "24 fps"))
        assertEquals(ChipTrail.NONE, LiveChipGlyphs.trail(ChipLook.ON, false, null, null), "the document does not ask for fps")
        assertEquals(ChipTrail.NONE, LiveChipGlyphs.trail(ChipLook.PENDING, true, LiveWhy.CONNECT, null), "gave up: no spinner")
        for (w in listOf(LiveWhy.AWAY, LiveWhy.HOT, LiveWhy.NOT_SET_UP, LiveWhy.PHONE_LIMIT)) {
            assertEquals(ChipTrail.NONE, LiveChipGlyphs.trail(ChipLook.PENDING, false, w, null), "blocked, not connecting: $w")
        }
        assertEquals(ChipTrail.NONE, LiveChipGlyphs.trail(ChipLook.OFFER, false, null, "24 fps"), "live off: nothing after the word")
    }

    @Test
    fun `the label is one string, identical in every state`() {
        assertEquals("LIVE", Regex("""<string name="live_chip_label">(.*?)</string>""").find(strings)?.groupValues?.get(1))
        for (old in listOf("live_chip_on", "live_chip_offer", "live_chip_pending")) assertTrue(!strings.contains("name=\"$old\""), old)
        assertEquals(3, Regex("""R\.string\.live_chip_label""").findAll(tiles).count(), "off, connecting and on all use the one label")
        assertTrue(tiles.contains("chip.text = context.getString(text)"), "the word only; no glyph or fps is appended to the text")
        assertTrue(!strings.contains("▶") && !strings.contains("●"))
        // one style for every state: size, weight and spacing are set once at creation
        assertEquals(1, Regex("""styleLive\(it, CHIP_SP \* k""").findAll(tiles).count())
        assertTrue(!tiles.contains("letterSpacing") && !glyph.contains("letterSpacing"))
    }

    @Test
    fun `lead glyphs share one slot and the trail has one fixed width, both scaled by the same chip factor`() {
        assertTrue(glyph.contains("const val SLOT_DP = 8"))
        assertEquals(1, Regex("""SLOT_DP\s*=""").findAll(glyph).count(), "a single slot value")
        assertEquals(1, Regex("""ChipLeadDrawable\(it, slotPx, textColor\)""").findAll(glyph).count(), "both lead glyphs are built in one loop with the one slot")
        assertTrue(glyph.contains("ChipLead.values().associateWith"))
        assertTrue(glyph.contains("override fun getIntrinsicWidth() = slot") && glyph.contains("setBounds(0, 0, slot, slot)"))
        assertTrue(glyph.contains("tri.lineTo(s, s / 2)"), "the play triangle spans the whole slot width")
        assertTrue(glyph.contains("override fun getIntrinsicWidth() = width"), "the trail drawable reports one width whatever it draws")
        assertTrue(glyph.contains("Math.max(paint.measureText(LiveChipGlyphs.TRAIL_SAMPLE), textSize)"), "wide enough for the fps text and the spinner")
        assertTrue(tiles.contains("LiveChipView(context, scaled(LiveChipGlyphs.SLOT_DP, k), scaled(LiveChipGlyphs.GAP_DP, k), scaled(LiveChipGlyphs.TRAIL_GAP_DP, k))"))
        assertTrue(tiles.contains("val k = if (small) SMALL_CHIP else 1f"), "a small tile: the same k for text, slots and gaps")
    }

    @Test
    fun `the spinner animates only while connecting, attached and visible, and is cancelled otherwise`() {
        assertTrue(glyph.contains("trail == ChipTrail.SPINNER && isAttachedToWindow && visibility == View.VISIBLE && windowVisibility == View.VISIBLE"))
        assertTrue(glyph.contains("override fun onDetachedFromWindow() { trailDrawable?.stop()"))
        assertTrue(glyph.contains("override fun onVisibilityChanged"))
        assertTrue(glyph.contains("it.removeAllUpdateListeners(); it.cancel()") && glyph.contains("animator = null"), "no animator or listener is left behind")
        assertTrue(glyph.contains("repeatCount = ValueAnimator.INFINITE") && glyph.contains("canvas.rotate(angle"), "a rotating arc")
        assertTrue(tiles.contains("chip.show(LiveChipGlyphs.lead(ui.chip), LiveChipGlyphs.trail(ui.chip, v.canRetry, ui.notice, ui.fps), ui.fps)"))
    }
}
