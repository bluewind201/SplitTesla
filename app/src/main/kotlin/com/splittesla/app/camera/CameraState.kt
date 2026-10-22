package com.splittesla.app.camera

import com.splittesla.core.camera.CameraStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared between the alert service, the view model and the screen. */
object CameraState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow(CameraStatus())
    val lastAlert = MutableStateFlow<String?>(null)
    val gpsOk = MutableStateFlow(false)

    /** Latest speed from the Commander's CAN stream; better than GPS speed in tunnels. */
    @Volatile var commanderSpeedKmh: Long? = null
    @Volatile var commanderSpeedAt: Long = 0
}
