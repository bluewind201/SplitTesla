package com.splittesla.core.assistant

/**
 * What a spoken command asks for. The AI model picks one of these by name;
 * without a model, [VoiceRules] recognises the common phrasings.
 *
 * Changing the car's own route needs the Fleet API, so route changes are
 * handed to Naver Map or Kakao Map on the phone instead.
 */
sealed interface VoiceAction {
    /** Start guidance to a place name or address. "집" and "회사" use the saved addresses. */
    data class Navigate(val place: String) : VoiceAction

    /** Keep the current destination and add a stop on the way. */
    data class AddStop(val place: String) : VoiceAction

    data object NearestCharger : VoiceAction
    data class Climate(val on: Boolean) : VoiceAction
    data class Temperature(val celsius: Float) : VoiceAction
    data object Lock : VoiceAction

    /** Speak the time and distance left. */
    data object RouteStatus : VoiceAction
    data object Weather : VoiceAction

    /** Mute or bring back the automatic driving announcements. */
    data class Announcements(val on: Boolean) : VoiceAction

    /** Just answer; nothing to do. */
    data object None : VoiceAction

    companion object {
        /** Names the AI model may use, with what each does, for its instructions. */
        val catalog = listOf(
            "navigate" to "목적지로 길 안내 시작 (query=장소명 또는 주소, 집/회사 가능)",
            "add_stop" to "현재 목적지는 유지하고 경유지 추가 (query=장소명)",
            "nearest_charger" to "가장 가까운 슈퍼차저로 안내",
            "climate_on" to "공조(에어컨/히터) 켜기",
            "climate_off" to "공조 끄기",
            "set_temp" to "실내 온도 설정 (value=섭씨)",
            "lock" to "문 잠그기",
            "route_status" to "남은 시간과 거리 안내",
            "weather" to "날씨 안내",
            "mute" to "자동 안내 끄기",
            "unmute" to "자동 안내 다시 켜기",
            "none" to "대답만 하고 아무것도 안 함",
        )

        /** Builds the action an AI reply named, or [None] when the name is unknown or a field is missing. */
        fun fromName(name: String?, query: String?, value: Double?): VoiceAction {
            val q = query?.trim().orEmpty()
            return when (name?.trim()?.lowercase()) {
                "navigate" -> if (q.isNotEmpty()) Navigate(q) else None
                "add_stop" -> if (q.isNotEmpty()) AddStop(q) else None
                "nearest_charger" -> NearestCharger
                "climate_on" -> Climate(true)
                "climate_off" -> Climate(false)
                "set_temp" -> value?.takeIf { it in 15.0..28.0 }?.let { Temperature(it.toFloat()) } ?: None
                "lock" -> Lock
                "route_status" -> RouteStatus
                "weather" -> Weather
                "mute" -> Announcements(false)
                "unmute" -> Announcements(true)
                else -> None
            }
        }
    }
}

/** Plain Korean phrasings understood without an AI model. */
object VoiceRules {
    private val navigateEndings = Regex("(으로|로|까지|에)?\\s*(가자|가 ?줘|가 ?주세요|안내해 ?줘|안내 ?해 ?주세요|안내|길 ?안내|가고 ?싶어|갈래)\\s*$")
    private val stopEndings = Regex("(에|을|를)?\\s*(들러 ?줘|들렀다 ?가자|들렀다 ?가|들러서 ?가자|들르자|경유해 ?줘|경유)\\s*$")
    private val leadIns = Regex("^(가는 ?길에|가다가|중간에|가는 ?김에)\\s*")
    private val degrees = Regex("(\\d{2}(?:\\.\\d)?)\\s*도")

    fun parse(command: String): VoiceAction {
        val t = command.trim().trimEnd('.', '!', '?', ' ')
        val flat = t.replace(" ", "")
        return when {
            flat.contains("충전소") || flat.contains("슈퍼차저") || flat.contains("충전하러") -> VoiceAction.NearestCharger
            stopEndings.containsMatchIn(t) -> place(t, stopEndings)?.let { VoiceAction.AddStop(it) } ?: VoiceAction.None
            flat.contains("조용") || flat.contains("그만말") || flat.contains("안내꺼") -> VoiceAction.Announcements(false)
            flat.contains("안내다시") || flat.contains("다시안내") || flat.contains("안내켜") -> VoiceAction.Announcements(true)
            (flat.contains("에어컨") || flat.contains("공조") || flat.contains("히터")) && flat.contains("꺼") -> VoiceAction.Climate(false)
            (flat.contains("에어컨") || flat.contains("공조") || flat.contains("히터")) && flat.contains("켜") -> VoiceAction.Climate(true)
            degrees.containsMatchIn(t) && (flat.contains("온도") || flat.contains("맞춰") || flat.contains("설정")) ->
                degrees.find(t)!!.groupValues[1].toDouble().let { VoiceAction.fromName("set_temp", null, it) }
            flat.contains("잠가") || flat.contains("잠궈") || flat.contains("문잠") -> VoiceAction.Lock
            flat.contains("얼마나남") || flat.contains("언제도착") || flat.contains("도착시간") || flat.contains("몇분남") -> VoiceAction.RouteStatus
            flat.contains("날씨") -> VoiceAction.Weather
            navigateEndings.containsMatchIn(t) -> place(t, navigateEndings)?.let { VoiceAction.Navigate(it) } ?: VoiceAction.None
            else -> VoiceAction.None
        }
    }

    private fun place(text: String, ending: Regex): String? =
        text.replace(ending, "").replace(leadIns, "").trim().takeIf { it.isNotEmpty() }
}
