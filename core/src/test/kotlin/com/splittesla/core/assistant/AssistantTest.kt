package com.splittesla.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantTest {
    @Test
    fun understandsCommonPhrasingsWithoutAModel() {
        assertEquals(VoiceAction.Navigate("강남역"), VoiceRules.parse("강남역으로 가자"))
        assertEquals(VoiceAction.Navigate("집"), VoiceRules.parse("집으로 안내해줘"))
        assertEquals(VoiceAction.Navigate("부산 해운대"), VoiceRules.parse("부산 해운대까지 길 안내"))
        assertEquals(VoiceAction.AddStop("스타벅스"), VoiceRules.parse("가는 길에 스타벅스 들러줘"))
        assertEquals(VoiceAction.AddStop("편의점"), VoiceRules.parse("편의점에 들렀다 가자"))
        assertEquals(VoiceAction.NearestCharger, VoiceRules.parse("가까운 충전소로 가자"))
        assertEquals(VoiceAction.Climate(true), VoiceRules.parse("에어컨 켜줘"))
        assertEquals(VoiceAction.Climate(false), VoiceRules.parse("히터 꺼"))
        assertEquals(VoiceAction.Temperature(22f), VoiceRules.parse("온도 22도로 맞춰줘"))
        assertEquals(VoiceAction.Lock, VoiceRules.parse("문 잠가줘"))
        assertEquals(VoiceAction.RouteStatus, VoiceRules.parse("언제 도착해?"))
        assertEquals(VoiceAction.Weather, VoiceRules.parse("도착지 날씨 어때"))
        assertEquals(VoiceAction.Announcements(false), VoiceRules.parse("조용히 해"))
        assertEquals(VoiceAction.None, VoiceRules.parse("오늘 기분 어때"))
    }

    @Test
    fun mapsModelActionNames() {
        assertEquals(VoiceAction.Navigate("서울역"), VoiceAction.fromName("navigate", "서울역", null))
        assertEquals(VoiceAction.None, VoiceAction.fromName("navigate", " ", null))
        assertEquals(VoiceAction.Temperature(21.5f), VoiceAction.fromName("SET_TEMP", null, 21.5))
        assertEquals(VoiceAction.None, VoiceAction.fromName("set_temp", null, 40.0))
        assertEquals(VoiceAction.None, VoiceAction.fromName("open_trunk", null, null))
    }

    @Test
    fun keepsOnlyPointsAheadOnTheRoute() {
        val seoul = LatLon(37.5665, 126.9780)
        val busan = LatLon(35.1796, 129.0756)
        assertTrue(Geo.distanceKm(seoul, busan) in 320.0..330.0)
        val daejeonSide = LatLon(36.35, 127.9) // near the line, halfway
        assertTrue(Geo.aheadOnRoute(seoul, busan, daejeonSide))
        assertFalse(Geo.aheadOnRoute(seoul, busan, LatLon(37.9, 126.6))) // behind the start
        assertFalse(Geo.aheadOnRoute(seoul, busan, LatLon(35.8, 126.7))) // far to the west
    }

    @Test
    fun sendsTheRouteAgainOnlyForAnotherPlace() {
        val gangnam = LatLon(37.4979, 127.0276)
        assertTrue(RouteShare.isNewDestination(null, gangnam))
        // The car re-snapping the same destination a few metres away is not a new route.
        assertFalse(RouteShare.isNewDestination(gangnam, LatLon(37.4981, 127.0279)))
        // A destination a block or more away is.
        assertTrue(RouteShare.isNewDestination(gangnam, LatLon(37.5010, 127.0276)))
    }
}
