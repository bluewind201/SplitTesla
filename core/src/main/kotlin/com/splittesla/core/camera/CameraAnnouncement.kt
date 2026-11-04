package com.splittesla.core.camera

import com.splittesla.core.navi.GuidanceAnnouncer
import kotlin.math.roundToInt

/** Kept outside the Android service so the spoken evidence is testable on the JVM. */
fun CameraAlert.spokenText(): String {
    val limit = camera.limitKmh
    val zone = camera.zone?.let { "$it " }.orEmpty()
    val kind = if (camera.signal) "신호 과속" else "과속"
    return when (this) {
        is CameraAlert.Approaching ->
            "${(distanceM / 50).roundToInt() * 50}미터 앞 ${zone}$kind 카메라, 제한속도 $limit" + if (overLimit) ". 속도를 줄이세요" else ""
        is CameraAlert.Final ->
            "${GuidanceAnnouncer.distanceWords(distanceM)} 앞 ${zone}단속 카메라, 제한속도 $limit" + if (overLimit) ". 속도를 줄이세요" else ""
        is CameraAlert.SectionStart -> "구간단속 시작, 제한속도 $limit"
        is CameraAlert.SectionEnd -> if (averageVerified) "구간단속 종료, 평균 속도 ${averageKmh.roundToInt()}킬로미터"
            else "구간단속 종료 지점, 평균 속도 미확인"
    }
}
