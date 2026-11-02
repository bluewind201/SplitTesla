package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.core.navi.RouteOption

/** Settings card for in-app guidance: the NAVER and charger API keys, and how guidance behaves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaviSettingsCard() {
    val prefs = NaviHub.settings
    val navi by NaviHub.state.collectAsState()
    var ncpId by rememberSaveable { mutableStateOf(prefs.ncpKeyId) }
    var ncpKey by rememberSaveable { mutableStateOf(prefs.ncpKey) }
    var searchId by rememberSaveable { mutableStateOf(prefs.searchId) }
    var searchSecret by rememberSaveable { mutableStateOf(prefs.searchSecret) }
    var evKey by rememberSaveable { mutableStateOf(prefs.evKey) }
    var saved by remember { mutableStateOf(prefs.hasMaps) }
    var option by remember { mutableStateOf(prefs.option) }
    var reroute by remember { mutableStateOf(prefs.autoReroute) }
    var faster by remember { mutableStateOf(prefs.fasterRoute) }
    var voice by remember { mutableStateOf(prefs.voice) }
    var inApp by remember { mutableStateOf(prefs.voiceInApp) }
    var reserve by remember { mutableIntStateOf(prefs.reserveSoc) }

    fun save() {
        prefs.ncpKeyId = ncpId
        prefs.ncpKey = ncpKey
        prefs.searchId = searchId
        prefs.searchSecret = searchSecret
        prefs.evKey = evKey
        saved = prefs.hasMaps
    }

    FoldSection(
        "길안내 API (네이버 · 충전소)",
        summary = when {
            navi.keyTest.isNotEmpty() && navi.keyTest.none { it.startsWith("❌") || it.startsWith("확인") } -> "✓ 연결됨"
            saved -> "✓ 키 등록됨"
            else -> "키 없음"
        },
        summaryColor = if (saved) Neon.green else Neon.muted,
    ) {
        Text("NAVER Cloud Platform · Maps", color = Neon.cyan, fontSize = 13.sp)
        Field(ncpId, { ncpId = it.trim() }, "Client ID (x-ncp-apigw-api-key-id)")
        Field(ncpKey, { ncpKey = it.trim() }, "Client Secret (x-ncp-apigw-api-key)", secret = true)
        Text("NAVER API HUB · 지역 검색 (상호명)", color = Neon.cyan, fontSize = 13.sp)
        Field(searchId, { searchId = it.trim() }, "Client ID · 선택")
        Field(searchSecret, { searchSecret = it.trim() }, "Client Secret · 선택", secret = true)
        Text("공공데이터포털 · 전기자동차 충전소 정보", color = Neon.cyan, fontSize = 13.sp)
        Field(evKey, { evKey = it.trim() }, "일반 인증키 · 비우면 과속카메라 키 사용", secret = true)
        ButtonGrid(
            true,
            "저장" to { save() },
            "저장하고 연결 테스트" to {
                save()
                NaviHub.testKeys()
            },
        )
        navi.keyTest.forEach {
            Text(it, color = if (it.startsWith("✅")) Neon.green else if (it.startsWith("❌")) Neon.red else Neon.muted, fontSize = 13.sp)
        }

        Text("기본 경로", color = Neon.cyan, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RouteOption.FAST, RouteOption.OPTIMAL, RouteOption.COMFORT, RouteOption.AVOID_TOLL).forEach { o ->
                GlowButton(o.label, {
                    prefs.option = o
                    option = o
                }, active = o == option)
            }
        }
        Toggle("경로를 벗어나면 자동 재탐색", reroute) { prefs.autoReroute = it; reroute = it }
        Toggle("5분마다 교통 확인, 3분 이상 빠른 길로 변경", faster) { prefs.fasterRoute = it; faster = it }
        Toggle("음성 안내", voice) { prefs.voice = it; voice = it }
        Toggle("음성 명령 '○○ 가자'를 앱 안 길안내로", inApp) { prefs.voiceInApp = it; inApp = it }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("도착 배터리 $reserve% 미만이면 충전소 제안", color = Neon.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            GlowButton("−5", { prefs.reserveSoc = reserve - 5; reserve = prefs.reserveSoc })
            GlowButton("+5", { prefs.reserveSoc = reserve + 5; reserve = prefs.reserveSoc })
        }
        Hint(
            "① NAVER Cloud Platform 콘솔 > Maps > Application 등록에서 Directions 5 · Geocoding · Reverse Geocoding · Dynamic Map을 체크하고 " +
                "Android 패키지 com.splittesla.app을 등록한 뒤 인증 정보의 Client ID / Client Secret을 넣으세요(경로·주소 검색·지도). " +
                "② 상호명 검색(예: 이마트)은 같은 NCP 콘솔 > Services > NAVER API HUB에서 이용 신청 후 Application을 등록하고 " +
                "그 Client ID / Client Secret을 넣으면 됩니다(하루 25,000회). 예전 NAVER Developers 검색 키도 그대로 씁니다. " +
                "없으면 주소와 휴대폰 검색으로만 찾습니다. " +
                "③ 공공 급속충전기는 공공데이터포털 '한국환경공단_전기자동차 충전소 정보' 활용신청 키(과속카메라와 같은 계정이면 비워 둠). " +
                "슈퍼차저는 차량 BLE로 받아 키 없이 제안합니다.",
        )
    }
}
