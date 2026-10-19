package com.thumbracer

/**
 * Every tunable value. Lengths in mm, times in ms. Fields are read and written by reflection
 * (ProfileStore JSON, SettingsActivity), so adding a var here adds it everywhere.
 */
class Profile {
    // modes
    var steerDigital = false
    var speedDigital = false
    var nitroWrist = false        // false = push both thumbs up, true = wrist flick
    var shiftDouble = false       // false = single retap shifts, true = double retap
    var engagedButton = false     // also press button 5 while both thumbs are down

    // calibration (filled by the wizard)
    var calibrated = false
    var restDY = 0.0
    var restSpan = 0.0
    var restMY = 0.0
    var steerMax = 22.0
    var stretchMax = 25.0
    var squeezeMax = 25.0
    var hbFull = 18.0

    // steering
    var steerDeadband = 0.05      // fraction of steerMax
    var cubicBlend = 0.6          // k: 0 = linear, 1 = pure cubic
    var steerSnap = 8.0
    var filterMinCutoff = 1.5     // One Euro, Hz
    var filterBeta = 0.1          // per mm/s of thumb speed (plan said 0.02 for normalized units)
    var returnRate = 6.0          // steer units per second toward center

    // gas / brake
    var speedDeadband = 6.0
    var speedSnap = 12.0

    // handbrake and nitro
    var hbStart = 8.0
    var nitroStart = 10.0
    var flickG = 15.0             // m/s^2
    var flickCooldown = 800.0

    // gate and gestures
    var graceMs = 200.0
    var retapRadius = 25.0
    var settleMs = 150.0
    var buttonHoldMs = 60.0
    var centerTolDY = 6.0
    var centerTolSpan = 10.0
    var centerTolMY = 8.0

    // feedback
    var colorOn = true
    var colorBrightness = 0.7
    var steerBar = false
    var thumbMarkers = false
    var hapticIntensity = 1.0
    var hapticEngage = true
    var hapticCenter = true
    var hapticLock = true
    var hapticPedalMax = true
    var hapticShift = true
    var hapticNitro = true

    /** Apply wizard medians: rest, squeeze, stretch, right, left, down, up -> each (dY, span, mY). */
    fun applyCalibration(steps: List<DoubleArray>) {
        require(steps.size == 7)
        val (rest, squeeze, stretch, right, left) = steps
        val down = steps[5]; val up = steps[6]
        restDY = rest[0]; restSpan = rest[1]; restMY = rest[2]
        squeezeMax = (restSpan - squeeze[1]).coerceAtLeast(8.0)
        stretchMax = (stretch[1] - restSpan).coerceAtLeast(8.0)
        steerMax = minOf(right[0] - restDY, restDY - left[0]).coerceAtLeast(6.0)
        hbFull = (down[2] - restMY).coerceAtLeast(6.0)
        nitroStart = (0.6 * (restMY - up[2])).coerceAtLeast(4.0)
        // thresholds keep the same proportions as the defaults
        val pedal = minOf(squeezeMax, stretchMax)
        steerSnap = steerMax * 8 / 22
        speedDeadband = pedal * 6 / 25
        speedSnap = pedal * 12 / 25
        hbStart = hbFull * 8 / 18
        calibrated = true
    }

}
