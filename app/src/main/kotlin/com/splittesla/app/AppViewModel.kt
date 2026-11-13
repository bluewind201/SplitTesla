package com.splittesla.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.splittesla.app.assistant.DriveAssistant
import com.splittesla.app.camera.CameraRepository
import com.splittesla.app.camera.GpsFix
import com.splittesla.app.camera.PhoneGps
import com.splittesla.app.logbook.PaymentMessages
import com.splittesla.app.logbook.PlaceInfo
import com.splittesla.app.ble.CommanderLink
import com.splittesla.app.logbook.LogbookController
import com.splittesla.app.overlay.DashboardOverlay
import com.splittesla.app.ble.TeslaLink
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.weather.Weather
import com.splittesla.app.weather.RegionRepository
import com.splittesla.app.weather.WeatherRepository
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.camera.CameraAlerter
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.camera.CameraStatus
import com.splittesla.core.camera.spokenText
import com.splittesla.core.drive.SpeedOrigin
import com.splittesla.core.drive.SpeedReading
import com.splittesla.core.drive.SpeedSource
import com.splittesla.core.logbook.DriveSample
import com.splittesla.core.logbook.HealthReading
import com.splittesla.core.logbook.MessageSource
import com.splittesla.core.s3xy.VehicleSignal
import com.splittesla.core.tesla.CarDrive
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import com.splittesla.app.update.AppUpdater
import com.splittesla.app.update.UpdateController
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
    val weather: Weather? = null,
    val gaugeStyle: GaugeStyle = GaugeStyle.TRACK,
    /** Show the dashboard over other apps while this app is in the background. */
    val overlay: Boolean = true,
    val demo: Boolean = false,
    /** District and neighbourhood, such as "분당구 정자동". */
    val region: String? = null,
    /** The car's own drive state over BLE (speed, gear, power), polled. */
    val carDrive: CarDrive? = null,
    /** When the Commander last pushed a speed. */
    val canSpeedAt: Long? = null,
    /** The phone's last GPS fix. */
    val gps: GpsFix? = null,
    /** The speed and gear on the gauge, and where they came from. Re-resolved every second. */
    val speed: SpeedReading = SpeedReading(null, SpeedOrigin.NONE, null),
    /** Speed camera ahead, or the section being driven. */
    val camera: CameraStatus = CameraStatus(),
    val cameraEnabled: Boolean = true,
    val cameraVoice: Boolean = true,
    val cameraCount: Int = 0,
    /** Only the built-in seed list is loaded. */
    val cameraSeed: Boolean = true,
    val cameraUpdatedAt: Long = 0,
    val cameraApiKey: String = "",
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
    private val weatherRepo = WeatherRepository(app)
    private val regions = RegionRepository(app)
    private val cameras = CameraRepository(app)
    private val _ui = MutableStateFlow(
        UiState(
            vin = prefs.vin,
            keyPaired = prefs.keyPaired,
            favorites = prefs.favorites,
            gaugeStyle = prefs.gaugeStyle,
            overlay = prefs.overlay,
            cameraEnabled = cameras.enabled,
            cameraVoice = cameras.voice,
            cameraSeed = cameras.usingSeed,
            cameraUpdatedAt = cameras.updatedAt,
            cameraApiKey = cameras.apiKey,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    val logbook: LogbookController = LogbookController.get(app).also { lb ->
        lb.log = { line -> log(line) }
        lb.placeProvider = {
            val s = _ui.value
            val p = position()
            PlaceInfo(s.region ?: s.nav?.locationName, p?.lat, p?.lon)
        }
    }

    private var commander: CommanderLink? = null
    private var car: TeslaLink? = null
    private val carMutex = Mutex()
    private val commanderMutex = Mutex()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val teslaPublicKeyHex: String by lazy { prefs.teslaKey.publicKeyBytes.joinToString("") { "%02x".format(it) } }

    /** Assistant: spoken driving notes and typed commands. */
    val assistant = DriveAssistant(this, viewModelScope)

    init {
        viewModelScope.launch {
            while (isActive) {
                position()?.let { p -> regions.nameAt(p)?.let { r -> _ui.update { it.copy(region = r) } } }
                delay(60_000)
            }
        }
    }

    /** Where the car is: its own GPS over BLE when known, else the phone's last fix. */
    private fun position(): LatLon? {
        val s = _ui.value
        val now = System.currentTimeMillis()
        s.gps?.takeIf { now - it.atMillis < 30_000 }?.let { return LatLon(it.latitude, it.longitude) }
        val cd = s.carDrive
        val cLat = cd?.latitude
        val cLon = cd?.longitude
        if (cLat != null && cLon != null) return LatLon(cLat, cLon)
        val nav = s.nav
        val lat = nav?.latitude
        val lon = nav?.longitude
        if (lat != null && lon != null) return LatLon(lat, lon)
        return weatherRepo.lastKnownLocation()?.let { LatLon(it.latitude, it.longitude) }
    }

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

    /** Self-update from the GitHub latest-apk release; installs only on screen and parked. */
    val updater: UpdateController = run {
        AppUpdater.log = { line -> log(line) }
        UpdateController(app, viewModelScope) {
            isForeground && (_ui.value.speed.kmh ?: 0f) < 3f
        }
    }

    fun setVin(vin: String) {
        val changed = vin != prefs.vin
        prefs.vin = vin
        if (changed) prefs.carAddress = ""
        _ui.update { it.copy(vin = prefs.vin, toast = "✅ VIN을 저장했습니다") }
        // A different car needs a fresh connection.
        if (changed) {
            car?.disconnect()
            car = null
            _ui.update { it.copy(car = LinkState.DISCONNECTED, carDrive = null) }
            carOff = false
            carFailures = 0
            carWake.trySend(Unit)
        }
    }

    fun setGaugeStyle(style: GaugeStyle) {
        prefs.gaugeStyle = style
        _ui.update { it.copy(gaugeStyle = style) }
    }

    fun setOverlay(on: Boolean) {
        prefs.overlay = on
        _ui.update { it.copy(overlay = on) }
        DashboardOverlay.sync(getApplication(), on)
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

    // ── Auto connect & continuous retry ──

    /** Set when the user explicitly taps a connected link off; cleared when they tap it on. */
    @Volatile
    private var commanderOff = false
    @Volatile
    private var carOff = false
    @Volatile
    private var commanderFailures = 0
    @Volatile
    private var carFailures = 0
    @Volatile
    private var lastCommanderPushAt = 0L
    @Volatile
    private var carPollErrors = 0

    private val commanderWake = Channel<Unit>(Channel.CONFLATED)
    private val carWake = Channel<Unit>(Channel.CONFLATED)
    private var commanderAutoJob: Job? = null
    private var carAutoJob: Job? = null

    /**
     * Continuously keeps both BLE links connected and immediately retries when
     * a link drops or when a manual connection attempt fails. Each link runs in
     * its own coroutine so S3XY and Car BLE never block each other.
     */
    fun startAutoConnect() {
        if (commanderAutoJob?.isActive != true) {
            commanderAutoJob = viewModelScope.launch {
                while (isActive) {
                    val active = isForeground || overlayShown || _ui.value.overlay
                    val state = _ui.value.commander
                    // Dead-link watchdog: if S3XY is marked CONNECTED but sent nothing for 18s, reconnect.
                    if (state == LinkState.CONNECTED && lastCommanderPushAt > 0L &&
                        System.currentTimeMillis() - lastCommanderPushAt > 18_000L
                    ) {
                        log("Commander 응답 없음 (18초 초과) — 연결 재설정")
                        commander?.disconnect()
                        commander = null
                        clearLive()
                        _ui.update { it.copy(commander = LinkState.DISCONNECTED) }
                        resolveSpeed()
                        commanderFailures = 0
                    }
                    if (!commanderOff && active && _ui.value.commander in setOf(LinkState.DISCONNECTED, LinkState.FAILED)) {
                        if (commanderFailures > 0) delay(1_200L) // brief pause after a drop before grabbing GATT
                        val ok = autoTry("S3XY") { ensureCommander() }
                        commanderFailures = if (ok) 0 else commanderFailures + 1
                    }
                    val waitMs = if (_ui.value.commander == LinkState.CONNECTED) {
                        5_000L
                    } else {
                        retryDelayMs(commanderFailures, active)
                    }
                    withTimeoutOrNull(waitMs) { commanderWake.receive() }
                }
            }
        }
        if (carAutoJob?.isActive != true) {
            carAutoJob = viewModelScope.launch {
                while (isActive) {
                    val active = isForeground || overlayShown || _ui.value.overlay
                    if (!carOff && prefs.vin.length == 17 && active && _ui.value.car in setOf(LinkState.DISCONNECTED, LinkState.FAILED)) {
                        if (carFailures > 0) delay(1_200L)
                        val ok = autoTry("차량 BLE") { ensureCar() }
                        carFailures = if (ok) 0 else carFailures + 1
                    }
                    val waitMs = if (_ui.value.car == LinkState.CONNECTED) {
                        5_000L
                    } else {
                        retryDelayMs(carFailures, active)
                    }
                    withTimeoutOrNull(waitMs) { carWake.receive() }
                }
            }
        }
        commanderWake.trySend(Unit)
        carWake.trySend(Unit)
    }

    /** Retry schedule: 2s -> 4s -> 6s -> 10s -> 15s while active (30s in background). */
    private fun retryDelayMs(failures: Int, active: Boolean): Long {
        if (!active) return 30_000L
        return when (failures) {
            0 -> 2_000L
            1 -> 3_000L
            2 -> 5_000L
            3 -> 8_000L
            4 -> 12_000L
            else -> 15_000L
        }
    }

    fun setForeground(on: Boolean) {
        val was = isForeground
        isForeground = on
        if (on && !was) {
            commanderFailures = 0
            carFailures = 0
            commanderWake.trySend(Unit)
            carWake.trySend(Unit)
            updater.onForeground()
        }
    }

    /** Set by the overlay service while the floating dashboard is on screen. */
    @Volatile
    var overlayShown = false

    private suspend fun autoTry(name: String, block: suspend () -> Unit): Boolean =
        try {
            block()
            log("$name 자동 연결됨")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$name 자동 연결 실패 (지속 재시도 중): ${e.message}")
            false
        }

    // ── Commander ──

    fun connectCommander() {
        commanderOff = false
        commanderFailures = 0
        startAutoConnect()
        viewModelScope.launch {
            try {
                ensureCommander()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("S3XY 연결 실패 (백그라운드에서 계속 재시도합니다): ${e.message}")
                _ui.update { it.copy(toast = "S3XY 연결 재시도 중… (${e.message})") }
                commanderWake.trySend(Unit)
            }
        }
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
                savedAddress = prefs.commanderAddress,
                onAddressLearned = { addr -> prefs.commanderAddress = addr },
                onData = { delta -> queueLive(delta) },
                onRawPush = { bytes ->
                    lastCommanderPushAt = System.currentTimeMillis()
                    if (_ui.value.logRawPushes) log("PUSH ${bytes.hex()}")
                },
                onDisconnect = {
                    log("Commander 연결 끊김 — 자동 재연결 시작")
                    clearLive()
                    _ui.update { it.copy(commander = LinkState.DISCONNECTED) }
                    resolveSpeed()
                    if (!commanderOff) {
                        commanderFailures = 0
                        commanderWake.trySend(Unit)
                    }
                },
                log = ::log,
            )
            commander = link
            link.connect()
            val session = link.session ?: error("Commander 세션을 열지 못했습니다")
            registerAndSubscribe(session)
            lastCommanderPushAt = System.currentTimeMillis()
            commanderFailures = 0
            return session
        } catch (e: Exception) {
            clearLive()
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
                // A push that lands after the link dropped must not bring old CAN values back.
                if (live != null && _ui.value.commander == LinkState.CONNECTED) {
                    val now = System.currentTimeMillis()
                    _ui.update {
                        it.copy(
                            live = live.mergedOnto(it.live),
                            pushCount = it.pushCount + n,
                            canSpeedAt = if (live.speedKmh != null) now else it.canSpeedAt,
                        )
                    }
                    resolveSpeed()
                }
            }
        }
    }

    /** Forgets every CAN value, so a dropped Commander leaves no frozen speed, gear or limit behind. */
    private fun clearLive() {
        synchronized(liveLock) {
            pendingLive = null
            pendingPushes = 0
            liveFlush?.cancel()
        }
        _ui.update { it.copy(live = VehicleData(), canSpeedAt = null) }
    }

    fun disconnectCommander() {
        commanderOff = true
        commander?.disconnect()
        commander = null
        clearLive()
        _ui.update { it.copy(commander = LinkState.DISCONNECTED) }
        resolveSpeed()
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
        carFailures = 0
        if (prefs.vin.length != 17) {
            _ui.update { it.copy(toast = "설정 탭에서 17자리 VIN을 먼저 저장하세요") }
            return
        }
        startAutoConnect()
        viewModelScope.launch {
            try {
                ensureCar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("차량 BLE 연결 실패 (백그라운드에서 계속 재시도합니다): ${e.message}")
                _ui.update { it.copy(toast = "차량 BLE 연결 재시도 중… (${e.message})") }
                carWake.trySend(Unit)
            }
        }
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
            val link = TeslaLink(
                context = getApplication(),
                vin = vin,
                key = prefs.teslaKey,
                savedAddress = prefs.carAddress,
                onAddressLearned = { addr -> prefs.carAddress = addr },
                onDisconnect = {
                    log("차량 BLE 연결 끊김 — 자동 재연결 시작")
                    _ui.update { it.copy(car = LinkState.DISCONNECTED, carDrive = null) }
                    resolveSpeed()
                    if (!carOff) {
                        carFailures = 0
                        carWake.trySend(Unit)
                    }
                },
                log = ::log,
            )
            car = link
            link.connect()
            val client = link.client ?: error("차량 BLE 세션을 열지 못했습니다")
            carFailures = 0
            carPollErrors = 0
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
        _ui.update { it.copy(car = LinkState.DISCONNECTED, carDrive = null) }
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

    // ── Speed without the Commander: the car's BLE drive state, then phone GPS ──

    private var lastTyreBar: List<Double?> = emptyList()
    private var lastTyreWarn: List<Boolean> = emptyList()
    private var cameraAlerter = CameraAlerter(CameraIndex(emptyList()))
    private var lastCameraFixAt = 0L

    private val phoneGps = PhoneGps(app) { fix ->
        _ui.update { it.copy(gps = fix) }
        resolveSpeed()
        feedCamera()
    }

    /** True while something shows the gauge: the app, split screen, or the floating dashboard. */
    private val gaugeVisible: Boolean get() = isForeground || overlayShown

    /**
     * Picks the gauge's speed and gear: Commander CAN while connected and
     * fresh, else the car's BLE drive state, else phone GPS. Stale values age
     * out, so a dropped link never leaves the needle frozen.
     */
    private fun resolveSpeed() {
        val s = _ui.value
        val cd = s.carDrive
        val reading = SpeedSource.resolve(
            nowMillis = System.currentTimeMillis(),
            commanderConnected = s.commander == LinkState.CONNECTED,
            canKmh = s.live.speedKmh?.toFloat(), canGear = s.live.gear?.label, canAtMillis = s.canSpeedAt,
            carKmh = cd?.speedKmh, carGear = cd?.gear, carAtMillis = cd?.atMillis,
            gpsKmh = s.gps?.speedKmh, gpsAtMillis = s.gps?.atMillis,
        )
        if (reading != s.speed) _ui.update { it.copy(speed = reading) }
    }

    /** Reads speed, gear, power and position from the car; the car pushes none of it over BLE. */
    private suspend fun pollCarDrive(client: TeslaClient, withLocation: Boolean) {
        val drive = client.state(StateCategory.DRIVE).driveState
        val loc = if (withLocation) {
            try {
                client.state(StateCategory.LOCATION).locationState
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val prev = _ui.value.carDrive
        var cd = CarDrive.from(drive, loc, System.currentTimeMillis())
        // Keep the last position when this poll skipped location.
        if (loc == null && prev != null) cd = cd.copy(latitude = prev.latitude, longitude = prev.longitude, heading = prev.heading)
        _ui.update { it.copy(carDrive = cd) }
        cd.odometerKm?.let(logbook::onOdometer)
        resolveSpeed()
        if (loc != null) feedCamera()
    }

    init {
        // Car drive state: fast while the gauge is on screen without the Commander
        // or while driving, slow otherwise. Location is asked for less often when
        // the phone's GPS already gives a position.
        viewModelScope.launch {
            var n = 0L
            var lastTyres = 0L
            while (isActive) {
                val s = _ui.value
                val client = car?.client
                val commanderOk = s.commander == LinkState.CONNECTED
                val driving = s.carDrive?.driving == true
                val interval = when {
                    gaugeVisible && !commanderOk -> 1_500L
                    driving && !commanderOk -> 2_000L
                    gaugeVisible || driving -> 10_000L
                    else -> 60_000L
                }
                if (client != null && s.car == LinkState.CONNECTED && s.busy == null) {
                    val gpsFresh = s.gps?.let { System.currentTimeMillis() - it.atMillis < SpeedSource.GPS_FRESH_MS } == true
                    try {
                        pollCarDrive(client, withLocation = !gpsFresh || n % 5 == 0L)
                        carPollErrors = 0
                        if (System.currentTimeMillis() - lastTyres > 10 * 60_000L) {
                            lastTyres = System.currentTimeMillis()
                            readTyres(client)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        carPollErrors++
                        if (n % 10 == 0L) log("차량 주행 상태 읽기 실패 ($carPollErrors): ${e.message}")
                        if (carPollErrors >= 3) {
                            log("차량 BLE 연속 응답 실패 — 연결 재설정")
                            carPollErrors = 0
                            car?.disconnect()
                            car = null
                            _ui.update { it.copy(car = LinkState.DISCONNECTED, carDrive = null) }
                            resolveSpeed()
                            if (!carOff) {
                                carFailures = 0
                                carWake.trySend(Unit)
                            }
                        }
                    }
                    n++
                }
                delay(interval)
            }
        }
        // Once a second: age out stale speeds, run the phone GPS while the gauge
        // is shown, feed the logbook's drive tracker and check the car's health.
        viewModelScope.launch {
            var tick = 0L
            while (isActive) {
                resolveSpeed()
                val wantGps = gaugeVisible && (_ui.value.cameraEnabled || _ui.value.commander != LinkState.CONNECTED)
                if (wantGps && !phoneGps.running) phoneGps.start() else if (!wantGps && phoneGps.running) phoneGps.stop()
                feedCamera() // deduped by fix time; covers a GPS with no fix while the car gives a position
                feedDrive()
                if (tick % 60 == 0L) feedHealth()
                tick++
                delay(1000)
            }
        }
    }

    private fun feedDrive() {
        val s = _ui.value
        val cd = s.carDrive?.takeIf { System.currentTimeMillis() - it.atMillis < 120_000 }
        val live = s.live
        if (s.speed.origin == SpeedOrigin.NONE && cd == null && live.isEmpty) return
        // GPS alone cannot tell a drive from a walk with the phone; it needs a gear from the car.
        if (s.speed.gear == null && cd == null && live.isEmpty) return
        val p = position()
        logbook.onDriveSample(
            DriveSample(
                timeMillis = System.currentTimeMillis(),
                gear = s.speed.gear,
                speedKmh = s.speed.kmh?.toDouble(),
                odometerKm = cd?.odometerKm,
                socPercent = live.socPercent?.toInt() ?: s.charge?.takeIf { it.hasBatteryLevel() }?.batteryLevel,
                latitude = p?.lat,
                longitude = p?.lon,
                place = s.region ?: s.nav?.locationName,
                packKwh = live.nominalFullPackNowKwh,
                remainingKwh = live.nominalRemainingKwh,
            ),
        )
    }


    private suspend fun readTyres(client: TeslaClient) {
        val t = try {
            client.state(StateCategory.TIRE_PRESSURE).tirePressureState
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        lastTyreBar = listOf(
            t.tpmsPressureFl.toDouble().takeIf { t.hasTpmsPressureFl() },
            t.tpmsPressureFr.toDouble().takeIf { t.hasTpmsPressureFr() },
            t.tpmsPressureRl.toDouble().takeIf { t.hasTpmsPressureRl() },
            t.tpmsPressureRr.toDouble().takeIf { t.hasTpmsPressureRr() },
        )
        lastTyreWarn = listOf(t.tpmsHardWarningFl, t.tpmsHardWarningFr, t.tpmsHardWarningRl, t.tpmsHardWarningRr)
        feedHealth()
    }

    /** 12 V, cell balance and tyres, for the logbook's automatic check notes. */
    private fun feedHealth() {
        val live = _ui.value.live
        val spread = live.cellMaxVolts?.let { max -> live.cellMinVolts?.let { min -> max - min } }
        val reading = HealthReading(
            lowVolts = live.lowBusVolts,
            tyreBar = lastTyreBar,
            tyreWarnings = lastTyreWarn,
            cellSpreadVolts = spread,
            cellMaxTempC = live.cellMaxTempC?.toDouble(),
        )
        if (reading.lowVolts == null && reading.tyreBar.all { it == null } && reading.tyreWarnings.none { it } && spread == null) return
        logbook.onHealth(reading)
    }

    // ── Speed cameras ──


    init {
        viewModelScope.launch { reloadCameras() }
    }

    private suspend fun reloadCameras() {
        val list = cameras.load()
        cameraAlerter = CameraAlerter(CameraIndex(list))
        _ui.update {
            it.copy(cameraCount = list.size, cameraSeed = cameras.usingSeed, cameraUpdatedAt = cameras.updatedAt, camera = CameraStatus())
        }
    }

    /**
     * Feeds the freshest position into the camera alerter: phone GPS (once a
     * second), else the car's own GPS over BLE, else the Commander's
     * navigation position. Speaks each new warning when voice is on.
     */
    private fun feedCamera() {
        val s = _ui.value
        if (!s.cameraEnabled) {
            if (s.camera.active) _ui.update { it.copy(camera = CameraStatus()) }
            return
        }
        val now = System.currentTimeMillis()
        val gps = s.gps?.takeIf { now - it.atMillis < SpeedSource.GPS_FRESH_MS }
        val cd = s.carDrive?.takeIf { now - it.atMillis < SpeedSource.CAR_FRESH_MS && it.latitude != null && it.longitude != null }
        val canLat = s.live.float(VehicleSignal.NAV_CAR_LATITUDE)?.toDouble()?.takeIf { it != 0.0 }
        val canLon = s.live.float(VehicleSignal.NAV_CAR_LONGITUDE)?.toDouble()?.takeIf { it != 0.0 }
        val speed = (s.speed.kmh ?: gps?.speedKmh ?: cd?.speedKmh ?: 0f).toDouble()
        val fix: Triple<Pair<Double, Double>, Double?, String>? = when {
            gps != null -> Triple(gps.latitude to gps.longitude, gps.bearingDeg?.toDouble(), "GPS")
            cd != null -> Triple(cd.latitude!! to cd.longitude!!, cd.heading?.toDouble(), "차량")
            s.commander == LinkState.CONNECTED && canLat != null && canLon != null && s.canSpeedAt != null ->
                Triple(canLat to canLon, null, "S3XY")
            else -> null
        }
        if (fix == null) {
            if (now - lastCameraFixAt > CameraAlerter.MAX_GAP_MS && s.camera.ahead != null) {
                cameraAlerter.clear()
                _ui.update { it.copy(camera = cameraAlerter.status) }
            }
            return
        }
        // The same fix fed twice would read as standing still.
        val fixAt = gps?.atMillis ?: cd?.atMillis ?: now
        if (fixAt == lastCameraFixAt) return
        lastCameraFixAt = fixAt
        val (pos, heading, source) = fix
        val alerts = cameraAlerter.update(
            lat = pos.first, lon = pos.second, headingDeg = heading, speedKmh = speed, timeMs = fixAt,
            roadLimitKmh = s.live.speedLimit?.toInt()?.takeIf { it > 0 }, positionSource = source,
        )
        val status = cameraAlerter.status
        if (status != s.camera) _ui.update { it.copy(camera = status) }
        if (s.cameraVoice) alerts.forEach { a -> assistant.speakAlert(a.spokenText()) }
        alerts.forEach { a -> log("과속 카메라: ${a.spokenText()}") }
    }

    fun setCameraEnabled(on: Boolean) {
        cameras.enabled = on
        if (!on) cameraAlerter.clear()
        _ui.update { it.copy(cameraEnabled = on, camera = if (on) it.camera else CameraStatus()) }
    }

    fun setCameraVoice(on: Boolean) {
        cameras.voice = on
        _ui.update { it.copy(cameraVoice = on) }
    }

    fun importCameras(uri: Uri) = run("카메라 데이터 가져오기", silent = true) {
        onCamerasSaved(cameras.importCsv(uri))
    }

    fun downloadCameras(key: String) = run("카메라 데이터 받기", silent = true) {
        cameras.apiKey = key
        _ui.update { it.copy(cameraApiKey = cameras.apiKey) }
        onCamerasSaved(cameras.download(cameras.apiKey))
    }

    fun clearCameras() = run("카메라 데이터 초기화", silent = true) {
        cameras.clearImported()
        reloadCameras()
        toast("내장 기본 카메라 목록으로 되돌렸습니다")
    }

    private suspend fun onCamerasSaved(n: Int) {
        log("과속 카메라 ${n}개 저장")
        reloadCameras()
        toast("과속 카메라 ${n}개를 저장했습니다")
    }

    // ── Payment messages ──

    /** Books charging, 하이패스, parking and car wash payments from the last 30 days of texts. */
    fun scanSmsInbox() = run("문자함 검사", silent = true) {
        val booked = PaymentMessages.scanInbox(getApplication())
        toast(if (booked.isEmpty()) "새로 등록할 결제 문자가 없습니다" else "결제 문자 ${booked.size}건을 차계부에 등록했습니다")
    }

    /** The test box: reads a pasted message as if it had just arrived. */
    fun testPaymentMessage(text: String) {
        if (text.isBlank()) return
        val line = logbook.onMessage(text, MessageSource.MANUAL, System.currentTimeMillis(), force = true)
        toast(line ?: "차계부 항목으로 인식하지 못했습니다 (키워드·금액 확인, 또는 이미 등록됨)")
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
        // The app is closed for good: the floating dashboard goes with it.
        DashboardOverlay.stop(getApplication())
        assistant.shutdown()
        commander?.disconnect()
        car?.disconnect()
        phoneGps.stop()
        logbook.endDrive()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
