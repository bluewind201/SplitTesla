package com.splittesla.core.navi

import com.splittesla.core.voice.SpeechQueue
import kotlin.test.*

class Phase2SpeechContextTest {
    private val event = TurnSpeechContext(1, 2, 1, 100.0, 200.0, false, 1000)
    @Test fun eventKeysDistinguishRoutesGuidesAndStages() {
        assertNotEquals(event.eventKey, event.copy(routeVersion = 2).eventKey)
        assertNotEquals(event.eventKey, event.copy(guideIndex = 3).eventKey)
        assertNotEquals(event.eventKey, event.copy(stage = 2).eventKey)
    }
    @Test fun movedPassedChangedRouteAndChangedEvidenceAreInvalidAtStart() {
        assertTrue(event.valid(event, 1500, true))
        assertFalse(event.valid(event.copy(alongM = 182.0, distanceM = 118.0), 5900, true))
        assertFalse(event.valid(event.copy(guideIndex = 3), 1100, true))
        assertFalse(event.valid(event.copy(routeVersion = 2), 1100, true))
        assertFalse(event.valid(event.copy(stage = 2), 1100, true))
        assertFalse(event.valid(event.copy(estimated = true), 1100, true))
        assertFalse(event.valid(event, 1100, false))
    }
    @Test fun staleDistanceIsRejectedEvenBeforeTheAgeDeadline() {
        assertFalse(event.valid(event.copy(alongM = 111.0), 1100, true))
        assertFalse(event.valid(event.copy(distanceM = 189.0), 1100, true))
        assertFalse(event.valid(event, 2501, true))
        assertTrue(event.valid(event.copy(alongM = 110.0, distanceM = 190.0), 2500, true))
    }
    @Test fun queueValidatesAtSelectionAndKeepsTheLatestGuideEvent() {
        var current = event
        val q = SpeechQueue()
        q.offer(SpeechQueue.Request(1, "우회전", event.eventKey, 70, 9000,
            group = "turn", deduplicate = true, validAtStart = { event.valid(current, 1100, true) }), 1000)
        current = event.copy(guideIndex = 3)
        assertNull(q.next(1100))
        assertTrue(q.offer(SpeechQueue.Request(1, "우회전", current.eventKey, 70, 9000,
            deduplicate = true), 1100))
        assertEquals(current.eventKey, q.next(1100)?.key)
    }
    @Test fun firstLoopProgressIsNotEvidenceOfAnAnchor() {
        assertEquals(GuidancePositionState.CHECKING, GuidancePositionState.resolve(false, false, false))
        assertEquals(GuidancePositionState.CHECKING, GuidancePositionState.resolve(false, false, true))
    }
    @Test fun rejectedFixPreservesRecentValidEstimateWithoutExtendingItsDeadline() {
        fun live(monotonicNow: Long = 1500, now: Long = 15000, speed: Double? = 60.0, anchor: Boolean = true,
                 speedAt: Long = 14500, wasEstimating: Boolean = true) = GuidanceDisplayPolicy.estimateStillLive(
            wasEstimating, anchor, 1000, monotonicNow, now, 10000, speed, speedAt)
        assertTrue(live())
        assertTrue(live(monotonicNow = 2000))
        assertFalse(live(monotonicNow = 2501))
        assertFalse(live(speed = null))
        assertFalse(live(anchor = false))
        assertFalse(live(now = 40001, speedAt = 40000))
        assertFalse(live(speedAt = 10000))
        assertFalse(live(wasEstimating = false))
    }
}
