package com.splittesla.app

import android.content.Context
import android.util.Base64
import com.splittesla.core.tesla.TeslaKey
import java.util.UUID

/** Settings and the phone's virtual key, kept in app-private storage. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("splittesla", Context.MODE_PRIVATE)

    var vin: String
        get() = prefs.getString("vin", "") ?: ""
        set(value) = prefs.edit().putString("vin", value.trim().uppercase()).apply()

    /** Persistent "{uuid}" the Commander keeps on its allowlist. */
    val commanderPhoneUuid: String
        get() = prefs.getString("commander_uuid", null) ?: "{${UUID.randomUUID().toString().lowercase()}}".also {
            prefs.edit().putString("commander_uuid", it).apply()
        }

    val teslaKey: TeslaKey
        get() {
            val priv = prefs.getString("tesla_key_private", null)
            val pub = prefs.getString("tesla_key_public", null)
            if (priv != null && pub != null) {
                return TeslaKey.fromEncoded(Base64.decode(priv, Base64.NO_WRAP), Base64.decode(pub, Base64.NO_WRAP))
            }
            val key = TeslaKey.generate()
            prefs.edit()
                .putString("tesla_key_private", Base64.encodeToString(key.encoded, Base64.NO_WRAP))
                .putString("tesla_key_public", Base64.encodeToString(key.publicKeyBytes, Base64.NO_WRAP))
                .apply()
            return key
        }
}
