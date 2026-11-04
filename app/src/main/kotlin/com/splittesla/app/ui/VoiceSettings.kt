package com.splittesla.app.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.assistant.VoiceChoice
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon

private const val SAMPLE = "300미터 앞에서 오른쪽 출구로 나가세요."

private val rates = listOf("느리게" to 0.85f, "보통" to 1f, "빠르게" to 1.15f, "아주 빠르게" to 1.3f)

/**
 * The voice for every spoken prompt (turn-by-turn, speed cameras and the
 * assistant): the Korean voices the phone's speech engine offers, each with a
 * sample, and how fast to speak. A choice is heard at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSettingsCard() {
    val context = LocalContext.current
    remember { VoiceChoice.load(context) }
    val setting by VoiceChoice.setting.collectAsState()
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    // Discovery engine only; all audible previews use the shared speech lease.
    val sampleSpeaker = remember { com.splittesla.app.assistant.Speaker(context) }
    val sampler = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (status == TextToSpeech.SUCCESS) {
                voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                    .filter { it.locale.language == "ko" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            }
        }
        sampler[0] = engine
        onDispose {
            sampleSpeaker.shutdown()
            engine.shutdown()
            sampler[0] = null
        }
    }

    fun hear(voiceName: String?, rate: Float) {
        sampleSpeaker.speak(SAMPLE, key = "preview:$voiceName:$rate", ttlMs = 3000, preview = setting.copy(voiceName = voiceName, rate = rate))
    }

    fun choose(voiceName: String?) {
        VoiceChoice.set(context, setting.copy(voiceName = voiceName))
        hear(voiceName, setting.rate)
    }

    val index = voices.indexOfFirst { it.name == setting.voiceName }
    val rateName = rates.minByOrNull { kotlin.math.abs(it.second - setting.rate) }?.first ?: "보통"
    FoldSection("음성", summary = (if (index >= 0) "음성 ${index + 1}" else "기본 음성") + " · $rateName") {
        Toggle("전체 음성 끄기", setting.muted) { VoiceChoice.set(context, setting.copy(muted = it)) }
        Text("길안내 · 과속카메라 · AI 비서가 모두 이 음성으로 말합니다", fontSize = 12.sp, color = Neon.muted)
        VoiceRow("기본 음성", "휴대폰 음성 엔진의 기본 한국어", setting.voiceName == null, { choose(null) }) { hear(null, setting.rate) }
        voices.forEachIndexed { i, v ->
            VoiceRow(
                "음성 ${i + 1}",
                v.name + if (v.isNetworkConnectionRequired) " · 인터넷 필요" else " · 기기 내장",
                setting.voiceName == v.name,
                { choose(v.name) },
            ) { hear(v.name, setting.rate) }
        }
        when {
            ready == false -> Hint("음성 엔진을 시작하지 못했습니다. 휴대폰 설정 > 텍스트 음성 변환을 확인하세요.")
            ready == true && voices.size < 2 -> Hint(
                "이 음성 엔진은 한국어 음성을 하나만 알려 줍니다. 휴대폰 설정 > 일반 > 텍스트 음성 변환에서 " +
                    "Google 음성 엔진이나 삼성 TTS로 바꾸고 한국어 음성 데이터를 받으면 여러 음성을 고를 수 있습니다.",
            )
        }
        Text("말 빠르기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Neon.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rates.forEach { (name, rate) ->
                GlowButton(name, {
                    VoiceChoice.set(context, setting.copy(rate = rate))
                    hear(setting.voiceName, rate)
                }, active = name == rateName)
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, onHear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(20.dp).border(2.dp, if (selected) Neon.lime else Neon.muted, CircleShape).padding(4.dp),
        ) {
            if (selected) Box(Modifier.size(12.dp).background(Neon.lime, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Neon.lime else Neon.text)
            Text(detail, fontSize = 11.sp, color = Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlowButton("들어보기", onHear)
    }
}
