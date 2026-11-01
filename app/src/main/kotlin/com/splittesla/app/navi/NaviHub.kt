package com.splittesla.app.navi

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.splittesla.app.camera.CameraRepository
import com.splittesla.app.camera.CameraState
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.navi.ChargePlanner
import com.splittesla.core.navi.ChargeStop
import com.splittesla.core.navi.ChargerSpot
import com.splittesla.core.navi.ConsumptionLearner
import com.splittesla.core.navi.EnergyModel
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.Maneuver
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteCamera
import com.splittesla.core.navi.RouteCameras
import com.splittesla.core.navi.RouteGeometry
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteProgress
import com.splittesla.core.navi.RouteTracker
import com.splittesla.core.tesla.ChargingSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class NaviPhase { IDLE, PREVIEW, GUIDING }

/** Everything the navigation screen, the dashboard banner and the service show. */
data class NaviUi(
    val phase: NaviPhase = NaviPhase.IDLE,
    val busy: String? = null,
    val message: String? = null,
    val results: List<NaviPlace> = emptyList(),
    val destination: NaviPlace? = null,
    val waypoints: List<NaviPlace> = emptyList(),
    val alternatives: List<NaviRoute> = emptyList(),
    /** Battery on arrival for each alternative, by option. */
    val alternativeSoc: Map<RouteOption, Double> = emptyMap(),
    val route: NaviRoute? = null,
    val progress: RouteProgress? = null,
    val position: LatLon? = null,
    val bearing: Float? = null,
    val gpsOk: Boolean = false,
    val socAtArrival: Double? = null,
    val kwhToGo: Double? = null,
    val learnedWhPerKm: Double? = null,
    val cameras: List<RouteCamera> = emptyList(),
    val nextCamera: RouteCamera? = null,
    val chargeStops: List<ChargeStop> = emptyList(),
    val chargeNote: String? = null,
    val lastSpoken: String? = null,
    /** Result of the key check on the settings screen, one line per API. */
    val keyTest: List<String> = emptyList(),
) {
    val guiding: Boolean get() = phase == NaviPhase.GUIDING
}

/** What the car link knows, fed in by the view model as it changes. */
data class CarFeed(
    val socPercent: Double? = null,
    val remainingKwh: Double? = null,
    val fullKwh: Double? = null,
    val powerKw: Double? = null,
    val speedKmh: Double? = null,
    val outsideC: Double? = null,
    val position: LatLon? = null,
    val superchargers: List<ChargingSite> = emptyList(),
    val at: Long = 0,
)

/**
 * In-app turn-by-turn guidance on NAVER Directions. Lives as long as the app
 * process so guidance keeps running with the screen off or a map in front;
 * [NaviService] feeds it GPS and speaks what it says.
 */
object NaviHub {
    // One thread for all guidance state; network calls suspend onto IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _state = MutableStateFlow(NaviUi())
    val state: StateFlow<NaviUi> = _state.asStateFlow()
    // A channel, not a shared flow: the first prompt is said before the service is up to listen.
    private val _speech = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val speech: Flow<String> = _speech.receiveAsFlow()

    private lateinit var app: Context
    private lateinit var prefs: NaviPrefs
    private lateinit var api: NaverApi
    private var log: (String) -> Unit = {}

    private var tracker: RouteTracker? = null
    private val announcer = GuidanceAnnouncer()
    private var learner = ConsumptionLearner()
    @Volatile private var car = CarFeed()
    @Volatile private var lastFix: Location? = null
    @Volatile private var lastFixAt = 0L
    private var lastRerouteAt = 0L
    private var lastGuideIndex: Int? = null
    private var chargeChecked = false
    private var arriving = false
    private var cameraIndex: CameraIndex? = null
    private var loops: Job? = null

    fun init(context: Context, logger: (String) -> Unit) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = NaviPrefs(app)
        api = NaverApi(prefs)
        log = logger
    }

    val settings: NaviPrefs get() = prefs
    val naver: NaverApi get() = api

    // ── Search and preview ──

    fun search(query: String) {
        if (query.isBlank()) return
        scope.launch {
            _state.update { it.copy(busy = "'$query' 찾는 중", message = null) }
            try {
                val q = query.trim()
                val near = bestPosition()
                val found = api.searchPlaces(q, near, near?.let { districtNear(it) })
                // Without NAVER's name search, or when it finds little, the phone's own geocoder knows many places by name.
                val phone = if (found.places.size < 3) phonePlaces(q, near) else emptyList()
                val hits = (found.places + phone)
                    .distinctBy { "%.4f,%.4f".format(it.point.lat, it.point.lon) }
                    .let { list -> near?.let { n -> list.sortedBy { RouteGeometry.metres(n, it.point) } } ?: list }
                val message = when {
                    hits.isNotEmpty() -> found.problem?.takeIf { it != NaverApi.NO_SEARCH_KEY }
                    found.problem == NaverApi.NO_SEARCH_KEY ->
                        "'$q'을(를) 찾지 못했습니다. 이마트처럼 이름으로 찾으려면 설정 > 길안내 API에 NAVER API HUB 지역 검색 키를 넣어 주세요"
                    else -> found.problem ?: "'$q' 검색 결과가 없습니다"
                }
                _state.update { it.copy(results = hits, message = message) }
            } catch (e: Exception) {
                fail("검색", e)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private var district: Pair<LatLon, String>? = null

    /** The district around [p] ("분당구"), looked up again only after moving 2 km. */
    private suspend fun districtNear(p: LatLon): String? {
        district?.let { (at, name) -> if (RouteGeometry.metres(at, p) < 2000) return name }
        if (!prefs.hasMaps) return null
        val name = runCatching { api.areaName(p) }.getOrNull()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: return null
        district = p to name
        return name
    }

    /** Places by name from the phone's geocoder (Google on most phones), within about 30 km of [near]. */
    @Suppress("DEPRECATION")
    private suspend fun phonePlaces(query: String, near: LatLon?): List<NaviPlace> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext emptyList()
        runCatching {
            val geocoder = Geocoder(app, Locale.KOREA)
            val found = if (near != null) {
                geocoder.getFromLocationName(query, 10, near.lat - 0.3, near.lon - 0.35, near.lat + 0.3, near.lon + 0.35)
            } else {
                geocoder.getFromLocationName(query, 10)
            }
            found.orEmpty().map { a ->
                val line = a.getAddressLine(0).orEmpty().removePrefix("대한민국 ")
                // featureName is often just the house number already in the address; then the query names it better.
                val name = a.featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) && !line.contains(it) } ?: query
                NaviPlace(name, LatLon(a.latitude, a.longitude), line, "휴대폰 검색")
            }
        }.getOrDefault(emptyList())
    }

    /** Routes to [place] for the default option and two alternatives, ready to start. */
    fun preview(place: NaviPlace, then: (() -> Unit)? = null) {
        scope.launch {
            val from = bestPosition() ?: return@launch fail("경로", NaviApiException("현재 위치를 몰라 경로를 찾을 수 없습니다. GPS를 켜거나 차량 BLE를 연결하세요"))
            _state.update { it.copy(busy = "경로 찾는 중", message = null, destination = place, waypoints = emptyList()) }
            try {
                val options = listOf(prefs.option, RouteOption.OPTIMAL, RouteOption.AVOID_TOLL).distinct()
                val routes = api.directions(from, place, emptyList(), options)
                if (routes.isEmpty()) throw NaviApiException("경로가 없습니다")
                val socs = routes.mapNotNull { r -> arrivalSoc(RouteTracker(r), 0.0)?.let { r.option to it } }.toMap()
                val chosen = routes.firstOrNull { it.option == prefs.option } ?: routes.first()
                _state.update {
                    it.copy(phase = if (it.guiding) it.phase else NaviPhase.PREVIEW, alternatives = routes, alternativeSoc = socs, route = if (it.guiding) it.route else chosen, results = emptyList())
                }
                then?.invoke()
            } catch (e: Exception) {
                fail("경로", e)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun choose(option: RouteOption) {
        val r = _state.value.alternatives.firstOrNull { it.option == option } ?: return
        prefs.option = option
        _state.update { it.copy(route = r) }
    }

    /** Whether a spoken "○○ 가자" should be guided here rather than handed to a phone navi app. */
    val handlesVoice: Boolean get() = ::prefs.isInitialized && prefs.voiceInApp && prefs.hasMaps

    /** Voice: finds [query] and guides there; [onFail] hears why not. */
    fun navigateTo(query: String, onFail: (String) -> Unit) {
        scope.launch {
            try {
                val hit = api.findPlaces(query, bestPosition()).firstOrNull() ?: return@launch onFail("'$query'을(를) 찾지 못했습니다.")
                preview(hit) { start() }
            } catch (e: Exception) {
                onFail("길안내를 시작하지 못했습니다. ${e.message}")
            }
        }
    }

    /** Voice: finds [query] and adds it as a stop on the current guidance. */
    fun addStopTo(query: String, onFail: (String) -> Unit) {
        scope.launch {
            try {
                val hit = api.findPlaces(query, bestPosition()).firstOrNull() ?: return@launch onFail("'$query'을(를) 찾지 못했습니다.")
                addStop(hit)
            } catch (e: Exception) {
                onFail("경유지를 넣지 못했습니다. ${e.message}")
            }
        }
    }

    fun clear() {
        scope.launch { if (!_state.value.guiding) _state.value = NaviUi() }
    }

    /** Drops the guidance in progress and guides to [place] instead. */
    fun replaceDestination(place: NaviPlace) {
        scope.launch {
            halt(null)
            preview(place) { start() }
        }
    }

    /** Calls each API once with the saved keys and reports what works. */
    fun testKeys() {
        scope.launch {
            _state.update { it.copy(keyTest = listOf("확인 중…")) }
            val here = bestPosition() ?: SEOUL_CITY_HALL
            val out = ArrayList<String>()
            suspend fun check(name: String, block: suspend () -> String) {
                out += try {
                    "✅ $name: ${block()}"
                } catch (e: Exception) {
                    "❌ $name: ${e.message ?: e.javaClass.simpleName}"
                }
                _state.update { it.copy(keyTest = out.toList()) }
            }
            if (prefs.hasMaps) {
                check("경로(Directions 5)") {
                    val goal = NaviPlace("시험", LatLon(here.lat + 0.01, here.lon + 0.01))
                    val r = api.directions(here, goal, emptyList(), listOf(RouteOption.FAST)).firstOrNull() ?: throw NaviApiException("경로 없음")
                    "${"%.1f".format(r.distanceM / 1000.0)}km · ${minutes(r.durationMs)}"
                }
                check("주소 검색(Geocoding)") { "${api.geocode("세종대로 110", here).size}건" }
                check("지역 코드(Reverse Geocoding)") { api.legalCode(here) ?: "결과 없음" }
            } else {
                out += "❌ NAVER Cloud Maps 키가 없습니다"
            }
            if (prefs.hasSearch) check("장소 검색(API HUB 지역)") { "'스타벅스' ${api.localSearch("스타벅스", here).size}건" }
            else out += "⚪ 장소 검색 키 없음 (주소와 휴대폰 검색으로 찾음)"
            val ev = prefs.evKey.ifBlank { CameraRepository(app).apiKey }
            if (ev.isNotBlank()) check("충전소(환경부)") { "종로구 급속 ${EvChargerApi.stations(ev, "11110").size}곳" }
            else out += "⚪ 충전소 키 없음 (테슬라 슈퍼차저만 제안)"
            _state.update { it.copy(keyTest = out.toList()) }
        }
    }

    // ── Guidance ──

    fun start() {
        val route = _state.value.route ?: return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(message = "위치 권한을 허용해야 길안내를 할 수 있습니다") }
            return
        }
        scope.launch {
            while (_speech.tryReceive().isSuccess) Unit
            tracker = RouteTracker(route)
            announcer.reset()
            learner = ConsumptionLearner()
            lastGuideIndex = null
            chargeChecked = false
            arriving = false
            prefs.remember(route.goal)
            _state.update { it.copy(phase = NaviPhase.GUIDING, alternatives = emptyList(), message = null, chargeStops = emptyList(), chargeNote = null) }
            try {
                ContextCompat.startForegroundService(app, Intent(app, NaviService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ refuses a foreground service started from the background (a voice command with the app hidden).
                log("길안내 서비스 시작 실패: ${e.message}")
                _state.update { it.copy(message = "앱을 화면에 띄운 상태에서 안내를 시작해 주세요") }
            }
            val eta = SimpleDateFormat("a h시 m분", Locale.KOREA).format(Date(System.currentTimeMillis() + route.durationMs))
            say("${route.goal.name}까지 경로 안내를 시작합니다. 약 ${minutes(route.durationMs)}, $eta 도착 예정입니다.")
            loadCameras(route)
            startLoops()
            lastFix?.let { handleFix(it) }
        }
    }

    fun stop(reason: String? = null) {
        scope.launch { halt(reason) }
    }

    private fun halt(reason: String?) {
        loops?.cancel()
        loops = null
        tracker = null
        _state.update { NaviUi(message = reason, destination = it.destination) }
    }

    /** Adds [place] as a stop before the destination and routes again from here. */
    fun addStop(place: NaviPlace) {
        _state.update { it.copy(waypoints = (it.waypoints + place).take(15)) }
        reroute("경유지 ${place.name}을(를) 넣어 경로를 다시 찾았습니다.")
    }

    fun rerouteNow() {
        if (_state.value.guiding) reroute("경로를 다시 찾았습니다.")
    }

    fun onFix(fix: Location) {
        scope.launch { handleFix(fix) }
    }

    private fun handleFix(fix: Location) {
        val here = LatLon(fix.latitude, fix.longitude)
        val t = tracker
        // In and around tunnels the chip still reports fixes, wandering beside the road or running
        // straight on through a bend. While guiding, a vague one is no fix: the car's speed carries on.
        if (t != null && fix.hasAccuracy() && fix.accuracy > VAGUE_FIX_M) return
        lastFix = fix
        lastFixAt = System.currentTimeMillis()
        if (!CameraState.running.value) CameraState.fix = fix
        if (t == null || !_state.value.guiding) {
            _state.update { it.copy(position = here, bearing = fix.takeIf { it.hasBearing() }?.bearing, gpsOk = true) }
            return
        }
        val p = t.update(here, fix.takeIf { it.hasAccuracy() }?.accuracy?.toDouble())
        val speed = car.speedKmh ?: fix.takeIf { it.hasSpeed() }?.speed?.times(3.6)?.toDouble()
        afterProgress(t, p, speed, here, fix.takeIf { it.hasBearing() }?.bearing)
        if (p.offRoute && prefs.autoReroute && System.currentTimeMillis() - lastRerouteAt > 15_000) {
            say("경로를 벗어나 다시 찾습니다.")
            reroute("새 경로로 안내합니다.")
        }
    }

    fun onCar(feed: CarFeed) {
        scope.launch {
            val prev = car
            car = feed
            val power = feed.powerKw
            val speed = feed.speedKmh
            // Only while guiding, and only between two readings close in time.
            if (tracker != null && power != null && speed != null && prev.at > 0 && feed.at - prev.at in 1..10_000) {
                learner.observe(power, speed, (feed.at - prev.at) / 1000.0, feed.outsideC)
            }
        }
    }

    private fun afterProgress(t: RouteTracker, p: RouteProgress, speedKmh: Double?, here: LatLon?, bearing: Float?) {
        val route = t.route
        // A waypoint behind us is done; later reroutes go to the rest.
        val passed = lastGuideIndex
        if (passed != null && p.nextGuideIndex != passed && route.guides.getOrNull(passed)?.maneuver == Maneuver.WAYPOINT) {
            _state.update { it.copy(waypoints = it.waypoints.drop(1)) }
        }
        lastGuideIndex = p.nextGuideIndex
        val kwh = EnergyModel.kwhToGo(t, p.alongM, learner.factor, car.outsideC)
        val soc = EnergyModel.socAtArrival(car.socPercent, car.remainingKwh, car.fullKwh, kwh)
        val cams = _state.value.cameras
        _state.update {
            it.copy(
                progress = p,
                // On the route the car is drawn on the line, facing along the road, the way navi apps do.
                position = if (!p.offRoute) t.snapped() else here ?: it.position,
                bearing = if (!p.offRoute) t.heading().toFloat() else bearing ?: it.bearing,
                gpsOk = System.currentTimeMillis() - lastFixAt < 4000,
                kwhToGo = kwh,
                socAtArrival = soc,
                learnedWhPerKm = learner.whPerKm,
                nextCamera = RouteCameras.next(cams, p.alongM),
            )
        }
        announcer.next(route, p, speedKmh)?.let(::say)
        if (soc != null && soc < prefs.reserveSoc && !chargeChecked) {
            chargeChecked = true
            planCharging(t, p.alongM, soc)
        }
        if (p.arrived && !arriving) {
            arriving = true
            scope.launch {
                delay(15_000)
                if (tracker === t) halt("목적지에 도착했습니다")
            }
        }
    }

    private fun startLoops() {
        loops?.cancel()
        loops = scope.launch {
            launch {
                // Tunnels: no fix for a few seconds, so move on by the car's speed.
                var last = System.currentTimeMillis()
                while (isActive) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val t = tracker ?: continue
                    val speed = car.speedKmh ?: lastFix?.takeIf { it.hasSpeed() }?.speed?.times(3.6)?.toDouble()
                    if (now - lastFixAt > 3000 && speed != null && speed > 3) {
                        afterProgress(t, t.advance(speed / 3.6 * (now - last) / 1000.0), speed, null, null)
                    }
                    last = now
                }
            }
            launch {
                while (isActive) {
                    delay(5 * 60_000L)
                    if (prefs.fasterRoute) checkFaster()
                }
            }
        }
    }

    private fun reroute(done: String) {
        lastRerouteAt = System.currentTimeMillis()
        val s = _state.value
        val goal = s.destination ?: s.route?.goal ?: return
        val option = s.route?.option ?: prefs.option
        scope.launch {
            val from = bestPosition() ?: return@launch
            try {
                val r = api.directions(from, goal, s.waypoints, listOf(option)).firstOrNull() ?: return@launch
                replaceRoute(r)
                say(done)
            } catch (e: Exception) {
                fail("재탐색", e)
            }
        }
    }

    private suspend fun checkFaster() {
        val s = _state.value
        val t = tracker ?: return
        val left = s.progress?.remainingMs ?: return
        val goal = s.route?.goal ?: return
        val from = bestPosition() ?: return
        try {
            val r = api.directions(from, goal, s.waypoints, listOf(t.route.option)).firstOrNull() ?: return
            val saved = left - r.durationMs
            if (saved >= 180_000) {
                replaceRoute(r)
                say("교통 상황이 바뀌어 약 ${minutes(saved)} 빠른 길로 바꿨습니다.")
            }
        } catch (e: Exception) {
            log("빠른 길 확인 실패: ${e.message}")
        }
    }

    private fun replaceRoute(r: NaviRoute) {
        val t = RouteTracker(r)
        tracker = t
        announcer.reset()
        lastGuideIndex = null
        chargeChecked = false
        arriving = false
        _state.update { it.copy(route = r, chargeStops = emptyList(), chargeNote = null) }
        loadCameras(r)
        val fix = lastFix
        if (fix != null) handleFix(fix) else afterProgress(t, t.progress(), car.speedKmh, null, null)
    }

    // ── Charging ──

    private fun planCharging(t: RouteTracker, alongM: Double, soc: Double) {
        scope.launch {
            val spots = ArrayList<ChargerSpot>()
            car.superchargers.forEach { c ->
                if (!c.closed) spots += ChargerSpot("${c.name} 슈퍼차저", LatLon(c.latitude, c.longitude), true, c.maxPowerKw, c.availableStalls, c.totalStalls, "Tesla", c.address)
            }
            val key = prefs.evKey.ifBlank { CameraRepository(app).apiKey }
            if (key.isNotBlank() && prefs.hasMaps) {
                try {
                    val codes = ChargePlanner.searchPoints(t, alongM).mapNotNull { api.legalCode(it)?.take(5) }.distinct()
                    codes.forEach { spots += EvChargerApi.stations(key, it) }
                } catch (e: Exception) {
                    log("충전소 검색 실패: ${e.message}")
                }
            }
            val stops = ChargePlanner.plan(t, alongM, spots, car.socPercent, car.fullKwh, learner.factor, car.outsideC)
            val note = when {
                stops.isNotEmpty() -> "도착 배터리 ${soc.roundToInt()}% 예상, 경로 근처 충전소 ${stops.size}곳"
                spots.isEmpty() -> "도착 배터리 ${soc.roundToInt()}% 예상. 충전소 정보가 없습니다(차량 BLE 연결 · 충전소 API 키 확인)"
                else -> "도착 배터리 ${soc.roundToInt()}% 예상, 경로 근처에 들를 만한 충전소가 없습니다"
            }
            _state.update { it.copy(chargeStops = stops, chargeNote = note) }
            say(if (stops.isNotEmpty()) "도착 배터리가 ${soc.roundToInt()}퍼센트로 예상됩니다. 경로 근처 충전소를 찾았습니다. 화면에서 경유지로 넣을 수 있어요." else "도착 배터리가 ${soc.roundToInt()}퍼센트로 예상됩니다.")
        }
    }

    // ── Helpers ──

    private fun arrivalSoc(t: RouteTracker, alongM: Double): Double? =
        EnergyModel.socAtArrival(car.socPercent, car.remainingKwh, car.fullKwh, EnergyModel.kwhToGo(t, alongM, learner.factor, car.outsideC))

    private fun loadCameras(route: NaviRoute) {
        scope.launch {
            try {
                val index = cameraIndex ?: CameraRepository(app).load().takeIf { it.isNotEmpty() }?.let(::CameraIndex)?.also { cameraIndex = it }
                    ?: return@launch
                val cams = withContext(Dispatchers.Default) { RouteCameras.along(RouteGeometry(route.path), index) }
                if (_state.value.route === route) _state.update { it.copy(cameras = cams) }
            } catch (e: Exception) {
                log("경로 위 카메라 계산 실패: ${e.message}")
            }
        }
    }

    /** Freshest of: this guidance's GPS, the camera service's GPS, the car's own position, the phone's last known spot. */
    fun bestPosition(): LatLon? {
        val now = System.currentTimeMillis()
        lastFix?.takeIf { now - lastFixAt < 120_000 }?.let { return LatLon(it.latitude, it.longitude) }
        CameraState.fix?.takeIf { now - it.time < 120_000 }?.let { return LatLon(it.latitude, it.longitude) }
        car.position?.let { return it }
        return try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { LatLon(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun say(text: String) {
        _state.update { it.copy(lastSpoken = text) }
        if (prefs.voice) _speech.trySend(text)
    }

    private fun fail(what: String, e: Exception) {
        val msg = if (e is NaviApiException) e.message ?: what else "$what 실패: ${e.message ?: e.javaClass.simpleName}"
        log("길안내 $msg")
        _state.update { it.copy(message = msg) }
    }

    private val SEOUL_CITY_HALL = LatLon(37.5663, 126.9779)

    /** Fixes vaguer than this are ignored while guiding; tunnels and their mouths produce them. */
    private const val VAGUE_FIX_M = 60f

    fun minutes(ms: Long): String {
        val m = (ms / 60_000.0).roundToInt().coerceAtLeast(1)
        return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
    }
}
