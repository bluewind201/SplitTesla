package com.splittesla.app.camera

import android.content.Context
import android.net.Uri
import com.splittesla.core.camera.CameraCsv
import com.splittesla.core.camera.CameraStore
import com.splittesla.core.camera.SpeedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The speed camera list, kept in app storage. It comes from the public
 * "전국무인교통단속카메라표준데이터": either a CSV the user downloaded from
 * data.go.kr, or the same data through its open API with the user's key.
 */
class CameraRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "cameras.tsv")
    private val prefs = context.getSharedPreferences("splittesla", Context.MODE_PRIVATE)

    val count: Int get() = prefs.getInt("camera_count", 0)
    val updatedAt: Long get() = prefs.getLong("camera_updated", 0)
    var apiKey: String
        get() = prefs.getString("camera_api_key", "") ?: ""
        set(value) = prefs.edit().putString("camera_api_key", value.trim()).apply()

    suspend fun load(): List<SpeedCamera> = withContext(Dispatchers.IO) {
        if (file.exists()) CameraStore.decode(file.readText()) else emptyList()
    }

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
            val root = responseRoot(body) ?: error(apiError(body))
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

    /** Asks for a single row; returns how many cameras the API holds. Fails with the API's own message. */
    suspend fun test(key: String): Int = withContext(Dispatchers.IO) {
        val body = fetch(key, 1, rows = 1)
        val root = responseRoot(body) ?: error(apiError(body))
        val header = root.optJSONObject("header")
        val code = header?.optString("resultCode")
        if (code != null && code != "00") error("API 오류 $code: ${header.optString("resultMsg")}")
        root.optJSONObject("body")?.optInt("totalCount", 0) ?: 0
    }

    /**
     * The part holding header and body. Some data.go.kr APIs wrap it in
     * {"response": …}; this camera API sends {"header": …, "body": …} bare.
     */
    private fun responseRoot(body: String): JSONObject? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optJSONObject("response") ?: json.takeIf { it.has("header") || it.has("body") }
    }

    /** data.go.kr answers a bad or unapproved key with an XML message instead of JSON. */
    private fun apiError(body: String): String {
        val msg = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>").find(body)?.groupValues?.get(1)
            ?: Regex("<errMsg>(.*?)</errMsg>").find(body)?.groupValues?.get(1)
        return when {
            msg?.contains("NOT_REGISTERED") == true -> "등록되지 않은 키입니다 (활용신청 승인 후 1~2시간 뒤부터 동작할 수 있어요)"
            msg != null -> "API 응답: $msg"
            else -> "API 응답 형식이 다릅니다: ${body.take(120)}"
        }
    }

    private fun fetch(key: String, page: Int, rows: Int = PAGE): String {
        // Keys from data.go.kr come either URL-encoded or not; encode only a raw one.
        val encodedKey = if ('%' in key) key else URLEncoder.encode(key, "UTF-8")
        val url = URL("$API?serviceKey=$encodedKey&pageNo=$page&numOfRows=$rows&type=json")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error(if (err.isNotBlank()) apiError(err) else "API HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun fromApi(o: JSONObject): SpeedCamera? {
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
        // The API uses the standard dataset's English field names; feed them through the same rules as the CSV.
        val header = listOf("무인교통단속카메라관리번호", "위도", "경도", "제한속도", "설치장소", "단속구간위치구분", "과속단속구간길이", "보호구역구분", "도로노선방향")
        val row = listOf(
            s("mnlssRegltCameraManageNo", "cameraManageNo"), s("latitude"), s("longitude"), s("lmttVe"),
            s("itlpc", "rdnmadr", "lnmadr"), s("regltSctnLcSe"), s("ovrspdRegltSctnLt"), s("prtcareaType"), s("roadRouteDrc"),
        )
        val csv = header.joinToString(",") + "\n" + row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
        return CameraCsv.parse(csv).firstOrNull()
    }

    private fun save(cameras: List<SpeedCamera>): Int {
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
