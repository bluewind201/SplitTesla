package com.splittesla.core.navi

/** Shared wording for main turn UI and foreground notification; no sensor thresholds change. */
enum class GuidancePositionState(val label: String) {
    CONFIRMED(""), CHECKING("위치 확인 중"), ESTIMATING("위치 추정 중 · 턴/거리 예상"),
    HELD("안내 일시 보류 · 턴/거리 확인 필요");
    companion object {
        fun resolve(gpsOk: Boolean, hasProgress: Boolean, estimatingNow: Boolean): GuidancePositionState = when {
            gpsOk -> CONFIRMED
            !hasProgress -> CHECKING
            estimatingNow -> ESTIMATING
            else -> HELD
        }
    }
}
