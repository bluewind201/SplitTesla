package com.splittesla.app.camera

import com.splittesla.core.camera.CameraStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared between the alert service, the view model and the screen. */
object CameraState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow(CameraStatus())
    val lastAlert = MutableStateFlow<String?>(null)
    val gpsOk = MutableStateFlow(false)

    /** Phone GPS speed while alerts run; the dashboard falls back to it without the Commander. */
    val gpsSpeedKmh = MutableStateFlow<Float?>(null)

    /** Latest phone GPS fix while alerts run; the assistant looks ahead from it without the car link. */
    @Volatile var fix: android.location.Location? = null

    /** Latest speed from the Commander's CAN stream; better than GPS speed in tunnels. */
    @Volatile var commanderSpeedKmh: Long? = null
    @Volatile var commanderSpeedAt: Long = 0
}
