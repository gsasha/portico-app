package com.meda.portico.store

import android.content.SharedPreferences

/**
 * An in-memory [SharedPreferences] for JVM tests (the interface only; nothing from android.jar's
 * stubbed classes runs). Like the real one, an editor's changes become visible at commit/apply.
 */
class FakeSharedPreferences(var commitResult: Boolean = true) : SharedPreferences {
    private val map = HashMap<String, Any?>()
    var commits = 0
        private set
    var applies = 0
        private set

    override fun getAll(): MutableMap<String, *> = synchronized(map) { HashMap(map) }
    override fun getString(key: String, defValue: String?): String? = synchronized(map) { map[key] as String? ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(map) { map[key] as MutableSet<String>? ?: defValues }
    override fun getInt(key: String, defValue: Int): Int = synchronized(map) { map[key] as Int? ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(map) { map[key] as Long? ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(map) { map[key] as Float? ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(map) { map[key] as Boolean? ?: defValue }
    override fun contains(key: String): Boolean = synchronized(map) { key in map }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        private fun write() = synchronized(map) {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            for ((k, v) in changes) if (v == null) map.remove(k) else map[k] = v
        }
        override fun commit(): Boolean {
            write()
            commits++
            return commitResult
        }
        override fun apply() {
            write()
            applies++
        }
    }
}
