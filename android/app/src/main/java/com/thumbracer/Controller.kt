package com.thumbracer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/** A thumb position in millimetres, landscape, y pointing down. */
data class Thumb(val x: Double, val y: Double)

enum class Event { ENGAGE, DISENGAGE, CENTER, LOCK, PEDAL_MAX, SHIFT, NITRO }

class Output(
    val steer: Double,      // -1..1, + = right
    val gas: Double,        // 0..1
    val brake: Double,
    val handbrake: Double,
    val buttons: Int,
    val flags: Int,
    val engaged: Boolean,
    val events: List<Event>,
) {
    val nitro get() = buttons and BTN_NITRO != 0

    companion object {
        const val BTN_SHIFT_UP = 1
        const val BTN_SHIFT_DOWN = 2
        const val BTN_NITRO = 4
        const val BTN_HANDBRAKE = 8
        const val BTN_ENGAGED = 16

        const val F_ENGAGED = 1
        const val F_LEFT = 2
        const val F_RIGHT = 4
        const val F_STEER_DIGITAL = 8
        const val F_SPEED_DIGITAL = 16
        const val F_GRACE = 32

        val NEUTRAL = Output(0.0, 0.0, 0.0, 0.0, 0, 0, false, emptyList())
    }
}

/** One Euro filter: smooth when slow, near-zero lag when fast. Works on mm. */
class OneEuro(private val p: Profile) {
    private var x = 0.0
    private var dx = 0.0
    private var lastT = -1.0

    fun reset() { lastT = -1.0 }

    fun filter(v: Double, tSec: Double): Double {
        if (lastT < 0) { lastT = tSec; x = v; dx = 0.0; return v }
        val dt = (tSec - lastT).coerceAtLeast(1e-4)
        lastT = tSec
        val ad = alpha(1.0, dt)
        dx = ad * ((v - x) / dt) + (1 - ad) * dx
        val a = alpha(p.filterMinCutoff + p.filterBeta * abs(dx), dt)
        x = a * v + (1 - a) * x
        return x
    }

    private fun alpha(cutoff: Double, dt: Double) = 1 / (1 + 1 / (2 * PI * cutoff) / dt)
}

/**
 * All control logic, pure Kotlin so it is unit-tested on the JVM. Call update() at a steady rate
 * (the engine uses 250 Hz) with the current thumbs; null means that thumb is off the glass.
 * Not thread-safe: one thread calls update(); flick() may be called from any thread.
 */
class Controller(val p: Profile) {
    private var engaged = false
    private var settleStart = -1.0
    private var sumDY = 0.0
    private var sumSpan = 0.0
    private var sumMY = 0.0
    private var samples = 0
    private var dY0 = 0.0
    private var span0 = 0.0
    private var mY0 = 0.0

    private var graceStart = -1.0
    private var graceLeft = false
    private var liftPos: Thumb? = null
    private var lastL: Thumb? = null
    private var lastR: Thumb? = null

    private var steer = 0.0
    private var gas = 0.0
    private var brake = 0.0
    private var handbrake = 0.0
    private var nitroHeld = false
    private var steerLatch = 0
    private var speedLatch = 0
    private val filter = OneEuro(p)
    private var lastT = -1.0

    private var shiftUpUntil = -1.0
    private var shiftDownUntil = -1.0
    private var nitroPulseUntil = -1.0
    private var lastFlick = -1e9
    private val lastTap = doubleArrayOf(-1e9, -1e9)
    private var lastCenterTick = -1e9
    private var lockLatched = false
    private var pedalLatched = false
    private var prevButtons = 0

    @Volatile private var flickPending = false

    /** Wrist-flick detected by the accelerometer. */
    fun flick() { flickPending = true }

    fun update(t: Double, l: Thumb?, r: Thumb?): Output {
        val ev = ArrayList<Event>(2)
        val dt = if (lastT < 0) 0.004 else ((t - lastT) / 1000).coerceIn(0.0, 0.1)
        lastT = t
        if (flickPending) {
            flickPending = false
            if (p.nitroWrist && engaged && t - lastFlick >= p.flickCooldown) {
                nitroPulseUntil = t + 120
                lastFlick = t
            }
        }

        if (!engaged) {
            if (l == null || r == null) { settleStart = -1.0; return neutral(l, r, ev) }
            if (settleStart < 0) { settleStart = t; sumDY = 0.0; sumSpan = 0.0; sumMY = 0.0; samples = 0 }
            sumDY += r.y - l.y; sumSpan += r.x - l.x; sumMY += (l.y + r.y) / 2; samples++
            if (t - settleStart < p.settleMs) return neutral(l, r, ev)
            val aDY = sumDY / samples; val aSpan = sumSpan / samples; val aMY = sumMY / samples
            // grabbed mid-turn? then trust the calibrated rest pose instead of this grip
            val natural = !p.calibrated || (abs(aDY - p.restDY) <= p.centerTolDY &&
                abs(aSpan - p.restSpan) <= p.centerTolSpan && abs(aMY - p.restMY) <= p.centerTolMY)
            if (natural) { dY0 = aDY; span0 = aSpan; mY0 = aMY } else { dY0 = p.restDY; span0 = p.restSpan; mY0 = p.restMY }
            engaged = true
            settleStart = -1.0
            graceStart = -1.0
            ev += Event.ENGAGE
        }

        if (l != null && r != null) {
            if (graceStart >= 0) { // a lifted thumb came back inside the grace window
                val back = if (graceLeft) l else r
                val lp = liftPos
                if (lp != null && hypot(back.x - lp.x, back.y - lp.y) <= p.retapRadius) tap(t, graceLeft, ev)
                graceStart = -1.0
            }
            lastL = l; lastR = r
            compute(t, dt, l, r, ev)
        } else {
            if (graceStart < 0) {
                graceStart = t
                graceLeft = l == null
                liftPos = if (graceLeft) lastL else lastR
            }
            if (t - graceStart > p.graceMs) {
                engaged = false
                graceStart = -1.0
                ev += Event.DISENGAGE
                return neutral(l, r, ev)
            }
        }
        return build(t, l, r, ev)
    }

    private fun tap(t: Double, left: Boolean, ev: MutableList<Event>) {
        val i = if (left) 0 else 1
        if (!p.shiftDouble || t - lastTap[i] <= 400) {
            if (left) shiftDownUntil = t + p.buttonHoldMs else shiftUpUntil = t + p.buttonHoldMs
            lastTap[i] = -1e9
            ev += Event.SHIFT
        } else {
            lastTap[i] = t
        }
    }

    private fun latch(cur: Int, v: Double, snap: Double): Int = when {
        abs(v) > snap -> sign(v).toInt()
        abs(v) < snap - 2 -> 0
        else -> cur
    }

    private fun zone(s: Double) = if (abs(s) < 0.01) 0 else sign(s).toInt()

    private fun compute(t: Double, dt: Double, l: Thumb, r: Thumb, ev: MutableList<Event>) {
        val d = (r.y - l.y) - dY0
        val newSteer = if (p.steerDigital) {
            steerLatch = latch(steerLatch, d, p.steerSnap)
            steerLatch.toDouble()
        } else {
            val n = (filter.filter(d, t / 1000) / p.steerMax).coerceIn(-1.0, 1.0)
            val db = p.steerDeadband
            val n2 = sign(n) * max(abs(n) - db, 0.0) / (1 - db)
            val shaped = (1 - p.cubicBlend) * n2 + p.cubicBlend * n2 * n2 * n2
            // apex dampening: moves toward center are rate-limited, moves away are not
            if (abs(shaped) < abs(steer) || shaped * steer < 0) moveToward(steer, shaped, p.returnRate * dt) else shaped
        }
        if (zone(steer) != 0 && zone(newSteer) != zone(steer) && t - lastCenterTick >= 120) {
            ev += Event.CENTER
            lastCenterTick = t
        }
        if (abs(newSteer) >= 0.98 && !lockLatched) { ev += Event.LOCK; lockLatched = true }
        if (abs(newSteer) < 0.9) lockLatched = false
        steer = newSteer

        val s = (r.x - l.x) - span0
        if (p.speedDigital) {
            speedLatch = latch(speedLatch, s, p.speedSnap)
            gas = if (speedLatch > 0) 1.0 else 0.0
            brake = if (speedLatch < 0) 1.0 else 0.0
        } else {
            val db = p.speedDeadband
            gas = if (s > db) ((s - db) / max(p.stretchMax - db, 1.0)).coerceIn(0.0, 1.0) else 0.0
            brake = if (s < -db) ((-s - db) / max(p.squeezeMax - db, 1.0)).coerceIn(0.0, 1.0) else 0.0
        }
        if ((gas >= 0.999 || brake >= 0.999) && !pedalLatched) { ev += Event.PEDAL_MAX; pedalLatched = true }
        if (gas < 0.95 && brake < 0.95) pedalLatched = false

        val h = (l.y + r.y) / 2 - mY0
        handbrake = ((h - p.hbStart) / max(p.hbFull - p.hbStart, 1.0)).coerceIn(0.0, 1.0)
        nitroHeld = !p.nitroWrist && -h > p.nitroStart
    }

    private fun moveToward(from: Double, to: Double, step: Double) =
        if (abs(to - from) <= step) to else from + step * sign(to - from)

    private fun sideFlags(l: Thumb?, r: Thumb?) =
        (if (l != null) Output.F_LEFT else 0) or (if (r != null) Output.F_RIGHT else 0)

    private fun build(t: Double, l: Thumb?, r: Thumb?, ev: MutableList<Event>): Output {
        var b = 0
        if (t < shiftUpUntil) b = b or Output.BTN_SHIFT_UP
        if (t < shiftDownUntil) b = b or Output.BTN_SHIFT_DOWN
        if (nitroHeld || t < nitroPulseUntil) b = b or Output.BTN_NITRO
        if (handbrake > 0.5) b = b or Output.BTN_HANDBRAKE
        if (p.engagedButton) b = b or Output.BTN_ENGAGED
        if (b and Output.BTN_NITRO != 0 && prevButtons and Output.BTN_NITRO == 0) ev += Event.NITRO
        prevButtons = b
        var f = Output.F_ENGAGED or sideFlags(l, r)
        if (p.steerDigital) f = f or Output.F_STEER_DIGITAL
        if (p.speedDigital) f = f or Output.F_SPEED_DIGITAL
        if (graceStart >= 0) f = f or Output.F_GRACE
        return Output(steer, gas, brake, handbrake, b, f, true, ev)
    }

    /** Everything released; also clears dynamic state so the next grip starts clean. */
    private fun neutral(l: Thumb?, r: Thumb?, ev: List<Event>): Output {
        steer = 0.0; gas = 0.0; brake = 0.0; handbrake = 0.0; nitroHeld = false
        steerLatch = 0; speedLatch = 0; prevButtons = 0
        lockLatched = false; pedalLatched = false
        shiftUpUntil = -1.0; shiftDownUntil = -1.0; nitroPulseUntil = -1.0
        filter.reset()
        return Output(0.0, 0.0, 0.0, 0.0, 0, sideFlags(l, r), false, ev)
    }
}
