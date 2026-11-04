package com.splittesla.core.navi

import kotlin.test.*

class GuidancePositionStateTest {
    @Test fun noFixAndNoProgressMeansChecking() {
        assertEquals(GuidancePositionState.CHECKING, GuidancePositionState.resolve(false, false, false))
    }
    @Test fun weakFixWithoutSpeedAndExpiredEstimationAreHeld() {
        assertEquals(GuidancePositionState.HELD, GuidancePositionState.resolve(false, true, false))
    }
    @Test fun onlyActiveAuthorizedIntegrationMayClaimEstimation() {
        assertEquals(GuidancePositionState.ESTIMATING, GuidancePositionState.resolve(false, true, true))
        assertEquals(GuidancePositionState.CONFIRMED, GuidancePositionState.resolve(true, true, false))
    }
}
