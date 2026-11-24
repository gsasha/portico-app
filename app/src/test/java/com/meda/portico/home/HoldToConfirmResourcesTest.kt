package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Hold to confirm (0.5.10-alpha), checked without a device (working directory = app/): a Home button that asks first is held for
 * a second with a green fill instead of answered in a dialog; the ring and in-call screens keep their dialog; the glue fires
 * once, cancels on every way a touch can end and leaves nothing behind. The pixels and the feel are for a phone.
 */
class HoldToConfirmResourcesTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val buttons by lazy { src("home/ActionButtons.kt") }
    private val touch by lazy { src("home/HoldToConfirmTouch.kt") }
    private val home by lazy { src("home/HomeActivity.kt") }
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }
    private val blockButton by lazy { buttons.substringAfter("private fun blockButton(").substringBefore("    /** What a block button is drawn with") }
    private val companion by lazy { buttons.substringAfter("companion object") }
    private val onPress by lazy { home.substringAfter("private fun onPress(").substringBefore("private fun pressTalk(") }

    @Test
    fun `a Home button that asks first is held, with no dialog on Home`() {
        assertTrue(blockButton.contains("val held = tile.confirm && tile.kind != \"call\" && tile.kind != \"talk\""))
        assertTrue(blockButton.contains("holdToConfirm(this, tile, on,"))
        // Home's press for a confirm tile is the dialog-free one
        assertTrue(onPress.contains("if (tile.confirm) ActionButtons.pressHeld(this, graph, tile, ::say)"))
        val held = companion.substringAfter("fun pressHeld(").substringBefore("private fun run(")
        assertFalse(held.contains("AlertDialog"), "the held press never opens the dialog")
        assertTrue(held.contains("run(context, graph, tile, say)"))
        assertTrue(held.contains("if (tile.state != ButtonState.READY) return"))
    }

    @Test
    fun `the ring and in-call screens keep the dialog and never use the held press`() {
        val press = companion.substringAfter("fun press(").substringBefore("fun pressHeld(")
        assertTrue(press.contains("AlertDialog.Builder(context)") && press.contains("if (tile.confirm) {"))
        for (f in listOf("call/InCallActivity.kt")) {
            val text = src(f)
            assertTrue(text.contains("ActionButtons.press(this, PorticoApp.graph(this), tile)") || text.contains("ActionButtons.press(this, graph, tile)"), f)
            assertFalse(text.contains("pressHeld") || text.contains("HoldToConfirm") || text.contains("blockRows"), f)
            assertTrue(text.contains("actionButtons.tile(tile, ::onExtraPress)"), f)
        }
        val tile = buttons.substringAfter("fun tile(").substringBefore("fun blockRows(")
        assertFalse(tile.contains("hold") || tile.contains("Hold"), "the shared tile (ring, in-call) is exactly as it was")
        assertTrue(strings.contains("home_confirm_title") && strings.contains("home_confirm_yes"), "the dialog's strings stay")
    }

    @Test
    fun `a button that does not ask first still runs on a plain tap and keeps its tooltip`() {
        assertTrue(blockButton.contains("setOnClickListener { onPress(tile) }"))
        assertTrue(blockButton.contains("if (tile.iconOnly) TooltipCompat.setTooltipText(this, label)"))
        // and both are in the not-held branch
        val notHeld = blockButton.substringAfter("} else {").substringBefore("    /**")
        assertTrue(notHeld.contains("setOnClickListener { onPress(tile) }") && notHeld.contains("TooltipCompat.setTooltipText"))
    }

    @Test
    fun `the held branch sets no tooltip and a plain click only says to hold`() {
        val hold = blockButton.substringAfter("private fun holdToConfirm(")
        assertFalse(hold.contains("TooltipCompat"), "a long press is the gesture; no tooltip")
        assertTrue(hold.contains("button.setOnClickListener { hint() }"))
        assertTrue(hold.contains("R.string.home_hold_hint"))
        assertEquals(1, Regex("TooltipCompat\\.setTooltipText").findAll(blockButton).count(), "the one tooltip is the not-held one")
    }

    @Test
    fun `TalkBack gets a Confirm and run action that runs the press, and the description says to hold`() {
        val hold = blockButton.substringAfter("private fun holdToConfirm(")
        assertTrue(hold.contains("ViewCompat.addAccessibilityAction(button, context.getString(R.string.home_hold_action))"))
        assertTrue(hold.substringAfter("addAccessibilityAction").contains("onPress(tile)"))
        assertTrue(blockButton.contains("context.getString(R.string.home_hold_description, contentDescription)"))
        assertTrue(strings.contains("<string name=\"home_hold_hint\">Hold to confirm</string>"))
        assertTrue(strings.contains("<string name=\"home_hold_action\">Confirm and run</string>"))
        assertTrue(strings.contains("<string name=\"home_hold_description\">%1\$s. Hold to confirm</string>"))
    }

    @Test
    fun `only an enabled button listens to the finger`() {
        val hold = blockButton.substringAfter("private fun holdToConfirm(")
        assertTrue(hold.indexOf("if (!on) return") in 0 until hold.indexOf("HoldToConfirmTouch("))
    }

    @Test
    fun `the action fires from one place, on the machine's FIRE, and the haptic comes first`() {
        assertEquals(1, Regex("onFire\\(\\)").findAll(touch).count(), "one call site for the action")
        val fire = touch.substringAfter("private fun fire()").substringBefore("/** The touch is over")
        assertTrue(fire.indexOf("performHapticFeedback") in 0 until fire.indexOf("onFire()"))
        assertTrue(fire.contains("HapticFeedbackConstants.CONFIRM") && fire.contains("HapticFeedbackConstants.LONG_PRESS"))
        assertTrue(fire.contains("Build.VERSION.SDK_INT >= 30"))
        // both callers of fire() are guarded by the machine's FIRE outcome
        assertEquals(2, Regex("outcome == HoldState\\.Outcome\\.FIRE").findAll(touch).count())
        assertEquals(2, Regex("[^a-zA-Z.]fire\\(\\)").findAll(touch.replace("private fun fire()", "")).count())
        // the up after a fire is the machine's NONE: nothing else may call the action
        assertFalse(touch.contains("performClick"))
    }

    @Test
    fun `every way a touch can end resets the fill and runs nothing`() {
        assertTrue(touch.contains("MotionEvent.ACTION_CANCEL -> abort()"))
        assertTrue(touch.contains("if (!inside(v, e)) abort() else v.drawableHotspotChanged(e.x, e.y)"), "moving off the button")
        assertTrue(touch.contains("override fun onViewDetachedFromWindow(v: View) = abort()"), "the view going away")
        assertTrue(touch.contains("!view.isAttachedToWindow || !view.hasWindowFocus() || !view.isShown"), "the screen going away")
        val up = touch.substringAfter("MotionEvent.ACTION_UP ->").substringBefore("MotionEvent.ACTION_CANCEL")
        assertTrue(up.contains("if (!inside(v, e)) {\n                    abort()"), "a lift off the button is a cancel")
    }

    @Test
    fun `the ScrollView may not take a held touch, and gets it back when the touch ends`() {
        val down = touch.substringAfter("MotionEvent.ACTION_DOWN ->").substringBefore("MotionEvent.ACTION_MOVE")
        assertTrue(down.contains("v.parent?.requestDisallowInterceptTouchEvent(true)"))
        assertTrue(touch.substringAfter("private fun stop()").contains("view.parent?.requestDisallowInterceptTouchEvent(false)"))
        assertTrue(touch.contains("scaledTouchSlop"))
    }

    @Test
    fun `no callback outlives the touch or the view`() {
        val stop = touch.substringAfter("private fun stop()")
        assertTrue(stop.contains("view.removeCallbacks(frame)") && stop.contains("view.removeCallbacks(resetFill)"))
        assertTrue(touch.contains("addOnAttachStateChangeListener"))
        assertTrue(touch.contains("view.postOnAnimation"), "frames are display-synced, not a free-running thread")
        assertFalse(touch.contains("Thread") || touch.contains("Timer") || touch.contains("CountDownTimer"))
        // a fresh down clears the previous touch's callbacks before it starts (a late reset must not wipe a new fill)
        val down = touch.substringAfter("MotionEvent.ACTION_DOWN ->").substringBefore("MotionEvent.ACTION_MOVE")
        assertTrue(down.indexOf("stop()") in 0 until down.indexOf("state.down("))
    }

    @Test
    fun `the fill is drawn over the button, clipped to its rounded corners, in the core colours`() {
        assertTrue(touch.contains("canvas.clipPath(clip)") && touch.contains("addRoundRect"))
        assertTrue(touch.contains("HoldFillLook.layer(dark)") && touch.contains("HoldFillLook.green(dark)"))
        assertTrue(buttons.contains("button.overlay.add(fill)"))
        assertTrue(buttons.contains("HoldFillDrawable(context.resources.displayMetrics.density, isDark(), ink, 8)"), "the same 8 dp radius as the button")
        assertEquals(1, Regex("cornerRadius = dp\\(8\\)").findAll(buttons.substringAfter("private fun roundedBackground(").substringBefore("mask")).count())
        assertTrue(blockButton.contains("paint?.ink ?: defaultLook(on).icon"), "the ring is the button's own ink")
    }

    @Test
    fun `the glue uses the core machine and the hold is the core constant`() {
        assertTrue(touch.contains("private val state = HoldState()"))
        assertFalse(Regex("1000|1_000").containsMatchIn(touch), "the duration lives in :core")
    }

    @Test
    fun `the hint is a short Toast and never the dialog`() {
        assertTrue(buttons.contains("Toast.makeText(context, R.string.home_hold_hint, Toast.LENGTH_SHORT).show()"))
        assertTrue(touch.contains("onHint()"))
    }

    @Test
    fun `the ring and in-call sources and the call and talk paths are untouched by the hold`() {
        // call and talk buttons never ask (core clears confirm for them) and are never held
        assertTrue(File("../core/src/main/kotlin/com/meda/portico/core/content/HomeModel.kt").readText().contains("confirm = a.confirm && !a.isCall && !a.isTalk"))
        assertTrue(blockButton.contains("tile.kind != \"call\" && tile.kind != \"talk\""))
        assertEquals(listOf("ActionButtons.kt"), File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("HoldToConfirmTouch(") && it.name != "HoldToConfirmTouch.kt" }.map { it.name }.toList())
    }
}
