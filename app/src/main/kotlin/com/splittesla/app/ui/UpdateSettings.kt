package com.splittesla.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.update.AppUpdater
import com.splittesla.app.update.UpdatePhase
import com.splittesla.app.ui.theme.Neon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings card for checking and downloading GitHub updates. */
@Composable
fun UpdateSettingsCard(vm: AppViewModel) {
    val context = LocalContext.current
    val s by AppUpdater.state.collectAsState()
    var auto by remember { mutableStateOf(vm.updater.auto) }
    val latest = s.latest
    val newer = latest != null && latest.versionCode > s.currentCode

    FoldSection(
        "버전 및 업데이트",
        summary = when {
            newer -> "새 버전 ${latest!!.versionName}"
            auto -> "✓ 자동 확인"
            else -> "수동"
        },
        summaryColor = when {
            newer -> Neon.amber
            auto -> Neon.green
            else -> Neon.muted
        },
    ) {
        ValueRow("현재 버전", "${s.currentName} (${s.currentCode})")
        latest?.let {
            ValueRow("GitHub 최신", "${it.versionName} (${it.versionCode}) · ${it.commit.take(7)}", if (newer) Neon.amber else null)
        }
        if (s.checkedAt > 0) {
            ValueRow("마지막 확인", SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(s.checkedAt)))
        }
        s.message?.let {
            Text(
                it,
                color = when (s.phase) {
                    UpdatePhase.FAILED -> Neon.red
                    UpdatePhase.UP_TO_DATE -> Neon.green
                    else -> Neon.amber
                },
                fontSize = 13.sp,
            )
        }
        Toggle("앱 실행 시 GitHub 최신 버전 자동 확인", auto) {
            auto = it
            vm.updater.auto = it
        }
        val busy = s.phase == UpdatePhase.CHECKING
        ButtonGrid(
            !busy,
            "지금 확인" to { vm.updater.checkNow(openDownloadIfNew = false) },
            (if (newer) "새 버전 다운로드" else "최신 APK 다운로드") to { AppUpdater.openDownload(context, latest) },
        )
        Hint("GitHub 릴리스(latest-apk)에서 최신 버전을 확인하고 브라우저로 내려받아 덮어쓰기 설치합니다.")
    }
}
