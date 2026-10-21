package com.splittesla.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.splittesla.app.ble.CommanderLink
import com.splittesla.app.ble.TeslaLink
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderStatus
import com.splittesla.core.s3xy.SwitchType
import com.splittesla.core.s3xy.VehicleData
import com.splittesla.core.tesla.ChargingSite
import com.splittesla.core.tesla.NavigationInfo
import com.splittesla.core.tesla.StateCategory
import com.splittesla.core.tesla.TeslaClient
import com.tesla.generated.vcsec.Vcsec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LinkState(val label: String) {
    DISCONNECTED("연결 안 됨"), CONNECTING("연결 중"), NEEDS_PAIRING("페어링 필요"), CONNECTED("연결됨"), FAILED("실패")
}

data class UiState(
    val commander: LinkState = LinkState.DISCONNECTED,
    val car: LinkState = LinkState.DISCONNECTED,
    val live: VehicleData = VehicleData(),
    val pushCount: Int = 0,
    val vehicleStatus: Vcsec.VehicleStatus? = null,
    val stateText: String = "",
    val busy: String? = null,
    val logRawPushes: Boolean = false,
    val vin: String = "",
    val nav: NavigationInfo? = null,
    val navUpdatedAt: Long? = null,
    val navAutoRefresh: Boolean = false,
    val chargingSites: List<ChargingSite> = emptyList(),
)

/**
 * Holds both BLE links. The split: the Commander streams live CAN data and
 * performs actions the official protocol lacks; the car's own BLE handles
 * charging, climate, locks and state queries.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _ui = MutableStateFlow(UiState(vin = prefs.vin))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var commander: CommanderLink? = null
    private var car: TeslaLink? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val teslaPublicKeyHex: String by lazy { prefs.teslaKey.publicKeyBytes.joinToString("") { "%02x".format(it) } }

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())} $line"
        _log.update { (it + stamped).takeLast(1000) }
    }

    fun setVin(vin: String) {
        prefs.vin = vin
        _ui.update { it.copy(vin = prefs.vin) }
    }

    fun setLogRawPushes(on: Boolean) = _ui.update { it.copy(logRawPushes = on) }

    // ── Commander ──

    fun connectCommander() {
        if (_ui.value.commander == LinkState.CONNECTING) return
        _ui.update { it.copy(commander = LinkState.CONNECTING) }
        viewModelScope.launch {
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
                registerAndSubscribe()
            } catch (e: Exception) {
                log("Commander 오류: ${e.message}")
                _ui.update { it.copy(commander = LinkState.FAILED) }
            }
        }
    }

    /**
     * Registers the phone UUID. A new phone is rejected until the driver
     * double-presses the right scroll button, so keep retrying for a minute.
     */
    private suspend fun registerAndSubscribe() {
        val session = commander?.session ?: return
        var status = session.sendUuid()
        if (status != CommanderStatus.SUCCESS) {
            log("Commander가 이 폰을 모릅니다 ($status). 핸들 오른쪽 스크롤 버튼을 두 번 누르세요")
            _ui.update { it.copy(commander = LinkState.NEEDS_PAIRING) }
            val deadline = System.currentTimeMillis() + 60_000
            while (status != CommanderStatus.SUCCESS && System.currentTimeMillis() < deadline) {
                delay(3000)
                status = session.sendUuid()
            }
            if (status != CommanderStatus.SUCCESS) {
                log("Commander 페어링 시간 초과")
                _ui.update { it.copy(commander = LinkState.FAILED) }
                return
            }
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

    fun commanderAction(action: CommanderAction, value: Long? = null, switchType: SwitchType = SwitchType.Toggle) {
        val session = commander?.session ?: return log("Commander가 연결되지 않았습니다")
        run("S3XY ${action.name}") {
            val status = session.tryAction(action, switchType, listOf(value ?: 1L))
            log("S3XY ${action.name} → $status")
        }
    }

    // ── Car ──

    fun connectCar() {
        val vin = prefs.vin
        if (vin.length != 17) return log("설정에서 17자리 VIN을 먼저 입력하세요")
        if (_ui.value.car == LinkState.CONNECTING) return
        _ui.update { it.copy(car = LinkState.CONNECTING) }
        viewModelScope.launch {
            try {
                car?.disconnect()
                val link = TeslaLink(getApplication(), vin, prefs.teslaKey, onDisconnect = {
                    log("차량 BLE 연결 끊김")
                    _ui.update { it.copy(car = LinkState.DISCONNECTED) }
                }, log = ::log)
                car = link
                link.connect()
                _ui.update { it.copy(car = LinkState.CONNECTED) }
                refreshStatus()
            } catch (e: Exception) {
                log("차량 연결 오류: ${e.message}")
                _ui.update { it.copy(car = LinkState.FAILED) }
            }
        }
    }

    fun disconnectCar() {
        car?.disconnect()
        car = null
        _ui.update { it.copy(car = LinkState.DISCONNECTED) }
    }

    fun sendAddKeyRequest() = withCar("키 등록 요청") {
        it.sendAddKeyRequest()
        log("키 등록 요청을 보냈습니다. 센터 콘솔에 키 카드를 대고 화면에서 승인하세요")
    }

    fun refreshStatus() = withCar("차량 상태") {
        val status = it.vehicleStatus()
        _ui.update { s -> s.copy(vehicleStatus = status) }
    }

    fun carCommand(label: String, block: suspend (TeslaClient) -> Unit) = withCar(label) {
        block(it)
        log("$label 완료")
    }

    fun queryState(category: StateCategory) = withCar("${category.name} 조회") {
        val data = it.state(category)
        _ui.update { s -> s.copy(stateText = data.toString().ifBlank { "(비어 있음)" }) }
    }

    // ── Navigation ──

    private var navJob: Job? = null

    fun refreshNavigation() = withCar("내비 정보") { fetchNavigation(it) }

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
                if (client != null && _ui.value.busy == null) {
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

    fun findChargingSites() = withCar("주변 슈퍼차저") {
        val sites = it.nearbyChargingSites()
        _ui.update { s -> s.copy(chargingSites = sites) }
        log("주변 슈퍼차저 ${sites.size}곳")
    }

    private fun withCar(label: String, block: suspend (TeslaClient) -> Unit) {
        val client = car?.client ?: return log("차량 BLE가 연결되지 않았습니다")
        run(label) { block(client) }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = label) }
            try {
                block()
            } catch (e: Exception) {
                log("$label 실패: ${e.message}")
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
