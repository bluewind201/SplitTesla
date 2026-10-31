package com.splittesla.app.assistant

import android.app.Activity
import android.content.Intent

/** A navigation app to open next to this one when the app starts. */
enum class CompanionNavi(val label: String, val pkg: String?) {
    NONE("안 띄움", null),
    TMAP("TMAP", "com.skt.tmap.ku"),
    NAVER("네이버 지도", "com.nhn.android.nmap"),
    KAKAO_NAVI("카카오내비", "com.locnall.KimGiSa"),
}

/**
 * Opens the chosen navigation app in split screen beside this app. Android
 * picks the split itself; the divider can be dragged and most phones keep
 * the last ratio. Phones that can't split from an app open it full screen.
 */
object SplitLaunch {
    fun open(activity: Activity, app: CompanionNavi, log: (String) -> Unit) {
        val pkg = app.pkg ?: return
        val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            log("${app.label} 앱이 설치돼 있지 않아 같이 띄우지 못했습니다")
            return
        }
        launch.addFlags(
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
        )
        try {
            activity.startActivity(launch)
            log("${app.label}을(를) 분할 화면으로 열었습니다")
        } catch (e: Exception) {
            log("${app.label} 열기 실패: ${e.message}")
        }
    }
}
