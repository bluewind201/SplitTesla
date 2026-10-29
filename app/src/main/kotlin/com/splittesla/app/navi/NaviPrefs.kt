package com.splittesla.app.navi

import android.content.Context
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.RouteOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app navigation settings and the API keys it needs. Keys stay on this
 * phone, like the AI and traffic keys.
 *
 * - NAVER Cloud Platform Maps (Application, "Maps" → Directions 5, Geocoding,
 *   Reverse Geocoding): route, address search, region codes for chargers.
 * - NAVER Developers search (지역): place names such as "강남역 스타벅스".
 * - data.go.kr 한국환경공단 전기자동차 충전소 정보: public fast chargers.
 */
class NaviPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("navi", Context.MODE_PRIVATE)

    var ncpKeyId: String
        get() = prefs.getString("ncp_key_id", "") ?: ""
        set(value) = prefs.edit().putString("ncp_key_id", value.trim()).apply()

    var ncpKey: String
        get() = prefs.getString("ncp_key", "") ?: ""
        set(value) = prefs.edit().putString("ncp_key", value.trim()).apply()

    var searchId: String
        get() = prefs.getString("search_id", "") ?: ""
        set(value) = prefs.edit().putString("search_id", value.trim()).apply()

    var searchSecret: String
        get() = prefs.getString("search_secret", "") ?: ""
        set(value) = prefs.edit().putString("search_secret", value.trim()).apply()

    /** 공공데이터포털 key for the charger API; empty falls back to the speed camera key (one key per account). */
    var evKey: String
        get() = prefs.getString("ev_key", "") ?: ""
        set(value) = prefs.edit().putString("ev_key", value.trim()).apply()

    var option: RouteOption
        get() = RouteOption.ofCode(prefs.getString("option", null) ?: "") ?: RouteOption.FAST
        set(value) = prefs.edit().putString("option", value.code).apply()

    /** Search again when the car leaves the route. */
    var autoReroute: Boolean
        get() = prefs.getBoolean("auto_reroute", true)
        set(value) = prefs.edit().putBoolean("auto_reroute", value).apply()

    /** Every few minutes compare traffic and switch to a route at least 3 minutes faster. */
    var fasterRoute: Boolean
        get() = prefs.getBoolean("faster_route", true)
        set(value) = prefs.edit().putBoolean("faster_route", value).apply()

    var voice: Boolean
        get() = prefs.getBoolean("voice", true)
        set(value) = prefs.edit().putBoolean("voice", value).apply()

    /** Battery percent on arrival below which charging stops are suggested. */
    var reserveSoc: Int
        get() = prefs.getInt("reserve_soc", 15)
        set(value) = prefs.edit().putInt("reserve_soc", value.coerceIn(5, 50)).apply()

    /** Spoken "○○ 가자" starts guidance in this app instead of the phone's navigation app. */
    var voiceInApp: Boolean
        get() = prefs.getBoolean("voice_in_app", true)
        set(value) = prefs.edit().putBoolean("voice_in_app", value).apply()

    val hasMaps: Boolean get() = ncpKeyId.isNotBlank() && ncpKey.isNotBlank()
    val hasSearch: Boolean get() = searchId.isNotBlank() && searchSecret.isNotBlank()

    /** The last few destinations, newest first. */
    var recent: List<NaviPlace>
        get() = try {
            val arr = JSONArray(prefs.getString("recent", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NaviPlace(o.getString("name"), LatLon(o.getDouble("lat"), o.getDouble("lon")), o.optString("address"), o.optString("category"))
            }
        } catch (_: Exception) {
            emptyList()
        }
        set(value) {
            val arr = JSONArray()
            value.take(8).forEach { p ->
                arr.put(JSONObject().put("name", p.name).put("lat", p.point.lat).put("lon", p.point.lon).put("address", p.address).put("category", p.category))
            }
            prefs.edit().putString("recent", arr.toString()).apply()
        }

    fun remember(place: NaviPlace) {
        recent = listOf(place) + recent.filterNot { it.name == place.name && it.address == place.address }
    }
}
