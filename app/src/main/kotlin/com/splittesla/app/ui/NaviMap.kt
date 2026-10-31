package com.splittesla.app.ui

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import com.splittesla.app.camera.CameraState
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.navi.NaviUi
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.glass
import com.splittesla.core.assistant.LatLon
import kotlinx.coroutines.delay

/**
 * The dashboard's portrait map: NAVER's night navigation map turned so the
 * way ahead points up, with the route (driven part greyed), the cameras on it
 * and the destination. It uses the NCP key from the 길안내 API settings, which
 * needs Dynamic Map enabled for this app. Without the key, or when NAVER
 * refuses it, the route is drawn on its own instead.
 */
@Composable
fun NaviMap(navi: NaviUi, modifier: Modifier = Modifier) {
    val keyId = NaviHub.settings.ncpKeyId
    var failed by remember(keyId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    if (keyId.isBlank() || failed) {
        if (navi.route != null) {
            RouteMap(navi, follow = true, modifier)
        } else {
            Box(modifier.glass(20.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (keyId.isBlank()) "설정 탭에 네이버 지도 키를 넣으면 지도가 나옵니다"
                    else "네이버 지도 인증 실패: NCP 콘솔에서 Dynamic Map과 앱 패키지 com.splittesla.app을 등록해 주세요",
                    fontSize = 12.sp, color = Neon.muted, textAlign = TextAlign.Center,
                )
            }
        }
        return
    }
    val mapView = rememberMapView(keyId) { failed = true } ?: return
    var map by remember(mapView) { mutableStateOf<NaverMap?>(null) }
    val density = LocalDensity.current
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.mapType = NaverMap.MapType.Navi
            m.isNightModeEnabled = true
            m.setLayerGroupEnabled(NaverMap.LAYER_GROUP_TRAFFIC, true)
            m.uiSettings.apply {
                isZoomControlEnabled = false
                isCompassEnabled = false
                isScaleBarEnabled = false
                isLocationButtonEnabled = false
                isScrollGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            m.locationOverlay.isVisible = true
            map = m
        }
    }

    // Without guidance there are no snapped fixes; follow whatever position the app knows.
    val idle by produceState<Pair<LatLon, Float?>?>(null, navi.guiding) {
        while (!navi.guiding) {
            value = NaviHub.bestPosition()?.let { p ->
                p to CameraState.fix?.takeIf { it.hasBearing() && it.speed > 3f }?.bearing
            }
            delay(1000)
        }
    }
    val here = if (navi.guiding) navi.position else idle?.first ?: navi.position
    val heading = if (navi.guiding) navi.bearing else idle?.second ?: navi.bearing

    val path = remember { PathOverlay() }
    val goal = remember { Marker() }
    val cameraMarkers = remember { mutableListOf<Marker>() }

    LaunchedEffect(map, navi.route) {
        val m = map ?: return@LaunchedEffect
        val route = navi.route
        if (route == null || route.path.size < 2) {
            path.map = null
            goal.map = null
            return@LaunchedEffect
        }
        with(density) {
            path.width = 9.dp.roundToPx()
            path.outlineWidth = 2.dp.roundToPx()
        }
        path.color = Neon.lime.toArgb()
        path.outlineColor = Neon.bg.toArgb()
        path.passedColor = Neon.faint.toArgb()
        path.passedOutlineColor = Neon.bg.toArgb()
        path.coords = route.path.map { LatLng(it.lat, it.lon) }
        path.map = m
        goal.position = LatLng(route.goal.point.lat, route.goal.point.lon)
        goal.iconTintColor = Neon.lime.toArgb()
        goal.captionText = route.goal.name
        goal.map = m
    }

    LaunchedEffect(map, navi.cameras) {
        val m = map ?: return@LaunchedEffect
        cameraMarkers.forEach { it.map = null }
        cameraMarkers.clear()
        navi.cameras.take(60).forEach { c ->
            cameraMarkers += Marker().apply {
                position = LatLng(c.camera.latitude, c.camera.longitude)
                iconTintColor = Neon.red.toArgb()
                width = with(density) { 18.dp.roundToPx() }
                height = with(density) { 24.dp.roundToPx() }
                captionText = "${c.camera.limitKmh}"
                this.map = m
            }
        }
    }

    LaunchedEffect(map, navi.progress?.alongM) {
        val route = navi.route ?: return@LaunchedEffect
        val along = navi.progress?.alongM ?: return@LaunchedEffect
        if (route.distanceM > 0) path.progress = (along / route.distanceM).coerceIn(0.0, 1.0)
    }

    LaunchedEffect(map, here, heading) {
        val m = map ?: return@LaunchedEffect
        val p = here ?: return@LaunchedEffect
        val at = LatLng(p.lat, p.lon)
        m.locationOverlay.position = at
        heading?.let { m.locationOverlay.bearing = it }
        // The car sits low in the tall frame so more of the road ahead shows.
        m.setContentPadding(0, (mapView.height * 0.45f).toInt(), 0, 0)
        val zoom = m.cameraPosition.zoom.takeIf { it > 5 } ?: 16.0
        m.moveCamera(
            CameraUpdate.toCameraPosition(CameraPosition(at, zoom, 0.0, (heading ?: 0f).toDouble()))
                .animate(CameraAnimation.Easing, 800),
        )
    }

    Box(modifier.clip(shape).background(Glass.backdropBottom, shape).glass(20.dp)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A MapView tied to the screen's lifecycle, signed in with [keyId]. Null when
 * the map cannot be created (no native library for this phone); [onFail] is
 * also called when NAVER rejects the key.
 */
@Composable
private fun rememberMapView(keyId: String, onFail: () -> Unit): MapView? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(keyId) {
        try {
            NaverMapSdk.getInstance(context).apply {
                client = NaverMapSdk.NcpKeyClient(keyId)
                onAuthFailedListener = NaverMapSdk.OnAuthFailedListener { onFail() }
            }
            // A TextureView map clips to the rounded pane and lets the cards on top blend in.
            MapView(context, NaverMapOptions().useTextureView(true))
        } catch (e: Throwable) {
            null
        }
    }
    if (mapView == null) {
        LaunchedEffect(Unit) { onFail() }
        return null
    }
    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        mapView.onCreate(Bundle())
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
