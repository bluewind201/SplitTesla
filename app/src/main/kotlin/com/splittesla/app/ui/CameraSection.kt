package com.splittesla.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.ui.theme.Neon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Speed camera alerts: on/off, the camera list, and the next camera ahead. */
@Composable
fun CameraSection(vm: AppViewModel, ui: UiState) {
    val running by CameraState.running.collectAsState()
    val status by CameraState.status.collectAsState()
    val lastAlert by CameraState.lastAlert.collectAsState()
    val gpsOk by CameraState.gpsOk.collectAsState()
    var showData by rememberSaveable { mutableStateOf(ui.cameraCount == 0) }
    var key by rememberSaveable { mutableStateOf(ui.cameraApiKey) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.setCameraAlerts(true)
        } else {
            vm.log("위치 권한이 없어 과속카메라 알림을 켤 수 없습니다")
        }
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importCameras)
    }

    FoldSection(
        "과속카메라 알림 (공공데이터)",
        summary = (if (running) "켜짐" else "꺼짐") + if (ui.cameraCount > 0) " · ${ui.cameraCount}개" else " · 데이터 없음",
        summaryColor = if (running) Neon.green else Neon.muted,
        startOpen = running || ui.cameraCount == 0,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(if (running) "켜짐" else "꺼짐", style = MaterialTheme.typography.titleMedium)
                Text(
                    "속도에 따라 200~600m 전에 한 번, 100m 전에 한 번 더 음성과 진동으로 알립니다. 화면이 꺼져도 동작합니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = running,
                onCheckedChange = { on ->
                    if (!on) {
                        vm.setCameraAlerts(false)
                    } else {
                        val wanted = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissions.launch(wanted.toTypedArray())
                    }
                },
            )
        }

        if (running) {
            if (!gpsOk) Text("GPS 신호를 기다리는 중…", style = MaterialTheme.typography.bodySmall)
            val ahead = status.ahead
            val distance = status.distanceM
            if (ahead != null && distance != null) {
                val speed = ui.live.speedKmh
                Text(
                    "${distance.roundToInt()}m 앞 · 제한 ${ahead.limitKmh}km/h",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (speed != null && speed > ahead.limitKmh) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                ahead.zone?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
                if (ahead.place.isNotBlank()) Text(ahead.place, style = MaterialTheme.typography.bodySmall)
                status.headingDeg?.let { heading ->
                    TextButton(onClick = { vm.ignoreCamera(ahead.id, heading) }) { Text("반대 방향 카메라예요 (이 방향에선 끄기)") }
                }
            } else {
                Text("앞쪽에 카메라 없음", style = MaterialTheme.typography.bodyMedium)
            }
            status.sectionAverageKmh?.let { avg ->
                val limit = status.sectionLimitKmh
                ValueRow(
                    "구간단속 평균",
                    "${avg.roundToInt()} / 제한 $limit km/h",
                    highlight = if (limit != null && avg > limit) MaterialTheme.colorScheme.error else null,
                )
                status.sectionRemainingM?.let { ValueRow("구간 남은 거리", "%.1f km".format(it / 1000)) }
                status.sectionSafeKmh?.let { ValueRow("남은 구간 권장 속도", "${it.roundToInt()} km/h 이하") }
            }
        }
        lastAlert?.let { Text("최근 알림: $it", style = MaterialTheme.typography.bodySmall) }

        val updated = ui.cameraUpdatedAt.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(it)) }
        ValueRow("카메라 데이터", if (ui.cameraCount > 0) "${ui.cameraCount}개 ($updated)" else "없음")
        val ignored = vm.ignoredCameraCount
        if (ignored > 0) TextButton(onClick = vm::clearIgnoredCameras) { Text("방향 제외한 카메라 ${ignored}개 되살리기") }
        TextButton(onClick = { showData = !showData }) { Text(if (showData) "데이터 받기 접기" else "카메라 데이터 받기·갱신") }
        if (showData) {
            Text(
                "방법 1: 공공데이터포털(data.go.kr)에서 '전국무인교통단속카메라표준데이터'를 검색해 CSV를 내려받고, 아래 버튼으로 그 파일을 고르세요.",
                style = MaterialTheme.typography.bodySmall,
            )
            ButtonGrid(ui.busy == null, "CSV 파일 가져오기" to { csvPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) })
            Text(
                "방법 2: 같은 데이터의 오픈API 활용신청을 하고, 받은 일반 인증키를 넣으면 앱이 직접 받아옵니다. 다음 갱신 때도 이 키를 씁니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("공공데이터 인증키") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ButtonGrid(
                ui.busy == null && key.isNotBlank(),
                "연결 테스트" to { vm.testCameraKey(key) },
                "API로 받기" to { vm.downloadCameras(key) },
            )
            ui.cameraTest?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("✅")) Neon.green else if (it.startsWith("❌")) Neon.red else Neon.muted,
                )
            }
        }
    }
}
