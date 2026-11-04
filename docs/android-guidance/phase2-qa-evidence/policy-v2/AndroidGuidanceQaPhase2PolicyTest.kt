package com.splittesla.core.qa

import com.splittesla.core.navi.TurnSpeechContext
import com.splittesla.core.voice.SpeechQueue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Policy v2 acceptance (PM msg_15c90d245ace). Kept in the evidence folder and copied into the
 * snapshot only after freeze, because it uses a speech API that was still changing when written.
 * f6 moved to q1/Q1Scenarios: it could not tell the announcer a sentence was cut, so it would fail on any implementation.
 */
class AndroidGuidanceQaPhase2PolicyTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run phase-2 policy QA")

    private fun req(key: String, p: Int, valid: () -> Boolean = { true }, dedupe: Boolean = true, exp: Long = 100_000) =
        SpeechQueue.Request(1, key, key, p, exp, deduplicate = dedupe, validAtStart = valid)

    @Test fun validityIsCheckedAtStartNotAtOffer() {
        val q = SpeechQueue(); var ok = true; var calls = 0
        assertTrue(q.offer(req("턴", 70, { calls++; ok }), 0))
        assertEquals(0, calls)
        ok = false
        assertNull(q.next(10)); assertEquals(1, calls)
    }

    @Test fun invalidWaitingTurnGivesWayToTheNextSentence() {
        val q = SpeechQueue()
        q.offer(req("낡은 턴", 70, { false }), 0); q.offer(req("예고", 60), 0)
        assertEquals("예고", q.next(0)?.key)
    }

    @Test fun manyInvalidSentencesDoNotBlockTheQueue() {
        val q = SpeechQueue(capacity = 32)
        repeat(32) { q.offer(req("낡은$it", 70, { false }), 0) }
        assertNull(q.next(0))
        assertTrue(q.offer(req("새 문장", 70), 1)); assertEquals("새 문장", q.next(1)?.key)
    }

    @Test fun rejectedSentenceDoesNotBurnItsDuplicateKey() {
        val q = SpeechQueue()
        q.offer(req("k", 60, { false }), 0); assertNull(q.next(0))
        assertTrue(q.offer(req("k", 60), 1), "a sentence never heard must not block its key for 8 s")
    }

    @Test fun sameEventKeyIsSuppressedDifferentEventsAreNot() {
        val q = SpeechQueue()
        q.offer(SpeechQueue.Request(1, "우회전.", "turn:1:3:2", 70, 1_000, deduplicate = true), 0); q.next(0); q.finish()
        assertFalse(q.offer(SpeechQueue.Request(1, "우회전.", "turn:1:3:2", 70, 2_000, deduplicate = true), 500))
        assertTrue(q.offer(SpeechQueue.Request(1, "우회전.", "turn:1:4:2", 70, 2_000, deduplicate = true), 500))
    }

    // ── TurnSpeechContext ──
    private val c = TurnSpeechContext(7, 3, 2, 500.0, 80.0, false, 1_000, listOf(700.0, 300.0, 80.0))

    @Test fun contextValidOnlyForSameRouteGuideStageAndPositionKind() {
        assertTrue(c.valid(c, 1_000, true))
        assertFalse(c.valid(c, 1_000, false), "position unusable")
        assertFalse(c.valid(c.copy(routeVersion = 8), 1_000, true), "route changed")
        assertFalse(c.valid(c.copy(guideIndex = 4), 1_000, true), "turn passed")
        assertFalse(c.valid(c.copy(guideIndex = null), 1_000, true), "no next turn")
        assertFalse(c.valid(c.copy(stage = 1), 1_000, true), "stage changed")
        assertFalse(c.valid(c.copy(estimated = true), 1_000, true), "real became estimated")
    }

    @Test fun contextExpiresAndRejectsBadNumbersAndClockSteps() {
        assertTrue(c.valid(c, 2_500, true))
        assertFalse(c.valid(c, 2_501, true), "older than 1.5 s")
        assertFalse(c.valid(c, 999, true), "clock before creation")
        assertTrue(c.valid(c.copy(alongM = 510.0, distanceM = 70.0), 1_000, true))
        assertFalse(c.valid(c.copy(alongM = 511.0, distanceM = 69.0), 1_000, true), "moved too far")
        assertFalse(c.valid(c.copy(alongM = Double.NaN), 1_000, true))
        assertFalse(c.valid(c.copy(distanceM = Double.NaN), 1_000, true))
        assertFalse(c.valid(c.copy(distanceM = null), 1_000, true))
    }

    @Test fun eventKeySeparatesRouteGuideAndStage() {
        val keys = listOf(c, c.copy(routeVersion = 8), c.copy(guideIndex = 4), c.copy(stage = 1)).map { it.eventKey }
        assertEquals(4, keys.toSet().size)
    }
}
