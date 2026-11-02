package com.splittesla.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.assistant.NaviApp
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon

/** App settings. Each card folds to its title, with its saved state shown beside it. */
@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val log by vm.log.collectAsState()
    val assistant by vm.assistant.state.collectAsState()
    val lastShared by vm.routeSync.last.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var vin by rememberSaveable { mutableStateOf(ui.vin) }
    var its by rememberSaveable { mutableStateOf(assistant.itsKey) }
    var overlay by remember { mutableStateOf(vm.routeSync.canOpenFromBackground()) }
    // Coming back from the system screen that grants it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { overlay = vm.routeSync.canOpenFromBackground() }
    // Commands connect on demand, so only a missing VIN or a running command blocks them.
    val carReady = ui.vin.length == 17 && ui.busy == null

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoldSection(
                "차량 VIN",
                summary = if (ui.vin.length == 17) "✓ 저장됨" else "입력 필요",
                summaryColor = if (ui.vin.length == 17) Neon.green else Neon.amber,
            ) {
                val valid = vin.length == 17
                OutlinedTextField(
                    value = vin,
                    // VINs use digits and capital letters except I, O and Q.
                    onValueChange = { input -> vin = input.uppercase().filter { it in VIN_CHARS }.take(17) },
                    label = { Text("VIN (17자리)") },
                    supportingText = {
                        Text(
                            when {
                                vin == ui.vin && valid -> "저장됨"
                                else -> "${vin.length}/17"
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (valid) vm.setVin(vin) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                ButtonGrid(valid, "저장" to { vm.setVin(vin) })
                Text(
                    "차량 화면 '컨트롤 > 소프트웨어' 또는 앞유리 왼쪽 아래에서 확인하세요. 저장하면 차량 BLE는 이 VIN으로 차를 찾습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            FoldSection(
                "테슬라 키 등록",
                summary = when {
                    ui.keyPaired -> "✓ 등록됨"
                    ui.keyPairing -> "승인 기다리는 중…"
                    else -> "미등록"
                },
                summaryColor = when {
                    ui.keyPaired -> Neon.green
                    ui.keyPairing -> Neon.amber
                    else -> Neon.muted
                },
                startOpen = !ui.keyPaired,
            ) {
                if (ui.keyPaired) {
                    Text("✅ 이 폰의 키가 차량에 등록되어 있습니다.", color = Neon.green, fontWeight = FontWeight.Bold)
                } else if (ui.keyPairing) {
                    Text("키 카드를 센터 콘솔에 대고 차량 화면에서 승인하세요. 승인되면 여기에 바로 표시됩니다.", color = Neon.amber)
                }
                Text(
                    "1) VIN을 저장하고 차 안에 탑니다.\n" +
                        "2) '키 등록 요청'을 누르면 차량에 자동으로 연결한 뒤 요청을 보냅니다.\n" +
                        "3) 센터 콘솔 리더에 키 카드를 대고, 차량 화면에서 승인합니다.\n" +
                        "4) 승인되면 '테슬라 키 등록 완료' 안내가 뜹니다. 안 뜨면 '등록 확인'을 누르세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.vin.length != 17) {
                    Text("VIN을 먼저 저장해야 버튼이 켜집니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                ButtonGrid(
                    carReady,
                    "키 등록 요청" to vm::sendAddKeyRequest,
                    "등록 확인" to vm::verifyKey,
                    "핑 테스트" to { vm.carCommand("핑") { it.ping() } },
                )
                Text("이 폰의 공개키", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(vm.teslaPublicKeyHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            FoldSection(
                "S3XY Commander 페어링",
                summary = if (ui.commander == LinkState.CONNECTED) "✓ 등록·연결됨" else ui.commander.label,
                summaryColor = if (ui.commander == LinkState.CONNECTED) Neon.green else Neon.muted,
            ) {
                Text(
                    "처음 연결하면 '페어링 필요'가 뜹니다. 그때 60초 안에 핸들 오른쪽 스크롤 버튼을 두 번 누르세요. " +
                        "등록되면 'S3XY Commander에 이 폰을 등록했습니다' 안내가 뜹니다. " +
                        "S3XY 공식 앱이 Commander에 연결돼 있으면 이 앱이 연결되지 않을 수 있으니 공식 앱을 종료하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("실시간 원본 패킷 로그 (분석용)")
                    Switch(checked = ui.logRawPushes, onCheckedChange = vm::setLogRawPushes)
                }
                ValueRow("수신 패킷 수", ui.pushCount.toString())
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoldSection(
                "테슬라 목적지 → 폰 내비 자동 공유",
                summary = if (assistant.autoShareRoute) "${assistant.naviApp.label} 자동" else "꺼짐",
                summaryColor = if (assistant.autoShareRoute) Neon.green else Neon.muted,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NaviApp.entries.forEach { a -> GlowButton(a.label, { vm.assistant.setNaviApp(a) }, active = a == assistant.naviApp) }
                }
                Toggle("차에서 목적지가 바뀌면 자동으로 보내기", assistant.autoShareRoute, vm.assistant::setAutoShareRoute)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (overlay) "✓ 앱이 뒤에 있어도 내비를 바로 엽니다" else "앱이 뒤에 있으면 알림을 한 번 눌러야 합니다",
                        color = if (overlay) Neon.green else Neon.amber,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (!overlay) GlowButton("다른 앱 위에 표시 허용", { context.startActivity(vm.routeSync.overlaySettingsIntent()) })
                }
                lastShared?.let { Text("최근 전송: $it", fontSize = 13.sp, color = Neon.cyan) }
                Hint(
                    "차 화면에서 목적지를 정하거나 바꾸면 10초 안에 고른 앱이 그 목적지로 안내를 시작합니다(차량 BLE 연결 필요). " +
                        "폰 내비는 자기 경로를 다시 계산하므로 테슬라와 경로가 다를 수 있고, 테슬라의 경유지는 전달되지 않습니다. " +
                        "카카오맵은 경로 화면까지 열리고 '안내 시작'을 한 번 눌러야 합니다. 반대로 폰 내비의 경로를 차로 보내는 것은 원격 API 전용이라 안 됩니다.",
                )
            }

            VoiceSettingsCard()

            FoldSection("계기판", summary = "${ui.cockpitLayout.letter} ${ui.cockpitLayout.title} · ${ui.gaugeStyle.title}") {
                Text("배치", style = Caption)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CockpitLayout.entries.forEach { l ->
                        GlowButton("${l.letter} ${l.title}", { vm.setCockpitLayout(l) }, active = l == ui.cockpitLayout)
                    }
                }
                Text("게이지", style = Caption)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeStyle.entries.forEach { g -> GlowButton(g.title, { vm.setGaugeStyle(g) }, active = g == ui.gaugeStyle) }
                    GlowButton("DEMO", { vm.setDemo(!ui.demo) }, active = ui.demo, accent = Neon.cyan)
                }
                Hint(
                    "A 균형형: 게이지 | 안내·카메라·도착 | 세로 지도. B 센터 게이지: 안내·카메라 | 게이지 | 세로 지도. " +
                        "C 몰입형: 탭 메뉴를 숨기고 게이지를 가장 크게, 메뉴는 왼쪽 위 ≡ 버튼. " +
                        "계기판에서 게이지를 누르면 모양이, 길게 누르면 배치가 바뀝니다. " +
                        "지도는 길안내 API의 NCP 키를 쓰며, NCP 콘솔의 같은 애플리케이션에서 Dynamic Map을 켜고 Android 패키지 com.splittesla.app을 등록해야 나옵니다.",
                )
            }

            NaviSettingsCard()

            FoldSection(
                "교통정보 API (사고·공사)",
                summary = when {
                    assistant.itsTest?.startsWith("✅") == true -> "✓ 연결됨"
                    assistant.itsKey.isNotBlank() -> "✓ 키 등록됨"
                    else -> "키 없음"
                },
                summaryColor = if (assistant.itsKey.isNotBlank()) Neon.green else Neon.muted,
            ) {
                Field(its, { its = it.trim() }, "국가교통정보센터 인증키", secret = true)
                ButtonGrid(
                    true,
                    "저장" to { vm.assistant.saveItsKey(its) },
                    "연결 테스트" to { vm.assistant.testItsKey(its) },
                )
                assistant.itsTest?.let {
                    Text(it, color = if (it.startsWith("✅")) Neon.green else if (it.startsWith("❌")) Neon.red else Neon.muted, fontSize = 13.sp)
                }
                Hint(
                    "its.go.kr 오픈API에서 '돌발상황정보' 키를 무료로 받아 넣으세요. 경로 앞의 사고·공사를 음성으로 알려줍니다. " +
                        "연결 테스트는 지금 위치(모르면 서울시청) 주변의 돌발상황을 한 번 받아 봅니다.",
                )
                if (assistant.events.isNotEmpty()) {
                    assistant.events.take(5).forEach { e ->
                        ValueRow(e.kind.ifBlank { "돌발" }, listOf(e.road, e.lanes).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
            }

            FoldSection("로그 (${log.size})") {
                ButtonGrid(
                    log.isNotEmpty(),
                    "복사" to { clipboard.setText(AnnotatedString(log.joinToString("\n"))) },
                    "공유" to {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "SplitTesla 로그")
                            putExtra(Intent.EXTRA_TEXT, log.joinToString("\n"))
                        }
                        context.startActivity(Intent.createChooser(send, "로그 공유"))
                    },
                )
                SelectionContainer {
                    Text(
                        log.takeLast(300).asReversed().joinToString("\n").ifEmpty { "(아직 없음)" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private val VIN_CHARS = ('0'..'9').toSet() + ('A'..'Z').toSet() - setOf('I', 'O', 'Q')
