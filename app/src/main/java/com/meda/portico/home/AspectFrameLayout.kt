package com.meda.portico.home

import android.content.Context
import android.util.AttributeSet
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** A [FrameLayout] whose height follows its width ([aspect], width over height): a camera picture box. */
class AspectFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var aspect: Float = 16f / 9f

    /** Fullscreen (DEV-768 P3): the width is also capped by what the height allows, so the 16:9 box fits a landscape window as well as a portrait one. */
    var fitHeight: Boolean = false

    /** Panel mode (0.6.99): the tallest this box may be, in px (0 = no cap). The width follows, so a Big camera fits the screen with its buttons. */
    var maxHeightPx: () -> Int = { 0 }

    init {
        // 0.6.48: Material-style rounded corners, ONE place for every camera tile (Home's big and small, the ring and in-call screens).
        // The outline clips the children too, the video surface (SurfaceView) and the picture included; fullscreen stays square.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, if (fitHeight) 0f else CORNER_DP * view.resources.displayMetrics.density)
            }
        }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = MeasureSpec.getSize(widthMeasureSpec)
        if (fitHeight && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) width = minOf(width, (MeasureSpec.getSize(heightMeasureSpec) * aspect).toInt())
        val cap = maxHeightPx()
        if (cap > 0) width = minOf(width, (cap * aspect).toInt())
        val height = (width / aspect).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    companion object {
        const val CORNER_DP = 12
    }
}
