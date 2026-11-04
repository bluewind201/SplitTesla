@file:Suppress("unused")
package android.content

import android.media.AudioManager

open class Context {
    open val applicationContext: Context get() = this
    open fun getSystemService(name: String): Any = audio
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    companion object {
        const val AUDIO_SERVICE = "audio"
        const val MODE_PRIVATE = 0
        var audio = AudioManager()
        var prefs = SharedPreferences()
    }
}
class SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    fun getString(k: String, d: String?) = map[k] as String? ?: d
    fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
    fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
    fun edit() = Editor()
    inner class Editor {
        fun putString(k: String, v: String?) = apply { map[k] = v }
        fun putFloat(k: String, v: Float) = apply { map[k] = v }
        fun putBoolean(k: String, v: Boolean) = apply { map[k] = v }
        fun apply() = Unit
    }
}
