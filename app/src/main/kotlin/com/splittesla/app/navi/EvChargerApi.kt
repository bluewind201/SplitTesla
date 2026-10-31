package com.splittesla.app.navi

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.ChargerSpot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 한국환경공단 전기자동차 충전소 정보 (data.go.kr, B552584/EvCharger) by
 * 시군구 code. One record per charger; they are grouped into stations with
 * a count of free fast chargers. A Tesla charges on DC Combo (with the
 * adapter) and NACS, so only those types count.
 */
object EvChargerApi {
    private const val API = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo"

    /** chgerType codes with DC Combo or NACS: 04 콤보, 05 차데모+콤보, 06 차데모+AC3상+콤보, 09 NACS, 10 콤보+NACS. */
    private val TESLA_FAST = setOf("04", "05", "06", "09", "10")

    /** stat 2 = 충전대기(free). */
    private const val FREE = "2"

    suspend fun stations(key: String, zscode: String): List<ChargerSpot> = withContext(Dispatchers.IO) {
        val encoded = if ('%' in key) key else URLEncoder.encode(key, "UTF-8")
        val body = fetch("$API?serviceKey=$encoded&pageNo=1&numOfRows=9999&zscode=$zscode&dataType=JSON")
        val items = items(body)
        data class Acc(var name: String, var point: LatLon, var free: Int, var total: Int, var kw: Int, var op: String, var addr: String)
        val byStation = LinkedHashMap<String, Acc>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optString("chgerType").padStart(2, '0') !in TESLA_FAST) continue
            if (o.optString("delYn") == "Y") continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lng = o.optString("lng").toDoubleOrNull() ?: continue
            val id = o.optString("statId").ifBlank { "$lat,$lng" }
            val acc = byStation.getOrPut(id) {
                Acc(o.optString("statNm"), LatLon(lat, lng), 0, 0, 0, o.optString("busiNm").ifBlank { o.optString("bnm") }, o.optString("addr"))
            }
            acc.total++
            if (o.optString("stat") == FREE) acc.free++
            acc.kw = maxOf(acc.kw, o.optString("output").toDoubleOrNull()?.toInt() ?: 0)
        }
        byStation.values.map { ChargerSpot(it.name, it.point, tesla = false, powerKw = it.kw, available = it.free, total = it.total, operator = it.op, address = it.addr) }
    }

    /** The item array, wherever this API version puts it. */
    private fun items(body: String): JSONArray {
        val json = runCatching { JSONObject(body) }.getOrElse { throw NaviApiException(apiError(body)) }
        val root = json.optJSONObject("response")?.optJSONObject("body") ?: json
        val code = json.optString("resultCode").ifBlank { json.optJSONObject("response")?.optJSONObject("header")?.optString("resultCode") ?: "" }
        if (code.isNotBlank() && code != "00") {
            throw NaviApiException("충전소 API 오류 $code: ${json.optString("resultMsg")}")
        }
        val items = root.opt("items")
        return when (items) {
            is JSONObject -> items.optJSONArray("item") ?: items.optJSONObject("item")?.let { JSONArray().put(it) } ?: JSONArray()
            is JSONArray -> items
            else -> JSONArray()
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw NaviApiException(if (err.isNotBlank()) apiError(err) else "충전소 API HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** data.go.kr answers a bad or unapproved key with XML. */
    private fun apiError(body: String): String {
        val msg = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>").find(body)?.groupValues?.get(1)
            ?: Regex("<errMsg>(.*?)</errMsg>").find(body)?.groupValues?.get(1)
        return when {
            msg?.contains("NOT_REGISTERED") == true -> "충전소 API에 등록되지 않은 키입니다 (공공데이터포털에서 '전기자동차 충전소 정보' 활용신청 필요)"
            msg != null -> "충전소 API 응답: $msg"
            else -> "충전소 API 응답 형식이 다릅니다: ${body.take(120)}"
        }
    }
}
