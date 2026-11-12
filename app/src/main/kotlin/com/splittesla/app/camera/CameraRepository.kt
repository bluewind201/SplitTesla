package com.splittesla.app.camera

import android.content.Context
import android.net.Uri
import com.splittesla.core.camera.CameraCsv
import com.splittesla.core.camera.CameraSeed
import com.splittesla.core.camera.CameraStore
import com.splittesla.core.camera.SpeedCamera
import com.splittesla.core.camera.mergeSameSpot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The speed camera list. Starts with the small built-in seed so alerts work
 * offline right away; the full public "전국무인교통단속카메라표준데이터"
 * (a CSV from data.go.kr, or its open API with the user's key) replaces it.
 */
class CameraRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "cameras.tsv")
    private val prefs = context.getSharedPreferences("splittesla", Context.MODE_PRIVATE)

    val count: Int get() = prefs.getInt("camera_count", 0)
    val updatedAt: Long get() = prefs.getLong("camera_updated", 0)
    var apiKey: String
        get() = prefs.getString("camera_api_key", "") ?: ""
        set(value) = prefs.edit().putString("camera_api_key", value.trim()).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("camera_enabled", true)
        set(value) = prefs.edit().putBoolean("camera_enabled", value).apply()

    var voice: Boolean
        get() = prefs.getBoolean("camera_voice", true)
        set(value) = prefs.edit().putBoolean("camera_voice", value).apply()

    /** The imported list, or the built-in seed when nothing was imported. */
    suspend fun load(): List<SpeedCamera> = withContext(Dispatchers.IO) {
        val imported = if (file.exists()) runCatching { mergeSameSpot(CameraStore.decode(file.readText())) }.getOrDefault(emptyList()) else emptyList()
        imported.ifEmpty { CameraSeed.cameras }
    }

    val usingSeed: Boolean get() = !file.exists() || count == 0

    suspend fun importCsv(uri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("파일을 열 수 없습니다")
        save(CameraCsv.parse(bytes))
    }

    suspend fun download(key: String): Int = withContext(Dispatchers.IO) {
        val all = ArrayList<SpeedCamera>()
        var page = 1
        var total = Int.MAX_VALUE
        while ((page - 1) * PAGE < total) {
            val body = fetch(key, page)
            val json = runCatching { JSONObject(body) }.getOrNull() ?: error("API 응답 형식이 다릅니다: ${body.take(120)}")
            val root = json.optJSONObject("response") ?: json
            val header = root.optJSONObject("header")
            val code = header?.optString("resultCode")
            if (code != null && code != "00") error("API 오류 $code: ${header.optString("resultMsg")}")
            val bodyObj = root.optJSONObject("body") ?: break
            total = bodyObj.optInt("totalCount", 0)
            val items = bodyObj.opt("items").let { if (it is JSONObject) it.optJSONArray("item") else it as? JSONArray } ?: break
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { o -> fromApi(o)?.let(all::add) }
            page++
        }
        save(all)
    }

    fun clearImported() {
        file.delete()
        prefs.edit().remove("camera_count").remove("camera_updated").apply()
    }

    private fun fetch(key: String, page: Int): String {
        val encodedKey = if ('%' in key) key else URLEncoder.encode(key, "UTF-8")
        val conn = URL("$API?serviceKey=$encodedKey&pageNo=$page&numOfRows=$PAGE&type=json").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            if (conn.responseCode != 200) error("API HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun fromApi(o: JSONObject): SpeedCamera? {
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
        val header = listOf("무인교통단속카메라관리번호", "위도", "경도", "제한속도", "설치장소", "단속구간위치구분", "과속단속구간길이", "보호구역구분", "단속구분", "도로노선방향")
        val row = listOf(
            s("mnlssRegltCameraManageNo", "cameraManageNo"), s("latitude"), s("longitude"), s("lmttVe"),
            s("itlpc", "rdnmadr", "lnmadr"), s("regltSctnLcSe"), s("ovrspdRegltSctnLt"), s("prtcareaType", "prtcareaSe"), s("regltSe"), s("roadRouteDrc", "roadRouteDirection"),
        )
        val csv = header.joinToString(",") + "\n" + row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
        return CameraCsv.parse(csv).firstOrNull()
    }

    private fun save(all: List<SpeedCamera>): Int {
        val cameras = mergeSameSpot(all)
        if (cameras.isEmpty()) error("과속 카메라를 하나도 찾지 못했습니다. 파일이나 키를 확인하세요")
        file.writeText(CameraStore.encode(cameras))
        prefs.edit().putInt("camera_count", cameras.size).putLong("camera_updated", System.currentTimeMillis()).apply()
        return cameras.size
    }

    private companion object {
        const val API = "https://api.data.go.kr/openapi/tn_pubr_public_unmanned_traffic_camera_api"
        const val PAGE = 1000
    }
}
