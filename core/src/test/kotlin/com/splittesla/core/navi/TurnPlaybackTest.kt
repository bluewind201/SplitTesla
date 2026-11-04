package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.voice.SpeechOutcome
import kotlin.test.*

class TurnPlaybackTest {
    private val path = (0..20).map { LatLon(37.5 + it * .0009, 127.0) }
    private val route = NaviRoute(RouteOption.FAST, path, emptyList(), listOf(
        RouteGuide(10, 3, "우회전", 1000, 72000), RouteGuide(20, 88, "목적지", 1000, 72000)),
        2000, 144000, 0, NaviPlace("끝", path.last()))
    private fun p(d: Double = 25.0) = RouteProgress(1000-d, 1000+d, 70000, 0.0, false, 0, d, null, false)
    @Test fun interruptedFinalTurnRegainsOpportunityUsingCurrentProgress() {
        val a = GuidanceAnnouncer()
        val first = assertNotNull(a.nextForPlayback(route,p(),30.0,1000))
        a.playback(first,SpeechOutcome.ACCEPTED)
        assertNull(a.nextForPlayback(route,p(20.0),30.0,2000))
        a.playback(first,SpeechOutcome.INTERRUPTED)
        val replacement = assertNotNull(a.nextForPlayback(route,p(15.0),30.0,2100))
        assertNotEquals(first.attempt,replacement.attempt)
        a.playback(replacement,SpeechOutcome.ACCEPTED)
        a.playback(replacement,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(10.0),30.0,3000))
    }
    @Test fun highSpeedWaitingTurnRefreshesWithoutDuplicatingAnAcceptedSentence() {
        val a=GuidanceAnnouncer()
        val first=assertNotNull(a.nextForPlayback(route,p(70.0),60.0,1000))
        val latest=assertNotNull(a.nextForPlayback(route,p(50.0),60.0,2000))
        a.playback(first,SpeechOutcome.DROPPED) // old replacement callback cannot remove new work
        a.playback(latest,SpeechOutcome.ACCEPTED)
        assertNull(a.nextForPlayback(route,p(30.0),60.0,3000))
        a.playback(latest,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(10.0),60.0,4000))
    }
    @Test fun droppedFinalTurnCanRegenerateButNeverAfterPassingOrLosingConfidence() {
        val a=GuidanceAnnouncer()
        val first=assertNotNull(a.nextForPlayback(route,p(),30.0,1000))
        a.playback(first,SpeechOutcome.DROPPED)
        assertNull(a.nextForPlayback(route,p().copy(nextGuideIndex=1,nextGuideDistanceM=1000.0),30.0,2000))
        assertNull(a.nextForPlayback(route,p().copy(positionUncertain=true),30.0,2000))
        assertNull(a.nextForPlayback(route,p().copy(offRoute=true),30.0,2000))
        assertNotNull(a.nextForPlayback(route,p(15.0),30.0,2000))
    }
    @Test fun pendingDoesNotRepeatOnEverySubsecondFixAndOldFeedbackCannotAffectNewRoute() {
        val a=GuidanceAnnouncer()
        val old=assertNotNull(a.nextForPlayback(route,p(),30.0,1000))
        assertNull(a.nextForPlayback(route,p(24.0),30.0,1100))
        a.reset()
        val fresh=assertNotNull(a.nextForPlayback(route,p(),30.0,2000))
        a.playback(old,SpeechOutcome.COMPLETED)
        a.playback(fresh,SpeechOutcome.ACCEPTED)
        assertNull(a.nextForPlayback(route,p(20.0),30.0,3000))
    }
    @Test fun droppedEarlyStageRegeneratesCurrentDistanceInsteadOfWaitingUntilNextStage() {
        val a=GuidanceAnnouncer()
        val first=assertNotNull(a.nextForPlayback(route,p(290.0),60.0,1000))
        a.playback(first,SpeechOutcome.DROPPED)
        val fresh=assertNotNull(a.nextForPlayback(route,p(220.0),60.0,5000))
        assertTrue(fresh.text.startsWith("200미터 앞"),fresh.text)
        a.playback(fresh,SpeechOutcome.ACCEPTED)
        a.playback(fresh,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(200.0),60.0,6000))
    }

    @Test fun startReservationPreventsConcurrentFixFromReplacingAcceptedWork() {
        val a=GuidanceAnnouncer()
        val first=assertNotNull(a.nextForPlayback(route,p(),30.0,1000))
        assertTrue(a.claimStart(first))
        assertNull(a.nextForPlayback(route,p(15.0),30.0,2000))
        a.playback(first,SpeechOutcome.DROPPED)
        val latest=assertNotNull(a.nextForPlayback(route,p(10.0),30.0,2100))
        assertFalse(a.claimStart(first))
        assertTrue(a.claimStart(latest))
    }

}
