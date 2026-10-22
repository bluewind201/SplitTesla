package com.splittesla.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaviTextTest {
    @Test
    fun readsCameraLimitAndDistance() {
        val hint = NaviText.parse(listOf("강남대로", "300m 앞 과속카메라 60km/h", "1.2km 앞 우회전"))!!
        assertEquals(60, hint.cameraLimitKmh)
        assertEquals(300, hint.cameraDistanceM)
        assertEquals("과속카메라", hint.cameraKind)
        assertEquals("강남대로 · 1.2km 앞 우회전", hint.guidance)
    }

    @Test
    fun readsLimitWrittenAsWord() {
        val hint = NaviText.parse(listOf("구간단속 제한 100", "남은 거리 1.5km"))!!
        assertEquals(100, hint.cameraLimitKmh)
        assertEquals(1500, hint.cameraDistanceM)
        assertEquals("구간단속", hint.cameraKind)
    }

    @Test
    fun guidanceWithoutCamera() {
        val hint = NaviText.parse(listOf("500m 후 좌회전", "도착 18:40"))!!
        assertNull(hint.cameraLimitKmh)
        assertNull(hint.cameraKind)
        assertEquals("500m 후 좌회전 · 도착 18:40", hint.guidance)
        assertNull(NaviText.parse(listOf(" ", "")))
    }
}
