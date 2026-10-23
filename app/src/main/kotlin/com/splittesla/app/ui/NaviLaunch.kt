package com.splittesla.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.splittesla.app.assistant.NaviApp

/**
 * Hands the car's destination to a navigation app on the phone.
 * The car's route can only be read over BLE, so the app recomputes its own route
 * to the same point. Missing apps open their Play Store page instead.
 */
object NaviLaunch {

    /**
     * The link that starts guidance to a point in [app]. Naver Map, TMAP and
     * Waze start turn-by-turn guidance straight away. Kakao Map opens a car
     * route one tap from guidance; launching Kakao Navi itself needs the Kakao
     * Navi SDK and a registered Kakao app key.
     */
    fun routeIntent(context: Context, app: NaviApp, lat: Double, lon: Double, label: String): Intent {
        val uri = when (app) {
            NaviApp.NAVER -> "nmap://navigation?dlat=$lat&dlng=$lon&dname=${Uri.encode(label)}&appname=${context.packageName}"
            // TMAP takes longitude as X and latitude as Y. The rGoX/rGoY form only
            // works on iOS; on Android it opens TMAP without a destination.
            NaviApp.TMAP -> "tmap://route?referrer=com.skt.Tmap&goalx=$lon&goaly=$lat&goalname=${Uri.encode(label)}"
            NaviApp.KAKAO -> "kakaomap://route?ep=$lat,$lon&by=CAR"
            NaviApp.WAZE -> "https://waze.com/ul?ll=$lat,$lon&navigate=yes&utm_source=${context.packageName}"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Waze's link is a web address; pin it to the app so a browser doesn't take it. The others
        // use their own schemes, which also reach older builds such as TMAP's ONE store package.
        if (app == NaviApp.WAZE) intent.setPackage(app.pkg)
        return intent
    }

    fun route(context: Context, app: NaviApp, lat: Double, lon: Double, label: String, adjacent: Boolean = false) {
        val intent = routeIntent(context, app, lat, lon, label)
        // In split screen, keep the map in the other half instead of covering this app.
        if (adjacent) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        launch(context, intent, app.pkg)
    }

    /** Opens the app on its home screen, for when the car has no destination yet. */
    fun open(context: Context, app: NaviApp) {
        val uri = when (app) {
            NaviApp.NAVER -> "nmap://map?appname=${context.packageName}"
            NaviApp.TMAP -> "tmap://"
            NaviApp.KAKAO -> "kakaomap://open"
            NaviApp.WAZE -> "waze://"
        }
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), app.pkg)
    }

    private fun launch(context: Context, intent: Intent, pkg: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openStore(context, pkg)
        }
    }

    private fun openStore(context: Context, pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
