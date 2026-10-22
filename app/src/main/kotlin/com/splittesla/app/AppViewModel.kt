package com.splittesla.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.splittesla.app.ble.CommanderLink
import com.splittesla.app.ble.TeslaLink
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderSession
import com.splittesla.core.s3xy.CommanderStatus
import com.splittesla.core.s3xy.SwitchType
import com.splittesla.core.s3xy.VehicleData
import com.splittesla.core.tesla.ChargingSite
import com.splittesla.core.tesla.NavigationInfo
import com.splittesla.core.tesla.StateCategory
import com.splittesla.core.tesla.TeslaClient
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec
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
    val favorites: Set<CommanderAction> = emptySet(),
    val nav: NavigationInfo? = null,
    val navUpdatedAt: Long? = null,
    val navAutoRefresh: Boolean = false,
    val chargingSites: List<ChargingSite> = emptyList(),
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
    private val _ui = MutableStateFlow(UiState(vin = prefs.vin, favorites = prefs.favorites))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var commander: CommanderLink? = null
    private var car: TeslaLink? = null
    private val carMutex = Mutex()
    private val commanderMutex = Mutex()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val teslaPublicKeyHex: String by lazy { prefs.teslaKey.publicKeyBytes.joinToString("") { "%02x".format(it) } }

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())} $line"
        _log.update { (it + stamped).takeLast(1000) }
    }

    fun consumeToast() = _ui.update { it.copy(toast = null) }

    fun setVin(vin: String) {
        val changed = vin != prefs.vin
        prefs.vin = vin
        _ui.update { it.copy(vin = prefs.vin, toast = "VIN 저장됨") }
        // A different car needs a fresh connection.
        if (changed && car != null) disconnectCar()
    }

    fun setLogRawPushes(on: Boolean) = _ui.update { it.copy(logRawPushes = on) }

    fun toggleFavorite(action: CommanderAction) {
        val next = _ui.value.favorites.let { if (action in it) it - action else it + action }
        prefs.favorites = next
        _ui.update { it.copy(favorites = next) }
    }

    // ── Commander ──

    fun connectCommander() = run("S3XY 연결", silent = true) { ensureCommander() }

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
                    _ui.update { it.copy(live = delta.mergedOnto(it.live), pushCount = it.pushCount + 1) }
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
        if (status != CommanderStatus.SUCCESS) {
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
        _ui.update { it.copy(commander = LinkState.CONNECTED) }
    }

    fun disconnectCommander() {
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

    fun connectCar() = run("차량 연결", silent = true) { ensureCar() }

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
            Refresh.CHARGE -> client.state(StateCategory.CHARGE).chargeState.let { s -> _ui.update { it.copy(charge = s) } }
            Refresh.CLIMATE -> client.state(StateCategory.CLIMATE).climateState.let { s -> _ui.update { it.copy(climate = s) } }
        }
    }

    fun disconnectCar() {
        car?.disconnect()
        car = null
        _ui.update { it.copy(car = LinkState.DISCONNECTED) }
    }

    fun sendAddKeyRequest() = withCar("키 등록 요청", silent = true) {
        it.sendAddKeyRequest()
        log("키 등록 요청을 보냈습니다. 센터 콘솔에 키 카드를 대고 화면에서 승인하세요")
        _ui.update { s -> s.copy(toast = "키 카드를 센터 콘솔에 대고 차량 화면에서 승인하세요") }
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
        commander?.disconnect()
        car?.disconnect()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
