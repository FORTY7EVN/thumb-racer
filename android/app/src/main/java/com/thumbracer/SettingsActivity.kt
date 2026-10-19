package com.thumbracer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Every Profile field as a switch or a number box, plus profile switch / new / delete / export / import. */
class SettingsActivity : Activity() {
    private lateinit var profile: Profile
    private val numbers = mutableMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        profile = ProfileStore.load()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Profile: ${ProfileStore.active}"; textSize = 22f })
        root.addView(button(this, "Switch or create profile") { pickProfile() })
        root.addView(button(this, "Export (share as text)") {
            save()
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, ProfileStore.toJson(profile))
            }, "Export profile"))
        })
        root.addView(button(this, "Import from clipboard") { import() })
        root.addView(button(this, "Reset this profile to defaults") {
            ProfileStore.save(Profile()); numbers.clear(); recreate()
        })
        if (ProfileStore.active != ProfileStore.DEFAULT) root.addView(button(this, "Delete this profile") {
            ProfileStore.delete(ProfileStore.active); ProfileStore.active = ProfileStore.DEFAULT; numbers.clear(); recreate()
        })

        for (f in ProfileStore.fields) {
            val label = f.name.replace(Regex("([A-Z])"), " $1").lowercase().replaceFirstChar { it.uppercase() }
            if (f.type == java.lang.Boolean.TYPE) {
                root.addView(Switch(this).apply {
                    text = label
                    isChecked = f.getBoolean(profile)
                    setOnCheckedChangeListener { _, on -> f.setBoolean(profile, on) }
                })
            } else {
                val box = EditText(this).apply {
                    setText(f.getDouble(profile).toString())
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    minEms = 5
                    gravity = Gravity.END
                }
                numbers[f.name] = box
                root.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@SettingsActivity).apply {
                        text = label
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(box)
                })
            }
        }
        root.addView(TextView(this).apply {
            alpha = 0.6f
            setPadding(0, pad, 0, 0)
            text = "Lengths in mm, times in ms. Calibration fills rest, max and threshold values; run it again after big changes."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Copy number boxes into the profile (bad input keeps the old value) and store it. */
    private fun save() {
        for (f in ProfileStore.fields) numbers[f.name]?.text?.toString()?.toDoubleOrNull()?.let { f.setDouble(profile, it) }
        ProfileStore.save(profile)
    }

    override fun onPause() {
        super.onPause()
        if (numbers.isNotEmpty()) save()
    }

    private fun pickProfile() {
        save()
        val names = ProfileStore.names()
        AlertDialog.Builder(this)
            .setTitle("Profiles")
            .setItems((names + "New profile…").toTypedArray()) { _, i ->
                if (i < names.size) { ProfileStore.active = names[i]; numbers.clear(); recreate() }
                else askName { name -> ProfileStore.save(profile, name); ProfileStore.active = name; numbers.clear(); recreate() }
            }
            .show()
    }

    private fun askName(then: (String) -> Unit) {
        val input = EditText(this).apply { hint = "e.g. Forza casual" }
        AlertDialog.Builder(this)
            .setTitle("Profile name")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(then) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun import() {
        val text = getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val p = text?.let { runCatching { ProfileStore.fromJson(it) }.getOrNull() }
        if (p == null) {
            Toast.makeText(this, "Clipboard has no Thumb Racer profile", Toast.LENGTH_LONG).show()
            return
        }
        askName { name -> ProfileStore.save(p, name); ProfileStore.active = name; numbers.clear(); recreate() }
    }
}
