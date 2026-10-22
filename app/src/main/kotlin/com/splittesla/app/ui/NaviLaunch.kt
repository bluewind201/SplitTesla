package com.splittesla.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands the car's destination to a Korean navigation app on the phone.
 * The car's route can only be read over BLE, so the app recomputes its own route
 * to the same point. Missing apps open their Play Store page instead.
 */
object NaviLaunch {
    private const val NAVER_MAP = "com.nhn.android.nmap"
    private const val KAKAO_MAP = "net.daum.android.map"

    /** Naver Map starts turn-by-turn guidance straight away. */
    fun naver(context: Context, lat: Double, lon: Double, label: String) {
        val uri = Uri.parse(
            "nmap://navigation?dlat=$lat&dlng=$lon&dname=${Uri.encode(label)}&appname=${context.packageName}",
        )
        launch(context, uri, NAVER_MAP)
    }

    /**
     * Kakao Map opens a car route to the point, one tap from guidance. Launching
     * Kakao Navi itself needs the Kakao Navi SDK and a registered Kakao app key.
     */
    fun kakao(context: Context, lat: Double, lon: Double) {
        launch(context, Uri.parse("kakaomap://route?ep=$lat,$lon&by=CAR"), KAKAO_MAP)
    }

    private fun launch(context: Context, uri: Uri, pkg: String) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
