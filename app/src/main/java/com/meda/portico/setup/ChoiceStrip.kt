package com.meda.portico.setup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.meda.portico.R

/**
 * 0.6.65: a single-choice selection as a classic segmented control. One row, always: the segments are square, joined edge to edge
 * with no gaps, equal width (weight 1) and fill the full width; one 1dp outline around the strip and thin 1dp dividers between the
 * segments. A label wraps onto up to two lines (centered) so that every choice fits the screen width; the selected segment is filled.
 * Behaviour is the radio group's: one choice, [bind]'s onPick runs on a tap of a different segment, never while the strip is set up.
 */
class ChoiceStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density + 0.5f).toInt()

    init {
        orientation = HORIZONTAL
        setBackgroundResource(R.drawable.choice_strip_bg)
        val line = px(1f)
        setPadding(line, line, line, line) // the segments sit inside the outline
        showDividers = SHOW_DIVIDER_MIDDLE
        updateDivider()
    }

    private fun updateDivider() {
        val d = GradientDrawable()
        d.setColor(context.getColor(if (isEnabled) R.color.choice_stroke else R.color.choice_stroke_disabled))
        d.setSize(px(1f), 0)
        dividerDrawable = d
    }

    /** One segment per [values], labelled by [labels], [current] selected. */
    fun <T : Any> bind(values: List<T>, labels: Map<T, Int>, current: T, onPick: (T) -> Unit) {
        removeAllViews()
        val segments = ArrayList<TextView>()
        for (v in values) {
            val b = TextView(context)
            val label = context.getString(labels.getValue(v))
            b.text = label
            b.contentDescription = label
            b.gravity = Gravity.CENTER
            b.textAlignment = View.TEXT_ALIGNMENT_CENTER
            b.maxLines = MAX_LINES
            b.ellipsize = TextUtils.TruncateAt.END // last resort, a single word wider than its segment
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            b.minHeight = px(MIN_HEIGHT_DP)
            b.setPadding(px(4f), px(4f), px(4f), px(4f))
            b.setBackgroundResource(R.drawable.choice_bg)
            b.setTextColor(context.getColorStateList(R.color.choice_text))
            b.isClickable = true
            b.isSelected = v == current
            b.isEnabled = isEnabled
            b.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            }
            b.setOnClickListener {
                if (b.isSelected) return@setOnClickListener
                segments.forEach { it.isSelected = it === b; it.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED) }
                onPick(v)
            }
            segments.add(b)
            addView(b, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (i in 0 until childCount) getChildAt(i).isEnabled = enabled
        updateDivider()
    }

    companion object {
        const val MAX_LINES = 2
        const val TEXT_SP = 13f
        const val MIN_HEIGHT_DP = 40f
    }
}
