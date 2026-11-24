package com.meda.portico.home

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.meda.portico.AppGraph
import com.meda.portico.R
import com.meda.portico.core.content.ButtonPalette
import com.meda.portico.core.content.ButtonReason
import com.meda.portico.core.content.ButtonFlow
import com.meda.portico.core.content.ButtonState
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.ContentRepository
import com.meda.portico.core.content.DefaultButtonLook
import com.meda.portico.core.content.HomeIcons
import com.meda.portico.core.content.RunResult

/**
 * A button of the document (a page's action, or a caller's ring or call button) as widgets, shared by Home and the
 * ring and in-call screens. Drawing only: whether it is enabled is the tile's [ButtonState], decided in `:core`.
 */
class ActionButtons(private val context: Context) {

    private fun dp(n: Int): Int = (n * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * A tile: the button (icon before the label, off unless ready; a live call's own button stays tappable) over one small
     * line (HA's name, the kind, "asks first", why it is off).
     */
    fun tile(tile: ButtonTile, onPress: (ButtonTile) -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(Button(context).apply {
            text = if (tile.state == ButtonState.RUNNING) "${tile.label} ${context.getString(R.string.home_running)}" else tile.label
            isEnabled = tile.state == ButtonState.READY || tile.state == ButtonState.CALLING
            minHeight = dp(56)
            setOnClickListener { onPress(tile) }
            applyIcon(this, HomeIcons.resolve(tile.icon, tile.kind))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val bits = buildList {
            tile.detail?.let { add(it) }
            add(kindText(tile.kind))
            if (tile.confirm) add(context.getString(R.string.home_asks_first))
            noteText(tile)?.let { add(it) }
        }
        box.addView(TextView(context).apply {
            text = bits.joinToString(" · ")
            textSize = 12f
            alpha = 0.7f
            setPadding(dp(4), 0, 0, 0)
        })
        return box
    }

    /**
     * A block's buttons as widgets (Home): one [ButtonFlowLayout] with every button (icon, optional text, palette colour) in
     * rows that `:core`'s [ButtonFlow] fits to the width (compact, then stretched, all of one height), then one small line
     * naming the buttons that are off and why. Drawing only.
     */
    fun blockRows(tiles: List<ButtonTile>, onPress: (ButtonTile) -> Unit, compact: Boolean = false): List<View> {
        val flow = ButtonFlowLayout(context, tiles.map { !it.iconOnly }, compact)
        for (tile in tiles) flow.addView(blockButton(tile, onPress))
        val out = ArrayList<View>()
        out.add(flow)
        val notes = tiles.mapNotNull { t -> noteText(t)?.let { "${t.label}: $it" } }
        if (notes.isNotEmpty()) {
            out.add(TextView(context).apply {
                text = notes.joinToString(" · ")
                textSize = 12f
                alpha = 0.7f
                setPadding(dp(4), 0, 0, dp(4))
            })
        }
        return out
    }

    /**
     * One block button. With a palette colour: a rounded fill with that colour's text (the light or the dark swatch by the
     * system theme). Without: the tonal default look ([DefaultButtonLook], derived from the theme's own page and text colours:
     * a rounded fill, a 1 dp outline, the icon in the text colour). A button that is off is drawn grey (palette) or dimmed
     * (default) whatever its colour, except a text Talk button, which keeps the platform's own off look. An icon-only button
     * is an [ImageButton] (a square of [ICON_ONLY_BOX] dp, the icon centred by its padding on both axes, no text), so its `contentDescription`
     * carries the label (and why it is off) and a long press shows the label as a tooltip.
     */
    private fun blockButton(tile: ButtonTile, onPress: (ButtonTile) -> Unit): View {
        // A talk of this phone under way stays tappable: the same button cancels it (connecting) or stops it (in the call).
        val on = tile.state == ButtonState.READY || tile.state == ButtonState.CALLING || tile.state == ButtonState.TALK_CONNECTING || tile.state == ButtonState.TALK_IN_CALL || tile.state == ButtonState.RINGING_IN
        val note = noteText(tile)
        val label = if (tile.state == ButtonState.RUNNING) "${tile.label} ${context.getString(R.string.home_running)}" else tile.label
        val swatch = ButtonPalette.swatch(tile.color, isDark())?.let { if (on) it else ButtonPalette.disabled(isDark()) }
        // APP-02: a call under way on a Talk or call button turns it green (the intercom app's green) with a white icon, no animation (0.6.48: the wave pattern is gone). Idle, the icon alone is green.
        val inCall = tile.state == ButtonState.CALLING || tile.state == ButtonState.TALK_CONNECTING || tile.state == ButtonState.TALK_IN_CALL || tile.state == ButtonState.RINGING_IN
        val basePaint = when {
            inCall && (tile.kind == "talk" || tile.kind == "call") -> Paint(CALL_GREEN, null, Color.WHITE, 0x33FFFFFF)
            swatch != null -> Paint(swatch.fill, null, swatch.text, (swatch.text and 0x00FFFFFF) or 0x33000000)
            // A Talk button with text keeps the platform's own look while it is off (its reason line says why).
            tile.kind == "talk" && !on && !tile.iconOnly -> null
            else -> defaultLook(on).let { Paint(it.fill, it.outline, it.icon, it.ripple) }
        }
        val isCallKind = tile.kind == "talk" || tile.kind == "call"
        val paint = if (isCallKind && !inCall && on && basePaint != null) Paint(basePaint.fill, basePaint.outline, CALL_GREEN, basePaint.ripple) else basePaint // idle: the icon (and a text label) green
        val iconKey = HomeIcons.callIcon(tile.kind, inCall) ?: HomeIcons.resolve(tile.icon, tile.kind)
        val view: View = if (tile.iconOnly) {
            iconOnlyButton(iconKey, paint)
        } else {
            Button(context).apply {
                text = label
                // The label as given (not forced ALL CAPS), at most two lines, cut with an ellipsis; TalkBack reads it whole.
                isAllCaps = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                minHeight = dp(BUTTON_HEIGHT)
                minimumHeight = dp(BUTTON_HEIGHT)
                // Compact: no platform minimum width and a slim side padding, so more buttons share a row (the flow stretches them).
                minWidth = 0
                minimumWidth = 0
                setPaddingRelative(dp(TEXT_PAD), paddingTop, dp(TEXT_PAD), paddingBottom)
                if (paint != null) {
                    background = roundedBackground(paint)
                    stateListAnimator = null
                    setTextColor(paint.ink)
                }
                applyIcon(this, HomeIcons.resolve(tile.icon, tile.kind))
            }
        }
        return view.apply {
            isEnabled = on
            contentDescription = if (note != null) "$label, $note" else label // the whole label, however the visible text is cut
            // A button that asks first is held, not tapped (0.5.10-alpha): no dialog, no tooltip (a long press IS the gesture).
            val held = tile.confirm && tile.kind != "call" && tile.kind != "talk"
            if (held) contentDescription = context.getString(R.string.home_hold_description, contentDescription)
            if (tile.kind == "talk" || tile.talkRoute) talkAccessibility(this, tile, note)
            if (held) {
                holdToConfirm(this, tile, on, paint?.ink ?: defaultLook(on).icon, onPress)
            } else {
                setOnClickListener { onPress(tile) }
                if (tile.iconOnly) TooltipCompat.setTooltipText(this, label)
            }
        }
    }

    /**
     * Makes [button] a hold-to-confirm button: finger down fills it green over [com.meda.portico.core.content.HoldToConfirm.HOLD_MS]
     * and then, once, runs [onPress] (which on Home goes straight to the run, [pressHeld]); a lift, a move off the button or a
     * cancel before that resets it and sends nothing; a quick tap says to hold. Only an enabled button listens to the finger.
     * TalkBack cannot hold and wait reliably, so it gets the action "Confirm and run" that runs [onPress] at once; its plain
     * click (or a keyboard Enter) says to hold. The tooltip is not set: it is a long press too.
     */
    private fun holdToConfirm(button: View, tile: ButtonTile, on: Boolean, ink: Int, onPress: (ButtonTile) -> Unit) {
        val hint = { Toast.makeText(context, R.string.home_hold_hint, Toast.LENGTH_SHORT).show() }
        button.setOnClickListener { hint() }
        if (!on) return
        val fill = HoldFillDrawable(context.resources.displayMetrics.density, isDark(), ink, 8)
        button.overlay.add(fill)
        button.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fill.setBounds(0, 0, v.width, v.height) }
        HoldToConfirmTouch(button, fill, onFire = { onPress(tile) }, onHint = hint)
        ViewCompat.addAccessibilityAction(button, context.getString(R.string.home_hold_action)) { _, _ ->
            onPress(tile)
            true
        }
    }

    /** What a block button is drawn with: its fill, its optional 1 dp outline, the colour of its icon and text, and its ripple. */
    private class Paint(val fill: Int, val outline: Int?, val ink: Int, val ripple: Int)

    /** The default (no colour) look for the theme in use: its page and primary text colour, or the palette's pages when unresolvable. */
    private fun defaultLook(enabled: Boolean): DefaultButtonLook.Look {
        val fallbackPage = if (isDark()) ButtonPalette.PAGE_DARK else ButtonPalette.PAGE_LIGHT
        val a = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground, android.R.attr.textColorPrimary))
        val page = a.getColor(0, fallbackPage)
        val text = a.getColorStateList(1)?.defaultColor ?: if (isDark()) 0xFFFFFFFF.toInt() else 0xDE000000.toInt()
        a.recycle()
        return DefaultButtonLook.look(page, text, enabled)
    }

    /** A rounded rectangle (8 dp) of the paint's fill and optional 1 dp outline, with a press ripple clipped to the same shape. */
    private fun roundedBackground(paint: Paint): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(paint.fill)
            cornerRadius = dp(8).toFloat()
            if (paint.outline != null) setStroke(dp(1), paint.outline)
        }
        val mask = GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            cornerRadius = dp(8).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(paint.ripple), shape, mask)
    }

    /**
     * An icon-only button: a real image view, not a text button with an empty label. A [ICON_ONLY_BOX] square whose padding
     * is exactly (box - icon) / 2 on every side, so the icon (scaled to fit the rest, centred) is in the middle on both axes
     * whatever the theme's text direction.
     */
    private fun iconOnlyButton(key: String?, paint: Paint?): ImageButton = ImageButton(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (dp(ICON_ONLY_BOX) - dp(ICON_ONLY_ICON)) / 2
        setPadding(pad, pad, pad, pad)
        minimumWidth = 0
        minimumHeight = 0
        if (paint != null) {
            background = roundedBackground(paint)
            stateListAnimator = null
        }
        val icon = key?.let { loadIcon(it) }
        if (icon != null) {
            if (paint != null) icon.setTint(paint.ink)
            setImageDrawable(icon)
        }
    }

    private fun loadIcon(key: String): Drawable? {
        val res = context.resources
        val id = res.getIdentifier(HomeIcons.drawableName(key), "drawable", context.packageName)
        if (id == 0) return null
        return androidx.core.content.res.ResourcesCompat.getDrawable(res, id, context.theme)?.mutate()
    }

    /**
     * TalkBack for a Talk button (blocks B7b): its state is announced after the label ("Ready", "Connecting to the gate", "In call", or
     * why it is off), and the click is named for what it will do: Cancel while connecting, Stop in the call. A button that is off
     * is disabled, so TalkBack reads it as dimmed and offers no click.
     */
    private fun talkAccessibility(button: View, tile: ButtonTile, note: String?) {
        val state = when (tile.state) {
            ButtonState.READY -> context.getString(R.string.home_talk_state_ready)
            ButtonState.TALK_CONNECTING -> context.getString(R.string.home_talk_state_connecting)
            ButtonState.TALK_IN_CALL -> context.getString(R.string.home_talk_state_in_call)
            ButtonState.RINGING_IN -> context.getString(R.string.home_talk_state_ringing)
            else -> note
        }
        if (state != null) ViewCompat.setStateDescription(button, state)
        val click = when (tile.state) {
            ButtonState.TALK_CONNECTING -> R.string.home_talk_action_cancel
            ButtonState.TALK_IN_CALL -> R.string.home_talk_action_stop
            else -> null
        }
        if (click != null) {
            ViewCompat.replaceAccessibilityAction(button, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, context.getString(click), null)
        }
    }

    /** The system theme: the palette has a light and a dark swatch per colour. */
    private fun isDark(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The words for a button that is off or live: its known server reason first (Talk), else its state's; null for none. */
    fun noteText(tile: ButtonTile): String? =
        tile.endLine ?: tile.reason?.let { context.getString(reasonText(it)) } ?: stateText(tile.state)?.let { context.getString(it) }

    private fun reasonText(reason: ButtonReason): Int = when (reason) {
        ButtonReason.TALK_OFF -> R.string.home_reason_talk_off
        ButtonReason.NO_DOOR -> R.string.home_reason_no_door
        ButtonReason.NOT_CONNECTED -> R.string.home_reason_not_connected
        ButtonReason.BUSY -> R.string.home_reason_busy
        ButtonReason.PHONE_ONLY -> R.string.home_reason_phone_only
        ButtonReason.CONNECTING -> R.string.home_reason_connecting
        ButtonReason.IN_CALL -> R.string.home_reason_in_call
    }

    /**
     * The icon before the text of a text button, 24 dp (a little taller than the text) and tinted with the button's own text
     * colours (so light and dark themes and the disabled look follow for free). A compound drawable is not an accessibility
     * node, and the label already says what the button does, so nothing is announced twice. A key with no drawable (cannot
     * happen: a test pins every key) leaves the button as plain text. An icon-only button does not come here: it is an
     * [ImageButton] (see [iconOnlyButton]).
     */
    private fun applyIcon(button: Button, key: String?) {
        if (key == null) return
        val icon = loadIcon(key) ?: return
        val size = dp(TEXT_ICON)
        icon.setBounds(0, 0, size, size)
        icon.setTintList(button.textColors)
        button.compoundDrawablePadding = dp(8)
        button.setCompoundDrawablesRelative(icon, null, null, null)
    }

    /** The word under a button that is off or live, if any. Call states are the server's reason, worded here. */
    private fun stateText(state: ButtonState): Int? = when (state) {
        ButtonState.UNAVAILABLE -> R.string.home_unavailable
        ButtonState.CALLING -> R.string.home_call_live
        ButtonState.TALK_CONNECTING -> R.string.home_talk_connecting
        ButtonState.TALK_IN_CALL -> R.string.home_talk_in_call
        ButtonState.RINGING_IN -> R.string.home_talk_ringing
        ButtonState.TARGET_OFFLINE -> R.string.home_call_offline
        ButtonState.TARGET_BUSY -> R.string.home_call_busy
        ButtonState.TARGET_DND -> R.string.home_call_dnd
        ButtonState.TARGET_RECEIVE_OFF -> R.string.home_call_receive_off
        ButtonState.TARGET_GONE -> R.string.home_call_gone
        ButtonState.CALLS_NOT_AVAILABLE -> R.string.home_call_not_available
        ButtonState.READY, ButtonState.RUNNING, ButtonState.OFFLINE -> null
    }

    private fun kindText(kind: String): String = when (kind) {
        "portico_open" -> context.getString(R.string.home_kind_portico_open)
        "lock_open" -> context.getString(R.string.home_kind_lock_open)
        "button_press" -> context.getString(R.string.home_kind_button_press)
        "script_run" -> context.getString(R.string.home_kind_script_run)
        "scene_activate" -> context.getString(R.string.home_kind_scene_activate)
        "call" -> context.getString(R.string.home_kind_call)
        "lock_lock" -> context.getString(R.string.home_kind_lock_lock)
        "lock_unlock" -> context.getString(R.string.home_kind_lock_unlock)
        "cover_open" -> context.getString(R.string.home_kind_cover_open)
        "cover_close" -> context.getString(R.string.home_kind_cover_close)
        "talk" -> context.getString(R.string.home_kind_talk)
        else -> kind
    }

    companion object {
        /** An icon-only button is this many dp square (at least the 48 dp touch target), its icon this many; the icon beside text this many. */
        const val ICON_ONLY_BOX = 56

        /** Every button of a row is this tall at least (Talk and Call, icon-only, used to be 64 against the text buttons' 56). */
        const val BUTTON_HEIGHT = 56
        const val ICON_ONLY_ICON = 30
        const val TEXT_ICON = 24
        const val TEXT_PAD = 10

        /** The intercom app's green (`RingActivity`'s `#16a34a`, its Answer button and in-call hang-up glyph). */
        const val CALL_GREEN = 0xFF16A34A.toInt()

        /**
         * A press of a non-call button: when it "asks first" a dialog does, then the run goes to HA by its action id
         * (the phone never names a service or an entity). [say] is told when nothing was sent because the app is paused.
         */
        fun press(context: Context, graph: AppGraph, tile: ButtonTile, say: (String) -> Unit) {
            if (tile.state != ButtonState.READY) return
            if (tile.confirm) {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.home_confirm_title, tile.label))
                    .setMessage(context.getString(R.string.home_confirm_message, tile.label))
                    .setPositiveButton(R.string.home_confirm_yes) { _, _ -> run(context, graph, tile, say) }
                    .setNegativeButton(R.string.home_confirm_no, null)
                    .show()
            } else {
                run(context, graph, tile, say)
            }
        }

        /**
         * A press of a button that asks first, once it was CONFIRMED by a hold (or TalkBack's "Confirm and run"): the run, with
         * no dialog. Home only; the ring and in-call screens keep [press] and its dialog.
         */
        fun pressHeld(context: Context, graph: AppGraph, tile: ButtonTile, say: (String) -> Unit) {
            if (tile.state != ButtonState.READY) return
            run(context, graph, tile, say)
        }

        private fun run(context: Context, graph: AppGraph, tile: ButtonTile, say: (String) -> Unit) {
            // False only while paused (or already running): the socket not being up is answered by the repository.
            if (!graph.runAction(tile.id) && graph.isPaused()) say(context.getString(R.string.home_run_paused))
        }
    }
}

/** The one line that says how the last press ended, for 20 seconds, on any screen that has buttons. */
object RunLine {
    const val SHOWN_MS = 20_000L

    fun text(context: Context, content: ContentRepository, nowWallMs: Long = System.currentTimeMillis()): String? {
        val run = content.lastRun?.takeIf { nowWallMs - it.atMs < SHOWN_MS } ?: return null
        val label = com.meda.portico.core.ha.Label.text(content.action(run.actionId)?.label, fallback = run.actionId)
        return context.getString(
            when (run.result) {
                RunResult.OK -> R.string.home_run_ok
                RunResult.NOT_CONNECTED -> R.string.home_run_not_connected
                RunResult.RATE_LIMITED -> R.string.home_run_rate_limited
                RunResult.UNAVAILABLE -> R.string.home_run_unavailable
                RunResult.NOT_ALLOWED -> R.string.home_run_not_allowed
                RunResult.FAILED -> R.string.home_run_failed
            },
            label,
        )
    }
}
