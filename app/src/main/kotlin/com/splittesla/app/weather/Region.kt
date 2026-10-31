package com.splittesla.app.weather

import android.content.Context
import android.location.Geocoder
import com.splittesla.app.navi.NaviHub
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.RouteGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The district and neighbourhood the car is in, for the dashboard's status
 * line. NAVER's reverse geocoding names Korean areas best; without its key the
 * phone's own geocoder answers.
 */
class RegionRepository(private val context: Context) {
    private var lastAt: LatLon? = null
    private var last: String? = null

    /** Asks again only after the car has moved [MOVE_M] metres. */
    suspend fun nameAt(p: LatLon): String? {
        val at = lastAt
        if (last != null && at != null && RouteGeometry.metres(at, p) < MOVE_M) return last
        val name = runCatching { if (NaviHub.settings.hasMaps) NaviHub.naver.areaName(p) else null }.getOrNull() ?: geocoder(p)
        if (name != null) {
            last = name
            lastAt = p
        }
        return name ?: last
    }

    @Suppress("DEPRECATION")
    private suspend fun geocoder(p: LatLon): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.KOREA).getFromLocation(p.lat, p.lon, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.subLocality ?: a.locality, a.thoroughfare).joinToString(" ").ifBlank { null }
            }
        }.getOrNull()
    }

    private companion object {
        const val MOVE_M = 800.0
    }
}
