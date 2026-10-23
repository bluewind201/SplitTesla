package com.splittesla.app.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import com.splittesla.app.ui.NaviLaunch
import com.splittesla.core.assistant.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Route changes from typed commands, handed to the chosen navigation app. Changing the car's
 * own route needs the Fleet API, which this app does not use.
 *
 * A spoken place name is turned into coordinates with the phone's geocoder,
 * looking near the car first. When that fails the map app's own search opens
 * with the name, one tap away from guidance.
 */
object RouteHandoff {

    suspend fun navigate(context: Context, app: NaviApp, place: String, near: LatLon?, log: (String) -> Unit) {
        val point = geocode(context, place, near)
        withContext(Dispatchers.Main) {
            if (point == null) {
                log("'$place' 좌표를 못 찾아 지도 검색으로 엽니다")
                search(context, app, place)
            } else {
                toPoint(context, app, point, place)
            }
        }
    }

    fun toPoint(context: Context, app: NaviApp, point: LatLon, label: String) =
        NaviLaunch.route(context, app, point.lat, point.lon, label)

    /**
     * Keeps the destination and adds [stop] before it. Naver Map takes the
     * stop in the route itself; the other apps' links have no stops,
     * so they guide to the stop first.
     */
    suspend fun viaStop(context: Context, app: NaviApp, stop: String, destination: LatLon, destName: String, near: LatLon?, log: (String) -> Unit) {
        val point = geocode(context, stop, near)
        withContext(Dispatchers.Main) {
            when {
                point == null -> {
                    log("'$stop' 좌표를 못 찾아 지도 검색으로 엽니다")
                    search(context, app, stop)
                }
                app == NaviApp.NAVER -> open(
                    context,
                    app,
                    Uri.parse(
                        "nmap://route/car?dlat=${destination.lat}&dlng=${destination.lon}&dname=${Uri.encode(destName)}" +
                            "&v1lat=${point.lat}&v1lng=${point.lon}&v1name=${Uri.encode(stop)}&appname=${context.packageName}",
                    ),
                )
                else -> toPoint(context, app, point, stop)
            }
        }
    }

    fun search(context: Context, app: NaviApp, query: String) = open(
        context,
        app,
        Uri.parse(
            when (app) {
                NaviApp.NAVER -> "nmap://search?query=${Uri.encode(query)}&appname=${context.packageName}"
                NaviApp.TMAP -> "tmap://search?name=${Uri.encode(query)}"
                NaviApp.KAKAO -> "kakaomap://search?q=${Uri.encode(query)}"
                NaviApp.WAZE -> "https://waze.com/ul?q=${Uri.encode(query)}&navigate=yes&utm_source=${context.packageName}"
            },
        ),
    )

    private fun open(context: Context, app: NaviApp, uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Same fallback as the navi tab: the app's store page.
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(context: Context, place: String, near: LatLon?): LatLon? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.KOREA)
        try {
            val local = near?.let {
                geocoder.getFromLocationName(place, 1, it.lat - 0.3, it.lon - 0.4, it.lat + 0.3, it.lon + 0.4)
            }?.firstOrNull()
            val hit = local ?: geocoder.getFromLocationName(place, 1)?.firstOrNull()
            hit?.let { LatLon(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
    }
}
