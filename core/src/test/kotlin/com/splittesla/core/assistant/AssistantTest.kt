package com.splittesla.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantTest {
    @Test
    fun findsWakeWordAndReturnsTheCommandAfterIt() {
        assertEquals("강남역으로 가자", WakeWord.match("자비스 강남역으로 가자", "자비스"))
        assertEquals("에어컨 켜줘", WakeWord.match("헤이, 자비스! 에어컨 켜줘", "자비스"))
        assertEquals("", WakeWord.match("자비스", "자 비스"))
        assertEquals("가자", WakeWord.match("Hey Tesla 가자", "hey tesla"))
        assertNull(WakeWord.match("강남역으로 가자", "자비스"))
    }

    @Test
    fun toleratesOneLetterOffForLongerWords() {
        // A swapped syllable is fine for a three-letter word...
        assertEquals("날씨 어때", WakeWord.match("쟈비스 날씨 어때", "자비스"))
        // ...but two letters out of three is not enough.
        assertNull(WakeWord.match("비스 날씨 어때", "자비스"))
        // Four or more letters may also lose or gain one.
        assertEquals("가자", WakeWord.match("스플릿슬라 가자", "스플릿테슬라"))
        assertEquals("가자", WakeWord.matchAny(listOf("아무 말", "스플릿 테슬라 가자"), "스플릿테슬라"))
    }

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
}
