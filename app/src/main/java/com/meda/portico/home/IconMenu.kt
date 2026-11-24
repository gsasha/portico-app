package com.meda.portico.home

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 0.6.49: the camera size chooser as a short column of ICONS (0.6.53: the video-size menu is a text list, see [Entry.list]) in a dark rounded panel (like the YouTube player's), opened UPWARD from its
 * button: the panel's bottom edge sits on the button's top edge; with no room above it flips below, and it is always clamped to the visible
 * window. An entry is an icon (or, for Auto, a short word), always with a [Entry.description] for TalkBack; the checked one has a light disc.
 * Drawing only: which entries and what they do is [ViewportController]'s.
 */
class IconMenu(private val context: Context) {
    /** One row: [icon] (a drawable) or else [text]; a [note] is a small non-clickable line. */
    class Entry(
        val id: Int,
        val icon: Int?,
        val text: String?,
        val description: String,
        val checked: Boolean,
        val note: Boolean = false,
        /** 0.6.53: a row of the YouTube-style TEXT list (a check mark, then [text]) instead of a 48 dp cell. */
        val list: Boolean = false,
        /** A [list] row that is only information (dimmed, no click). */
        val clickable: Boolean = true,
    )

    private fun dp(n: Int): Int = (n * context.resources.displayMetrics.density + 0.5f).toInt()

    fun show(anchor: View, entries: List<Entry>, onPick: (Int) -> Unit, onDismiss: () -> Unit): PopupWindow {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { setColor(0xEE212121.toInt()); cornerRadius = dp(12).toFloat() }
        }
        var window: PopupWindow? = null
        for (e in entries) {
            if (e.note) {
                column.addView(
                    TextView(context).apply {
                        text = e.text
                        setTextColor(0xFFBBBBBB.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        maxWidth = dp(160)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                continue
            }
            if (e.list) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(16), 0)
                    minimumHeight = dp(44)
                }
                row.addView(
                    TextView(context).apply {
                        text = if (e.checked) "\u2713" else ""
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the row says it is selected
                    },
                    LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                row.addView(
                    TextView(context).apply {
                        text = e.text
                        setTextColor(if (e.clickable) Color.WHITE else 0xFF9E9E9E.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        if (e.checked) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                row.contentDescription = e.description
                row.isSelected = e.checked
                row.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (e.checked) 0x33FFFFFF else Color.TRANSPARENT) }
                if (e.clickable) {
                    row.isClickable = true
                    row.isFocusable = true
                    row.setOnClickListener {
                        window?.dismiss()
                        onPick(e.id)
                    }
                }
                column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                continue
            }
            val cell: View = if (e.icon != null) {
                ImageView(context).apply {
                    setImageResource(e.icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
            } else {
                TextView(context).apply {
                    text = e.text
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
            }
            cell.contentDescription = e.description
            cell.isClickable = true
            cell.isFocusable = true
            cell.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (e.checked) 0x44FFFFFF else Color.TRANSPARENT) }
            cell.setOnClickListener {
                window?.dismiss()
                onPick(e.id)
            }
            column.addView(cell, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        column.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = column.measuredWidth
        val h = column.measuredHeight
        val popup = PopupWindow(column, w, h, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setOnDismissListener { onDismiss() }
        }
        window = popup
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        val p = place(at[0], at[1], anchor.width, anchor.height, w, h, frame.left, frame.top, frame.right, frame.bottom)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, p.first, p.second)
        return popup
    }

    companion object {
        /** The top-left of a [w] x [h] panel for an anchor at ([ax], [ay]) of size [aw] x [ah], inside the visible frame: above the anchor, else below, right edges aligned, clamped. Pure. */
        fun place(ax: Int, ay: Int, aw: Int, ah: Int, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
            var y = ay - h // upward: the panel's bottom edge on the button's top edge
            if (y < top) y = ay + ah // no room above: flip below
            y = y.coerceAtMost(bottom - h).coerceAtLeast(top)
            val x = (ax + aw - w).coerceAtMost(right - w).coerceAtLeast(left)
            return x to y
        }
    }
}
