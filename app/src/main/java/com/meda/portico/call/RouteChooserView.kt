package com.meda.portico.call

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import com.meda.portico.R
import com.meda.portico.core.callbar.CallRoute

/**
 * APP-02: the audio route chooser, Speaker / Bluetooth / Phone, one tap each. It replaces A-06's one cycling key.
 * Bluetooth is drawn only while a Bluetooth audio device is connected; the whole chooser is gone when there is nothing to
 * choose (a tablet). The lit word is where the sound goes now, so Auto (nothing chosen yet) lights what proximity picked.
 * Drawing only: the rules are `:core`'s [com.meda.portico.core.callbar.CallRoutes] and [com.meda.portico.core.call.AudioRoutePlan].
 */
class RouteChooserView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val buttons = LinkedHashMap<CallRoute, Button>()
    private var control: CallAudioControl? = null

    init {
        orientation = HORIZONTAL
        for ((route, label) in listOf(CallRoute.SPEAKER to R.string.route_speaker, CallRoute.BLUETOOTH to R.string.route_bluetooth, CallRoute.PHONE to R.string.route_phone)) {
            val b = Button(context).apply {
                setText(label)
                isAllCaps = false
                maxLines = 2
                minHeight = dp(48)
                minimumHeight = dp(48)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { control?.choose(com.meda.portico.core.callbar.CallRoutes.choiceFor(route)) }
            }
            buttons[route] = b
            addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = if (childCount == 0) 0 else dp(4) })
        }
        visibility = View.GONE
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    fun bind(control: CallAudioControl) {
        this.control = control
        refresh()
    }

    /** Redraws from the platform's current device list and the stored choice. Cheap; called on every route change. */
    fun refresh() {
        val c = control ?: return
        val available = c.routes()
        val lit = c.lit()
        visibility = if (com.meda.portico.core.callbar.CallRoutes.chooserVisible(available)) View.VISIBLE else View.GONE
        for ((route, b) in buttons) {
            b.visibility = if (route in available) View.VISIBLE else View.GONE
            val on = route == lit
            b.isSelected = on
            ViewCompat.setStateDescription(b, context.getString(if (on) R.string.route_state_on else R.string.route_state_off))
            val shape = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                if (on) setColor(ON_FILL) else setStroke(dp(1), OUTLINE)
                if (!on) setColor(Color.TRANSPARENT)
            }
            b.background = shape
            b.setTextColor(if (on) Color.WHITE else resolvedTextColor())
        }
    }

    private fun resolvedTextColor(): Int {
        val a = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColorStateList(0)?.defaultColor ?: Color.GRAY
        a.recycle()
        return c
    }

    private companion object {
        /** The lit word: a blue that reads on a light and a dark page alike (white text on it clears 4.5:1). */
        const val ON_FILL = 0xFF1D4ED8.toInt()
        const val OUTLINE = 0x80808080.toInt()
    }
}
