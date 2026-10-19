package com.thumbracer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Named profiles as JSON in SharedPreferences, plus the last used PC. */
object ProfileStore {
    private lateinit var prefs: SharedPreferences
    private const val PREFIX = "profile:"
    const val DEFAULT = "Default"

    /** Profile's settable fields, in declaration order. */
    val fields: List<Field> = Profile::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .onEach { it.isAccessible = true }

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("thumbracer", Context.MODE_PRIVATE)
    }

    var active: String
        get() = prefs.getString("active", DEFAULT) ?: DEFAULT
        set(v) = prefs.edit().putString("active", v).apply()

    fun names(): List<String> =
        (prefs.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) } + DEFAULT).distinct().sorted()

    fun load(name: String = active): Profile = prefs.getString(PREFIX + name, null)?.let { fromJson(it) } ?: Profile()

    fun save(p: Profile, name: String = active) = prefs.edit().putString(PREFIX + name, toJson(p)).apply()

    fun delete(name: String) = prefs.edit().remove(PREFIX + name).apply()

    fun toJson(p: Profile): String {
        val o = JSONObject()
        for (f in fields) o.put(f.name, f.get(p))
        return o.toString(2)
    }

    /** Unknown keys are ignored, missing ones keep their defaults, wrong types throw. */
    fun fromJson(s: String): Profile {
        val o = JSONObject(s)
        val p = Profile()
        for (f in fields) {
            if (!o.has(f.name)) continue
            when (f.type) {
                java.lang.Boolean.TYPE -> f.setBoolean(p, o.getBoolean(f.name))
                java.lang.Double.TYPE -> f.setDouble(p, o.getDouble(f.name))
            }
        }
        return p
    }

    // last PC
    fun saveLast(name: String, ip: String, port: Int) =
        prefs.edit().putString("lastName", name).putString("lastIp", ip).putInt("lastPort", port).apply()

    fun last(): Link.Pc? {
        val ip = prefs.getString("lastIp", null) ?: return null
        return Link.Pc(prefs.getString("lastName", ip) ?: ip, ip, prefs.getInt("lastPort", Protocol.DATA_PORT))
    }
}
