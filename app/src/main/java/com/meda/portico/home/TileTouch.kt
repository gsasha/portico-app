package com.meda.portico.home

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.meda.portico.core.content.TileAction
import com.meda.portico.core.content.TileGesture

/**
 * Feeds one camera tile's touches to its [TileGesture] (blocks B6, design 6.3) and nothing else: every decision (tap or
 * pinch or pan or scroll, the zoom, the bounds) is `:core`'s, tested without a phone. This is only the plumbing from
 * Android's events and `ScaleGestureDetector`'s finger maths to that state machine, and the one parent-intercept rule:
 * while a touch is a pinch or a pan of a zoomed picture, or starts on a zoomed tile, the page's ScrollView must not take it.
 *
 * It always returns true, so a tap and a drag across an unzoomed tile still reach the ScrollView (which steals the
 * touch with a cancel when it is a vertical scroll) and the activity's page swipe (which only watches). Double tap is
 * not used: a tap is the size toggle, or the zoom reset while zoomed. [onAction] runs for a tap.
 */
class TileTouch(context: Context, private val gesture: TileGesture, private val onAction: (TileAction) -> Unit) : View.OnTouchListener {

    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            gesture.scaleBegin(detector.focusX, detector.focusY)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            gesture.scale(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = gesture.scaleEnd()
    }).apply { isQuickScaleEnabled = false }

    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.down(ev.x, ev.y, ev.eventTime)
                if (gesture.zoomed) v.parent?.requestDisallowInterceptTouchEvent(true) // a drag here is a pan, not a page scroll
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture.pointerDown()
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 1) gesture.move(ev.x, ev.y)
                if (gesture.claiming) v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> gesture.pointerUp(ev.pointerCount - 1)
            MotionEvent.ACTION_UP -> onAction(gesture.up(ev.eventTime))
            MotionEvent.ACTION_CANCEL -> gesture.cancel()
        }
        return true
    }
}
