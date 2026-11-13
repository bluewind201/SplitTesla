package com.splittesla.app.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What CI publishes next to the APK on the latest-apk release (latest.json). */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val commit: String,
    val notes: String,
)

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, FAILED }

data class UpdateState(
    val currentName: String = "",
    val currentCode: Long = 0,
    val latest: UpdateInfo? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Int = 0,
    val message: String? = null,
    val checkedAt: Long = 0,
)

/**
 * Self-update from GitHub, like Portunus: CI puts SplitTesla.apk and
 * latest.json on the latest-apk release; this reads the json, downloads a
 * newer APK, checks its hash, package and signing key against the installed
 * app, and hands it to PackageInstaller. Once this app has installed itself
 * once, Android 12+ lets later updates go through without a prompt.
 */
object AppUpdater {
    const val MANIFEST_URL = "https://github.com/bluewind201/SplitTesla/releases/download/latest-apk/latest.json"
    const val ACTION_STATUS = "com.splittesla.app.UPDATE_STATUS"

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Status lines for the app log, set by the view model. */
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
                    // Keep a finished download ready across re-checks.
                    phase = when {
                        !newer -> UpdatePhase.UP_TO_DATE
                        apkFile(context, info).exists() && it.phase == UpdatePhase.READY -> UpdatePhase.READY
                        else -> UpdatePhase.IDLE
                    },
                    message = if (newer) "새 버전 ${info.versionName} (${info.versionCode})" else "최신 버전입니다",
                )
            }
            if (newer) info else null
        } catch (e: Exception) {
            set(UpdatePhase.FAILED, "확인 실패: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    fun apkFile(context: Context, info: UpdateInfo) = File(File(context.cacheDir, "updates"), "SplitTesla-${info.versionCode}.apk")

    /** Downloads and verifies the APK. Returns the file only when it is safe to install. */
    suspend fun download(context: Context, info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val file = apkFile(context, info)
        try {
            if (file.exists() && sha256(file) == info.sha256 && verify(context, file, info) == null) {
                set(UpdatePhase.READY, "${info.versionName} 설치 준비됨")
                return@withContext file
            }
            file.parentFile?.listFiles()?.forEach { it.delete() } // older downloads
            file.parentFile?.mkdirs()
            set(UpdatePhase.DOWNLOADING)
            val conn = open(info.apkUrl)
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) _state.update { it.copy(progress = (done * 100 / total).toInt()) }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val problem = if (hash != info.sha256) "파일 해시가 다릅니다" else verify(context, file, info)
            if (problem != null) {
                file.delete()
                set(UpdatePhase.FAILED, "검증 실패: $problem")
                return@withContext null
            }
            set(UpdatePhase.READY, "${info.versionName} 다운로드 완료")
            file
        } catch (e: Exception) {
            file.delete()
            set(UpdatePhase.FAILED, "다운로드 실패: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** Null when the APK is this app, newer, and signed with the same key; else the reason. */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun verify(context: Context, file: File, info: UpdateInfo): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "APK를 읽을 수 없습니다"
        if (archive.packageName != context.packageName) return "다른 앱의 APK입니다"
        if (versionCode(archive) <= versionCode(installed(context))) return "설치된 버전보다 높지 않습니다"
        if (versionCode(archive) != info.versionCode) return "버전 정보가 맞지 않습니다"
        val mine = signers(pm.getPackageInfo(context.packageName, flags))
        val theirs = signers(archive)
        if (mine.isEmpty() || mine != theirs) return "서명 키가 다릅니다 (재설치 필요)"
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory?.takeLast(1)?.toTypedArray() }
        } else {
            pi.signatures
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Android asks once per app before it may install APKs. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the verified APK to PackageInstaller; the result comes back to [UpdateReceiver]. */
    fun install(context: Context, file: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("SplitTesla.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(ACTION_STATUS).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                UpdatePrefs(context).relaunch = true
                set(UpdatePhase.INSTALLING, "설치 중… 앱이 잠시 닫혔다가 다시 열립니다")
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            UpdatePrefs(context).relaunch = false
            set(UpdatePhase.FAILED, "설치 실패: ${e.message ?: e.javaClass.simpleName}")
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

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("splittesla_update", Context.MODE_PRIVATE)

    /** Check, download and install new GitHub builds on their own. */
    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) = prefs.edit().putBoolean("auto", value).apply()

    /** An install this app started; reopen the app once it lands. */
    var relaunch: Boolean
        get() = prefs.getBoolean("relaunch", false)
        // commit, not apply: the process is killed moments later by the install.
        set(value) { prefs.edit().putBoolean("relaunch", value).commit() }
}
