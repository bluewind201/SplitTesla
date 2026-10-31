package com.splittesla.app.assistant

import com.splittesla.core.assistant.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class TrafficEvent(
    val id: String,
    /** 교통사고, 공사, 기상, 재난, 기타 … as the center labels them. */
    val kind: String,
    val detail: String,
    val road: String,
    val lanes: String,
    val message: String,
    val position: LatLon,
) {
    val isAccident: Boolean get() = kind.contains("사고")
    val isRoadwork: Boolean get() = kind.contains("공사")
}

/**
 * Live incidents (accidents, roadwork, weather closures) from the Korean
 * National Transport Information Center open API (국가교통정보센터 돌발상황정보).
 * Needs a free key from its.go.kr. The response layout follows the center's
 * published sample; it has not been checked against a live key yet.
 */
class TrafficEvents(private val apiKey: String) {

    suspend fun around(a: LatLon, b: LatLon, marginDeg: Double = 0.05): List<TrafficEvent> = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US,
            "https://openapi.its.go.kr:9443/eventInfo?apiKey=%s&type=all&eventType=all&minX=%.5f&maxX=%.5f&minY=%.5f&maxY=%.5f&getType=json",
            URLEncoder.encode(apiKey, "UTF-8"),
            min(a.lon, b.lon) - marginDeg, max(a.lon, b.lon) + marginDeg,
            min(a.lat, b.lat) - marginDeg, max(a.lat, b.lat) + marginDeg,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("교통정보 HTTP $code ${text.take(120)}".trim())
            parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): List<TrafficEvent> {
        // A wrong key comes back as a short text or XML message rather than JSON.
        val root = runCatching { JSONObject(text) }.getOrNull() ?: error("키를 확인하세요. 응답: ${text.take(120)}")
        val header = root.optJSONObject("header")
        if (header != null && header.optInt("resultCode", 0) != 0) error("교통정보: ${header.optString("resultMsg")}")
        val items: JSONArray = when (val raw = root.optJSONObject("body")?.opt("items")) {
            is JSONArray -> raw
            is JSONObject -> raw.optJSONArray("item") ?: JSONArray().put(raw)
            else -> JSONArray()
        }
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val x = o.optString("coordX").toDoubleOrNull() ?: return@mapNotNull null
            val y = o.optString("coordY").toDoubleOrNull() ?: return@mapNotNull null
            TrafficEvent(
                id = listOf(o.optString("linkId"), o.optString("startDate"), o.optString("eventType"), x, y).joinToString("|"),
                kind = o.optString("eventType"),
                detail = o.optString("eventDetailType"),
                road = o.optString("roadName"),
                lanes = o.optString("lanesBlocked"),
                message = o.optString("message"),
                position = LatLon(y, x),
            )
        }
    }
}
