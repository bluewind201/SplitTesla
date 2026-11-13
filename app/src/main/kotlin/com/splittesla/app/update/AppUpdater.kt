package com.splittesla.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What CI publishes next to the APK on the latest-apk release (latest.json). */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val commit: String,
    val notes: String,
)

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, READY, FAILED }

data class UpdateState(
    val currentName: String = "",
    val currentCode: Long = 0,
    val latest: UpdateInfo? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val message: String? = null,
    val checkedAt: Long = 0,
)

/**
 * Checks the GitHub `latest-apk` release (`latest.json`) and opens the release
 * APK URL in the system browser when the user taps Update. Does not declare
 * `REQUEST_INSTALL_PACKAGES` or `PackageInstaller`, avoiding Google Play
 * Protect's overlay + package-installer dropper block.
 */
object AppUpdater {
    const val MANIFEST_URL = "https://github.com/bluewind201/SplitTesla/releases/download/latest-apk/latest.json"

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile var log: (String) -> Unit = {}

    fun init(context: Context) {
        val pi = installed(context)
        _state.update { it.copy(currentName = pi.versionName ?: "", currentCode = versionCode(pi)) }
    }

    fun set(phase: UpdatePhase, message: String? = null) {
        _state.update { it.copy(phase = phase, message = message) }
        message?.let { log("업데이트: $it") }
    }

    /** Reads latest.json. Returns the release if it is newer than this app, else null. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        init(context)
        set(UpdatePhase.CHECKING)
        try {
            val json = JSONObject(httpText("$MANIFEST_URL?t=${System.currentTimeMillis()}"))
            val info = UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").lowercase(),
                commit = json.optString("commit"),
                notes = json.optString("notes"),
            )
            val newer = info.versionCode > _state.value.currentCode
            _state.update {
                it.copy(
                    latest = info,
                    checkedAt = System.currentTimeMillis(),
                    phase = if (newer) UpdatePhase.READY else UpdatePhase.UP_TO_DATE,
                    message = if (newer) "새 버전 ${info.versionName} (빌드 ${info.versionCode}) 다운로드 가능" else "최신 버전입니다",
                )
            }
            if (newer) info else null
        } catch (e: Exception) {
            set(UpdatePhase.FAILED, "확인 실패: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    fun openDownload(context: Context, info: UpdateInfo? = _state.value.latest) {
        val url = info?.apkUrl ?: "https://github.com/bluewind201/SplitTesla/releases/download/latest-apk/SplitTesla.apk"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Suppress("DEPRECATION")
    private fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    @Suppress("DEPRECATION")
    private fun versionCode(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SplitTesla-Updater")
        conn.setRequestProperty("Cache-Control", "no-cache")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        return conn
    }

    private fun httpText(url: String): String = open(url).let { c -> c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() } }
}

class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("splittesla_update", Context.MODE_PRIVATE)

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) = prefs.edit().putBoolean("auto", value).apply()
}
