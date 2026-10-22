package com.splittesla.core.tesla

import com.google.protobuf.ByteString
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.keys.Keys
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.universalmessage.UniversalMessage.Domain
import com.tesla.generated.universalmessage.UniversalMessage.MessageFault_E
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import com.tesla.generated.vcsec.Vcsec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Carries whole (unframed) RoutableMessages to and from the car. The Android
 * BLE layer frames, chunks and reassembles them and calls [TeslaClient.onMessage].
 */
fun interface TeslaTransport {
    suspend fun send(message: ByteArray)
}

/** Parts of [CarServer] vehicle data that can be requested one at a time over BLE. */
enum class StateCategory { CHARGE, CLIMATE, DRIVE, LOCATION, CLOSURES, TIRE_PRESSURE, SOFTWARE_UPDATE, MEDIA }

class TeslaFaultException(val fault: MessageFault_E) : TeslaProtocolException("차량 거부: ${fault.name}")

class TeslaCommandException(message: String) : TeslaProtocolException(message)

/**
 * A BLE client for one car, implementing Tesla's vehicle-command protocol:
 * per-domain session handshake, AES-GCM signed commands, encrypted
 * responses. Requests are serialized; the car handles one at a time anyway.
 */
class TeslaClient(
    private val transport: TeslaTransport,
    val vin: String,
    private val key: TeslaKey,
    private val clock: () -> Long = System::currentTimeMillis,
    private val responseTimeoutMillis: Long = 10_000,
) {
    private val inbox = Channel<RoutableMessage>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val sessions = HashMap<Domain, TeslaSession>()
    private val infotainmentAddress = Crypto.randomBytes(16)

    /** Feed every reassembled message from the car here. */
    fun onMessage(bytes: ByteArray) {
        val message = runCatching { RoutableMessage.parseFrom(bytes) }.getOrNull() ?: return
        inbox.trySend(message)
    }

    fun hasSession(domain: Domain): Boolean = sessions.containsKey(domain)

    fun resetSessions() = sessions.clear()

    // ── Vehicle security (VCSEC): works while the car sleeps ──

    suspend fun lock() = rke(Vcsec.RKEAction_E.RKE_ACTION_LOCK)
    suspend fun unlock() = rke(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK)
    suspend fun wake() = rke(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE)
    suspend fun openTrunk() = closure { setRearTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE) }
    suspend fun openFrunk() = closure { setFrontTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE) }

    /** Lock, sleep and closure state, read without a session and without waking the car. */
    suspend fun vehicleStatus(): Vcsec.VehicleStatus = mutex.withLock {
        val payload = Vcsec.UnsignedMessage.newBuilder()
            .setInformationRequest(
                Vcsec.InformationRequest.newBuilder()
                    .setInformationRequestType(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS),
            ).build().toByteArray()
        val reply = exchange(Domain.DOMAIN_VEHICLE_SECURITY, payload, authenticated = false) { plain ->
            Vcsec.FromVCSECMessage.parseFrom(plain).hasVehicleStatus()
        }
        Vcsec.FromVCSECMessage.parseFrom(reply).vehicleStatus
    }

    /**
     * Asks the car to enroll this phone's key. The driver then taps a key card
     * on the console and confirms on the screen. Returns once sent.
     */
    suspend fun sendAddKeyRequest(role: Keys.Role = Keys.Role.ROLE_DRIVER) = mutex.withLock {
        val payload = Vcsec.UnsignedMessage.newBuilder().setWhitelistOperation(
            Vcsec.WhitelistOperation.newBuilder()
                .setAddKeyToWhitelistAndAddPermissions(
                    Vcsec.PermissionChange.newBuilder()
                        .setKey(Vcsec.PublicKey.newBuilder().setPublicKeyRaw(ByteString.copyFrom(key.publicKeyBytes)))
                        .setKeyRole(role),
                )
                .setMetadataForKey(
                    Vcsec.KeyMetadata.newBuilder().setKeyFormFactor(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE),
                ),
        ).build()
        val envelope = Vcsec.ToVCSECMessage.newBuilder().setSignedMessage(
            Vcsec.SignedMessage.newBuilder()
                .setProtobufMessageAsBytes(payload.toByteString())
                .setSignatureType(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY),
        ).build()
        transport.send(envelope.toByteArray())
    }

    // ── Infotainment: the car must be awake ──

    suspend fun chargeStart() = infotainment {
        setChargingStartStopAction(CarServer.ChargingStartStopAction.newBuilder().setStart(Common.Void.getDefaultInstance()))
    }

    suspend fun chargeStop() = infotainment {
        setChargingStartStopAction(CarServer.ChargingStartStopAction.newBuilder().setStop(Common.Void.getDefaultInstance()))
    }

    suspend fun setChargeLimit(percent: Int) = infotainment {
        setChargingSetLimitAction(CarServer.ChargingSetLimitAction.newBuilder().setPercent(percent))
    }

    suspend fun setChargingAmps(amps: Int) = infotainment {
        setSetChargingAmpsAction(CarServer.SetChargingAmpsAction.newBuilder().setChargingAmps(amps))
    }

    suspend fun chargePortOpen() = infotainment { setChargePortDoorOpen(CarServer.ChargePortDoorOpen.getDefaultInstance()) }
    suspend fun chargePortClose() = infotainment { setChargePortDoorClose(CarServer.ChargePortDoorClose.getDefaultInstance()) }

    suspend fun climate(on: Boolean) = infotainment {
        setHvacAutoAction(CarServer.HvacAutoAction.newBuilder().setPowerOn(on))
    }

    suspend fun setTemperature(driverCelsius: Float, passengerCelsius: Float) = infotainment {
        setHvacTemperatureAdjustmentAction(
            CarServer.HvacTemperatureAdjustmentAction.newBuilder()
                .setDriverTempCelsius(driverCelsius)
                .setPassengerTempCelsius(passengerCelsius)
                .setLevel(
                    CarServer.HvacTemperatureAdjustmentAction.Temperature.newBuilder()
                        .setTEMPMAX(Common.Void.getDefaultInstance()),
                ),
        )
    }

    suspend fun flashLights() = infotainment {
        setVehicleControlFlashLightsAction(CarServer.VehicleControlFlashLightsAction.getDefaultInstance())
    }

    suspend fun honk() = infotainment {
        setVehicleControlHonkHornAction(CarServer.VehicleControlHonkHornAction.getDefaultInstance())
    }

    suspend fun sentry(on: Boolean) = infotainment {
        setVehicleControlSetSentryModeAction(CarServer.VehicleControlSetSentryModeAction.newBuilder().setOn(on))
    }

    suspend fun windows(vent: Boolean) = infotainment {
        val action = CarServer.VehicleControlWindowAction.newBuilder()
        if (vent) action.setVent(Common.Void.getDefaultInstance()) else action.setClose(Common.Void.getDefaultInstance())
        setVehicleControlWindowAction(action)
    }

    /** Authenticated no-op; succeeds when the key is enrolled and the car is awake. */
    suspend fun ping() = infotainment { setPing(CarServer.Ping.newBuilder().setPingId(1)) }

    suspend fun state(category: StateCategory): Vehicle.VehicleData {
        val get = CarServer.GetVehicleData.newBuilder()
        when (category) {
            StateCategory.CHARGE -> get.setGetChargeState(CarServer.GetChargeState.getDefaultInstance())
            StateCategory.CLIMATE -> get.setGetClimateState(CarServer.GetClimateState.getDefaultInstance())
            StateCategory.DRIVE -> get.setGetDriveState(CarServer.GetDriveState.getDefaultInstance())
            StateCategory.LOCATION -> get.setGetLocationState(CarServer.GetLocationState.getDefaultInstance())
            StateCategory.CLOSURES -> get.setGetClosuresState(CarServer.GetClosuresState.getDefaultInstance())
            StateCategory.TIRE_PRESSURE -> get.setGetTirePressureState(CarServer.GetTirePressureState.getDefaultInstance())
            StateCategory.SOFTWARE_UPDATE -> get.setGetSoftwareUpdateState(CarServer.GetSoftwareUpdateState.getDefaultInstance())
            StateCategory.MEDIA -> get.setGetMediaState(CarServer.GetMediaState.getDefaultInstance())
        }
        val response = infotainment { setGetVehicleData(get) }
        return response.vehicleData
    }

    /**
     * Active route and GPS position. Asked for in two requests: together the
     * reply can pass the 1 KB BLE message limit, and the car then refuses
     * with MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED. Position is optional.
     */
    suspend fun navigation(): NavigationInfo {
        val drive = state(StateCategory.DRIVE)
        val merged = Vehicle.VehicleData.newBuilder(drive)
        try {
            merged.setLocationState(state(StateCategory.LOCATION).locationState)
        } catch (_: TeslaFaultException) {
            // Route without position still answers "how long until we arrive".
        }
        return NavigationInfo.from(merged.build())
    }

    /** Superchargers near the car, nearest first. [radiusMiles] follows the car's unit. */
    suspend fun nearbyChargingSites(count: Int = 10, radiusMiles: Int = 200): List<ChargingSite> {
        val request = CarServer.GetNearbyChargingSites.newBuilder()
            .setIncludeMetaData(true).setCount(count).setRadius(radiusMiles)
        val response = infotainment { setGetNearbyChargingSites(request) }
        return response.getNearbyChargingSites.superchargersList.map(ChargingSite::from).sortedBy { it.distanceKm }
    }

    // ── Plumbing ──

    private suspend fun rke(action: Vcsec.RKEAction_E) {
        val payload = Vcsec.UnsignedMessage.newBuilder().setRKEAction(action).build().toByteArray()
        vcsecCommand(payload)
    }

    private suspend fun closure(configure: Vcsec.ClosureMoveRequest.Builder.() -> Unit) {
        val request = Vcsec.ClosureMoveRequest.newBuilder().apply(configure)
        val payload = Vcsec.UnsignedMessage.newBuilder().setClosureMoveRequest(request).build().toByteArray()
        vcsecCommand(payload)
    }

    /** VCSEC replies with interim command statuses; an empty message means success. */
    private suspend fun vcsecCommand(payload: ByteArray) = mutex.withLock {
        exchange(Domain.DOMAIN_VEHICLE_SECURITY, payload, authenticated = true) { plain ->
            val reply = Vcsec.FromVCSECMessage.parseFrom(plain)
            if (reply.hasNominalError()) {
                throw TeslaCommandException("VCSEC 오류: ${reply.nominalError.genericError.name}")
            }
            !reply.hasCommandStatus()
        }
    }

    private suspend fun infotainment(configure: CarServer.VehicleAction.Builder.() -> Unit): CarServer.Response =
        mutex.withLock {
            val action = CarServer.Action.newBuilder()
                .setVehicleAction(CarServer.VehicleAction.newBuilder().apply(configure))
                .build().toByteArray()
            val reply = exchange(Domain.DOMAIN_INFOTAINMENT, action, authenticated = true) { true }
            val response = CarServer.Response.parseFrom(reply)
            if (response.actionStatus.result == CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR) {
                val reason = response.actionStatus.resultReason.plainText.ifEmpty { "unspecified error" }
                throw TeslaCommandException("차량이 명령을 실행하지 못함: $reason")
            }
            response
        }

    /**
     * Sends one request and waits for its terminal reply, re-syncing the
     * session and retrying when the car reports a stale counter or clock.
     */
    private suspend fun exchange(
        domain: Domain,
        payload: ByteArray,
        authenticated: Boolean,
        isTerminal: (ByteArray) -> Boolean,
    ): ByteArray {
        var lastFault: MessageFault_E? = null
        repeat(MAX_ATTEMPTS) {
            val session = if (authenticated) ensureSession(domain) else null
            val address = addressFor(domain)
            val uuid = Crypto.randomBytes(16)
            val builder = RoutableMessage.newBuilder()
                .setToDestination(UniversalMessage.Destination.newBuilder().setDomain(domain))
                .setFromDestination(UniversalMessage.Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(address)))
                .setProtobufMessageAsBytes(ByteString.copyFrom(payload))
                .setUuid(ByteString.copyFrom(uuid))
            if (session != null) {
                builder.flags = 1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE_VALUE
                session.authorize(builder, EXPIRES_IN_SECONDS, clock())
            }
            val request = builder.build()
            val requestHash = session?.requestHash(request) ?: ByteArray(0)
            val seenCounters = HashSet<Long>()
            drainInbox()
            transport.send(request.toByteArray())

            while (true) {
                val reply = awaitReply(domain, address) ?: throw TeslaProtocolException("차량 응답 시간 초과")
                if (session != null) applySessionUpdate(session, reply)
                val fault = reply.signedMessageStatus.signedMessageFault
                if (fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) throw KeyNotPairedException()
                if (fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
                    if (fault in RETRYABLE_FAULTS) {
                        lastFault = fault
                        delay(RETRY_DELAY_MILLIS)
                        return@repeat
                    }
                    throw TeslaFaultException(fault)
                }
                if (reply.payloadCase != RoutableMessage.PayloadCase.PROTOBUF_MESSAGE_AS_BYTES) continue
                val plain = if (reply.signatureData.hasAESGCMResponseData()) {
                    val s = session ?: throw TeslaProtocolException("encrypted reply without a session")
                    val (bytes, counter) = s.decrypt(reply, requestHash)
                    if (!seenCounters.add(counter)) throw TeslaProtocolException("replayed response")
                    bytes
                } else {
                    reply.protobufMessageAsBytes.toByteArray()
                }
                if (isWaitStatus(domain, plain)) continue
                if (isTerminal(plain)) return plain
            }
        }
        throw lastFault?.let { TeslaFaultException(it) } ?: TeslaProtocolException("재시도 실패")
    }

    private suspend fun ensureSession(domain: Domain): TeslaSession {
        sessions[domain]?.let { return it }
        repeat(MAX_ATTEMPTS) {
            val address = addressFor(domain)
            val uuid = Crypto.randomBytes(16)
            val request = RoutableMessage.newBuilder()
                .setToDestination(UniversalMessage.Destination.newBuilder().setDomain(domain))
                .setFromDestination(UniversalMessage.Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(address)))
                .setSessionInfoRequest(
                    UniversalMessage.SessionInfoRequest.newBuilder().setPublicKey(ByteString.copyFrom(key.publicKeyBytes)),
                )
                .setUuid(ByteString.copyFrom(uuid))
                .build()
            drainInbox()
            transport.send(request.toByteArray())
            while (true) {
                val reply = awaitReply(domain, address) ?: break
                val fault = reply.signedMessageStatus.signedMessageFault
                if (fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) throw KeyNotPairedException()
                if (fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE && fault !in RETRYABLE_FAULTS) throw TeslaFaultException(fault)
                if (reply.payloadCase != RoutableMessage.PayloadCase.SESSION_INFO) continue
                if (!reply.signatureData.hasSessionInfoTag()) continue
                val session = TeslaSession.fromHandshake(
                    key = key,
                    vin = vin,
                    challenge = uuid,
                    encodedInfo = reply.sessionInfo.toByteArray(),
                    tag = reply.signatureData.sessionInfoTag.tag.toByteArray(),
                    nowMillis = clock(),
                )
                sessions[domain] = session
                return session
            }
            delay(RETRY_DELAY_MILLIS)
        }
        throw TeslaProtocolException("${domain.name} 세션 연결 실패")
    }

    private fun applySessionUpdate(session: TeslaSession, reply: RoutableMessage) {
        if (reply.payloadCase != RoutableMessage.PayloadCase.SESSION_INFO) return
        if (!reply.signatureData.hasSessionInfoTag()) return
        runCatching {
            session.updateSignedSessionInfo(
                challenge = reply.requestUuid.toByteArray(),
                encodedInfo = reply.sessionInfo.toByteArray(),
                tag = reply.signatureData.sessionInfoTag.tag.toByteArray(),
                nowMillis = clock(),
            )
        }
    }

    /** VCSEC says WAIT while busy or while waiting for a key card tap. */
    private fun isWaitStatus(domain: Domain, plain: ByteArray): Boolean {
        if (domain != Domain.DOMAIN_VEHICLE_SECURITY) return false
        val reply = runCatching { Vcsec.FromVCSECMessage.parseFrom(plain) }.getOrNull() ?: return false
        if (!reply.hasCommandStatus()) return false
        val status = reply.commandStatus.operationStatus
        return status == Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT || status == Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR
    }

    private suspend fun awaitReply(domain: Domain, address: ByteArray): RoutableMessage? {
        val deadline = clock() + responseTimeoutMillis
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            val message = withTimeoutOrNull(remaining) { inbox.receive() } ?: return null
            if (message.fromDestination.domain != domain) continue
            if (!message.toDestination.routingAddress.toByteArray().contentEquals(address)) continue
            return message
        }
    }

    private fun drainInbox() {
        while (inbox.tryReceive().isSuccess) Unit
    }

    private fun addressFor(domain: Domain): ByteArray =
        if (domain == Domain.DOMAIN_INFOTAINMENT) infotainmentAddress else Crypto.randomBytes(16)

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 500L
        private const val EXPIRES_IN_SECONDS = 15L

        private val RETRYABLE_FAULTS = setOf(
            MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
            MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
            MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
            MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
        )
    }
}
