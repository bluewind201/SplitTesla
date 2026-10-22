package com.splittesla.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.splittesla.app.camera.NaviNotificationListener
import com.splittesla.app.ui.theme.Neon
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Speed camera and turn info read from Naver Map / TMAP / Kakao Navi running in the background. */
@Composable
fun NaviFeedSection() {
    val context = LocalContext.current
    val feed by NaviNotificationListener.feed.collectAsState()
    var allowed by remember { mutableStateOf(NaviNotificationListener.allowed(context)) }
    // Picks up the permission once the user comes back from the settings screen.
    LaunchedEffect(Unit) {
        while (!allowed) {
            delay(2000)
            allowed = NaviNotificationListener.allowed(context)
        }
    }

    Section("내비 앱 안내 (네이버·TMAP·카카오)") {
        when {
            !allowed -> {
                Text(
                    "네이버 지도나 TMAP이 뒤에서 길안내할 때 알림에 띄우는 과속카메라·회전 안내를 여기로 가져옵니다. " +
                        "아래 버튼을 눌러 '스플릿테슬라 내비 안내 읽기'를 허용해 주세요.",
                    color = Neon.muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                ButtonGrid(
                    true,
                    "알림 읽기 허용" to {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
            }
            feed == null -> Text(
                "내비 앱에서 길안내를 시작하면 여기에 표시됩니다.",
                color = Neon.muted, fontSize = 13.sp,
            )
            else -> {
                val f = feed!!
                val hint = f.hint
                if (hint?.cameraKind != null) {
                    val parts = listOfNotNull(
                        hint.cameraDistanceM?.let { if (it >= 1000) "%.1fkm 앞".format(it / 1000.0) else "${it}m 앞" },
                        hint.cameraKind,
                        hint.cameraLimitKmh?.let { "${it}km/h" },
                    )
                    Text(parts.joinToString(" · "), color = Neon.amber, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                (hint?.guidance ?: f.lines.joinToString(" · ")).let {
                    Text(it, color = Neon.text, fontSize = 14.sp, lineHeight = 19.sp)
                }
                Text(
                    "${f.app} · ${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(f.at))}",
                    color = Neon.muted, fontSize = 11.sp,
                )
            }
        }
    }
}
