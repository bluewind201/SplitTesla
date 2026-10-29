package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.camera.SectionRole
import com.splittesla.core.camera.SpeedCamera

/** A speed camera on the active route, with where it sits along the line. */
data class RouteCamera(val camera: SpeedCamera, val alongM: Double)

/**
 * The cameras that lie on the route itself, within [maxLateralM] of the line.
 * The national list has one record per direction at nearly the same spot, so
 * a camera for the other carriageway can still match; the alert service's
 * heading check still decides what is spoken.
 */
object RouteCameras {
    fun along(geometry: RouteGeometry, index: CameraIndex, maxLateralM: Double = 35.0, stepM: Double = 400.0): List<RouteCamera> {
        if (geometry.path.size < 2) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<RouteCamera>()
        var d = 0.0
        while (d <= geometry.lengthM) {
            val p = geometry.pointAt(d)
            for (c in index.near(p.lat, p.lon, stepM)) {
                if (!seen.add(c.id)) continue
                val hit = geometry.project(LatLon(c.latitude, c.longitude)) ?: continue
                if (hit.lateralM > maxLateralM) continue
                out += RouteCamera(c, hit.alongM)
            }
            d += stepM
        }
        // Section ends repeat their start's limit; keep both, they mark the section.
        return out.sortedBy { it.alongM }
    }

    /** The next camera ahead of [alongM], skipping the end of a section the car is inside. */
    fun next(cameras: List<RouteCamera>, alongM: Double): RouteCamera? =
        cameras.firstOrNull { it.alongM > alongM + 10 && it.camera.section != SectionRole.END }
            ?: cameras.firstOrNull { it.alongM > alongM + 10 }
}
