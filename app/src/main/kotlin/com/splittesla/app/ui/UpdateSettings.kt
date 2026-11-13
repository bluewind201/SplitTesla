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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.splittesla.app.AppViewModel
import com.splittesla.app.update.AppUpdater
import com.splittesla.app.update.UpdatePhase
import com.splittesla.app.ui.theme.Neon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings card for the GitHub self-update. */
@Composable
fun UpdateSettingsCard(vm: AppViewModel) {
    val context = LocalContext.current
    val s by AppUpdater.state.collectAsState()
    var auto by remember { mutableStateOf(vm.updater.auto) }
    var canInstall by remember { mutableStateOf(AppUpdater.canInstall(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = AppUpdater.canInstall(context) }
    val latest = s.latest
    val newer = latest != null && latest.versionCode > s.currentCode

    FoldSection(
        "자동 업데이트",
        summary = when {
            s.phase == UpdatePhase.DOWNLOADING -> "다운로드 ${s.progress}%"
            newer -> "새 버전 ${latest!!.versionName}"
            !canInstall -> "권한 필요"
            auto -> "✓ 켜짐"
            else -> "꺼짐"
        },
        summaryColor = when {
            newer || !canInstall -> Neon.amber
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
        Toggle("GitHub에 새 버전이 올라오면 자동으로 받아 설치", auto) {
            auto = it
            vm.updater.auto = it
        }
        val busy = s.phase == UpdatePhase.CHECKING || s.phase == UpdatePhase.DOWNLOADING || s.phase == UpdatePhase.INSTALLING
        ButtonGrid(
            !busy,
            "지금 확인" to { vm.updater.checkNow(install = false) },
            (if (newer) "지금 업데이트" else "확인 후 설치") to { vm.updater.checkNow(install = true) },
        )
        if (!canInstall) {
            ButtonGrid(true, "앱 설치 허용" to { context.startActivity(AppUpdater.installPermissionIntent(context)) })
        }
        Hint(
            "앱을 켤 때와 6시간마다 GitHub 릴리스(latest-apk)를 확인합니다. 새 버전은 미리 받아 두고, " +
                "정차 중 이 앱이 화면에 있을 때 설치합니다(설치하면 앱이 잠깐 닫혔다 다시 열립니다). " +
                "서명 키와 해시가 맞는 APK만 설치하며, 처음 한 번은 Android 확인 화면에서 '업데이트'를 눌러야 하고 그 뒤로는 묻지 않습니다(Android 12 이상).",
        )
    }
}
