package com.splittesla.app.navi

import com.splittesla.core.assistant.Geo
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGuide
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An API answered with an error the user can act on (bad key, no route, …). */
class NaviApiException(message: String) : Exception(message)

/**
 * NAVER Cloud Platform Maps (Directions 5/15, Geocoding, Reverse Geocoding)
 * and NAVER Developers local search. Every call runs on the IO dispatcher.
 */
class NaverApi(private val prefs: NaviPrefs) {

    /** Routes for each of [options] from [start] to [goal] through [waypoints] (up to 15). */
    suspend fun directions(
        start: LatLon,
        goal: NaviPlace,
        waypoints: List<NaviPlace> = emptyList(),
        options: List<RouteOption> = listOf(prefs.option),
    ): List<NaviRoute> {
        requireMaps()
        val api = if (waypoints.size > 5) "map-direction-15/v1/driving" else "map-direction/v1/driving"
        val query = buildString {
            append("start=").append(lngLat(start))
            append("&goal=").append(lngLat(goal.point))
            if (waypoints.isNotEmpty()) append("&waypoints=").append(enc(waypoints.take(15).joinToString("|") { lngLat(it.point) }))
            append("&option=").append(options.distinct().take(3).joinToString(":") { it.code })
        }
        val json = JSONObject(get("$MAPS/$api?$query", mapsHeaders()))
        val code = json.optInt("code", -1)
        if (code != 0) throw NaviApiException(directionsError(code, json.optString("message")))
        val routes = json.getJSONObject("route")
        val now = System.currentTimeMillis()
        return options.mapNotNull { opt ->
            val arr = routes.optJSONArray(opt.code) ?: return@mapNotNull null
            if (arr.length() == 0) null else parseRoute(arr.getJSONObject(0), opt, goal, waypoints, now)
        }
    }

    /** Addresses (도로명 · 지번) matching [query], nearest to [near] first. */
    suspend fun geocode(query: String, near: LatLon?): List<NaviPlace> {
        requireMaps()
        val coord = near?.let { "&coordinate=${lngLat(it)}" } ?: ""
        val json = JSONObject(get("$MAPS/map-geocode/v2/geocode?query=${enc(query)}$coord", mapsHeaders()))
        if (json.optString("status") != "OK") throw NaviApiException("주소 검색 실패: ${json.optString("errorMessage").ifBlank { json.optString("status") }}")
        val arr = json.optJSONArray("addresses") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val road = a.optString("roadAddress")
            NaviPlace(
                name = road.ifBlank { a.optString("jibunAddress") },
                point = LatLon(a.getString("y").toDouble(), a.getString("x").toDouble()),
                address = a.optString("jibunAddress"),
                category = "주소",
            )
        }
    }

    /** 법정동 code (10 digits) of the place at [p]; its first 5 digits are the 시군구 code. */
    suspend fun legalCode(p: LatLon): String? {
        requireMaps()
        val json = JSONObject(get("$MAPS/map-reversegeocode/v2/gc?coords=${lngLat(p)}&orders=legalcode&output=json", mapsHeaders()))
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optJSONObject("code")?.optString("id")?.takeIf { it.length >= 5 }
    }

    /** Businesses and landmarks from NAVER local search (5 at most per query, NAVER's limit). */
    suspend fun localSearch(query: String, near: LatLon?): List<NaviPlace> {
        if (!prefs.hasSearch) return emptyList()
        val headers = mapOf("X-Naver-Client-Id" to prefs.searchId, "X-Naver-Client-Secret" to prefs.searchSecret)
        val json = JSONObject(get("$SEARCH/v1/search/local.json?query=${enc(query)}&display=5&sort=random", headers))
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<NaviPlace>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val name = o.optString("title").replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
            val road = o.optString("roadAddress").ifBlank { o.optString("address") }
            val x = o.optString("mapx").toDoubleOrNull()
            val y = o.optString("mapy").toDoubleOrNull()
            // Current responses are WGS84 × 10^7; older KATECH values are small numbers, so look the address up instead.
            val point = if (x != null && y != null && x > 1e8) LatLon(y / 1e7, x / 1e7)
            else if (prefs.hasMaps && road.isNotBlank()) runCatching { geocode(road, near).firstOrNull()?.point }.getOrNull()
            else null
            point?.let { out += NaviPlace(name, it, road, o.optString("category").substringAfterLast(">")) }
        }
        return near?.let { n -> out.sortedBy { Geo.distanceKm(n, it.point) } } ?: out
    }

    /** Place search for the search box: businesses first, then addresses. */
    suspend fun findPlaces(query: String, near: LatLon?): List<NaviPlace> {
        val local = runCatching { localSearch(query, near) }.getOrElse { if (!prefs.hasMaps) throw it else emptyList() }
        val addresses = if (prefs.hasMaps) runCatching { geocode(query, near) }.getOrDefault(emptyList()) else emptyList()
        return (local + addresses).distinctBy { "%.4f,%.4f".format(it.point.lat, it.point.lon) }
    }

    private fun parseRoute(o: JSONObject, option: RouteOption, goal: NaviPlace, waypoints: List<NaviPlace>, now: Long): NaviRoute {
        val summary = o.getJSONObject("summary")
        val pathArr = o.getJSONArray("path")
        val path = ArrayList<LatLon>(pathArr.length())
        for (i in 0 until pathArr.length()) {
            val p = pathArr.getJSONArray(i)
            path += LatLon(p.getDouble(1), p.getDouble(0))
        }
        val sections = o.optJSONArray("section")?.let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                RouteSection(s.optInt("pointIndex"), s.optInt("pointCount"), s.optInt("distance"), s.optString("name"), s.optInt("congestion"), s.optInt("speed"))
            }
        } ?: emptyList()
        val guides = o.optJSONArray("guide")?.let { arr ->
            (0 until arr.length()).map { i ->
                val g = arr.getJSONObject(i)
                RouteGuide(g.optInt("pointIndex"), g.optInt("type"), g.optString("instructions"), g.optInt("distance"), g.optLong("duration"))
            }
        } ?: emptyList()
        return NaviRoute(
            option = option,
            path = path,
            sections = sections,
            guides = guides,
            distanceM = summary.optInt("distance"),
            durationMs = summary.optLong("duration"),
            tollFare = summary.optInt("tollFare"),
            goal = goal,
            waypoints = waypoints,
            fetchedAt = now,
        )
    }

    private fun requireMaps() {
        if (!prefs.hasMaps) throw NaviApiException("설정 탭 '길안내 API'에 NAVER Cloud Maps 키(Client ID · Secret)를 넣어 주세요")
    }

    private fun mapsHeaders() = mapOf("x-ncp-apigw-api-key-id" to prefs.ncpKeyId, "x-ncp-apigw-api-key" to prefs.ncpKey)

    private fun directionsError(code: Int, message: String) = when (code) {
        1 -> "출발지와 도착지가 같습니다"
        2 -> "출발지나 도착지가 도로 주변이 아닙니다"
        3 -> "자동차로 갈 수 있는 길이 없습니다"
        4 -> "경유지가 도로 주변이 아닙니다"
        5 -> "경유지를 포함한 직선 거리가 1,500km를 넘습니다"
        else -> "경로 검색 실패 ($code ${message.take(80)})"
    }

    companion object {
        private const val MAPS = "https://maps.apigw.ntruss.com"
        private const val SEARCH = "https://openapi.naver.com"

        fun lngLat(p: LatLon) = "%.7f,%.7f".format(java.util.Locale.US, p.lon, p.lat)
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** GET with headers; a 4xx/5xx becomes a readable [NaviApiException]. */
        suspend fun get(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw NaviApiException(httpError(code, body))
                body
            } finally {
                conn.disconnect()
            }
        }

        private fun httpError(code: Int, body: String): String {
            val detail = runCatching {
                val o = JSONObject(body)
                o.optJSONObject("error")?.optString("message") ?: o.optString("errorMessage").ifBlank { o.optString("message") }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(120)
            return when (code) {
                401 -> "인증 실패(401): 키가 맞는지, 콘솔에서 해당 API를 사용 신청했는지 확인하세요. $detail"
                403 -> "권한 없음(403): 이 키로 해당 API를 쓸 수 없습니다(서비스 선택 확인). $detail"
                429 -> "호출 한도 초과(429). 잠시 후 다시 시도하세요"
                else -> "HTTP $code: $detail"
            }
        }
    }
}
