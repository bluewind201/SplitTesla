package com.splittesla.app.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class Weather(
    val tempC: Double,
    val code: Int,
    val windKmh: Double?,
    val fetchedAt: Long,
) {
    val label: String get() = describe(code).first
    val icon: String get() = describe(code).second

    companion object {
        /** WMO weather codes as used by Open-Meteo. */
        fun describe(code: Int): Pair<String, String> = when (code) {
            0 -> "맑음" to "☀"
            1, 2 -> "구름 조금" to "⛅"
            3 -> "흐림" to "☁"
            45, 48 -> "안개" to "≋"
            in 51..57 -> "이슬비" to "☂"
            in 61..67, in 80..82 -> "비" to "☔"
            in 71..77, 85, 86 -> "눈" to "❄"
            in 95..99 -> "뇌우" to "⚡"
            else -> "날씨" to "•"
        }
    }
}

/**
 * Current weather from Open-Meteo: free, no key, works anywhere the phone has
 * data. Position comes from the car's route reading or the phone's last fix.
 */
class WeatherRepository(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    suspend fun fetch(lat: Double, lon: Double): Weather = withContext(Dispatchers.IO) {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,wind_speed_10m",
                lat, lon,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            Weather(
                tempC = current.getDouble("temperature_2m"),
                code = current.getInt("weather_code"),
                windKmh = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() },
                fetchedAt = System.currentTimeMillis(),
            )
        } finally {
            conn.disconnect()
        }
    }
}
