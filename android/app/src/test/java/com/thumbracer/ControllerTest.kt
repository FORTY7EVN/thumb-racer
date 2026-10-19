package com.thumbracer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ControllerTest {
    private val restL = Thumb(30.0, 35.0)
    private val restR = Thumb(110.0, 35.0)

    private var t = 0.0
    private val events = mutableListOf<Event>()

    /** Run the controller at 250 Hz for [ms] with fixed thumbs; returns the last output. */
    private fun run(c: Controller, ms: Int, l: Thumb?, r: Thumb?): Output {
        var o = Output.NEUTRAL
        repeat(ms / 4) { t += 4; o = c.update(t, l, r); events += o.events }
        return o
    }

    private fun engaged(p: Profile = Profile()) = Controller(p).also { run(it, 200, restL, restR) }

    @Test fun engagesOnlyAfterSettleWithBothThumbs() {
        val c = Controller(Profile())
        assertFalse(run(c, 500, restL, null).engaged)
        assertFalse(run(c, 100, restL, restR).engaged)
        val o = run(c, 100, restL, restR)
        assertTrue(o.engaged)
        assertEquals(0.0, o.steer, 1e-9)
        assertTrue(Event.ENGAGE in events)
    }

    @Test fun steeringSignDeadbandAndLock() {
        val c = engaged()
        assertEquals(0.0, run(c, 200, restL, restR.copy(y = 35.5)).steer, 1e-9) // inside deadband
        assertTrue(run(c, 400, restL, restR.copy(y = 45.0)).steer > 0.1)       // right thumb lower = right
        val c2 = engaged()
        assertTrue(run(c2, 400, restL.copy(y = 45.0), restR).steer < -0.1)
        val c3 = engaged()
        assertEquals(1.0, run(c3, 600, restL.copy(y = 20.0), restR.copy(y = 50.0)).steer, 1e-6)
        assertTrue(Event.LOCK in events)
    }

    @Test fun returnToCenterIsRateLimited() {
        val c = engaged()
        run(c, 600, restL.copy(y = 20.0), restR.copy(y = 50.0))
        val after40ms = run(c, 40, restL, restR).steer
        assertTrue("dampened, not instant: $after40ms", after40ms > 0.5)
        assertEquals(0.0, run(c, 600, restL, restR).steer, 1e-6)
        assertTrue(Event.CENTER in events)
    }

    @Test fun commonVerticalMoveDoesNotSteer() {
        val c = engaged()
        val o = run(c, 400, restL.copy(y = 55.0), restR.copy(y = 55.0))
        assertEquals(0.0, o.steer, 0.02)
        assertEquals(1.0, o.handbrake, 1e-9)
        assertTrue(o.buttons and Output.BTN_HANDBRAKE != 0)
    }

    @Test fun nitroByPushingUp() {
        val c = engaged()
        assertTrue(run(c, 100, restL.copy(y = 20.0), restR.copy(y = 20.0)).nitro)
        assertTrue(Event.NITRO in events)
    }

    @Test fun gasCoastBrakeZones() {
        val c = engaged()
        assertEquals(0.0, run(c, 100, restL, restR.copy(x = 114.0)).gas, 1e-9)   // within 6 mm coast
        assertEquals(1.0, run(c, 100, restL, restR.copy(x = 140.0)).gas, 1e-9)
        val o = run(c, 100, restL.copy(x = 60.0), restR)
        assertEquals(0.0, o.gas, 1e-9)
        assertEquals(1.0, o.brake, 1e-9)
        val half = run(c, 100, restL, restR.copy(x = 125.5))
        assertEquals(9.5 / 19.0, half.gas, 1e-6) // span +15.5: (15.5-6)/(25-6)
    }

    @Test fun digitalModesSnap() {
        val p = Profile().apply { steerDigital = true; speedDigital = true }
        val c = engaged(p)
        val o = run(c, 20, restL, restR.copy(x = 125.0, y = 44.0))
        assertEquals(1.0, o.steer, 0.0)
        assertEquals(1.0, o.gas, 0.0)
        assertEquals(1.0, run(c, 20, restL, restR.copy(x = 125.0, y = 41.0)).steer, 0.0) // hysteresis hold
        assertEquals(0.0, run(c, 20, restL, restR.copy(x = 125.0, y = 40.0)).steer, 0.0)
    }

    @Test fun shortLiftHoldsAndRetapShifts() {
        val c = engaged()
        run(c, 400, restL, restR.copy(y = 45.0))
        val held = run(c, 100, restL, null)
        assertTrue(held.engaged)
        assertTrue(held.steer > 0.1)
        assertTrue(held.flags and Output.F_GRACE != 0)
        val back = run(c, 4, restL, restR.copy(y = 45.0))
        assertTrue(back.buttons and Output.BTN_SHIFT_UP != 0)
        assertTrue(Event.SHIFT in events)
        assertEquals(0, run(c, 100, restL, restR.copy(y = 45.0)).buttons and Output.BTN_SHIFT_UP)
    }

    @Test fun leftRetapShiftsDownAndDoubleModeNeedsTwo() {
        val c = engaged(Profile().apply { shiftDouble = true })
        run(c, 50, null, restR)
        assertEquals(0, run(c, 4, restL, restR).buttons and Output.BTN_SHIFT_DOWN)
        run(c, 50, restL, restR)
        run(c, 50, null, restR)
        assertTrue(run(c, 4, restL, restR).buttons and Output.BTN_SHIFT_DOWN != 0)
    }

    @Test fun longLiftDisengagesAndNeutralizes() {
        val c = engaged()
        run(c, 400, restL, restR.copy(x = 140.0, y = 45.0))
        val o = run(c, 300, restL, null)
        assertFalse(o.engaged)
        assertEquals(0.0, o.steer, 0.0)
        assertEquals(0.0, o.gas, 0.0)
        assertEquals(0, o.buttons)
        assertTrue(Event.DISENGAGE in events)
    }

    @Test fun midTurnGrabUsesCalibratedRest() {
        val p = Profile().apply { calibrated = true; restDY = 0.0; restSpan = 80.0; restMY = 35.0 }
        val c = Controller(p)
        val o = run(c, 400, restL, restR.copy(y = 55.0)) // grabbed fully staggered
        assertTrue(o.engaged)
        assertTrue(abs(o.steer) > 0.5)
    }

    @Test fun wristFlickPulsesNitroWithCooldown() {
        val c = engaged(Profile().apply { nitroWrist = true })
        c.flick()
        assertTrue(run(c, 8, restL, restR).nitro)
        assertFalse(run(c, 200, restL, restR).nitro)
        c.flick()
        assertFalse(run(c, 8, restL, restR).nitro) // cooldown 800 ms
    }

    @Test fun switchedOffFunctionsStayNeutral() {
        val p = Profile().apply {
            useSteering = false; useGasBrake = false; useHandbrake = false; useNitro = false; useShifting = false
        }
        val c = engaged(p)
        var o = run(c, 400, restL.copy(x = 10.0, y = 55.0), restR.copy(x = 140.0, y = 70.0)) // turn + gas + handbrake
        assertEquals(0.0, o.steer, 0.0)
        assertEquals(0.0, o.gas, 0.0)
        assertEquals(0.0, o.handbrake, 0.0)
        o = run(c, 100, restL.copy(y = 10.0), restR.copy(y = 10.0)) // push up = nitro
        assertFalse(o.nitro)
        run(c, 50, restL, null)
        assertEquals(0, run(c, 4, restL, restR).buttons) // retap = no shift
        assertTrue(o.engaged)
    }

    @Test fun calibrationScalesThresholds() {
        val p = Profile()
        p.applyCalibration(listOf(
            doubleArrayOf(1.0, 80.0, 35.0),   // rest
            doubleArrayOf(1.0, 50.0, 35.0),   // squeeze
            doubleArrayOf(1.0, 110.0, 35.0),  // stretch
            doubleArrayOf(31.0, 80.0, 35.0),  // right
            doubleArrayOf(-25.0, 80.0, 35.0), // left
            doubleArrayOf(1.0, 80.0, 55.0),   // down
            doubleArrayOf(1.0, 80.0, 15.0),   // up
        ))
        assertEquals(26.0, p.steerMax, 1e-9)
        assertEquals(30.0, p.squeezeMax, 1e-9)
        assertEquals(30.0, p.stretchMax, 1e-9)
        assertEquals(20.0, p.hbFull, 1e-9)
        assertEquals(12.0, p.nitroStart, 1e-9)
        assertTrue(p.calibrated)
    }

    @Test fun inputPacketLayout() {
        val o = Output(1.0, 1.0, 0.0, 0.5, 3, 1, true, emptyList())
        val b = Protocol.input(7, 9, 1000, o)
        assertEquals(Protocol.INPUT_SIZE, b.size)
        assertEquals(listOf('T'.code, 'R'.code, 1, 1), b.take(4).map { it.toInt() })
        assertEquals(7, b[4].toInt())
        assertEquals(0xFF, b[14].toInt() and 0xFF) // steer 32767 = 0x7FFF little-endian
        assertEquals(0x7F, b[15].toInt())
        assertEquals(3, b[22].toInt())
        assertEquals(1, b[24].toInt())
    }
}
