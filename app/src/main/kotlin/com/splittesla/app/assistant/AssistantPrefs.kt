package com.splittesla.app.assistant

import android.content.Context

enum class LlmProvider(val label: String, val defaultModel: String, val keyHint: String) {
    CLAUDE("Claude", "claude-haiku-5-5", "console.anthropic.com 에서 발급 (sk-ant-…)"),
    GPT("ChatGPT", "gpt-4o-mini", "platform.openai.com 에서 발급 (sk-…)"),
    GEMINI("Gemini", "gemini-2.5-flash", "aistudio.google.com 에서 발급 (AIza…)"),
}

enum class NaviApp(val label: String, val pkg: String) {
    NAVER("네이버 지도", "com.nhn.android.nmap"),
    TMAP("TMAP", "com.skt.tmap.ku"),
    KAKAO("카카오맵", "net.daum.android.map"),
    WAZE("Waze", "com.waze"),
}

/**
 * Assistant and phone-navigation settings, in their own file so they stay
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

    var naviApp: NaviApp
        get() = NaviApp.entries.firstOrNull { it.name == prefs.getString("navi_app", null) } ?: NaviApp.NAVER
        set(value) = prefs.edit().putString("navi_app", value.name).apply()

    /** Send the car's destination to [naviApp] on its own whenever it changes. */
    var autoShareRoute: Boolean
        get() = prefs.getBoolean("auto_share_route", true)
        set(value) = prefs.edit().putBoolean("auto_share_route", value).apply()

    /** Navigation app opened in split screen when this app starts. */
    var companionNavi: CompanionNavi
        get() = CompanionNavi.entries.firstOrNull { it.name == prefs.getString("companion_navi", null) } ?: CompanionNavi.NONE
        set(value) = prefs.edit().putString("companion_navi", value.name).apply()

    var homeAddress: String
        get() = prefs.getString("home", "") ?: ""
        set(value) = prefs.edit().putString("home", value.trim()).apply()

    var workAddress: String
        get() = prefs.getString("work", "") ?: ""
        set(value) = prefs.edit().putString("work", value.trim()).apply()

    /** 국가교통정보센터(its.go.kr) open API key for accident and roadwork events. */
    var itsKey: String
        get() = prefs.getString("its_key", "") ?: ""
        set(value) = prefs.edit().putString("its_key", value.trim()).apply()
}
