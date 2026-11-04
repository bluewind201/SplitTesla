package com.splittesla.core.camera

/** Speed-based remains the default. Limit tiers are observations; catch-up is our own design. */
enum class CameraWarningPreset {
    SPEED, LIMIT;
    fun stages(limitKmh: Int, speedKmh: Double): List<Double> = when (this) {
        SPEED -> listOf((speedKmh / 3.6 * 12).coerceIn(200.0, 600.0))
        LIMIT -> when {
            limitKmh >= 80 -> listOf(1000.0, 500.0)
            limitKmh >= 50 -> listOf(600.0, 300.0)
            else -> listOf(300.0)
        }
    }
}

/** Only the nearest eligible stage is emitted; skipped older stages are consumed atomically. */
class CameraWarningStages {
    private val consumed = mutableMapOf<String, MutableSet<Double>>()
    fun due(id: String, stages: List<Double>, distanceM: Double): Boolean {
        val seen = consumed.getOrPut(id) { mutableSetOf() }
        val crossed = stages.filter { distanceM <= it }
        val due = crossed.any { it !in seen }
        seen.addAll(crossed)
        return due
    }
    fun consume(id: String, stages: List<Double>) { consumed.getOrPut(id) { mutableSetOf() }.addAll(stages) }
    fun retain(ids: Set<String>) { consumed.keys.retainAll(ids) }
}
