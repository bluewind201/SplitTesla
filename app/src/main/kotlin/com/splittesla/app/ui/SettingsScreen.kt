package com.splittesla.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState

@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val log by vm.log.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var vin by rememberSaveable { mutableStateOf(ui.vin) }
    // Commands connect on demand, so only a missing VIN or a running command blocks them.
    val carReady = ui.vin.length == 17 && ui.busy == null

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("차량 VIN") {
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

            Section("테슬라 키 등록 (처음 한 번)") {
                Text(
                    "1) VIN을 저장하고 차 안에 탑니다.\n" +
                        "2) '키 등록 요청'을 누르면 차량에 자동으로 연결한 뒤 요청을 보냅니다.\n" +
                        "3) 센터 콘솔 리더에 키 카드를 대고, 차량 화면에서 승인합니다.\n" +
                        "4) '핑 테스트'가 성공하면 등록 완료입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.vin.length != 17) {
                    Text("VIN을 먼저 저장해야 버튼이 켜집니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                ButtonGrid(
                    carReady,
                    "키 등록 요청" to vm::sendAddKeyRequest,
                    "핑 테스트" to { vm.carCommand("핑") { it.ping() } },
                )
                Text("이 폰의 공개키", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(vm.teslaPublicKeyHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("S3XY Commander 페어링") {
                Text(
                    "처음 연결하면 '페어링 필요'가 뜹니다. 그때 60초 안에 핸들 오른쪽 스크롤 버튼을 두 번 누르세요. " +
                        "S3XY 공식 앱이 Commander에 연결돼 있으면 이 앱이 연결되지 않을 수 있으니 공식 앱을 종료하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("실시간 원본 패킷 로그 (분석용)")
                    Switch(checked = ui.logRawPushes, onCheckedChange = vm::setLogRawPushes)
                }
                ValueRow("수신 패킷 수", ui.pushCount.toString())
            }

            Section("로그 (${log.size})") {
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
