package com.thumbracer

import android.app.Activity
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Turns MotionEvents into a left and a right thumb (first touch on each screen half), in mm.
 * Extra fingers are ignored. A touch landing inside [reserved] is reported via [reservedDown] instead.
 */
class ThumbTracker(private val dm: DisplayMetrics) {
    private var leftId = -1
    private var rightId = -1
    private var reservedId = -1
    val leftPx = PointF()
    val rightPx = PointF()
    var reserved: ((Float, Float, Int, Int) -> Boolean)? = null
    var reservedDownAt = -1L
        private set

    val left get() = if (leftId >= 0) mm(leftPx) else null
    val right get() = if (rightId >= 0) mm(rightPx) else null

    private fun mm(p: PointF) = Thumb(p.x / dm.xdpi * 25.4, p.y / dm.ydpi * 25.4)

    fun onTouch(v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.requestUnbufferedDispatch(e)
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                when {
                    reserved?.invoke(x, y, v.width, v.height) == true && reservedId < 0 -> {
                        reservedId = id; reservedDownAt = e.eventTime
                    }
                    x < v.width / 2f && leftId < 0 -> leftId = id
                    x >= v.width / 2f && rightId < 0 -> rightId = id
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> clear()
        }
        for (i in 0 until e.pointerCount) {
            when (e.getPointerId(i)) {
                leftId -> leftPx.set(e.getX(i), e.getY(i))
                rightId -> rightPx.set(e.getX(i), e.getY(i))
            }
        }
    }

    private fun release(id: Int) {
        if (id == leftId) leftId = -1
        if (id == rightId) rightId = -1
        if (id == reservedId) { reservedId = -1; reservedDownAt = -1 }
    }

    fun clear() { leftId = -1; rightId = -1; reservedId = -1; reservedDownAt = -1 }
}

/** Full screen for drive and calibration: no bars, no notch gap, edge swipes kept for the thumbs. */
fun Activity.goFullScreen(content: View) {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // fastest refresh mode at the current resolution
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        val cur = d.mode
        d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.let { preferredDisplayModeId = it.modeId }
    }
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }
    content.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        // the system honours up to 200 dp per edge; it keeps the part it allows
        v.systemGestureExclusionRects = listOf(Rect(0, 0, 80, v.height), Rect(v.width - 80, 0, v.width, v.height))
    }
}
