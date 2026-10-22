package com.splittesla.core.camera

/** What a phone navigation app's notification says right now. */
data class NaviHint(
    val cameraLimitKmh: Int?,
    val cameraDistanceM: Int?,
    /** "구간단속", "과속카메라", "신호위반" … as the app wrote it, when a camera is mentioned. */
    val cameraKind: String?,
    /** The rest: next turn and other guidance, one line. */
    val guidance: String?,
)

/**
 * Pulls speed camera details out of the text Naver Map, TMAP or Kakao Navi
 * show in their notification while guiding in the background. The apps do not
 * publish a format, so this looks for the usual Korean wording: a camera word,
 * a limit like "60km/h" or "제한 60", and a distance like "300m" or "1.2km".
 */
object NaviText {
    private val cameraWord = Regex("(구간\\s?단속|과속\\s?(?:카메라|단속)?|단속\\s?카메라|이동식\\s?단속|신호\\s?(?:위반|과속)|카메라)")
    private val limit = Regex("(?:제한\\s*(?:속도)?\\s*)?(\\d{2,3})\\s*(?:km/h|㎞/h|km|㎞)(?!\\s*(?:앞|후|전))|제한\\s*(?:속도)?\\s*(\\d{2,3})")
    private val distance = Regex("(\\d+(?:\\.\\d+)?)\\s*(km|㎞|m)\\s*(?:앞|후|전|남음)?")

    fun parse(lines: List<String>): NaviHint? {
        val text = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (text.isEmpty()) return null
        val cameraLine = text.firstOrNull { cameraWord.containsMatchIn(it) }
        val kind = cameraLine?.let { cameraWord.find(it)?.value?.replace(" ", "") }
        var limitKmh: Int? = null
        var distM: Int? = null
        if (cameraLine != null) {
            // Search the camera line first, then the whole notification.
            for (source in listOf(cameraLine) + text) {
                if (limitKmh == null) limitKmh = findLimit(source)
                if (distM == null) distM = findDistance(source)
            }
        }
        val guidance = text.filter { it != cameraLine }.joinToString(" · ").ifBlank { null }
        return NaviHint(limitKmh, distM, kind, guidance)
    }

    private fun findLimit(s: String): Int? = limit.findAll(s)
        .mapNotNull { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
        .firstOrNull { it in 20..130 && it % 10 == 0 }

    private fun findDistance(s: String): Int? = distance.findAll(s)
        .filter { m ->
            // "60km/h" is a limit, not a distance.
            val after = s.substring(m.range.last + 1).trimStart()
            !after.startsWith("/h")
        }
        .mapNotNull { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            if (m.groupValues[2] == "m") v.toInt() else (v * 1000).toInt()
        }
        .firstOrNull { it in 1..5000 }
}
