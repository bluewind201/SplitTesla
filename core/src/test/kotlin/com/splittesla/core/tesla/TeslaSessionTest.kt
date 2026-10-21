package com.splittesla.core.tesla

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeslaSessionTest {
    @Test
    fun keyAgreementMatchesProtocolDoc() {
        assertContentEquals(TestVectors.sharedKey, TestVectors.client().sharedKey(TestVectors.vehiclePublic))
        assertContentEquals(TestVectors.sharedKey, TestVectors.vehicle().sharedKey(TestVectors.clientPublic))
    }

    @Test
    fun publicKeyEncodingRoundTrips() {
        assertContentEquals(TestVectors.clientPublic, TestVectors.client().publicKeyBytes)
        val generated = TeslaKey.generate()
        val restored = TeslaKey.fromEncoded(generated.encoded, generated.publicKeyBytes)
        assertContentEquals(generated.publicKeyBytes, restored.publicKeyBytes)
    }

    @Test
    fun handshakeTagFromProtocolDocVerifies() {
        val session = handshakeSession(nowMillis = 2_650_000)
        assertEquals(6, session.counter)
        assertEquals(2650, session.timestamp(2_650_000))
    }

    @Test
    fun tamperedHandshakeIsRejected() {
        val badTag = TestVectors.sessionInfoTag.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<TeslaProtocolException> {
            TeslaSession.fromHandshake(TestVectors.client(), TestVectors.VIN, TestVectors.handshakeUuid, TestVectors.sessionInfo, badTag, 0)
        }
    }

    @Test
    fun commandEncryptionMatchesProtocolDoc() {
        val now = 2_650_000L
        val session = handshakeSession(now)
        val builder = UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(UniversalMessage.Destination.newBuilder().setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT))
            .setProtobufMessageAsBytes(ByteString.copyFrom(TestVectors.commandPlaintext))
            .setFlags(2)
        session.authorize(builder, expiresInSeconds = 5, nowMillis = now, nonce = TestVectors.commandNonce)

        val gcm = builder.signatureData.aesgcmPersonalizedData
        assertEquals(7, gcm.counter)
        assertEquals(2655, gcm.expiresAt)
        assertContentEquals(TestVectors.commandCiphertext, builder.protobufMessageAsBytes.toByteArray())
        assertContentEquals(TestVectors.commandTag, gcm.tag.toByteArray())
        assertContentEquals(TestVectors.clientPublic, builder.signatureData.signerIdentity.publicKey.toByteArray())
    }

    @Test
    fun metadataSerializationMatchesProtocolDoc() {
        val meta = Metadata()
            .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(5))
            .add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(3))
            .add(Signatures.Tag.TAG_PERSONALIZATION, TestVectors.VIN.toByteArray())
            .add(Signatures.Tag.TAG_EPOCH, hex("4c463f9cc0d3d26906e982ed224adde6"))
            .addUInt32(Signatures.Tag.TAG_EXPIRES_AT, 2655)
            .addUInt32(Signatures.Tag.TAG_COUNTER, 7)
            .addUInt32(Signatures.Tag.TAG_FLAGS, 2)
        assertContentEquals(TestVectors.commandMetadata, meta.serialized())
    }

    @Test
    fun localNameMatchesProtocolDoc() {
        assertEquals("S1a87a5a75f3df858C", TeslaBleConstants.localName("5YJS0000000000000"))
    }

    @Test
    fun framingRoundTripsAcrossChunks() {
        val message = ByteArray(300) { it.toByte() }
        val deframer = BleDeframer(clock = { 0 })
        val out = BleFraming.chunks(message, blockLength = 20).flatMap { deframer.feed(it) }
        assertEquals(1, out.size)
        assertContentEquals(message, out[0])
    }

    private fun handshakeSession(nowMillis: Long) = TeslaSession.fromHandshake(
        TestVectors.client(), TestVectors.VIN, TestVectors.handshakeUuid,
        TestVectors.sessionInfo, TestVectors.sessionInfoTag, nowMillis,
    )
}
