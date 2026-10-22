package com.splittesla.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.assistant.LlmProvider
import com.splittesla.app.assistant.MicState
import com.splittesla.app.assistant.NaviApp
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.rememberPulse

/** Voice assistant: microphone and wake word on the left, AI model and places on the right. */
@Composable
fun AssistantScreen(vm: AppViewModel, modifier: Modifier) {
    val assistant = vm.assistant
    val s by assistant.state.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) assistant.startMic() else vm.log("마이크 권한이 거부되어 음성 명령을 쓸 수 없습니다")
    }

    var wake by rememberSaveable { mutableStateOf(s.wakeWord) }
    var typed by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(s.provider) }
    var key by rememberSaveable(provider) { mutableStateOf(assistant.apiKey(provider)) }
    var model by rememberSaveable(provider) { mutableStateOf(assistant.model(provider)) }
    var home by rememberSaveable { mutableStateOf(s.homeAddress) }
    var work by rememberSaveable { mutableStateOf(s.workAddress) }
    var its by rememberSaveable { mutableStateOf(s.itsKey) }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("음성 비서") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MicDot(s.mic)
                    Column(Modifier.weight(1f)) {
                        Text(s.mic.label, color = Neon.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            if (s.thinking) "AI가 생각하는 중…" else "\"${s.wakeWord}\" 하고 부른 뒤 말하세요",
                            color = Neon.muted, fontSize = 13.sp,
                        )
                    }
                    val on = s.mic != MicState.OFF && s.mic != MicState.NO_PERMISSION && s.mic != MicState.UNAVAILABLE
                    GlowButton(
                        if (on) "마이크 끄기" else "마이크 켜기",
                        { if (on) assistant.stopMic() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        filled = !on,
                    )
                }
                s.heard?.let { Line("들은 말", it) }
                s.said?.let { Line("비서", it, Neon.lime) }
                Toggle("앱을 켜면 마이크 바로 켜기", s.micOnStart, assistant::setMicOnStart)
                Toggle("상황별 자동 안내 (탑승·경로·사고·날씨·도착)", s.announcements, assistant::setAnnouncements)
                ButtonGrid(true, "탑승 멘트 듣기" to { assistant.testGreeting() }, "지금 상황 안내" to { assistant.testBriefing() })
            }

            Section("호출어") {
                Field(wake, { wake = it }, "호출어 (예: 헤이 테슬라, 자비스)")
                Hint("짧은 이름보다 두 단어 이상이 덜 헷갈립니다. 평소 대화에 잘 안 나오는 말로 정하세요.")
                ButtonGrid(wake.isNotBlank() && wake != s.wakeWord, "호출어 저장" to { assistant.saveVoice(wake, home, work, its) })
            }

            Section("말로 해보기 / 직접 입력") {
                Field(typed, { typed = it }, "예: 강남역으로 가자, 가는 길에 스타벅스 들러줘", onDone = {
                    if (typed.isNotBlank()) assistant.handleCommandText(typed)
                })
                ButtonGrid(typed.isNotBlank(), "실행" to { assistant.handleCommandText(typed) })
                Hint(
                    "할 수 있는 말: ○○으로 가자 · 가는 길에 ○○ 들러줘 · 가까운 충전소 · 집으로 가자 · 에어컨 켜/꺼 · 온도 22도 · 문 잠가 · " +
                        "언제 도착해 · 날씨 어때 · 조용히 해 / 안내 다시 켜. AI 키가 있으면 자유롭게 물어봐도 됩니다.",
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("AI 모델") {
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

            Section("길 안내 앱") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NaviApp.entries.forEach { a -> GlowButton(a.label, { assistant.setNaviApp(a) }, active = a == s.naviApp) }
                }
                Hint(
                    "차량 내비의 경로는 Fleet API 없이는 바꿀 수 없어서, 음성으로 목적지·경유지를 바꾸면 폰의 이 앱이 새 경로로 안내합니다. " +
                        "경유지는 네이버 지도만 지원하고, 카카오맵은 경유지로 먼저 안내합니다.",
                )
            }

            Section("자주 가는 곳") {
                Field(home, { home = it }, "집 주소")
                Field(work, { work = it }, "회사 주소")
            }

            Section("사고·공사 정보") {
                Field(its, { its = it.trim() }, "국가교통정보센터 인증키", secret = true)
                Hint("its.go.kr 오픈API에서 '돌발상황정보' 키를 무료로 받으면 경로 앞의 사고·공사를 알려줍니다. 없으면 차량 내비의 정체 시간만 씁니다.")
                ButtonGrid(true, "저장" to { assistant.saveVoice(wake.ifBlank { s.wakeWord }, home, work, its) })
                if (s.events.isNotEmpty()) {
                    s.events.take(5).forEach { e -> Line(e.kind.ifBlank { "돌발" }, listOf(e.road, e.lanes).filter { it.isNotBlank() }.joinToString(" · ")) }
                }
            }
        }
    }
}

@Composable
private fun MicDot(mic: MicState) {
    val color = mic.color()
    val alpha = if (mic == MicState.LISTENING || mic == MicState.COMMAND) rememberPulse() else 1f
    Box(Modifier.size(18.dp).background(color.copy(alpha = alpha), CircleShape))
}

@Composable
private fun Line(label: String, text: String, color: Color = Neon.text) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Neon.muted, fontSize = 13.sp)
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Neon.muted, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, onDone: (() -> Unit)? = null) {
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
            unfocusedBorderColor = Neon.stroke,
            cursorColor = Neon.lime,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
