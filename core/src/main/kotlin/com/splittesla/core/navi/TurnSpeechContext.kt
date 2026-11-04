package com.splittesla.core.navi

import kotlin.math.abs

/** Evidence captured with one route/guide/stage event, checked again immediately before TTS. */
data class TurnSpeechContext(
    val routeVersion: Long, val guideIndex: Int?, val stage: Int,
    val alongM: Double, val distanceM: Double?, val estimated: Boolean, val createdAt: Long,
    val thresholds: List<Double> = emptyList(),
    val stableStage: Int? = null,
) {
    val eventKey: String get() = "turn:$routeVersion:$guideIndex:${stableStage ?: stage}"
    fun valid(current: TurnSpeechContext, now: Long, positionUsable: Boolean): Boolean =
        positionUsable && now - createdAt in 0..1500 && routeVersion == current.routeVersion &&
            guideIndex == current.guideIndex && stage == current.stage && estimated == current.estimated &&
            alongM.isFinite() && current.alongM.isFinite() && abs(alongM - current.alongM) <= 10 &&
            if (distanceM == null) current.distanceM == null
            else current.distanceM?.let { it.isFinite() && distanceM.isFinite() && abs(it - distanceM) <= 10 } == true
}
