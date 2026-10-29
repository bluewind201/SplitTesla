package com.splittesla.core.navi

/**
 * Battery use for the rest of a route. Starts from a typical Model 3/Y curve
 * (Wh/km by speed, 20 °C) and scales it by what this car has actually used on
 * this drive ([ConsumptionLearner]). NAVER gives average speeds only for
 * major-road sections; the rest of the route uses the trip's average speed.
 */
object EnergyModel {
    private val speeds = doubleArrayOf(0.0, 20.0, 30.0, 50.0, 60.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0, 140.0)
    private val whPerKm = doubleArrayOf(150.0, 130.0, 120.0, 115.0, 120.0, 135.0, 145.0, 158.0, 172.0, 190.0, 210.0, 232.0)

    /** Typical Wh/km at [speedKmh], with heating or cooling load from [outsideC]. */
    fun whPerKm(speedKmh: Double, outsideC: Double? = null): Double {
        val v = speedKmh.coerceIn(0.0, speeds.last())
        val i = speeds.indexOfFirst { it >= v }.coerceAtLeast(1)
        val t = (v - speeds[i - 1]) / (speeds[i] - speeds[i - 1])
        val base = whPerKm[i - 1] + (whPerKm[i] - whPerKm[i - 1]) * t
        return base * climateFactor(outsideC)
    }

    fun climateFactor(c: Double?): Double = when {
        c == null -> 1.0
        c < 0 -> 1.25
        c < 10 -> 1.12
        c > 30 -> 1.06
        else -> 1.0
    }

    /** kWh needed from [fromAlongM] to the end of the route. */
    fun kwhToGo(tracker: RouteTracker, fromAlongM: Double, factor: Double, outsideC: Double?): Double {
        val route = tracker.route
        val geo = tracker.geometry
        val total = geo.lengthM
        if (total <= 0) return 0.0
        val avgSpeed = (route.distanceM / 1000.0) / (route.durationMs / 3_600_000.0).coerceAtLeast(1e-6)
        var covered = 0.0
        var wh = 0.0
        for (s in route.sections) {
            val start = geo.alongOf(s.pointIndex)
            val end = geo.alongOf(s.pointIndex + s.pointCount)
            val metres = (end - maxOf(start, fromAlongM)).coerceAtLeast(0.0)
            if (metres <= 0) continue
            val v = if (s.speedKmh > 0) s.speedKmh.toDouble() else avgSpeed
            wh += metres / 1000.0 * whPerKm(v, outsideC)
            covered += metres
        }
        val rest = (total - fromAlongM - covered).coerceAtLeast(0.0)
        wh += rest / 1000.0 * whPerKm(avgSpeed, outsideC)
        return wh * factor / 1000.0
    }

    /** Battery percent left on arrival, or null without a known battery level. */
    fun socAtArrival(socNow: Double?, remainingKwh: Double?, fullKwh: Double?, needKwh: Double): Double? {
        val full = fullKwh?.takeIf { it > 20 } ?: DEFAULT_PACK_KWH
        val now = remainingKwh ?: socNow?.let { it / 100.0 * full } ?: return null
        return (now - needKwh) / full * 100.0
    }

    /** Usable pack size used when the car's own figure is unknown (Model Y Long Range). */
    const val DEFAULT_PACK_KWH = 75.0
}

/**
 * Learns this drive's Wh/km against the model curve from the battery power
 * the Commander streams. Below 2 km of samples the model curve is used as is.
 */
class ConsumptionLearner {
    private var actualWh = 0.0
    private var modelWh = 0.0
    private var metres = 0.0

    /** One sample: battery power out (kW, positive when driving), speed and the time since the last sample. */
    fun observe(powerKw: Double, speedKmh: Double, seconds: Double, outsideC: Double?) {
        if (seconds <= 0 || seconds > 10 || speedKmh < 5) return
        val m = speedKmh / 3.6 * seconds
        metres += m
        actualWh += powerKw * seconds / 3.6
        modelWh += m / 1000.0 * EnergyModel.whPerKm(speedKmh, outsideC)
    }

    /** Actual over model, clamped so one hill or one launch cannot swing the forecast. */
    val factor: Double
        get() = if (metres < 2000 || modelWh <= 0) 1.0 else (actualWh / modelWh).coerceIn(0.6, 1.8)

    val whPerKm: Double? get() = if (metres < 2000) null else actualWh / (metres / 1000.0)
}
