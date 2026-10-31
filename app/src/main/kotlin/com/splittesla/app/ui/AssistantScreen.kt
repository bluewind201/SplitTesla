package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.assistant.CompanionNavi
import com.splittesla.app.assistant.LlmProvider
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Glass

/**
 * The assistant: spoken driving notes and typed commands on the left, the AI
 * model and saved places on the right. Every card folds to its title.
 */
@Composable
fun AssistantScreen(vm: AppViewModel, modifier: Modifier) {
    val assistant = vm.assistant
    val s by assistant.state.collectAsState()

    var typed by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(s.provider) }
    var key by rememberSaveable(provider) { mutableStateOf(assistant.apiKey(provider)) }
    var model by rememberSaveable(provider) { mutableStateOf(assistant.model(provider)) }
    var home by rememberSaveable { mutableStateOf(s.homeAddress) }
    var work by rememberSaveable { mutableStateOf(s.workAddress) }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoldSection("자동 음성 안내", summary = if (s.announcements) "켜짐" else "꺼짐", startOpen = true) {
                Toggle("상황별 자동 안내 (탑승·경로·사고·날씨·도착)", s.announcements, assistant::setAnnouncements)
                if (s.watching.isNotBlank()) Line("지금", s.watching)
                s.lastAnnounce?.let { Line("최근 안내", it) }
                if (s.thinking) Hint("AI가 문장을 만드는 중…")
                s.said?.let { Line("비서", it, Neon.lime) }
                Hint(
                    "자동으로 말하는 때: 앱 시작(탑승), 차에 새 경로, 경로 앞 사고·공사, 정체가 5분 이상 늘거나 풀릴 때, " +
                        "긴 주행 중 15분마다 브리핑, 비·눈 시작, 도착 직전. 차량 경로가 없어도 폰 GPS로 진행 방향 8km 안의 사고·공사를 알려줍니다.",
                )
                ButtonGrid(
                    true,
                    "탑승 멘트 듣기" to { assistant.testGreeting() },
                    "지금 상황 안내" to { assistant.testBriefing() },
                )
            }

            FoldSection("직접 입력") {
                Field(typed, { typed = it }, "예: 강남역으로 가자, 가는 길에 스타벅스 들러줘", onDone = {
                    if (typed.isNotBlank()) assistant.handleCommandText(typed)
                })
                ButtonGrid(typed.isNotBlank(), "실행" to { assistant.handleCommandText(typed) })
                s.heard?.let { Line("입력", it) }
                Hint(
                    "할 수 있는 말: ○○으로 가자 · 가는 길에 ○○ 들러줘 · 가까운 충전소 · 집으로 가자 · 에어컨 켜/꺼 · 온도 22도 · 문 잠가 · " +
                        "언제 도착해 · 날씨 어때 · 조용히 해 / 안내 다시 켜. AI 키가 있으면 자유롭게 물어봐도 됩니다.",
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FoldSection(
                "AI 모델",
                summary = if (s.hasKey) "✓ ${s.provider.label} 등록됨" else "키 없음",
                summaryColor = if (s.hasKey) Neon.green else Neon.muted,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LlmProvider.entries.forEach { p ->
                        GlowButton(p.label, { provider = p }, active = p == provider)
                    }
                }
                Field(key, { key = it.trim() }, "${provider.label} API 키", secret = true)
                Hint(provider.keyHint)
                Field(model, { model = it.trim() }, "모델 이름 (기본 ${provider.defaultModel})")
                ButtonGrid(true, "저장" to { assistant.saveAi(provider, key, model) })
                Hint(
                    when {
                        s.hasKey -> "사용 중: ${s.provider.label} · ${s.model}. 키는 이 폰에만 저장됩니다."
                        else -> "키가 없으면 정해진 문장으로 안내하고, 간단한 명령만 알아듣습니다."
                    },
                )
            }

            FoldSection(
                "자주 가는 곳",
                summary = listOfNotNull(s.homeAddress.takeIf { it.isNotBlank() }?.let { "집" }, s.workAddress.takeIf { it.isNotBlank() }?.let { "회사" })
                    .joinToString("·").ifEmpty { null }?.let { "✓ $it 저장됨" },
                summaryColor = Neon.green,
            ) {
                Field(home, { home = it }, "집 주소")
                Field(work, { work = it }, "회사 주소")
                ButtonGrid(true, "저장" to { assistant.savePlaces(home, work) })
            }

            FoldSection("앱 켤 때 같이 띄울 내비", summary = s.companionNavi.label) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompanionNavi.entries.forEach { a -> GlowButton(a.label, { assistant.setCompanionNavi(a) }, active = a == s.companionNavi) }
                }
                Hint(
                    "앱을 켜면 고른 내비를 화면 분할로 옆에 엽니다. 비율은 가운데 막대를 끌어 맞추면 다음부터 그대로 열리는 폰이 많아요. " +
                        "분할을 못 하는 폰에서는 내비가 전체 화면으로 열립니다. " +
                        "분할 화면에서는 탭 없이 계기판만 크게 보이고, 카메라·경로·교통·차량 상태가 한 화면에 모입니다. 오른쪽 위 '메뉴'로 탭 화면에 갈 수 있어요.",
                )
            }

            Hint("길 안내 앱 선택, 테슬라 목적지 자동 공유, 교통정보 API 키는 설정 탭에 있습니다.")
        }
    }
}

@Composable
private fun Line(label: String, text: String, color: Color = Neon.text) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Neon.muted, fontSize = 13.sp)
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = Neon.muted, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Neon.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Neon.lime, checkedThumbColor = Color.Black),
        )
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, onDone: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Go else ImeAction.Done),
        keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Neon.text,
            unfocusedTextColor = Neon.text,
            focusedLabelColor = Neon.lime,
            unfocusedLabelColor = Neon.muted,
            focusedBorderColor = Neon.lime,
            unfocusedBorderColor = Glass.edgeLit,
            focusedContainerColor = Glass.fillTop,
            unfocusedContainerColor = Glass.fillBottom,
            cursorColor = Neon.lime,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
