package com.thumbracer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DriveActivity : Activity(), SensorEventListener {
    private lateinit var profile: Profile
    private var engine: Engine? = null
    private lateinit var view: DriveView
    private var wifiLock: WifiManager.WifiLock? = null
    private var sensors: SensorManager? = null
    private var dialogs = 0 // open menus; output stays neutral until all are closed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        profile = ProfileStore.load()
        view = DriveView(this)
        setContentView(view)
        goFullScreen(view)
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "thumbracer")
        sensors = getSystemService(SensorManager::class.java)
    }

    override fun onResume() {
        super.onResume()
        if (Link.state == Link.State.IDLE) ProfileStore.last()?.let { Link.connect(it) }
        engine = Engine(this, profile).also { it.start() }
        wifiLock?.acquire()
        sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        engine?.let { it.running = false; it.join(300) } // the engine says BYE on exit
        engine = null
        if (wifiLock?.isHeld == true) wifiLock?.release()
        sensors?.unregisterListener(this)
        view.tracker.clear()
        ProfileStore.save(profile)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = e.values
        if (sqrt(x * x + y * y + z * z) > profile.flickG) engine?.controller?.flick()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pauseFor(d: AlertDialog.Builder) {
        dialogs++
        engine?.paused = true
        view.tracker.clear()
        d.setOnDismissListener {
            if (--dialogs == 0) { engine?.paused = false; goFullScreen(view) }
        }.show()
    }

    /** Quick settings mid-session; output is neutral while it is open. */
    private fun openOverlay() {
        if (dialogs > 0) return
        val p = profile
        val items = arrayOf(
            "Turn functions on/off…",
            "Steering: ${if (p.steerDigital) "Digital" else "Analog"} (tap to switch)",
            "Gas/brake: ${if (p.speedDigital) "Digital" else "Analog"} (tap to switch)",
            "Steering sensitivity: more",
            "Steering sensitivity: less",
            "Nitro: ${if (p.nitroWrist) "Wrist flick" else "Push thumbs up"} (tap to switch)",
            "Exit to connect screen",
        )
        pauseFor(AlertDialog.Builder(this)
            .setTitle("Thumb Racer — ${ProfileStore.active}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openFunctions()
                    1 -> p.steerDigital = !p.steerDigital
                    2 -> p.speedDigital = !p.speedDigital
                    3 -> p.steerMax = (p.steerMax * 0.9).coerceAtLeast(5.0)
                    4 -> p.steerMax = p.steerMax * 1.1
                    5 -> p.nitroWrist = !p.nitroWrist
                    6 -> finish()
                }
                ProfileStore.save(p)
            })
    }

    private fun openFunctions() {
        val p = profile
        val names = arrayOf("Steering", "Gas / brake", "Handbrake", "Nitro", "Gear shifting", "Screen color", "Vibration")
        val on = booleanArrayOf(p.useSteering, p.useGasBrake, p.useHandbrake, p.useNitro, p.useShifting,
            p.colorOn, p.hapticIntensity > 0)
        pauseFor(AlertDialog.Builder(this)
            .setTitle("Functions")
            .setMultiChoiceItems(names, on) { _, i, checked -> on[i] = checked }
            .setPositiveButton("Done") { _, _ ->
                p.useSteering = on[0]; p.useGasBrake = on[1]; p.useHandbrake = on[2]
                p.useNitro = on[3]; p.useShifting = on[4]; p.colorOn = on[5]
                p.hapticIntensity = if (on[6]) p.hapticIntensity.takeIf { it > 0 } ?: 1.0 else 0.0
                ProfileStore.save(p)
            }
            .setNegativeButton("Cancel", null))
    }

    @SuppressLint("ViewConstructor")
    inner class DriveView(ctx: Context) : View(ctx) {
        val tracker = ThumbTracker(resources.displayMetrics)
        private val dp = resources.displayMetrics.density
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 22 * dp }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 12 * dp }
        private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 12 * dp }
        private val bar = Paint().apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0x55FFFFFF }

        init {
            // top-center box: hold a finger there for 1 s to open quick settings
            tracker.reserved = { x, y, w, h -> y < h * 0.2f && x > w * 0.35f && x < w * 0.65f }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            tracker.onTouch(this, e)
            engine?.thumbs?.set(Thumbs(tracker.left, tracker.right))
            return true
        }

        private fun mix(target: Int, amount: Double): Int {
            val a = (amount.coerceIn(0.0, 1.0).pow(0.7) * profile.colorBrightness).toFloat()
            return Color.rgb((Color.red(target) * a).toInt(), (Color.green(target) * a).toInt(), (Color.blue(target) * a).toInt())
        }

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            if (tracker.reservedDownAt >= 0 && now - tracker.reservedDownAt > 1000) post { openOverlay() }
            val o = engine?.latest?.get() ?: Output.NEUTRAL
            val w = width.toFloat()
            val h = height.toFloat()
            val connected = Link.state == Link.State.CONNECTED
            when {
                !connected -> {
                    c.drawColor(0xFF303030.toInt())
                    val msg = when (Link.state) {
                        Link.State.REJECTED -> when (Link.rejectReason) {
                            1 -> "PC is busy with another phone"
                            2 -> "vJoy is missing on the PC"
                            else -> "PC host version mismatch"
                        }
                        Link.State.IDLE -> "No PC chosen: go back and pick one"
                        else -> "Connecting to ${Link.targetName}…"
                    }
                    c.drawText(msg, w / 2, h / 2, text)
                }
                !o.engaged -> {
                    c.drawColor(0xFF101010.toInt())
                    c.drawText("Place both thumbs", w / 2, h / 2, text)
                }
                !profile.colorOn -> c.drawColor(Color.BLACK)
                o.gas > 0 -> c.drawColor(mix(0xFF00C853.toInt(), o.gas))
                o.brake > 0 -> c.drawColor(mix(0xFFE53935.toInt(), o.brake))
                else -> c.drawColor(Color.BLACK) // pure black: AMOLED pixels off
            }
            if (connected && o.engaged) {
                val half = border.strokeWidth / 2
                if (o.handbrake > 0.5) {
                    border.color = 0xFFFFB300.toInt()
                    c.drawRect(half, half, w - half, h - half, border)
                }
                if (o.nitro) {
                    border.color = 0xFF00E5FF.toInt()
                    border.alpha = (155 + 100 * sin(now / 1000.0 * 2 * Math.PI * 4)).toInt()
                    c.drawRect(half + border.strokeWidth, half + border.strokeWidth, w - half - border.strokeWidth, h - half - border.strokeWidth, border)
                }
                if (profile.steerBar) {
                    val x = w / 2 + (o.steer * w / 2).toFloat()
                    c.drawRect(minOf(w / 2, x), 0f, maxOf(w / 2, x), 4 * dp, bar)
                }
            }
            if (profile.thumbMarkers) {
                if (o.flags and Output.F_LEFT != 0) c.drawCircle(tracker.leftPx.x, tracker.leftPx.y, 40 * dp, ring)
                if (o.flags and Output.F_RIGHT != 0) c.drawCircle(tracker.rightPx.x, tracker.rightPx.y, 40 * dp, ring)
            }
            val ping = if (connected && Link.rttMs >= 0) "${Link.rttMs} ms" else "—"
            c.drawText("$ping · ${ProfileStore.active} · hold top-center for menu", 8 * dp, h - 8 * dp, small)
            postInvalidateOnAnimation() // redraw every display frame
        }
    }
}
