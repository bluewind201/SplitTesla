package com.splittesla.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speed camera guidance: on/off, voice, the camera list (built-in seed,
 * the data.go.kr CSV, or its open API), and the location permission the
 * phone GPS needs.
 */
@Composable
fun CameraSettingsCard(vm: AppViewModel, ui: UiState) {
    val context = LocalContext.current
    var located by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var key by rememberSaveable { mutableStateOf(ui.cameraApiKey) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        located = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importCameras) }
    val updated = ui.cameraUpdatedAt.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(it)) }

    FoldSection(
        "과속 카메라 안내",
        summary = when {
            !ui.cameraEnabled -> "꺼짐"
            ui.cameraSeed -> "✓ 켜짐 · 기본 ${ui.cameraCount}개"
            else -> "✓ 켜짐 · ${"%,d".format(ui.cameraCount)}개"
        },
        summaryColor = if (ui.cameraEnabled) Neon.green else Neon.muted,
    ) {
        Toggle("과속 카메라 안내 (계기판·내비 위 계기판에 표시)", ui.cameraEnabled, vm::setCameraEnabled)
        Toggle("음성으로 알리기", ui.cameraVoice, vm::setCameraVoice)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (located) "✓ 위치 권한 허용됨 (휴대폰 GPS 사용)" else "위치 권한이 없으면 차량 BLE 위치만 씁니다",
                color = if (located) Neon.green else Neon.amber,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (!located) {
                GlowButton("권한 허용", {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                })
            }
        }
        ValueRow(
            "카메라 데이터",
            if (ui.cameraSeed) "내장 기본 목록 ${ui.cameraCount}개" else "${"%,d".format(ui.cameraCount)}개 ($updated)",
        )
        Hint(
            "속도에 따라 200~600m 전에 한 번, 100m 전에 한 번 더 알립니다. 위치는 휴대폰 GPS → 차량 BLE → S3XY 순으로 씁니다. " +
                "전국 데이터는 공공데이터포털(data.go.kr) '전국무인교통단속카메라표준데이터' CSV를 내려받아 가져오거나, 오픈API 인증키로 바로 받으세요.",
        )
        ButtonGrid(
            ui.busy == null,
            "CSV 파일 가져오기" to { csvPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
            "기본 목록으로" to { vm.clearCameras() },
        )
        Field(key, { key = it.trim() }, "공공데이터 인증키", secret = true)
        ButtonGrid(ui.busy == null && key.isNotBlank(), "API로 받기" to { vm.downloadCameras(key) })
    }
}
