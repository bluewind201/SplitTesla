package com.splittesla.app

import android.content.Context
import android.util.Base64
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.tesla.TeslaKey
import java.util.UUID

/** Settings and the phone's virtual key, kept in app-private storage. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("splittesla", Context.MODE_PRIVATE)

    var vin: String
        get() = prefs.getString("vin", "") ?: ""
        set(value) = prefs.edit().putString("vin", value.trim().uppercase()).apply()

    var gaugeStyle: GaugeStyle
        get() = GaugeStyle.entries.firstOrNull { it.name == prefs.getString("gauge_style", null) } ?: GaugeStyle.TRACK
        set(value) = prefs.edit().putString("gauge_style", value.name).apply()

    /** The car accepted this phone's key; remembered so the settings screen can say so before connecting. */
    var keyPaired: Boolean
        get() = prefs.getBoolean("key_paired", false)
        set(value) = prefs.edit().putBoolean("key_paired", value).apply()

    /** Show the dashboard over other apps while this app is in the background. */
    var overlay: Boolean
        get() = prefs.getBoolean("overlay", true)
        set(value) = prefs.edit().putBoolean("overlay", value).apply()

    /** S3XY actions pinned to the top of the actions tab. */
    var favorites: Set<CommanderAction>
        get() = prefs.getStringSet("favorites", null)
            ?.mapNotNull { name -> CommanderAction.entries.firstOrNull { it.name == name } }?.toSet()
            ?: setOf(CommanderAction.HAZARD_LIGHTS, CommanderAction.FOLD_MIRRORS, CommanderAction.OPEN_GLOVE_BOX)
        set(value) = prefs.edit().putStringSet("favorites", value.map { it.name }.toSet()).apply()

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
