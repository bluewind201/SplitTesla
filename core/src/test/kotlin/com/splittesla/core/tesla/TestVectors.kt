package com.splittesla.core.tesla

import java.math.BigInteger

/** Test keys and values published in teslamotors/vehicle-command pkg/protocol/protocol.md. */
internal object TestVectors {
    const val VIN = "5YJ30123456789ABC"

    val vehicleScalar = BigInteger("344EE5B466A7CF1EEB12B6F50331DB2E5EC5834EF5F4BEFCFD8CBE55C2528D70", 16)
    val vehiclePublic = hex(
        "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97",
    )
    val clientScalar = BigInteger("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB", 16)
    val clientPublic = hex(
        "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a714330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e",
    )

    val sharedKey = hex("1b2fce19967b79db696f909cff89ea9a")
    val handshakeUuid = hex("1588d5a30eabc6f8fc9a951b11f6fd11")
    val sessionInfo = hex(
        "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000",
    )
    val sessionInfoTag = hex("996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002")

    val commandMetadata = hex(
        "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff",
    )
    val commandNonce = hex("dbf79447fa156674dae1caed")
    val commandPlaintext = hex("120452020801")
    val commandCiphertext = hex("38038e8c0f2e")
    val commandTag = hex("c228e0ff64991481db3a7bbc133696c5")

    fun client() = TeslaKey.fromScalar(clientScalar, clientPublic)
    fun vehicle() = TeslaKey.fromScalar(vehicleScalar, vehiclePublic)
}

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
