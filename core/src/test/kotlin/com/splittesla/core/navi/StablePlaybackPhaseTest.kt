package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.voice.SpeechOutcome
import kotlin.test.*

class StablePlaybackPhaseTest {
    private val line=(0..20).map { LatLon(37.5+it*.0009,127.0) }
    private val route=NaviRoute(RouteOption.FAST,line,emptyList(),listOf(
        RouteGuide(10,3,"우회전",1000,72000),RouteGuide(20,88,"목적지",1000,72000)),2000,144000,0,NaviPlace("끝",line.last()))
    private fun p(d:Double)=RouteProgress(1000-d,1000+d,70000,0.0,false,0,d,null,false)
    private fun heard(from:Double,speed:(Double)->Double):List<String> {
        val a=GuidanceAnnouncer();val text=mutableListOf<String>();var d=from;var time=0L
        while(d>8) {
            a.nextForPlayback(route,p(d),speed(d),time)?.let {
                a.claimStart(it);a.playback(it,SpeechOutcome.STARTED);a.playback(it,SpeechOutcome.COMPLETED);text+=it.text
            }
            d-=22;time+=1000
        }
        return text
    }
    @Test fun nearFirstHighTierThenSlowingHasOneImmediateNotice() {
        val text=heard(148.0) { if(it>100)85.0 else 70.0 }
        assertEquals(1,text.count { !it.contains("앞,") },text.toString())
    }
    @Test fun ordinarySeventyEightEightyTwoSeventyEightApproachHasOneImmediateNotice() {
        val text=heard(760.0) { if(it<=150 && it>80)82.0 else 78.0 }
        assertEquals(1,text.count { !it.contains("앞,") },text.toString())
    }
    @Test fun tierJitterAndSpeedingUpAfterImmediateGuidanceNeverRepeatIt() {
        var i=0
        val jitter=heard(760.0) { if(i++%2==0)79.0 else 81.0 }
        assertEquals(1,jitter.count { !it.contains("앞,") },jitter.toString())
        val speeding=heard(760.0) { if(it<=60)82.0 else 78.0 }
        assertEquals(1,speeding.count { !it.contains("앞,") },speeding.toString())
    }
    @Test fun interruptedImmediateStageSurvivesTierChangeAndCompletesOnlyOnce() {
        val a=GuidanceAnnouncer()
        val high=assertNotNull(a.nextForPlayback(route,p(148.0),85.0,1000))
        a.claimStart(high);a.playback(high,SpeechOutcome.STARTED)
        a.playback(high,SpeechOutcome.INTERRUPTED)
        val low=assertNotNull(a.nextForPlayback(route,p(120.0),70.0,2000))
        assertFalse(low.text.contains("앞,"),low.text)
        assertTrue(low.attempt>high.attempt)
        a.playback(high,SpeechOutcome.COMPLETED)
        assertTrue(a.claimStart(low))
        a.playback(low,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(60.0),70.0,3000))
    }
    @Test fun aCompletedAdvanceDistanceIsNotAnotherStageAfterChangingTier() {
        val a=GuidanceAnnouncer()
        val low=assertNotNull(a.nextForPlayback(route,p(280.0),30.0,1000))
        a.playback(low,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(260.0),60.0,2000))
        val last=assertNotNull(a.nextForPlayback(route,p(70.0),60.0,3000))
        a.playback(last,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(20.0),30.0,4000))
    }
    @Test fun unheardAdvancePhaseRefreshesAcrossTierChangeWithoutMovingBackOutwards() {
        val a=GuidanceAnnouncer()
        val high=assertNotNull(a.nextForPlayback(route,p(480.0),100.0,1000))
        a.playback(high,SpeechOutcome.DROPPED)
        val refreshed=assertNotNull(a.nextForPlayback(route,p(400.0),60.0,2000))
        assertTrue(refreshed.text.startsWith("400미터 앞"),refreshed.text)
        a.playback(refreshed,SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route,p(380.0),60.0,3000))
        assertNotNull(a.nextForPlayback(route,p(280.0),60.0,4000))
    }

}
