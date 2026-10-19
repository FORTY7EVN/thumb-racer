package com.thumbracer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Connect screen: PCs found on Wi-Fi, last used PC, manual IP, and links to calibration and settings. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var shown = emptySet<Link.Pc>()

    private val tick = object : Runnable {
        override fun run() {
            Link.discover()
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Thumb Racer"; textSize = 28f })
        status = TextView(this).apply { text = "Searching for PCs on this Wi-Fi…"; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        val ip = EditText(this).apply {
            hint = "PC IP address, e.g. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ip)
            addView(Button(this@MainActivity).apply {
                text = "Connect"
                setOnClickListener { ip.text.toString().trim().takeIf { it.isNotEmpty() }?.let { drive(Link.Pc(it, it, Protocol.DATA_PORT)) } }
            })
        })
        root.addView(button(this, "Calibrate thumbs") { startActivity(Intent(this, CalibrateActivity::class.java)) })
        root.addView(button(this, "Settings & profiles") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            alpha = 0.7f
            text = "Run ThumbRacerHost.exe on the PC first.\n" +
                "• Phone and PC on the same network, 5 GHz Wi-Fi if you can\n" +
                "• Battery saver off; HyperOS: Settings > Apps > Thumb Racer > Battery saver > No restrictions\n" +
                "• PC firewall: allow the host on Private networks\n" +
                "• While driving, hold a finger in the top-center of the screen for 1 s for quick settings"
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Link.found.clear()
        shown = emptySet()
        refresh(force = true)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun refresh(force: Boolean = false) {
        val last = ProfileStore.last()
        val pcs = Link.found.values.sortedBy { it.name }.toSet()
        if (!force && pcs == shown) return
        shown = pcs
        list.removeAllViews()
        if (last != null) list.addView(button(this, "Last used: ${last.name} (${last.ip})") { drive(last) })
        for (pc in pcs) if (pc.ip != last?.ip || pc.port != last.port || pc.name != last.name) {
            list.addView(button(this, "${pc.name}  ·  ${pc.ip}") { drive(pc) })
        }
        status.text = if (pcs.isEmpty()) "Searching for PCs on this Wi-Fi…" else "Tap your PC:"
    }

    private fun drive(pc: Link.Pc) {
        ProfileStore.saveLast(pc.name, pc.ip, pc.port)
        Link.connect(pc)
        startActivity(Intent(this, DriveActivity::class.java))
    }
}

fun button(ctx: Context, label: String, onClick: (View) -> Unit) = Button(ctx).apply {
    text = label
    isAllCaps = false
    setOnClickListener(onClick)
}
