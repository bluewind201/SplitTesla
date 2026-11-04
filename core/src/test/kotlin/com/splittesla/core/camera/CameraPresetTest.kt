package com.splittesla.core.camera

import kotlin.test.*

class CameraPresetTest {
    @Test fun observedLimitBoundariesAndLegacyDefaults() {
        assertEquals(listOf(1000.0, 500.0), CameraWarningPreset.LIMIT.stages(80, 0.0))
        assertEquals(listOf(600.0, 300.0), CameraWarningPreset.LIMIT.stages(79, 200.0))
        assertEquals(listOf(600.0, 300.0), CameraWarningPreset.LIMIT.stages(50, 0.0))
        assertEquals(listOf(300.0), CameraWarningPreset.LIMIT.stages(49, 200.0))
        assertEquals(listOf(200.0), CameraWarningPreset.SPEED.stages(100, 0.0))
        assertEquals(listOf(600.0), CameraWarningPreset.SPEED.stages(30, 200.0))
    }
    @Test fun skippingWindowsEmitsOneCurrentWarningAndNeverReplaysOldStage() {
        val p = CameraWarningStages(); val stages = listOf(1000.0, 500.0)
        assertFalse(p.due("c", stages, 1100.0))
        assertTrue(p.due("c", stages, 420.0))
        assertFalse(p.due("c", stages, 410.0))
        assertFalse(p.due("c", stages, 900.0))
    }
    @Test fun stagesEmitOnceAndFinalConsumesAll() {
        val p = CameraWarningStages(); val stages = listOf(600.0, 300.0)
        assertTrue(p.due("c", stages, 590.0))
        assertFalse(p.due("c", stages, 500.0))
        assertTrue(p.due("c", stages, 250.0))
        assertFalse(p.due("c", stages, 200.0))
        p.consume("near", stages)
        assertFalse(p.due("near", stages, 95.0))
    }
    @Test fun actualFixesProduceTwoLimitStagesThenActualFinalAndNoDuplicates() {
        val camera = SpeedCamera("c", 37.6, 127.0, 80, "camera")
        val a = CameraAlerter(CameraIndex(listOf(camera)))
        a.warningPreset = CameraWarningPreset.LIMIT
        val alerts = mutableListOf<CameraAlert>()
        for ((i, d) in (1100 downTo 25 step 25).withIndex()) {
            alerts += a.update(camera.latitude - d / 111195.0, 127.0, 0.0, 90.0, i * 1000L)
        }
        assertEquals(2, alerts.count { it is CameraAlert.Approaching })
        assertEquals(1, alerts.count { it is CameraAlert.Final })
        assertTrue(alerts.filterIsInstance<CameraAlert.Final>().single().spokenText().contains("100미터"))
    }
    @Test fun nearFirstDetectionSkipsOldStagesAndKeepsFinal() {
        val camera = SpeedCamera("c", 37.6, 127.0, 80, "camera")
        val a = CameraAlerter(CameraIndex(listOf(camera)))
        a.warningPreset = CameraWarningPreset.LIMIT
        val alerts = mutableListOf<CameraAlert>()
        for ((i, d) in listOf(450, 400, 350, 90, 60).withIndex()) {
            alerts += a.update(camera.latitude - d / 111195.0, 127.0, 0.0, 90.0, i * 4000L)
        }
        assertEquals(1, alerts.count { it is CameraAlert.Approaching })
        assertEquals(1, alerts.count { it is CameraAlert.Final })
    }
    @Test fun changingPresetDoesNotRepeatAlreadyConsumedStages() {
        val camera = SpeedCamera("c", 37.6, 127.0, 80, "camera")
        val a = CameraAlerter(CameraIndex(listOf(camera)))
        val alerts = mutableListOf<CameraAlert>()
        for ((i, d) in listOf(650, 550, 500, 450, 400, 300, 200, 90).withIndex()) {
            if (i == 3) a.warningPreset = CameraWarningPreset.LIMIT
            alerts += a.update(camera.latitude - d / 111195.0, 127.0, 0.0, 180.0, i * 2000L)
        }
        assertEquals(1, alerts.count { it is CameraAlert.Approaching })
        assertEquals(1, alerts.count { it is CameraAlert.Final })
    }
}
