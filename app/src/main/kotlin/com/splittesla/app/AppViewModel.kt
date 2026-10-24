package com.splittesla.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.splittesla.app.assistant.DriveAssistant
import com.splittesla.app.assistant.RouteSync
import com.splittesla.app.ble.CommanderLink
import com.splittesla.app.camera.CameraRepository
import com.splittesla.app.logbook.LogbookController
import com.splittesla.app.camera.CameraState
import com.splittesla.app.camera.SpeedCameraService
import com.splittesla.app.ble.TeslaLink
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.weather.Weather
import com.splittesla.app.weather.WeatherRepository
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderSession
import com.splittesla.core.s3xy.CommanderStatus
import com.splittesla.core.s3xy.SwitchType
import com.splittesla.core.s3xy.VehicleData
import com.splittesla.core.tesla.ChargingSite
import com.splittesla.core.tesla.NavigationInfo.Companion.odometerKm
import com.splittesla.core.tesla.NavigationInfo
import com.splittesla.core.tesla.StateCategory
import com.splittesla.core.tesla.TeslaClient
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec
import kotlinx.coroutines.CancellationException
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LinkState(val label: String) {
    DISCONNECTED("연결 안 됨"), CONNECTING("연결 중"), NEEDS_PAIRING("페어링 필요"), CONNECTED("연결됨"), FAILED("실패")
}

/** What to re-read from the car after a command so the screen shows the new state. */
enum class Refresh { NONE, STATUS, CHARGE, CLIMATE }

data class UiState(
    val commander: LinkState = LinkState.DISCONNECTED,
    val car: LinkState = LinkState.DISCONNECTED,
    val live: VehicleData = VehicleData(),
    val pushCount: Int = 0,
    val vehicleStatus: Vcsec.VehicleStatus? = null,
    val charge: Vehicle.ChargeState? = null,
    val climate: Vehicle.ClimateState? = null,
    val stateText: String = "",
    val busy: String? = null,
    val toast: String? = null,
    val logRawPushes: Boolean = false,
    val vin: String = "",
    /** The car has accepted this phone's key. */
    val keyPaired: Boolean = false,
    /** Waiting for the driver to tap the key card and approve on the car's screen. */
    val keyPairing: Boolean = false,
    val favorites: Set<CommanderAction> = emptySet(),
    val nav: NavigationInfo? = null,
    val navUpdatedAt: Long? = null,
    val navAutoRefresh: Boolean = false,
    val chargingSites: List<ChargingSite> = emptyList(),
    val cameraCount: Int = 0,
    val cameraUpdatedAt: Long = 0,
    val cameraApiKey: String = "",
    /** Result of the last speed camera API connection test. */
    val cameraTest: String? = null,
    val weather: Weather? = null,
    val gaugeStyle: GaugeStyle = GaugeStyle.TRACK,
    val demo: Boolean = false,
)

/**
 * Holds both BLE links. The split: the Commander streams live CAN data and
 * performs actions the official protocol lacks; the car's own BLE handles
 * charging, climate, locks and state queries.
 *
 * Every command connects its link on demand, so a button never sits disabled
 * just because the user has not tapped the connection chip first.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val cameras = CameraRepository(app)
    private val weatherRepo = WeatherRepository(app)
    private val _ui = MutableStateFlow(
        UiState(
            vin = prefs.vin,
            keyPaired = prefs.keyPaired,
            favorites = prefs.favorites,
            cameraCount = cameras.count,
            cameraUpdatedAt = cameras.updatedAt,
            cameraApiKey = cameras.apiKey,
            gaugeStyle = prefs.gaugeStyle,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    val logbook = LogbookController(app, ::log)

    private var commander: CommanderLink? = null
    private var car: TeslaLink? = null
    private val carMutex = Mutex()
    private val commanderMutex = Mutex()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val teslaPublicKeyHex: String by lazy { prefs.teslaKey.publicKeyBytes.joinToString("") { "%02x".format(it) } }

    /** Assistant: spoken driving notes and typed commands. */
    val assistant = DriveAssistant(this, viewModelScope)

    /** Sends the car's destination to the phone's navigation app when it changes. */
    val routeSync = RouteSync(this, viewModelScope).also { it.start() }

    /** True while the app is on screen, including split screen beside a map. */
    var isForeground = false
        private set
    private val _multiWindow = MutableStateFlow(false)
    /** Split screen (or a pop-up window) beside another app, usually the navi. */
    val multiWindow: StateFlow<Boolean> = _multiWindow.asStateFlow()
    var inMultiWindow: Boolean
        get() = _multiWindow.value
        set(value) { _multiWindow.value = value }

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())} $line"
        _log.update { (it + stamped).takeLast(1000) }
    }

    fun consumeToast() = _ui.update { it.copy(toast = null) }

    fun toast(text: String) = _ui.update { it.copy(toast = text) }

    fun setVin(vin: String) {
        val changed = vin != prefs.vin
        prefs.vin = vin
        _ui.update { it.copy(vin = prefs.vin, toast = "✅ VIN을 저장했습니다") }
        // A different car needs a fresh connection.
        if (changed && car != null) disconnectCar()
    }

    fun setGaugeStyle(style: GaugeStyle) {
        prefs.gaugeStyle = style
        _ui.update { it.copy(gaugeStyle = style) }
    }

    fun setDemo(on: Boolean) = _ui.update { it.copy(demo = on) }

    // ── Weather ──

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshWeatherNow()
                delay(15 * 60_000L)
            }
        }
    }

    fun refreshWeather() {
        viewModelScope.launch { refreshWeatherNow() }
    }

    private suspend fun refreshWeatherNow() {
        val nav = _ui.value.nav
        val (lat, lon) = if (nav?.latitude != null && nav.longitude != null) {
            nav.latitude!! to nav.longitude!!
        } else {
            weatherRepo.lastKnownLocation()?.let { it.latitude to it.longitude } ?: run {
                log("날씨: 위치를 아직 모릅니다")
                return
            }
        }
        try {
            val w = weatherRepo.fetch(lat, lon)
            _ui.update { it.copy(weather = w) }
        } catch (e: Exception) {
            log("날씨 받기 실패: ${e.message}")
        }
    }

    fun setLogRawPushes(on: Boolean) = _ui.update { it.copy(logRawPushes = on) }

    fun toggleFavorite(action: CommanderAction) {
        val next = _ui.value.favorites.let { if (action in it) it - action else it + action }
        prefs.favorites = next
        _ui.update { it.copy(favorites = next) }
    }

    // ── Auto connect ──

    /** Set when the user taps a link off; auto connect leaves that link alone until they tap it on. */
    private var commanderOff = false
    private var carOff = false
    private var autoJob: Job? = null

    /**
     * Connects both links as soon as the app opens and reconnects them after a
     * drop, while the app is on screen. Runs outside [run] so it never shows
     * as busy or blocks a button; a tap simply joins the same connection.
     */
    fun startAutoConnect() {
        if (autoJob?.isActive == true) return
        autoJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                if (isForeground) {
                    val s = _ui.value
                    val idle = setOf(LinkState.DISCONNECTED, LinkState.FAILED)
                    val tries = buildList {
                        if (!commanderOff && s.commander in idle) add(launch { autoTry("S3XY") { ensureCommander() } })
                        if (!carOff && prefs.vin.length == 17 && s.car in idle) add(launch { autoTry("차량") { ensureCar() } })
                    }
                    tries.forEach { it.join() }
                    val failed = _ui.value.commander == LinkState.FAILED || _ui.value.car == LinkState.FAILED
                    failures = if (tries.isNotEmpty() && failed) failures + 1 else 0
                }
                // Retry quickly at first, then ease off while the car is out of range:
                // 15 s, 30 s, 1 min, 2 min, then every 3 min. Scanning disturbs
                // Bluetooth audio and the hotspot, so a car that isn't there
                // should not be searched for nonstop.
                delay(minOf(15_000L shl minOf(failures, 4), 180_000L))
            }
        }
    }

    fun setForeground(on: Boolean) {
        isForeground = on
    }

    private suspend fun autoTry(name: String, block: suspend () -> Unit) {
        try {
            block()
            log("$name 자동 연결됨")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$name 자동 연결 실패: ${e.message}")
        }
    }

    // ── Commander ──

    fun connectCommander() {
        commanderOff = false
        run("S3XY 연결", silent = true) { ensureCommander() }
    }

    private suspend fun ensureCommander(): CommanderSession = commanderMutex.withLock {
        commander?.session?.takeIf { _ui.value.commander == LinkState.CONNECTED } ?: connectCommanderNow()
    }

    private suspend fun connectCommanderNow(): CommanderSession {
        _ui.update { it.copy(commander = LinkState.CONNECTING) }
        try {
            commander?.disconnect()
            val link = CommanderLink(
                context = getApplication(),
                phoneUuid = prefs.commanderPhoneUuid,
                onData = { delta ->
                    delta.speedKmh?.let {
                        CameraState.commanderSpeedKmh = it
                        CameraState.commanderSpeedAt = System.currentTimeMillis()
                    }
                    queueLive(delta)
                },
                onRawPush = { bytes -> if (_ui.value.logRawPushes) log("PUSH ${bytes.hex()}") },
                onDisconnect = {
                    log("Commander 연결 끊김")
                    _ui.update { it.copy(commander = LinkState.DISCONNECTED) }
                },
                log = ::log,
            )
            commander = link
            link.connect()
            val session = link.session ?: error("Commander 세션을 열지 못했습니다")
            registerAndSubscribe(session)
            return session
        } catch (e: Exception) {
            _ui.update { it.copy(commander = LinkState.FAILED) }
            throw e
        }
    }

    /**
     * Registers the phone UUID. A new phone is rejected until the driver
     * double-presses the right scroll button, so keep retrying for a minute.
     */
    private suspend fun registerAndSubscribe(session: CommanderSession) {
        var status = session.sendUuid()
        val wasNew = status != CommanderStatus.SUCCESS
        if (wasNew) {
            log("Commander가 이 폰을 모릅니다 ($status). 핸들 오른쪽 스크롤 버튼을 두 번 누르세요")
            _ui.update { it.copy(commander = LinkState.NEEDS_PAIRING, toast = "핸들 오른쪽 스크롤 버튼을 두 번 누르세요") }
            val deadline = System.currentTimeMillis() + 60_000
            while (status != CommanderStatus.SUCCESS && System.currentTimeMillis() < deadline) {
                delay(3000)
                status = session.sendUuid()
            }
            if (status != CommanderStatus.SUCCESS) error("Commander 페어링 시간 초과")
        }
        log("Commander 등록 완료")
        val sub = session.subscribe()
        log("실시간 데이터 구독: $sub")
        _ui.update {
            it.copy(commander = LinkState.CONNECTED, toast = if (wasNew) "✅ S3XY Commander에 이 폰을 등록했습니다" else it.toast)
        }
    }

    // The Commander streams many CAN frames a second. Redrawing the screen for
    // each one kept the phone busy enough to stutter video, so pushes are
    // merged and shown at most four times a second.
    private val liveLock = Any()
    private var pendingLive: VehicleData? = null
    private var pendingPushes = 0
    private var liveFlush: Job? = null

    private fun queueLive(delta: VehicleData) {
        synchronized(liveLock) {
            pendingLive = pendingLive?.let { delta.mergedOnto(it) } ?: delta
            pendingPushes++
            if (liveFlush?.isActive == true) return
            liveFlush = viewModelScope.launch {
                delay(250)
                val (live, n) = synchronized(liveLock) {
                    val out = pendingLive to pendingPushes
                    pendingLive = null
                    pendingPushes = 0
                    out
                }
                if (live != null) _ui.update { it.copy(live = live.mergedOnto(it.live), pushCount = it.pushCount + n) }
            }
        }
    }

    fun disconnectCommander() {
        commanderOff = true
        commander?.disconnect()
        commander = null
        _ui.update { it.copy(commander = LinkState.DISCONNECTED) }
    }

    fun commanderAction(
        action: CommanderAction,
        label: String = action.name,
        value: Long? = null,
        switchType: SwitchType = SwitchType.Toggle,
    ) = run(label) {
        val status = ensureCommander().tryAction(action, switchType, listOf(value ?: 1L))
        log("S3XY ${action.name} → $status")
        if (status != CommanderStatus.SUCCESS) error("Commander 응답 $status")
    }

    // ── Car ──

    fun connectCar() {
        carOff = false
        run("차량 연결", silent = true) { ensureCar() }
    }

    private suspend fun ensureCar(): TeslaClient = carMutex.withLock {
        car?.client?.takeIf { _ui.value.car == LinkState.CONNECTED } ?: connectCarNow()
    }

    private suspend fun connectCarNow(): TeslaClient {
        val vin = prefs.vin
        if (vin.length != 17) error("설정 탭에서 17자리 VIN을 먼저 저장하세요")
        _ui.update { it.copy(car = LinkState.CONNECTING) }
        try {
            car?.disconnect()
            val link = TeslaLink(getApplication(), vin, prefs.teslaKey, onDisconnect = {
                log("차량 BLE 연결 끊김")
                _ui.update { it.copy(car = LinkState.DISCONNECTED) }
            }, log = ::log)
            car = link
            link.connect()
            val client = link.client ?: error("차량 BLE 세션을 열지 못했습니다")
            _ui.update { it.copy(car = LinkState.CONNECTED) }
            checkKey(client)
            loadCarState(client)
            return client
        } catch (e: Exception) {
            _ui.update { it.copy(car = LinkState.FAILED) }
            throw e
        }
    }

    /** Fills the car screen right after connecting. Charge and climate need an enrolled key. */
    private fun loadCarState(client: TeslaClient) {
        viewModelScope.launch {
            try {
                val status = client.vehicleStatus()
                _ui.update { it.copy(vehicleStatus = status) }
                odometerKm(client.state(StateCategory.DRIVE).driveState)?.let(logbook::onOdometer)
                refresh(client, Refresh.CHARGE)
                refresh(client, Refresh.CLIMATE)
            } catch (e: Exception) {
                log("차량 상태 읽기 실패: ${e.message}")
            }
        }
    }

    private suspend fun refresh(client: TeslaClient, what: Refresh) {
        when (what) {
            Refresh.NONE -> Unit
            Refresh.STATUS -> client.vehicleStatus().let { s -> _ui.update { it.copy(vehicleStatus = s) } }
            Refresh.CHARGE -> client.state(StateCategory.CHARGE).chargeState.let { s ->
                _ui.update { it.copy(charge = s) }
                logbook.onChargeState(s)
            }
            Refresh.CLIMATE -> client.state(StateCategory.CLIMATE).climateState.let { s -> _ui.update { it.copy(climate = s) } }
        }
    }

    fun disconnectCar() {
        carOff = true
        car?.disconnect()
        car = null
        _ui.update { it.copy(car = LinkState.DISCONNECTED) }
    }

    fun sendAddKeyRequest() = withCar("키 등록 요청", silent = true) {
        it.sendAddKeyRequest()
        log("키 등록 요청을 보냈습니다. 센터 콘솔에 키 카드를 대고 화면에서 승인하세요")
        _ui.update { s -> s.copy(toast = "키 카드를 센터 콘솔에 대고 차량 화면에서 승인하세요", keyPairing = true) }
        watchKeyApproval(it)
    }

    private var keyJob: Job? = null

    /** Checks every few seconds for two minutes whether the driver approved the key on the car. */
    private fun watchKeyApproval(client: TeslaClient) {
        keyJob?.cancel()
        keyJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 120_000
            try {
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(4000)
                    val paired = try {
                        client.isKeyPaired()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("키 등록 확인 중 오류: ${e.message}")
                        false
                    }
                    if (paired) {
                        onKeyPaired(true)
                        return@launch
                    }
                }
                _ui.update { it.copy(toast = "아직 키 등록이 확인되지 않았습니다. 승인 후 '등록 확인'을 눌러 보세요") }
            } finally {
                _ui.update { it.copy(keyPairing = false) }
            }
        }
    }

    private fun onKeyPaired(announce: Boolean) {
        val before = prefs.keyPaired
        prefs.keyPaired = true
        log("차량이 이 폰의 키를 인식했습니다")
        _ui.update {
            it.copy(
                keyPaired = true,
                keyPairing = false,
                toast = if (announce || !before) "✅ 테슬라 키 등록 완료! 이제 이 폰으로 차를 제어할 수 있습니다" else it.toast,
            )
        }
    }

    /** Learns the key state quietly after each connection, so the settings screen stays right. */
    private fun checkKey(client: TeslaClient) {
        viewModelScope.launch {
            try {
                if (client.isKeyPaired()) {
                    onKeyPaired(announce = false)
                } else {
                    prefs.keyPaired = false
                    _ui.update { it.copy(keyPaired = false) }
                }
            } catch (e: Exception) {
                log("키 등록 상태 확인 실패: ${e.message}")
            }
        }
    }

    /** "등록 확인" button: asks the car whether it knows this phone's key. */
    fun verifyKey() = withCar("키 등록 확인", silent = true) {
        if (it.isKeyPaired()) {
            onKeyPaired(announce = true)
        } else {
            prefs.keyPaired = false
            _ui.update { s -> s.copy(keyPaired = false, toast = "아직 등록되지 않았습니다. '키 등록 요청' 후 키 카드를 대고 승인하세요") }
        }
    }

    fun refreshCar() = withCar("차량 상태", silent = true) {
        refresh(it, Refresh.STATUS)
        try {
            refresh(it, Refresh.CHARGE)
            refresh(it, Refresh.CLIMATE)
        } catch (e: Exception) {
            log("충전·공조 상태 읽기 실패: ${e.message}")
        }
    }

    fun carCommand(label: String, refresh: Refresh = Refresh.NONE, block: suspend (TeslaClient) -> Unit) =
        withCar(label) {
            block(it)
            log("$label 완료")
            if (refresh != Refresh.NONE) {
                // Give the car a moment to apply the change before reading it back.
                delay(1500)
                try {
                    refresh(it, refresh)
                } catch (e: Exception) {
                    log("상태 갱신 실패: ${e.message}")
                }
            }
        }

    fun queryState(category: StateCategory) = withCar("${category.name} 조회", silent = true) {
        val data = it.state(category)
        _ui.update { s -> s.copy(stateText = data.toString().ifBlank { "(비어 있음)" }) }
    }

    // ── Navigation ──

    private var navJob: Job? = null

    fun refreshNavigation() = withCar("내비 정보", silent = true) { fetchNavigation(it) }

    private suspend fun fetchNavigation(client: TeslaClient) {
        val nav = client.navigation()
        _ui.update { it.copy(nav = nav, navUpdatedAt = System.currentTimeMillis()) }
        nav.odometerKm?.let(logbook::onOdometer)
    }

    /** Polls the route every 10 s while on; the car does not push route changes over BLE. */
    fun setNavAutoRefresh(on: Boolean) {
        _ui.update { it.copy(navAutoRefresh = on) }
        navJob?.cancel()
        if (!on) return
        navJob = viewModelScope.launch {
            while (isActive) {
                val client = car?.client
                if (client != null && _ui.value.car == LinkState.CONNECTED && _ui.value.busy == null) {
                    try {
                        fetchNavigation(client)
                    } catch (e: Exception) {
                        log("내비 자동 갱신 실패: ${e.message}")
                    }
                }
                delay(10_000)
            }
        }
    }

    fun findChargingSites() = withCar("주변 슈퍼차저", silent = true) {
        val sites = it.nearbyChargingSites()
        _ui.update { s -> s.copy(chargingSites = sites) }
        log("주변 슈퍼차저 ${sites.size}곳")
    }

    // ── Logbook ──

    init {
        // The car does not push charge state over BLE, so poll it while a cable
        // is in; that is how a finished charge lands in the logbook.
        viewModelScope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                val client = car?.client
                val plugged = _ui.value.charge?.chargingState?.typeCase?.name !in setOf(null, "DISCONNECTED", "TYPE_NOT_SET")
                if (client != null && plugged && _ui.value.car == LinkState.CONNECTED && _ui.value.busy == null) {
                    try {
                        refresh(client, Refresh.CHARGE)
                    } catch (e: Exception) {
                        log("충전 상태 자동 갱신 실패: ${e.message}")
                    }
                }
            }
        }
    }

    /** Reads the odometer so a new logbook entry starts with the right number. */
    fun refreshOdometer() = withCar("주행거리 읽기", silent = true) {
        odometerKm(it.state(StateCategory.DRIVE).driveState)?.let(logbook::onOdometer) ?: error("차량이 주행거리를 보내지 않았습니다")
    }

    fun exportLogbook(uri: Uri) = run("차계부 내보내기") { logbook.exportCsv(uri) }

    // ── Speed cameras ──

    fun importCameras(uri: Uri) = run("카메라 데이터 가져오기", silent = true) {
        onCamerasLoaded(cameras.importCsv(uri))
    }

    fun downloadCameras(key: String) = run("카메라 데이터 받기", silent = true) {
        cameras.apiKey = key
        _ui.update { it.copy(cameraApiKey = cameras.apiKey) }
        onCamerasLoaded(cameras.download(cameras.apiKey))
    }

    /** Saves the key and asks the API for one row, to show whether the key works. */
    fun testCameraKey(key: String) = run("카메라 API 연결 테스트", silent = true) {
        cameras.apiKey = key
        _ui.update { it.copy(cameraApiKey = cameras.apiKey, cameraTest = "연결 확인 중…") }
        val result = try {
            val total = cameras.test(cameras.apiKey)
            log("카메라 API 연결 테스트 성공: 전국 ${total}개")
            "✅ 연결 성공 · 전국 카메라 ${total}개 조회 가능"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("카메라 API 연결 테스트 실패: ${e.message}")
            "❌ 연결 실패: ${e.message}"
        }
        _ui.update { it.copy(cameraTest = result, toast = result) }
    }

    private fun onCamerasLoaded(n: Int) {
        log("과속 카메라 ${n}개 저장")
        _ui.update { it.copy(cameraCount = n, cameraUpdatedAt = cameras.updatedAt, toast = "✅ 과속 카메라 ${n}개를 등록했습니다") }
        // A running service keeps its old list; restart it to pick up the new one.
        if (CameraState.running.value) {
            SpeedCameraService.stop(getApplication())
            SpeedCameraService.start(getApplication())
        }
    }

    fun setCameraAlerts(on: Boolean) {
        if (!on) {
            prefs.cameraAlerts = false
            SpeedCameraService.stop(getApplication())
        } else if (cameras.count == 0) {
            _ui.update { it.copy(toast = "먼저 내비 탭에서 공공데이터 카메라 데이터를 받으세요") }
        } else {
            prefs.cameraAlerts = true
            SpeedCameraService.start(getApplication())
        }
    }

    /** Starts the alerts again on app start when they were left on, so the dashboard has camera data. */
    fun resumeCameraAlerts(locationAllowed: Boolean) {
        if (prefs.cameraAlerts && locationAllowed && cameras.count > 0 && !CameraState.running.value) {
            try {
                SpeedCameraService.start(getApplication())
            } catch (e: Exception) {
                log("과속카메라 알림 다시 켜기 실패: ${e.message}")
            }
        }
    }

    private fun withCar(label: String, silent: Boolean = false, block: suspend (TeslaClient) -> Unit) =
        run(label, silent) { block(ensureCar()) }

    /** Runs one user action, showing progress and a short result message. */
    private fun run(label: String, silent: Boolean = false, block: suspend () -> Unit) {
        if (_ui.value.busy != null) {
            _ui.update { it.copy(toast = "'${it.busy}' 처리 중입니다") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = label) }
            try {
                block()
                if (!silent) _ui.update { it.copy(toast = "✓ $label") }
            } catch (e: Exception) {
                log("$label 실패: ${e.message}")
                _ui.update { it.copy(toast = "$label 실패: ${e.message}") }
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    override fun onCleared() {
        assistant.shutdown()
        commander?.disconnect()
        car?.disconnect()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
