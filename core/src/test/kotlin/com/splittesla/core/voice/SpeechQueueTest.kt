package com.splittesla.core.voice

import kotlin.test.*

class SpeechQueueTest {
    private fun r(owner: Long = 1, key: String = "a", priority: Int = 10, expires: Long = 10000, group: String? = null) =
        SpeechQueue.Request(owner, key, key, priority, expires, group)

    @Test fun initializationPreservesMultipleRequestsAndPriorityIsStable() {
        val q = SpeechQueue()
        q.offer(r(key = "assistant"), 0)
        q.offer(r(key = "camera", priority = 60), 0)
        q.offer(r(key = "turn", priority = 70), 0)
        q.offer(r(key = "final", priority = 100), 0)
        assertEquals("final", q.next(0)?.key)
        assertNull(q.next(0))
        q.finish(); assertEquals("turn", q.next(0)?.key)
        q.finish(); assertEquals("camera", q.next(0)?.key)
        q.finish(); assertEquals("assistant", q.next(0)?.key)
    }
    @Test fun cancellationAndReleaseCannotCutAnotherOwner() {
        val q = SpeechQueue()
        q.offer(r(owner = 1, key = "camera", priority = 100), 0)
        q.next(0)
        q.offer(r(owner = 2, key = "turn"), 0)
        assertFalse(q.cancel(2))
        assertEquals("camera", q.active?.key)
        assertTrue(q.cancel(1))
        assertNull(q.active)
        assertNull(q.next(0))
    }
    @Test fun urgentWaitingWorkDoesNotPreemptActiveSpeech() {
        val q = SpeechQueue()
        q.offer(r(key = "turn", priority = 70), 0); q.next(0)
        q.offer(r(key = "final", priority = 100), 0)
        assertEquals("turn", q.active?.key)
        assertNull(q.next(0))
        q.finish(); assertEquals("final", q.next(0)?.key)
    }
    @Test fun expiryDuplicateAndCooldownUseMonotonicTime() {
        val q = SpeechQueue()
        assertFalse(q.offer(r(expires = 0), 0))
        assertTrue(q.offer(r(expires = 10), 0))
        assertFalse(q.offer(r(), 0))
        assertNull(q.next(10))
        assertTrue(q.offer(r(), 11)); q.next(11)
        assertFalse(q.offer(r(), 11)); q.finish()
        assertFalse(q.offer(r(), 8010))
        assertTrue(q.offer(r(), 8011))
    }
    @Test fun cameraStageSupersedesOnlyOwnWaitingStage() {
        val q = SpeechQueue()
        q.offer(r(key = "old", group = "camera"), 0)
        q.offer(r(owner = 2, key = "other", group = "camera"), 0)
        q.offer(r(key = "final", priority = 100, group = "camera"), 0)
        assertEquals("final", q.next(0)?.key); q.finish()
        assertEquals("other", q.next(0)?.key); q.finish()
        assertNull(q.next(0))
    }
    @Test fun boundedQueueKeepsEssentialWorkAndClearCancelsAll() {
        val q = SpeechQueue(2)
        q.offer(r(key = "one"), 0); q.offer(r(key = "two"), 0)
        assertFalse(q.offer(r(key = "three"), 0))
        assertTrue(q.offer(r(key = "final", priority = 100), 0))
        assertEquals("final", q.next(0)?.key)
        q.clear(); assertNull(q.active); assertNull(q.next(0))
    }
    @Test fun finalPreemptsTurnsButOrdinaryApproachDoesNot() {
        val q = SpeechQueue()
        q.offer(r(key = "assistant"), 0); q.next(0)
        assertTrue(q.shouldPreempt(r(key = "final", priority = 100)))
        assertFalse(q.shouldPreempt(r(key = "reroute", priority = 30)))
        q.finish()
        q.offer(r(key = "turn", priority = 70), 0); q.next(0)
        assertTrue(q.shouldPreempt(r(key = "final", priority = 100)))
        assertFalse(q.shouldPreempt(r(key = "approach", priority = 60)))
    }
    @Test fun equalPrioritiesAreFifoAndExpiredUrgencyDoesNotBlock() {
        val q = SpeechQueue()
        q.offer(r(key = "first"), 0); q.offer(r(key = "second"), 0)
        q.offer(r(key = "expired", priority = 100, expires = 1), 0)
        assertEquals("first", q.next(1)?.key)
        q.finish(); assertEquals("second", q.next(1)?.key)
    }
}
