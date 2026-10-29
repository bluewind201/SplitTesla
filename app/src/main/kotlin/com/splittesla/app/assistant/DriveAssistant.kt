package com.splittesla.app.assistant

import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.ui.Cockpit
import com.splittesla.app.weather.Weather
import com.splittesla.app.weather.WeatherRepository
import com.splittesla.core.assistant.Geo
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.assistant.VoiceAction
import com.splittesla.core.assistant.VoiceRules
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.tesla.NavigationInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class AssistantUi(
    val provider: LlmProvider = LlmProvider.CLAUDE,
    val hasKey: Boolean = false,
    val model: String = "",
    val announcements: Boolean = true,
    val naviApp: NaviApp = NaviApp.NAVER,
    val autoShareRoute: Boolean = true,
    val companionNavi: CompanionNavi = CompanionNavi.NONE,
    val homeAddress: String = "",
    val workAddress: String = "",
    val itsKey: String = "",
    /** Result of the last traffic API connection test, shown under the key. */
    val itsTest: String? = null,
    val heard: String? = null,
    val said: String? = null,
    val thinking: Boolean = false,
    val destinationWeather: Weather? = null,
    val events: List<TrafficEvent> = emptyList(),
    /** What the automatic guidance is working from right now, or why it is quiet. */
    val watching: String = "",
    /** Time and text of the last automatic announcement. */
    val lastAnnounce: String? = null,
)

/**
 * The in-car assistant. It speaks on its own when something happens
 * (getting in, a new route, an accident ahead, rain at the destination,
 * arriving) and runs commands typed on the AI tab. There is no wake word or
 * always-on microphone; the driver asked for it to be taken out.
 *
 * Wording comes from the chosen AI model when a key is saved, and from fixed
 * Korean sentences otherwise, so it still works offline or without a key.
 * The car's own route cannot be changed without the Fleet API, so route
 * commands open the chosen phone navigation app with the new destination.
 */
class DriveAssistant(private val vm: AppViewModel, private val scope: CoroutineScope) {
    private val context = vm.getApplication<android.app.Application>()
    private val prefs = AssistantPrefs(context)
    private val weatherRepo = WeatherRepository(context)
    private val llmMutex = Mutex()

    private val _state = MutableStateFlow(snapshotPrefs(AssistantUi()))
    val state: StateFlow<AssistantUi> = _state.asStateFlow()

    private val speaker = Speaker(context, vm::log)

    // ── Settings ──

    private fun snapshotPrefs(s: AssistantUi) = s.copy(
        provider = prefs.provider,
        hasKey = prefs.apiKey(prefs.provider).isNotBlank(),
        model = prefs.model(prefs.provider),
        announcements = prefs.announcements,
        naviApp = prefs.naviApp,
        autoShareRoute = prefs.autoShareRoute,
        companionNavi = prefs.companionNavi,
        homeAddress = prefs.homeAddress,
        workAddress = prefs.workAddress,
        itsKey = prefs.itsKey,
    )

    private fun reloadPrefs() = _state.update { snapshotPrefs(it) }

    fun apiKey(p: LlmProvider) = prefs.apiKey(p)
    fun model(p: LlmProvider) = prefs.model(p)

    fun saveAi(provider: LlmProvider, key: String, model: String) {
        prefs.provider = provider
        prefs.setApiKey(provider, key)
        prefs.setModel(provider, model)
        reloadPrefs()
        vm.toast(if (key.isBlank()) "${provider.label} 키를 지웠습니다" else "✅ ${provider.label} 키를 등록했습니다")
    }

    fun savePlaces(home: String, work: String) {
        prefs.homeAddress = home
        prefs.workAddress = work
        reloadPrefs()
        vm.toast("✅ 집·회사 주소를 저장했습니다")
    }

    fun saveItsKey(key: String) {
        prefs.itsKey = key
        _state.update { snapshotPrefs(it).copy(itsTest = null) }
        vm.toast(if (key.isBlank()) "교통정보 키를 지웠습니다" else "✅ 교통정보 API 키를 등록했습니다")
    }

    /**
     * Asks the traffic API for incidents around the car (or Seoul City Hall
     * when the location is unknown) and shows whether the key works.
     */
    fun testItsKey(key: String) {
        val k = key.trim().ifBlank { prefs.itsKey }
        if (k.isBlank()) {
            vm.toast("교통정보 API 키를 먼저 넣으세요")
            return
        }
        _state.update { it.copy(itsTest = "연결 확인 중…") }
        scope.launch {
            val center = here(vm.ui.value) ?: LatLon(37.5663, 126.9779)
            val result = try {
                val n = TrafficEvents(k).around(center, center, marginDeg = 0.3).size
                vm.log("교통정보 연결 테스트 성공: 주변 돌발상황 ${n}건")
                "✅ 연결 성공 · 주변 돌발상황 ${n}건"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.log("교통정보 연결 테스트 실패: ${e.message}")
                "❌ 연결 실패: ${e.message}"
            }
            _state.update { it.copy(itsTest = result) }
            vm.toast(result)
        }
    }

    fun setCompanionNavi(app: CompanionNavi) {
        prefs.companionNavi = app
        reloadPrefs()
    }

    fun setNaviApp(app: NaviApp) {
        prefs.naviApp = app
        reloadPrefs()
        vm.toast("✅ 길 안내 앱: ${app.label}")
    }

    fun setAutoShareRoute(on: Boolean) {
        prefs.autoShareRoute = on
        reloadPrefs()
    }

    fun setAnnouncements(on: Boolean) {
        prefs.announcements = on
        reloadPrefs()
        if (on) watchDrive()
    }

    // ── Lifecycle ──

    /** Called once the permission answer is in. */
    fun onAppStart() {
        watchDrive()
    }

    fun shutdown() {
        speaker.shutdown()
    }

    // ── Commands ──

    /** Runs a typed command from the AI screen. */
    fun handleCommandText(command: String) {
        _state.update { it.copy(heard = command) }
        scope.launch { handleCommand(command) }
    }

    private suspend fun handleCommand(command: String) {
        val llm = client()
        var action: VoiceAction = VoiceRules.parse(command)
        var reply: String? = null
        if (llm != null) {
            _state.update { it.copy(thinking = true) }
            try {
                val text = llmMutex.withLock { llm.complete(commandSystemPrompt(), "${facts()}\n\n운전자: $command") }
                val json = JSONObject(text.substringAfter("{", "").let { "{$it" }.substringBeforeLast("}") + "}")
                action = VoiceAction.fromName(
                    json.optString("action"),
                    json.optString("query").takeIf { it.isNotBlank() },
                    json.optDouble("value").takeIf { !it.isNaN() },
                )
                reply = json.optString("say").takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.log("AI 명령 해석 실패, 기본 규칙 사용: ${e.message}")
            } finally {
                _state.update { it.copy(thinking = false) }
            }
        }
        perform(action, reply)
    }

    private suspend fun perform(action: VoiceAction, reply: String?) {
        val ui = vm.ui.value
        when (action) {
            is VoiceAction.Navigate -> {
                val place = resolveSaved(action.place) ?: run {
                    say("${action.place} 주소를 AI 탭에 먼저 저장해 주세요.")
                    return
                }
                say(reply ?: "${action.place}(으)로 안내를 시작합니다.")
                if (NaviHub.handlesVoice) NaviHub.navigateTo(place) { say(it) }
                else RouteHandoff.navigate(context, prefs.naviApp, place, here(ui), vm::log)
            }
            is VoiceAction.AddStop -> {
                if (NaviHub.handlesVoice && NaviHub.state.value.guiding) {
                    say(reply ?: "${action.place}를 경유지로 넣습니다.")
                    NaviHub.addStopTo(action.place) { say(it) }
                    return
                }
                val nav = ui.nav
                val dest = nav?.let { n -> n.destinationLat?.let { lat -> n.destinationLon?.let { LatLon(lat, it) } } }
                if (nav == null || dest == null) {
                    say(reply ?: "${action.place}(으)로 안내합니다. 차량에 설정된 목적지가 없어 경유지 대신 바로 안내해요.")
                    RouteHandoff.navigate(context, prefs.naviApp, action.place, here(ui), vm::log)
                } else {
                    say(reply ?: "${action.place}를 경유지로 넣어 ${nav.destination ?: "목적지"}까지 안내합니다.")
                    RouteHandoff.viaStop(context, prefs.naviApp, action.place, dest, nav.destination ?: "목적지", here(ui), vm::log)
                }
            }
            VoiceAction.NearestCharger -> nearestCharger(reply)
            is VoiceAction.Climate -> {
                say(reply ?: if (action.on) "공조를 켭니다." else "공조를 끕니다.")
                vm.carCommand(if (action.on) "공조 켜기" else "공조 끄기") { it.climate(action.on) }
            }
            is VoiceAction.Temperature -> {
                say(reply ?: "실내 온도를 ${fmtTemp(action.celsius)}도로 맞춥니다.")
                vm.carCommand("온도 ${action.celsius}") { it.setTemperature(action.celsius, action.celsius) }
            }
            VoiceAction.Lock -> {
                say(reply ?: "문을 잠급니다.")
                vm.carCommand("잠금") { it.lock() }
            }
            VoiceAction.RouteStatus -> say(reply ?: routeStatusText(ui.nav))
            VoiceAction.Weather -> say(reply ?: weatherText(ui))
            is VoiceAction.Announcements -> {
                setAnnouncements(action.on)
                say(reply ?: if (action.on) "자동 안내를 다시 켭니다." else "알겠어요. 자동 안내를 끕니다.")
            }
            VoiceAction.None -> say(reply ?: "잘 못 알아들었어요. 예를 들어 '강남역으로 가자'처럼 말해 주세요.")
        }
    }

    private fun resolveSaved(place: String): String? = when (place.replace(" ", "")) {
        "집", "우리집" -> prefs.homeAddress.takeIf { it.isNotBlank() }
        "회사", "직장" -> prefs.workAddress.takeIf { it.isNotBlank() }
        else -> place
    }

    private suspend fun nearestCharger(reply: String?) {
        if (vm.ui.value.chargingSites.isEmpty()) {
            say("주변 슈퍼차저를 찾고 있어요.")
            vm.findChargingSites()
            // findChargingSites runs in the background; wait for its answer.
            for (i in 0 until 20) {
                if (vm.ui.value.chargingSites.isNotEmpty()) break
                delay(1000)
            }
        }
        val site = vm.ui.value.chargingSites
            .filter { !it.closed }
            .sortedWith(compareBy({ it.availableStalls == 0 }, { it.distanceKm }))
            .firstOrNull()
        if (site == null) {
            say("주변 슈퍼차저를 찾지 못했어요. 차량 연결을 확인해 주세요.")
            return
        }
        say(reply ?: "${site.name} 슈퍼차저로 안내합니다. ${site.distanceKm.roundToInt()}킬로미터, 빈 자리 ${site.availableStalls}개예요.")
        val spot = NaviPlace("${site.name} 슈퍼차저", LatLon(site.latitude, site.longitude), site.address, "슈퍼차저")
        // Guiding here already: the charger becomes a stop on the way.
        if (NaviHub.handlesVoice && NaviHub.state.value.guiding) return NaviHub.addStop(spot)
        if (NaviHub.handlesVoice) return NaviHub.preview(spot) { NaviHub.start() }
        RouteHandoff.toPoint(context, prefs.naviApp, LatLon(site.latitude, site.longitude), "${site.name} 슈퍼차저")
    }

    // ── Automatic announcements ──

    private var driveJob: Job? = null
    private var greeted = false
    private var lastRouteKey: String? = null
    private var arrivedKey: String? = null
    private var lastDelay = 0.0
    private val announcedEvents = HashSet<String>()
    private var lastEventsCheck = 0L
    private var lastRainy: Boolean? = null
    private var navPolling = false
    private var lastBriefing = 0L

    private fun watchDrive() {
        if (driveJob?.isActive == true) return
        driveJob = scope.launch {
            val started = System.currentTimeMillis()
            while (isActive && prefs.announcements) {
                try {
                    tick(vm.ui.value, started)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    vm.log("AI 안내 오류: ${e.message}")
                }
                delay(5000)
            }
        }
    }

    private suspend fun tick(ui: UiState, started: Long) {
        // Route updates need the route read from the car every few seconds. Turned on once,
        // so a driver who switches it off on the navi tab keeps it off.
        if (ui.car == LinkState.CONNECTED && !navPolling) {
            navPolling = true
            if (!ui.navAutoRefresh) vm.setNavAutoRefresh(true)
        }

        // Boarding: once per app start, when a link comes up or after 20 s.
        if (!greeted) {
            val linked = ui.car == LinkState.CONNECTED || ui.commander == LinkState.CONNECTED
            if (linked || System.currentTimeMillis() - started > 20_000) {
                greeted = true
                greetNow()
            }
        }

        val nav = ui.nav?.takeIf { it.hasRoute }
        _state.update { it.copy(watching = watchingText(ui, nav != null)) }
        if (nav == null) {
            // No route from the car (no destination, or the car link is down):
            // look ahead along the way the phone is moving instead.
            if (lastRouteKey != null) announcedEvents.clear()
            lastRouteKey = null
            checkAhead(ui)
            checkRain(ui)
            return
        }
        val routeKey = "${nav.destination}|${nav.destinationLat}|${nav.destinationLon}"

        // New route.
        if (routeKey != lastRouteKey) {
            lastRouteKey = routeKey
            arrivedKey = null
            lastDelay = nav.trafficDelayMinutes ?: 0.0
            lastBriefing = System.currentTimeMillis()
            announcedEvents.clear()
            val destWeather = destination(nav)?.let { fetchWeather(it) }
            _state.update { it.copy(destinationWeather = destWeather, events = emptyList()) }
            val events = checkEvents(ui, force = true)
            announce(
                "경로 시작",
                "새 경로가 설정됐습니다. 목적지, 도착 예정, 정체, 도착지 날씨, 경로상 사고나 공사를 한두 문장으로 요약하세요.",
            ) { routeStartText(nav, destWeather, events) }
            return
        }
        // Arrival.
        val minutes = nav.minutesToArrival
        if (minutes != null && minutes <= 1.5 && arrivedKey != routeKey) {
            arrivedKey = routeKey
            val w = _state.value.destinationWeather
            announce("도착", "곧 목적지에 도착합니다. 도착 안내와 함께 도착지 날씨에 맞는 짧은 한마디를 하세요.") {
                "곧 ${nav.destination ?: "목적지"}에 도착합니다." + (w?.let { " 바깥은 ${it.label}, ${it.tempC.roundToInt()}도예요." } ?: "")
            }
            return
        }

        // Traffic getting worse or clearing.
        val delayMin = nav.trafficDelayMinutes ?: 0.0
        if (delayMin - lastDelay >= 5) {
            announce("정체 증가", "경로 정체가 늘었습니다. 늘어난 지연 시간을 알리고 침착하게 운전하도록 짧게 말하세요.") {
                "앞쪽 정체로 도착이 약 ${delayMin.roundToInt()}분 늦어질 것 같아요."
            }
        } else if (lastDelay - delayMin >= 5) {
            announce("정체 해소", "경로 정체가 풀렸습니다. 줄어든 지연과 새 도착 예정을 짧게 알리세요.") {
                "정체가 풀려 도착이 약 ${(lastDelay - delayMin).roundToInt()}분 빨라졌어요." +
                    (nav.minutesToArrival?.let { " 이제 ${it.roundToInt()}분 남았어요." } ?: "")
            }
        }
        lastDelay = delayMin

        // New accidents and roadwork ahead, every 3 minutes.
        val from = here(ui)
        val fresh = checkEvents(ui, force = false).filter { announcedEvents.add(it.id) }
        if (fresh.isNotEmpty()) {
            val e = fresh.sortedBy { from?.let { f -> Geo.distanceKm(f, it.position) } ?: 0.0 }.first()
            announce("돌발 상황", "경로 앞쪽에 새 돌발 상황이 있습니다. 거리, 위치(도로명)와 종류, 차로 통제를 짧게 알리고 주의를 당부하세요.") {
                eventText(e, from, "경로 앞쪽")
            }
        } else if (System.currentTimeMillis() - lastBriefing > BRIEFING_MS && (nav.minutesToArrival ?: 0.0) > 10) {
            // A short update every 15 minutes on longer drives.
            lastBriefing = System.currentTimeMillis()
            announce("경로 브리핑", "주행 중 정기 안내입니다. 남은 시간과 거리, 정체, 경로상 사고·공사 수를 한두 문장으로 알리세요.") {
                routeStatusText(nav) +
                    (nav.trafficDelayMinutes?.takeIf { it >= 3 }?.let { " 정체로 ${it.roundToInt()}분 늦어지고 있어요." } ?: " 길은 대체로 원활해요.") +
                    (_state.value.events.size.takeIf { it > 0 }?.let { " 경로에 사고·공사가 ${it}건 있어요." } ?: "")
            }
        }
        checkRain(ui)
    }

    /**
     * Without a route from the car, warns about accidents and roadwork on the
     * road ahead: a corridor [AHEAD_KM] long in the direction of travel, from
     * the car's or the phone's GPS. Checked every 3 minutes while moving.
     */
    private suspend fun checkAhead(ui: UiState) {
        val key = prefs.itsKey.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        if (now - lastEventsCheck < 3 * 60_000) return
        val from = here(ui) ?: return
        val heading = heading(ui) ?: return
        lastEventsCheck = now
        val ahead = Geo.offset(from, heading, AHEAD_KM)
        val list = try {
            TrafficEvents(key).around(from, ahead, marginDeg = 0.02).filter { Geo.aheadOnRoute(from, ahead, it.position, minWidthKm = 0.8) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.log("교통 돌발정보 실패: ${e.message}")
            return
        }
        _state.update { it.copy(events = list) }
        val fresh = list.filter { announcedEvents.add(it.id) }.sortedBy { Geo.distanceKm(from, it.position) }
        if (fresh.isEmpty()) return
        announce("전방 돌발 상황", "진행 방향 앞쪽에 돌발 상황이 있습니다. 거리, 도로명, 종류, 차로 통제를 짧게 알리고 주의를 당부하세요.") {
            eventText(fresh.first(), from, "전방") + if (fresh.size > 1) " 그 밖에도 ${fresh.size - 1}건이 더 있어요." else ""
        }
    }

    private fun eventText(e: TrafficEvent, from: LatLon?, fallbackPlace: String): String {
        val km = from?.let { Geo.distanceKm(it, e.position) }
        val where = buildString {
            if (km != null) append(if (km < 1) "${(km * 10).roundToInt() * 100}미터 앞 " else "${km.roundToInt()}킬로미터 앞 ")
            append(e.road.ifBlank { fallbackPlace })
        }
        val kind = e.kind.ifBlank { "돌발 상황" }
        return "주의하세요. ${where}에 $kind${subject(kind)} 있어요." +
            (e.lanes.takeIf { it.isNotBlank() }?.let { " $it 통제 중입니다." } ?: "")
    }

    /** 이 or 가, whichever fits after [word] (공사가, 사고가, 통제가 / 낙하물이). */
    private fun subject(word: String): String {
        val c = word.lastOrNull() ?: return "이"
        if (c !in '가'..'힣') return "이"
        return if ((c - '가') % 28 == 0) "가" else "이"
    }

    /** One line for the AI tab: what the guidance is working from, or why it is quiet. */
    private fun watchingText(ui: UiState, hasRoute: Boolean): String {
        val its = prefs.itsKey.isNotBlank()
        return when {
            hasRoute -> "테슬라 경로 기준으로 안내 중" + if (its) " (사고·공사 포함)" else " (교통정보 키를 넣으면 사고·공사도 안내)"
            ui.car != LinkState.CONNECTED && freshFix() == null ->
                "차량 BLE 미연결이라 경로를 모릅니다. 과속카메라 알림을 켜면 폰 GPS로 전방 교통상황을 안내합니다"
            !its -> "경로 없음. 설정 탭에 교통정보 키를 넣으면 진행 방향의 사고·공사를 안내합니다"
            heading(ui) == null -> "정차 중. 출발하면 진행 방향 ${AHEAD_KM.roundToInt()}km 안의 사고·공사를 확인합니다"
            else -> "진행 방향 ${AHEAD_KM.roundToInt()}km 안의 사고·공사를 3분마다 확인 중"
        }
    }

    private fun freshFix(): android.location.Location? =
        CameraState.fix?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000 }

    /** Direction of travel, only while moving fast enough for it to mean something. */
    private fun heading(ui: UiState): Double? {
        val fix = freshFix()
        if (fix != null && fix.hasBearing() && fix.hasSpeed() && fix.speed > 4f) return fix.bearing.toDouble()
        val nav = ui.nav
        val moving = (nav?.speedKmh ?: 0.0) > 15 || (ui.live.speedKmh ?: 0L) > 15
        return nav?.heading?.toDouble()?.takeIf { moving && ui.car == LinkState.CONNECTED }
    }

    /** Speaks once when rain or snow starts or stops where the car is. */
    private suspend fun checkRain(ui: UiState) {
        val w = ui.weather ?: return
        val rainy = w.code in 51..67 || w.code in 71..86 || w.code in 95..99
        val before = lastRainy
        lastRainy = rainy
        if (before == false && rainy) {
            announce("기상 변화", "지금 위치에 ${w.label}가 내리기 시작했습니다. 감속과 차간거리 확보를 짧게 당부하세요.") {
                "${w.label}가 오기 시작했어요. 속도를 줄이고 차간거리를 넉넉히 두세요."
            }
        }
    }

    private suspend fun checkEvents(ui: UiState, force: Boolean): List<TrafficEvent> {
        val key = prefs.itsKey.takeIf { it.isNotBlank() } ?: return emptyList()
        val now = System.currentTimeMillis()
        if (!force && now - lastEventsCheck < 3 * 60_000) return emptyList()
        lastEventsCheck = now
        val from = here(ui) ?: return emptyList()
        val to = destination(ui.nav) ?: return emptyList()
        return try {
            TrafficEvents(key).around(from, to).filter { Geo.aheadOnRoute(from, to, it.position) }.also { list ->
                _state.update { it.copy(events = list) }
                if (force) list.forEach { announcedEvents.add(it.id) }
            }
        } catch (e: Exception) {
            vm.log("교통 돌발정보 실패: ${e.message}")
            emptyList()
        }
    }

    /** Speaks [fallback], or the AI model's wording of the same moment when a key is saved. */
    private suspend fun announce(event: String, instruction: String, fallback: () -> String) {
        if (!prefs.announcements) return
        val llm = client()
        val text = if (llm == null) {
            fallback()
        } else {
            _state.update { it.copy(thinking = true) }
            try {
                llmMutex.withLock {
                    llm.complete(ANNOUNCE_SYSTEM, "${facts()}\n\n상황: $event\n지시: $instruction", maxTokens = 200)
                }.ifBlank { fallback() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.log("AI 안내 생성 실패, 기본 문장 사용: ${e.message}")
                fallback()
            } finally {
                _state.update { it.copy(thinking = false) }
            }
        }
        vm.log("AI 안내($event): $text")
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
        _state.update { it.copy(lastAnnounce = "$time $event: $text") }
        say(text)
    }

    /** Test button: speak the boarding greeting now. */
    fun testGreeting() {
        scope.launch { greetNow() }
    }

    private suspend fun greetNow() {
        announce("탑승", "운전자가 방금 차에 탔습니다. 짧게 환영 인사를 하고, 배터리와 날씨 중 운전에 도움 되는 것만 덧붙이세요.") {
            greetingText(vm.ui.value)
        }
    }

    /** Test button: sum up the current drive now. */
    fun testBriefing() {
        scope.launch {
            announce("현재 상황 요약", "지금 주행 상황을 두세 문장으로 요약하세요. 경로가 없으면 배터리와 날씨만 말하세요.") {
                val ui = vm.ui.value
                if (ui.nav?.hasRoute == true) routeStatusText(ui.nav) + " " + weatherText(ui) else greetingText(ui)
            }
        }
    }

    private fun say(text: String, interrupt: Boolean = false) {
        _state.update { it.copy(said = text) }
        speaker.speak(text, interrupt)
    }

    // ── Facts and fixed sentences ──

    private fun client(): LlmClient? {
        val p = prefs.provider
        val key = prefs.apiKey(p).takeIf { it.isNotBlank() } ?: return null
        return LlmClient(p, key, prefs.model(p))
    }

    private fun here(ui: UiState): LatLon? {
        val nav = ui.nav
        if (ui.car == LinkState.CONNECTED && nav?.latitude != null && nav.longitude != null) return LatLon(nav.latitude!!, nav.longitude!!)
        freshFix()?.let { return LatLon(it.latitude, it.longitude) }
        if (nav?.latitude != null && nav.longitude != null) return LatLon(nav.latitude!!, nav.longitude!!)
        return weatherRepo.lastKnownLocation()?.let { LatLon(it.latitude, it.longitude) }
    }

    private fun destination(nav: NavigationInfo?): LatLon? {
        val lat = nav?.destinationLat ?: return null
        val lon = nav.destinationLon ?: return null
        return LatLon(lat, lon)
    }

    private suspend fun fetchWeather(p: LatLon): Weather? = try {
        weatherRepo.fetch(p.lat, p.lon)
    } catch (e: Exception) {
        vm.log("도착지 날씨 실패: ${e.message}")
        null
    }

    /** Everything the model may use, as plain Korean lines. */
    private fun facts(): String {
        val ui = vm.ui.value
        val c = Cockpit.from(ui, CameraState.status.value, CameraState.gpsSpeedKmh.value)
        val s = _state.value
        val nav = ui.nav
        return buildString {
            appendLine("[차량 상황]")
            appendLine("시각: ${SimpleDateFormat("M월 d일 E요일 HH:mm", Locale.KOREA).format(Date())}")
            c.speedKmh?.let { appendLine("속도: ${it.roundToInt()}km/h") }
            c.activeLimit?.let { appendLine("단속카메라 제한속도: ${it}km/h") }
            c.limitKmh?.let { appendLine("도로 제한속도(테슬라 지도): ${it}km/h") }
            c.gear?.let { appendLine("기어: $it") }
            c.socPercent?.let { appendLine("배터리: $it%") }
            c.rangeKm?.let { appendLine("주행 가능 거리: ${it}km") }
            c.outsideTempC?.let { appendLine("바깥 온도: ${it.roundToInt()}도") }
            c.insideTempC?.let { appendLine("실내 온도: ${it.roundToInt()}도") }
            ui.weather?.let { appendLine("현재 위치 날씨: ${it.label}, ${it.tempC.roundToInt()}도") }
            if (nav?.hasRoute == true) {
                appendLine("목적지: ${nav.destination ?: "(이름 없음)"}")
                nav.minutesToArrival?.let { appendLine("남은 시간: ${it.roundToInt()}분") }
                nav.kmToArrival?.let { appendLine("남은 거리: ${"%.1f".format(it)}km") }
                nav.trafficDelayMinutes?.takeIf { it >= 1 }?.let { appendLine("정체 지연: ${it.roundToInt()}분") }
                nav.energyAtArrival?.let { appendLine("도착 시 배터리: ${it.roundToInt()}%") }
                s.destinationWeather?.let { appendLine("도착지 날씨: ${it.label}, ${it.tempC.roundToInt()}도") }
            } else {
                appendLine("경로: 없음")
            }
            if (s.events.isNotEmpty()) {
                appendLine("경로상 돌발 상황:")
                s.events.take(5).forEach { e ->
                    appendLine("- ${e.kind} ${e.detail} / ${e.road} / ${e.lanes} / ${e.message.take(60)}")
                }
            }
            CameraState.status.value.let { cam ->
                val d = cam.distanceM
                if (cam.ahead != null && d != null && d < 2000) appendLine("과속카메라: ${d.roundToInt()}m 앞, 제한 ${cam.ahead!!.limitKmh}km/h")
            }
        }.trim()
    }

    private fun commandSystemPrompt(): String = buildString {
        appendLine("당신은 테슬라 차량용 한국어 음성 비서입니다. 운전자의 말을 듣고 할 일을 하나 고르고, 소리 내어 읽을 짧은 대답을 만드세요.")
        appendLine("반드시 JSON 한 개만 출력합니다. 형식: {\"action\":\"이름\",\"query\":\"장소\",\"value\":숫자,\"say\":\"대답\"}")
        appendLine("action 목록:")
        VoiceAction.catalog.forEach { (name, desc) -> appendLine("- $name: $desc") }
        appendLine("규칙: say는 한두 문장, 존댓말, 숫자는 읽기 쉽게. 이모지와 마크다운 금지. 운전 중이므로 안전을 우선하세요.")
        appendLine("질문(배터리, 도착 시간, 날씨 등)에는 아래 차량 상황으로 답하고 action은 none으로 합니다. 모르는 값은 지어내지 마세요.")
        appendLine("경로 변경이나 목적지 설정은 폰의 ${prefs.naviApp.label} 앱으로 넘겨서 안내합니다.")
    }

    private fun greetingText(ui: UiState): String {
        val hour = SimpleDateFormat("H", Locale.KOREA).format(Date()).toInt()
        val hello = when (hour) {
            in 5..10 -> "좋은 아침이에요."
            in 11..16 -> "안녕하세요."
            in 17..21 -> "좋은 저녁이에요."
            else -> "늦은 시간이네요, 안전 운전하세요."
        }
        val c = Cockpit.from(ui, CameraState.status.value, null)
        val battery = c.socPercent?.let { " 배터리는 $it%" + (c.rangeKm?.let { r -> ", 약 ${r}킬로미터 갈 수 있어요." } ?: "예요.") } ?: ""
        val weather = ui.weather?.let { " 지금 바깥은 ${it.label}, ${it.tempC.roundToInt()}도입니다." } ?: ""
        return hello + battery + weather
    }

    private fun routeStartText(nav: NavigationInfo, w: Weather?, events: List<TrafficEvent>): String = buildString {
        append("${nav.destination ?: "목적지"}까지")
        nav.kmToArrival?.let { append(" ${it.roundToInt()}킬로미터,") }
        nav.minutesToArrival?.let { append(" 약 ${it.roundToInt()}분 걸려요.") } ?: append(" 안내를 시작합니다.")
        nav.trafficDelayMinutes?.takeIf { it >= 3 }?.let { append(" 정체로 ${it.roundToInt()}분 정도 늦어지고 있어요.") }
        w?.let { append(" 도착지 날씨는 ${it.label}, ${it.tempC.roundToInt()}도예요.") }
        val accidents = events.count { it.isAccident }
        val works = events.count { it.isRoadwork }
        if (accidents > 0) append(" 경로 앞쪽에 사고 ${accidents}건이 있으니 주의하세요.")
        if (works > 0) append(" 공사 구간이 ${works}곳 있어요.")
    }

    private fun routeStatusText(nav: NavigationInfo?): String {
        if (nav?.hasRoute != true) return "지금은 설정된 경로가 없어요."
        val min = nav.minutesToArrival?.roundToInt()
        val km = nav.kmToArrival?.let { "%.1f".format(it) }
        return "${nav.destination ?: "목적지"}까지" +
            (km?.let { " ${it}킬로미터," } ?: "") +
            (min?.let { " 약 ${it}분 남았어요." } ?: " 남은 시간을 아직 몰라요.")
    }

    private fun weatherText(ui: UiState): String {
        val here = ui.weather?.let { "지금 이곳은 ${it.label}, ${it.tempC.roundToInt()}도" }
        val there = _state.value.destinationWeather?.let { "도착지는 ${it.label}, ${it.tempC.roundToInt()}도" }
        val parts = listOfNotNull(here, there)
        return if (parts.isEmpty()) "날씨 정보를 아직 받지 못했어요." else parts.joinToString("이고, ") + "예요."
    }

    private fun fmtTemp(c: Float) = if (c % 1f == 0f) c.toInt().toString() else c.toString()

    private companion object {
        const val AHEAD_KM = 8.0
        const val BRIEFING_MS = 15 * 60_000L

        const val ANNOUNCE_SYSTEM =
            "당신은 테슬라 차량용 한국어 음성 비서입니다. 주어진 차량 상황과 지시에 맞춰 운전자에게 소리 내어 읽어줄 안내 멘트만 출력하세요. " +
                "한두 문장, 60자 안팎, 존댓말, 친근하고 차분하게. 이모지·마크다운·따옴표 금지. 주어진 값만 쓰고 모르는 것은 말하지 마세요."
    }
}
