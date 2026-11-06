package com.splittesla.app.assistant

import android.content.Context

enum class LlmProvider(val label: String, val defaultModel: String, val keyHint: String) {
    CLAUDE("Claude", "claude-haiku-5-5", "console.anthropic.com 에서 발급 (sk-ant-…)"),
    GPT("ChatGPT", "gpt-4o-mini", "platform.openai.com 에서 발급 (sk-…)"),
    GEMINI("Gemini", "gemini-2.5-flash", "aistudio.google.com 에서 발급 (AIza…)"),
}

/**
 * Assistant settings, in their own file so they stay
 * apart from the car key and the rest of the app's settings. API keys stay
 * on this phone.
 */
class AssistantPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    var provider: LlmProvider
        get() = LlmProvider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: LlmProvider.CLAUDE
        set(value) = prefs.edit().putString("provider", value.name).apply()

    fun apiKey(p: LlmProvider): String = prefs.getString("key_${p.name}", "") ?: ""
    fun setApiKey(p: LlmProvider, key: String) = prefs.edit().putString("key_${p.name}", key.trim()).apply()

    fun model(p: LlmProvider): String = prefs.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModel(p: LlmProvider, model: String) = prefs.edit().putString("model_${p.name}", model.trim()).apply()

    /** Speak boarding, route, accident, weather and arrival notes on its own. */
    var announcements: Boolean
        get() = prefs.getBoolean("announcements", true)
        set(value) = prefs.edit().putBoolean("announcements", value).apply()

    /** 국가교통정보센터(its.go.kr) open API key for accident and roadwork events. */
    var itsKey: String
        get() = prefs.getString("its_key", "") ?: ""
        set(value) = prefs.edit().putString("its_key", value.trim()).apply()
}
