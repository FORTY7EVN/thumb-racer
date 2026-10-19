package com.thumbracer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View

/** 7-step wizard: hold each pose 1.5 s, the median of the samples is recorded. Saves into the active profile. */
class CalibrateActivity : Activity() {
    private val steps = listOf(
        "Rest" to "Thumbs where they feel natural",
        "Squeeze" to "Thumbs as close together as comfortable",
        "Stretch" to "Thumbs as wide apart as comfortable",
        "Turn right" to "Right thumb down, left thumb up: full stagger",
        "Turn left" to "Left thumb down, right thumb up: full stagger",
        "Slide down" to "Both thumbs down together (handbrake)",
        "Push up" to "Both thumbs up together (nitro)",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        val v = CalibrateView(this)
        setContentView(v)
        goFullScreen(v)
    }

    @SuppressLint("ViewConstructor")
    inner class CalibrateView(ctx: Context) : View(ctx) {
        private val tracker = ThumbTracker(resources.displayMetrics)
        private val dp = resources.displayMetrics.density
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 28 * dp }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textAlign = Paint.Align.CENTER; textSize = 16 * dp }
        private val fill = Paint().apply { color = 0xFF00C853.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = Color.WHITE }
        private val vib = ctx.getSystemService(Vibrator::class.java)

        private var step = 0
        private var recordStart = -1L
        private var waitLift = false
        private val samples = ArrayList<DoubleArray>()
        private val results = ArrayList<DoubleArray>()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            tracker.onTouch(this, e)
            if (step >= steps.size && e.actionMasked == MotionEvent.ACTION_UP) finish()
            invalidate()
            return true
        }

        /** Sampled once per frame, so a perfectly still thumb still records. */
        private fun sample() {
            val l = tracker.left
            val r = tracker.right
            val now = SystemClock.uptimeMillis()
            if (l == null || r == null) {
                recordStart = -1; samples.clear() // a lift restarts the step
                if (l == null && r == null) waitLift = false
                return
            }
            if (waitLift) return
            if (recordStart < 0) recordStart = now
            samples += doubleArrayOf(r.y - l.y, r.x - l.x, (l.y + r.y) / 2)
            if (now - recordStart < 1500) return
            results += DoubleArray(3) { i -> samples.map { it[i] }.sorted()[samples.size / 2] }
            samples.clear(); recordStart = -1; waitLift = true; step++
            vib?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            if (step == steps.size) {
                val p = ProfileStore.load()
                p.applyCalibration(results)
                ProfileStore.save(p)
            }
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val w = width.toFloat()
            val h = height.toFloat()
            if (step >= steps.size) {
                c.drawText("Calibration saved to \"${ProfileStore.active}\"", w / 2, h / 2, big)
                c.drawText("Tap to finish", w / 2, h / 2 + 32 * dp, body)
                return
            }
            sample()
            if (step >= steps.size) { invalidate(); return }
            val (title, hint) = steps[step]
            c.drawText("${step + 1}/${steps.size}  $title", w / 2, h * 0.3f, big)
            c.drawText(if (waitLift) "Lift both thumbs for the next step" else "$hint, then hold still", w / 2, h * 0.3f + 32 * dp, body)
            if (recordStart >= 0) {
                val f = ((SystemClock.uptimeMillis() - recordStart) / 1500f).coerceIn(0f, 1f)
                c.drawRect(w * 0.25f, h * 0.45f, w * 0.25f + w * 0.5f * f, h * 0.45f + 8 * dp, fill)
            }
            if (tracker.left != null && tracker.right != null) postInvalidateOnAnimation()
            tracker.left?.let { c.drawCircle(tracker.leftPx.x, tracker.leftPx.y, 40 * dp, ring) }
            tracker.right?.let { c.drawCircle(tracker.rightPx.x, tracker.rightPx.y, 40 * dp, ring) }
        }
    }
}
