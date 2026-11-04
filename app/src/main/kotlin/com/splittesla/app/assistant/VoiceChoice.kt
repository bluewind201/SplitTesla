package com.splittesla.app.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The voice every spoken prompt uses (the assistant, turn-by-turn guidance
 * and speed camera alerts), picked in Settings. [Setting.voiceName] is a
 * text-to-speech voice name; null keeps the engine's own Korean voice.
 */
object VoiceChoice {
    data class Setting(val voiceName: String? = null, val rate: Float = 1f, val muted: Boolean = false)

    private const val PREFS = "voice"
    private val _setting = MutableStateFlow(Setting())
    val setting: StateFlow<Setting> = _setting.asStateFlow()
    @Volatile private var loaded = false

    /** Reads the saved choice once; every speaker calls this before it talks. */
    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _setting.value = Setting(p.getString("name", null), p.getFloat("rate", 1f), p.getBoolean("muted", false))
        loaded = true
    }

    fun set(context: Context, setting: Setting) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", setting.voiceName)
            .putFloat("rate", setting.rate)
            .putBoolean("muted", setting.muted)
            .apply()
        _setting.value = setting
        loaded = true
    }
}
