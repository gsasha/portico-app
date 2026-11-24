package com.meda.portico.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * The four rounded corners of a camera tile, painted over its picture in the page's background colour. A live-video SurfaceView is
 * composited by the system outside the view tree, so neither `clipToOutline` nor a parent's clip rounds it; a view drawn ABOVE it does
 * draw over its hole, so the corners are masked here. Draws nothing for taps (not clickable); the corner size is [AspectFrameLayout.CORNER_DP].
 */
class CornerMaskView(context: Context, private val radiusDp: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val tv = TypedValue()
        paint.color = if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else 0xFF000000.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = radiusDp * resources.displayMetrics.density
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        path.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }
}
