package com.splittesla.core.tesla

import com.google.protobuf.ByteString
import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import com.tesla.generated.vcsec.Vcsec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs [TeslaClient] against a simulated car that implements the vehicle side
 * of protocol.md with the published vehicle test key.
 */
class TeslaClientTest {
    private class FakeCar(private val enrolled: Boolean = true) {
        val vehicleKey = TestVectors.vehicle()
        val epoch = hex("00112233445566778899aabbccddeeff")
        var counter = 0
        var clock = 1000
        var responseCounter = 0
        val received = mutableListOf<RoutableMessage>()
        val executed = mutableListOf<String>()
        lateinit var client: TeslaClient

        fun handle(bytes: ByteArray) {
            val request = RoutableMessage.parseFrom(bytes)
            received += request
            when {
                request.hasSessionInfoRequest() -> answerHandshake(request)
                request.signatureData.hasAESGCMPersonalizedData() -> answerCommand(request)
                else -> answerUnsigned(request)
            }
        }

        private fun sessionKey(request: RoutableMessage) =
            vehicleKey.sharedKey(request.signatureData.signerIdentity.publicKey.toByteArray())

        private fun replyTo(request: RoutableMessage) = RoutableMessage.newBuilder()
            .setToDestination(UniversalMessage.Destination.newBuilder().setRoutingAddress(request.fromDestination.routingAddress))
            .setFromDestination(UniversalMessage.Destination.newBuilder().setDomain(request.toDestination.domain))
            .setRequestUuid(request.uuid)

        private fun answerHandshake(request: RoutableMessage) {
            val info = Signatures.SessionInfo.newBuilder()
                .setCounter(counter)
                .setPublicKey(ByteString.copyFrom(vehicleKey.publicKeyBytes))
                .setEpoch(ByteString.copyFrom(epoch))
                .setClockTime(clock)
                .apply { if (!enrolled) status = Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST }
                .build().toByteArray()
            val k = vehicleKey.sharedKey(request.sessionInfoRequest.publicKey.toByteArray())
            val subkey = Crypto.hmacSha256(k, "session info".toByteArray())
            val tag = Metadata(subkey)
                .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_HMAC_VALUE.toByte()))
                .add(Signatures.Tag.TAG_PERSONALIZATION, TestVectors.VIN.toByteArray())
                .add(Signatures.Tag.TAG_CHALLENGE, request.uuid.toByteArray())
                .checksum(info)
            val reply = replyTo(request)
                .setSessionInfo(ByteString.copyFrom(info))
                .setSignatureData(
                    Signatures.SignatureData.newBuilder()
                        .setSessionInfoTag(Signatures.HMAC_Signature_Data.newBuilder().setTag(ByteString.copyFrom(tag))),
                )
            client.onMessage(reply.build().toByteArray())
        }

        private fun answerCommand(request: RoutableMessage) {
            val gcm = request.signatureData.aesgcmPersonalizedData
            val k = sessionKey(request)
            val aad = Metadata()
                .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(5))
                .add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(request.toDestination.domainValue.toByte()))
                .add(Signatures.Tag.TAG_PERSONALIZATION, TestVectors.VIN.toByteArray())
                .add(Signatures.Tag.TAG_EPOCH, gcm.epoch.toByteArray())
                .addUInt32(Signatures.Tag.TAG_EXPIRES_AT, gcm.expiresAt.toLong())
                .addUInt32(Signatures.Tag.TAG_COUNTER, gcm.counter.toLong())
                .addUInt32(Signatures.Tag.TAG_FLAGS, request.flags.toLong())
                .checksum()
            val plain = Crypto.gcmOpen(k, gcm.nonce.toByteArray(), request.protobufMessageAsBytes.toByteArray(), gcm.tag.toByteArray(), aad)
            check(gcm.counter > counter) { "counter must increase" }
            check(gcm.expiresAt > clock) { "command already expired" }
            counter = gcm.counter

            val response: ByteArray = if (request.toDestination.domain == UniversalMessage.Domain.DOMAIN_INFOTAINMENT) {
                val action = CarServer.Action.parseFrom(plain).vehicleAction
                executed += action.vehicleActionMsgCase.name
                val out = CarServer.Response.newBuilder()
                    .setActionStatus(CarServer.ActionStatus.newBuilder().setResult(CarServer.OperationStatus_E.OPERATIONSTATUS_OK))
                if (action.hasGetVehicleData()) {
                    out.setVehicleData(
                        Vehicle.VehicleData.newBuilder()
                            .setChargeState(Vehicle.ChargeState.newBuilder().setBatteryLevel(77))
                            .setDriveState(
                                Vehicle.DriveState.newBuilder()
                                    .setActiveRouteDestination("Seoul Station")
                                    .setActiveRouteMinutesToArrival(12.5f)
                                    .setActiveRouteMilesToArrival(10f)
                                    .setActiveRouteCoordinates(Common.LatLong.newBuilder().setLatitude(37.55f).setLongitude(126.97f)),
                            )
                            .setLocationState(Vehicle.LocationState.newBuilder().setLatitude(37.5f).setLongitude(127.0f).setHeading(90)),
                    )
                }
                if (action.hasGetNearbyChargingSites()) {
                    out.setGetNearbyChargingSites(
                        CarServer.NearbyChargingSites.newBuilder()
                            .addSuperchargers(CarServer.Superchargers.newBuilder().setName("Far").setDistanceMiles(20f))
                            .addSuperchargers(
                                CarServer.Superchargers.newBuilder().setName("Near").setDistanceMiles(2f)
                                    .setAvailableStalls(3).setTotalStalls(8),
                            ),
                    )
                }
                out.build().toByteArray()
            } else {
                val unsigned = Vcsec.UnsignedMessage.parseFrom(plain)
                executed += unsigned.subMessageCase.name + ":" + unsigned.rkeAction.name
                // An interim WAIT status first, then the empty message that means success.
                sendEncrypted(request, k, Vcsec.FromVCSECMessage.newBuilder().setCommandStatus(
                    Vcsec.CommandStatus.newBuilder().setOperationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT),
                ).build().toByteArray())
                Vcsec.FromVCSECMessage.getDefaultInstance().toByteArray()
            }
            sendEncrypted(request, k, response)
        }

        private fun sendEncrypted(request: RoutableMessage, k: ByteArray, plain: ByteArray) {
            responseCounter += 1
            val requestHash = byteArrayOf(5) + request.signatureData.aesgcmPersonalizedData.tag.toByteArray()
            val flags = 0
            val aad = Metadata()
                .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(9))
                .add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(request.toDestination.domainValue.toByte()))
                .add(Signatures.Tag.TAG_PERSONALIZATION, TestVectors.VIN.toByteArray())
                .addUInt32(Signatures.Tag.TAG_COUNTER, responseCounter.toLong())
                .addUInt32(Signatures.Tag.TAG_FLAGS, flags.toLong())
                .add(Signatures.Tag.TAG_REQUEST_HASH, requestHash)
                .addUInt32(Signatures.Tag.TAG_FAULT, 0)
                .checksum()
            val sealed = Crypto.gcmSeal(k, Crypto.randomBytes(12), plain, aad)
            val reply = replyTo(request)
                .setProtobufMessageAsBytes(ByteString.copyFrom(sealed.ciphertext))
                .setFlags(flags)
                .setSignatureData(
                    Signatures.SignatureData.newBuilder().setAESGCMResponseData(
                        Signatures.AES_GCM_Response_Signature_Data.newBuilder()
                            .setNonce(ByteString.copyFrom(sealed.nonce))
                            .setCounter(responseCounter)
                            .setTag(ByteString.copyFrom(sealed.tag)),
                    ),
                )
            client.onMessage(reply.build().toByteArray())
        }

        private fun answerUnsigned(request: RoutableMessage) {
            val status = Vcsec.FromVCSECMessage.newBuilder().setVehicleStatus(
                Vcsec.VehicleStatus.newBuilder()
                    .setVehicleLockState(Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)
                    .setVehicleSleepStatus(Vcsec.VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP),
            ).build()
            client.onMessage(replyTo(request).setProtobufMessageAsBytes(status.toByteString()).build().toByteArray())
        }
    }

    private fun connect(car: FakeCar): TeslaClient {
        val client = TeslaClient({ car.handle(it) }, TestVectors.VIN, TestVectors.client(), responseTimeoutMillis = 2000)
        car.client = client
        return client
    }

    @Test
    fun infotainmentCommandsRunEncryptedEndToEnd() = runTest {
        val car = FakeCar()
        val client = connect(car)
        client.chargeStart()
        client.setChargeLimit(80)
        val data = client.state(StateCategory.CHARGE)
        assertEquals(77, data.chargeState.batteryLevel)
        assertEquals(
            listOf("CHARGINGSTARTSTOPACTION", "CHARGINGSETLIMITACTION", "GETVEHICLEDATA"),
            car.executed,
        )
        assertEquals(1, car.received.count { it.hasSessionInfoRequest() }, "one handshake per domain")
        assertTrue(car.received.filter { !it.hasSessionInfoRequest() }.all { it.flags == 2 }, "requests ask for encrypted responses")
    }

    @Test
    fun navigationReadsRouteAndNearbySites() = runTest {
        val car = FakeCar()
        val client = connect(car)
        val nav = client.navigation()
        // Drive and location are asked for separately to stay under the 1 KB reply limit.
        assertEquals(listOf("GETVEHICLEDATA", "GETVEHICLEDATA"), car.executed)
        assertEquals("Seoul Station", nav.destination)
        assertEquals(12.5, nav.minutesToArrival)
        assertEquals(16.09, nav.kmToArrival!!, 0.01)
        assertEquals(37.55, nav.destinationLat!!, 0.001)
        assertEquals(90, nav.heading)
        assertTrue(nav.hasRoute)
        val sites = client.nearbyChargingSites()
        assertEquals(listOf("Near", "Far"), sites.map { it.name })
        assertEquals(3, sites.first().availableStalls)
    }

    @Test
    fun vcsecCommandWaitsPastInterimStatus() = runTest {
        val car = FakeCar()
        val client = connect(car)
        client.unlock()
        client.lock()
        assertEquals(listOf("RKEACTION:RKE_ACTION_UNLOCK", "RKEACTION:RKE_ACTION_LOCK"), car.executed)
    }

    @Test
    fun vehicleStatusNeedsNoSession() = runTest {
        val car = FakeCar()
        val client = connect(car)
        val status = client.vehicleStatus()
        assertEquals(Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED, status.vehicleLockState)
        assertEquals(0, car.received.count { it.hasSessionInfoRequest() })
    }

    @Test
    fun unenrolledKeyIsReported() = runTest {
        val car = FakeCar(enrolled = false)
        val client = connect(car)
        assertFailsWith<KeyNotPairedException> { client.ping() }
    }

    @Test
    fun addKeyRequestIsAPresentKeyEnvelope() = runTest {
        var sent: ByteArray? = null
        val client = TeslaClient({ sent = it }, TestVectors.VIN, TestVectors.client())
        client.sendAddKeyRequest()
        val envelope = Vcsec.ToVCSECMessage.parseFrom(sent!!)
        assertEquals(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY, envelope.signedMessage.signatureType)
        val op = Vcsec.UnsignedMessage.parseFrom(envelope.signedMessage.protobufMessageAsBytes).whitelistOperation
        assertEquals(ByteString.copyFrom(TestVectors.clientPublic), op.addKeyToWhitelistAndAddPermissions.key.publicKeyRaw)
        assertEquals(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE, op.metadataForKey.keyFormFactor)
    }
}
