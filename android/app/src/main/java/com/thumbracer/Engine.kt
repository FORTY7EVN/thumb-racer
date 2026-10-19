package com.thumbracer

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Current thumbs, written by the touch view, read by the engine. */
class Thumbs(val left: Thumb?, val right: Thumb?)

/**
 * The 250 Hz loop: runs the controller on the latest thumbs (so grace and button timers advance
 * even without touch events), plays haptics, streams INPUT, pings, and re-says HELLO when needed.
 */
class Engine(ctx: Context, val profile: Profile) : Thread("engine") {
    val thumbs = AtomicReference(Thumbs(null, null))
    val latest = AtomicReference(Output.NEUTRAL)
    val controller = Controller(profile)
    @Volatile var running = true
    @Volatile var paused = false
    private val haptics = Haptics(ctx, profile)

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var next = System.nanoTime()
        var seq = 0L
        var lastPing = 0L
        var lastHello = 0L
        while (running) {
            val now = SystemClock.uptimeMillis()
            val th = if (paused) Thumbs(null, null) else thumbs.get()
            val o = controller.update(System.nanoTime() / 1e6, th.left, th.right)
            latest.set(o)
            haptics.play(o.events)
            when (Link.state) {
                Link.State.CONNECTED -> {
                    Link.send(Protocol.input(Link.session, ++seq, now, o))
                    if (now - lastPing >= 500) { Link.send(Protocol.ping(Link.session, now.toInt())); lastPing = now }
                    if (now - Link.lastPong > 2000) Link.state = Link.State.CONNECTING
                }
                Link.State.CONNECTING, Link.State.REJECTED ->
                    if (now - lastHello >= 1000) { Link.send(Protocol.hello(Build.MODEL)); lastHello = now }
                Link.State.IDLE -> {}
            }
            next += 4_000_000
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait) else next = System.nanoTime() // fell behind: don't burst
        }
        Link.bye()
    }
}

class Haptics(ctx: Context, private val p: Profile) {
    private val vib: Vibrator? = ctx.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
    private val amp = vib?.hasAmplitudeControl() == true

    fun play(events: List<Event>) {
        val v = vib ?: return
        if (p.hapticIntensity <= 0) return
        for (e in events) effect(e)?.let { v.vibrate(it) }
    }

    private fun level(a: Int) = if (amp) (a * p.hapticIntensity).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE

    private fun one(ms: Long, a: Int) = VibrationEffect.createOneShot(ms, level(a))

    /** Pulses of [onMs] separated by [gapMs]. */
    private fun pulses(n: Int, onMs: Long, gapMs: Long, a: Int): VibrationEffect {
        val t = LongArray(2 * n) { if (it % 2 == 0) (if (it == 0) 0 else gapMs) else onMs }
        val amps = IntArray(2 * n) { if (it % 2 == 0) 0 else level(a) }
        return VibrationEffect.createWaveform(t, amps, -1)
    }

    private fun effect(e: Event): VibrationEffect? = when (e) {
        Event.ENGAGE -> if (p.hapticEngage) pulses(2, 15, 40, 120) else null
        Event.DISENGAGE -> if (p.hapticEngage) one(40, 80) else null
        Event.CENTER -> if (p.hapticCenter) one(8, 140) else null
        Event.LOCK -> if (p.hapticLock) one(30, 255) else null
        Event.PEDAL_MAX -> if (p.hapticPedalMax) one(25, 200) else null
        Event.SHIFT -> if (p.hapticShift) one(12, 180) else null
        Event.NITRO -> if (p.hapticNitro) pulses(3, 10, 20, 200) else null
    }
}
